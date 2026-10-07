plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// Release builds pass -PappVersion=X.Y.Z from the git tag (see .github/workflows/build.yml). versionCode is
// major·10000 + minor·100 + patch, so minor and patch must stay below 100 (the CI tag check enforces the same).
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.1"
if (!Regex("""\d{1,4}\.\d{1,2}\.\d{1,2}""").matches(appVersion)) {
    throw GradleException("appVersion must look like 3.2.1 (minor and patch below 100), got \"$appVersion\"")
}

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
            manifestPlaceholders["appLabel"] = "Budget"
        }
        // A separate app, so a local build installs next to the release one (different signing key) without
        // touching its data. src/debug/res/xml/shortcuts.xml points the shortcuts at this package.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Budget (debug)"
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
