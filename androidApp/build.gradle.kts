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

    defaultConfig {
        applicationId = "cloud.airchive.personal"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "AIRCHIVE_FIREBASE_PROJECT_ID", "\"${airchiveConfigValue("firebase.projectId")}\"")
        buildConfigField("String", "AIRCHIVE_DEVICE_ID", "\"${airchiveConfigValue("device.id")}\"")
        buildConfigField("String", "AIRCHIVE_DAY_TIME_ZONE", "\"${airchiveConfigValue("day.timeZone")}\"")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
}
