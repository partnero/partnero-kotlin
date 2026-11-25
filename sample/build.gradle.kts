plugins {
    alias(libs.plugins.android.application)
}

fun stringProperty(name: String, default: String): String =
    "\"" + providers.gradleProperty(name).getOrElse(default) + "\""

android {
    namespace = "com.partnero.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.partnero.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PARTNERO_PROGRAM_ID", stringProperty("partnero.programId", "YOUR_PROGRAM_ID"))
        buildConfigField("String", "PARTNERO_BASE_URL", stringProperty("partnero.baseUrl", "https://app.partnero.com"))
        buildConfigField("String", "PARTNERO_REFERRAL_PARAMETER", stringProperty("partnero.referralParameter", "aff"))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":partnero"))
    implementation(libs.kotlinx.coroutines.android)
}
