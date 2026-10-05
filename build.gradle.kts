plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// Release builds pass -PappVersion=X.Y.Z from the git tag (see .github/workflows/build.yml).
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.1"

android {
    namespace = "com.gh00ul.budget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gh00ul.budget"
        minSdk = 26
        targetSdk = 35
        versionName = appVersion
        versionCode = appVersion.split(".").map(String::toInt)
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    }

    // Every release must be signed with the same key or Android refuses to install it as an update.
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "budget"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
