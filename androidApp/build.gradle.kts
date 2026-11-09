import java.io.File
import java.io.StringReader
import java.util.Properties
import cloud.airchive.gradle.GenerateAppUpdateVerifierFixturesTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val ciDependencyConfigurationNames = listOf(
    "debugCompileClasspath",
    "debugRuntimeClasspath",
    "releaseCompileClasspath",
    "releaseRuntimeClasspath",
    "debugAndroidTestCompileClasspath",
    "debugAndroidTestRuntimeClasspath",
)

dependencyLocking {
    lockMode = LockMode.STRICT
}

configurations.matching { it.name in ciDependencyConfigurationNames }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

tasks.register("resolveCiDependencies") {
    group = "verification"
    description = "Resolves the app dependency configurations used by CI."
    notCompatibleWithConfigurationCache("This maintenance task resolves configurations to write Gradle lock state.")
    doLast {
        ciDependencyConfigurationNames.forEach { configurationName ->
            val configuration = configurations.getByName(configurationName)
            val componentCount = configuration.incoming.resolutionResult.allComponents.size
            logger.lifecycle("Resolved $configurationName ($componentCount components).")
        }
    }
}

val airchiveConfig = Properties().apply {
    rootProject.file("config/airchive-mobile.properties").inputStream().use { load(it) }
}

fun airchiveConfigValue(key: String): String = requireNotNull(airchiveConfig.getProperty(key)) {
    "Missing required non-secret mobile configuration: $key"
}.trim()

/**
 * Optional configuration value: absent or blank properties become an empty
 * string, so public Firebase client options (owner sign-in, task 3.1) can stay
 * unconfigured without breaking the build. The owner-auth factory turns blanks
 * into an actionable MissingOptions state.
 */
fun airchiveConfigValueOrBlank(key: String): String =
    airchiveConfig.getProperty(key)?.trim().orEmpty()

val airchiveVersionFile = rootProject.layout.projectDirectory.file("config/airchive-version.properties")
val airchiveVersionProperties = providers.fileContents(airchiveVersionFile).asText.map { contents ->
    Properties().apply { load(StringReader(contents)) }
}
val airchiveVersionCode = airchiveVersionProperties.map { properties ->
    requireNotNull(properties.getProperty("versionCode")?.trim()?.toIntOrNull()) {
        "Invalid versionCode in config/airchive-version.properties."
    }.also { require(it > 0) { "versionCode in config/airchive-version.properties must be positive." } }
}
val airchiveVersionName = airchiveVersionProperties.map { properties ->
    require(!properties.getProperty("versionName").isNullOrBlank()) {
        "Missing versionName in config/airchive-version.properties."
    }
    properties.getProperty("versionName").trim()
}
val appUpdateManifestUrl = "https://github.com/cnhy-nero-diskard/AirchiveApp/releases/latest/download/update.json"
val appUpdateSignerSha256 = "2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202"
if (providers.gradleProperty("airchive.versionCode").isPresent) {
    error("The airchive.versionCode override has been removed; edit config/airchive-version.properties instead.")
}
val releaseBuildCommit = providers.gradleProperty("airchive.buildCommit").orNull
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    require(releaseBuildCommit?.matches(Regex("[0-9a-f]{40}")) == true) {
        "Release builds require -Pairchive.buildCommit with a full 40-character lowercase commit hash."
    }
}

/** Renders a value as a safe Kotlin string literal for a BuildConfig field. */
fun buildConfigStringLiteral(value: String): String = "\"" +
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") +
    "\""

