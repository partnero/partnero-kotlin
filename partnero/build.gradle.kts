plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

val versionName = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.partnero"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates("com.partnero", "partnero-android", versionName)

    pom {
        name.set("Partnero for Android")
        description.set("Attribute Android app users to Partnero partners with referral codes and links.")
        inceptionYear.set("2026")
        url.set("https://github.com/partnero/partnero-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("partnero")
                name.set("Partnero")
                url.set("https://www.partnero.com")
            }
        }
        scm {
            url.set("https://github.com/partnero/partnero-kotlin")
            connection.set("scm:git:git://github.com/partnero/partnero-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/partnero/partnero-kotlin.git")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
