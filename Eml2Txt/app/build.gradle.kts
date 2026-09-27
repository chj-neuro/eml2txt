plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.chanhee.eml2txt"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.chanhee.eml2txt"
        minSdk = 26          // Android 8.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed signing key kept in the repo, so every new build installs over the old one
    // (GitHub would otherwise sign each build with a different random key).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The app itself uses no libraries. JUnit is only for the tests.
    testImplementation("junit:junit:4.13.2")
}