// Release signing values are consumed by Gradle only; none are written to
// BuildConfig, resources, or the APK. Keep the keystore outside the repository.
val releaseSigningEnvironment = mapOf(
    "AIRCHIVE_RELEASE_STORE_FILE" to providers.environmentVariable("AIRCHIVE_RELEASE_STORE_FILE").orNull.orEmpty(),
    "AIRCHIVE_RELEASE_STORE_PASSWORD" to providers.environmentVariable("AIRCHIVE_RELEASE_STORE_PASSWORD").orNull.orEmpty(),
    "AIRCHIVE_RELEASE_KEY_ALIAS" to providers.environmentVariable("AIRCHIVE_RELEASE_KEY_ALIAS").orNull.orEmpty(),
    "AIRCHIVE_RELEASE_KEY_PASSWORD" to providers.environmentVariable("AIRCHIVE_RELEASE_KEY_PASSWORD").orNull.orEmpty(),
)
val hasPrivateReleaseSigning = releaseSigningEnvironment.values.all(String::isNotBlank)
// Android Studio's Generate Signed APK wizard supplies these AGP properties.
// Only presence and the non-secret file path are passed to our preflight task;
// AGP itself consumes and validates the signing credentials.
val studioSigningKeys = listOf(
    "android.injected.signing.store.file",
    "android.injected.signing.store.password",
    "android.injected.signing.key.alias",
    "android.injected.signing.key.password",
)
val hasStudioReleaseSigning = studioSigningKeys.all {
    !providers.gradleProperty(it).orNull.isNullOrBlank()
}
val signingStorePath = if (hasStudioReleaseSigning) {
    providers.gradleProperty("android.injected.signing.store.file").get()
} else {
    releaseSigningEnvironment.getValue("AIRCHIVE_RELEASE_STORE_FILE")
}
val verifyPrivateReleaseSigning = tasks.register("verifyPrivateReleaseSigning") {
    group = "verification"
    description = "Checks that private release signing is configured without printing credential values."
    inputs.property("signingConfigured", hasStudioReleaseSigning || hasPrivateReleaseSigning)
    inputs.property("signingStorePath", if (signingStorePath.isBlank()) "" else rootProject.file(signingStorePath).absolutePath)
    doLast {
        check(inputs.properties["signingConfigured"] == true) {
            "Release signing is not configured. Use Android Studio's Generate Signed APK wizard, or set AIRCHIVE_RELEASE_STORE_FILE, " +
                "AIRCHIVE_RELEASE_STORE_PASSWORD, AIRCHIVE_RELEASE_KEY_ALIAS, and " +
                "AIRCHIVE_RELEASE_KEY_PASSWORD in the build environment."
        }
        check(File(inputs.properties.getValue("signingStorePath") as String).isFile) {
            "The configured release keystore file does not exist."
        }
    }
}

tasks.configureEach {
    if (name in setOf("preReleaseBuild", "validateSigningRelease", "packageRelease", "assembleRelease", "bundleRelease", "installRelease")) {
        dependsOn(verifyPrivateReleaseSigning)
    }
}

android {
    namespace = "cloud.airchive.personal"
    compileSdk = 36

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasPrivateReleaseSigning) {
            create("privateRelease") {
                storeFile = rootProject.file(releaseSigningEnvironment.getValue("AIRCHIVE_RELEASE_STORE_FILE"))
                storePassword = releaseSigningEnvironment.getValue("AIRCHIVE_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("AIRCHIVE_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("AIRCHIVE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "cloud.airchive.personal"
        minSdk = 26
        targetSdk = 36
        versionCode = airchiveVersionCode.get()
        versionName = airchiveVersionName.get()
        manifestPlaceholders["airchiveBuildCommit"] = "dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AIRCHIVE_FIREBASE_PROJECT_ID", "\"${airchiveConfigValue("firebase.projectId")}\"")
        buildConfigField("String", "AIRCHIVE_APP_UPDATE_MANIFEST_URL", buildConfigStringLiteral(""))
        buildConfigField("String", "AIRCHIVE_APP_UPDATE_SIGNER_SHA256", buildConfigStringLiteral(""))
        buildConfigField(
            "String",
            "AIRCHIVE_FIREBASE_API_KEY",
            buildConfigStringLiteral(airchiveConfigValueOrBlank("firebase.apiKey")),
        )
        buildConfigField(
            "String",
            "AIRCHIVE_FIREBASE_APPLICATION_ID",
            buildConfigStringLiteral(airchiveConfigValueOrBlank("firebase.applicationId")),
        )
        buildConfigField(
            "String",
            "AIRCHIVE_GOOGLE_WEB_CLIENT_ID",
            buildConfigStringLiteral(airchiveConfigValueOrBlank("firebase.googleWebClientId")),
        )
        buildConfigField("String", "AIRCHIVE_DEVICE_ID", "\"${airchiveConfigValue("device.id")}\"")
        buildConfigField("String", "AIRCHIVE_DAY_TIME_ZONE", "\"${airchiveConfigValue("day.timeZone")}\"")
        buildConfigField(
            "String",
            "AIRCHIVE_THINQ_TEMPERATURE_STEP_OVERRIDE",
            buildConfigStringLiteral(airchiveConfigValueOrBlank("thinq.temperatureStepOverride")),
        )
        buildConfigField("int", "AIRCHIVE_FIRESTORE_MAX_PAGE_SIZE", airchiveConfigValue("firestore.maxPageSize"))
        buildConfigField(
            "String",
            "AIRCHIVE_FIRESTORE_GET_PATHS",
            "\"${airchiveConfigValue("firestore.getPaths")}\"",
        )
        buildConfigField(
            "String",
            "AIRCHIVE_FIRESTORE_LIST_PATHS",
            "\"${airchiveConfigValue("firestore.listPaths")}\"",
        )
        buildConfigField(
            "boolean",
            "AIRCHIVE_FIRESTORE_CLIENT_WRITES_ALLOWED",
            airchiveConfigValue("firestore.clientWritesAllowed"),
        )
        buildConfigField(
            "boolean",
            "AIRCHIVE_OWNER_ENROLLMENT_REQUIRED",
            airchiveConfigValue("firestore.ownerEnrollmentRequired"),
        )
    }

    buildTypes {
        getByName("debug") {
            // Keep the development build installable beside the privately signed
            // production package, whose signing key is not available on every machine.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField(
                "String",
                "AIRCHIVE_FIREBASE_APPLICATION_ID",
                buildConfigStringLiteral(airchiveConfigValueOrBlank("firebase.debugApplicationId")),
            )
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["airchiveBuildCommit"] = releaseBuildCommit.orEmpty()
            buildConfigField("String", "AIRCHIVE_APP_UPDATE_MANIFEST_URL", buildConfigStringLiteral(appUpdateManifestUrl))
            buildConfigField("String", "AIRCHIVE_APP_UPDATE_SIGNER_SHA256", buildConfigStringLiteral(appUpdateSignerSha256))
            if (hasPrivateReleaseSigning) {
                signingConfig = signingConfigs.getByName("privateRelease")
            }
        }
    }
}

