import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
val releaseVersionCode = providers.gradleProperty("airchive.versionCode").orNull?.let { raw ->
    val parsed = requireNotNull(raw.toIntOrNull()) { "airchive.versionCode must be a positive integer." }
    require(parsed > 0) { "airchive.versionCode must be a positive integer." }
    parsed
} ?: 1

val verifyPrivateReleaseSigning = tasks.register("verifyPrivateReleaseSigning") {
    group = "verification"
    description = "Checks that private release signing is configured without printing credential values."
    doLast {
        val requiredEnvironment = listOf(
            "AIRCHIVE_RELEASE_STORE_FILE",
            "AIRCHIVE_RELEASE_STORE_PASSWORD",
            "AIRCHIVE_RELEASE_KEY_ALIAS",
            "AIRCHIVE_RELEASE_KEY_PASSWORD",
        )
        val missingEnvironment = requiredEnvironment.filter { System.getenv(it).isNullOrBlank() }
        check(missingEnvironment.isEmpty()) {
            "Release signing is not configured. Set AIRCHIVE_RELEASE_STORE_FILE, " +
                "AIRCHIVE_RELEASE_STORE_PASSWORD, AIRCHIVE_RELEASE_KEY_ALIAS, and " +
                "AIRCHIVE_RELEASE_KEY_PASSWORD in the build environment."
        }
        check(File(System.getenv("AIRCHIVE_RELEASE_STORE_FILE")).isFile) {
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
        versionCode = releaseVersionCode
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AIRCHIVE_FIREBASE_PROJECT_ID", "\"${airchiveConfigValue("firebase.projectId")}\"")
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
        getByName("release") {
            isMinifyEnabled = false
            if (hasPrivateReleaseSigning) {
                signingConfig = signingConfigs.getByName("privateRelease")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
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
}