val appUpdateVerifierFixtureAssets = layout.buildDirectory.dir("generated/appUpdateVerifierFixtures")
val androidSdkDirectory = extensions
    .getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    .sdkComponents.sdkDirectory
val androidSdkRoot = androidSdkDirectory.get().asFile
android.sourceSets.getByName("androidTest").assets.directories.add(appUpdateVerifierFixtureAssets.get().asFile.absolutePath)
android.sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("shared/schemas").absolutePath)

val executableSuffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
val generateAppUpdateVerifierFixtures = tasks.register<GenerateAppUpdateVerifierFixturesTask>("generateAppUpdateVerifierFixtures") {
    group = "verification"
    description = "Builds throwaway-signed APK archives used only by updater instrumentation tests."
    fixtureDirectory.set(appUpdateVerifierFixtureAssets)
    aaptExecutable.set(File(androidSdkRoot, "build-tools/36.0.0/aapt$executableSuffix"))
    apkSignerExecutable.set(File(androidSdkRoot, "build-tools/36.0.0/apksigner$executableSuffix"))
    androidJar.set(File(androidSdkRoot, "platforms/android-36/android.jar"))
    keytoolExecutable.set(File(System.getProperty("java.home"), "bin/keytool$executableSuffix"))
}

tasks.configureEach {
    if (name in setOf(
            "mergeDebugAndroidTestAssets",
            "generateDebugAndroidTestLintModel",
            "lintAnalyzeDebugAndroidTest",
        )
    ) {
        dependsOn(generateAppUpdateVerifierFixtures)
        dependsOn(":shared:copyRoomSchemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Native debug-only scene for isolated Home layout and interaction verification.
    add("debugImplementation", libs.compose.foundation)
    add("debugImplementation", libs.compose.material3)

    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Fixes high-severity GHSA-xxph-c9ww-hj94 in the transitive Firebase/gRPC dependency graph.")
        }
    }

    implementation(project(":shared"))
    implementation(libs.room.runtime)
    implementation(libs.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.ktor.client.cio)
    // Current official Google sign-in stack: AndroidX Credential Manager +
    // GetGoogleIdOption + GoogleIdTokenCredential (no legacy GoogleSignInClient).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.room3.testing)
    androidTestImplementation(libs.sqlite.bundled)
}
