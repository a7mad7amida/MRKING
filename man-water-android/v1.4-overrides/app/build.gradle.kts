plugins {
    id("com.android.application")
}

android {
    namespace = "ps.man.water"
    compileSdk = 35

    defaultConfig {
        applicationId = "ps.man.water"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.4.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("MAN_WATER_KEYSTORE") ?: "upload-key.jks")
            storePassword = System.getenv("MAN_WATER_STORE_PASSWORD")
            keyAlias = System.getenv("MAN_WATER_KEY_ALIAS") ?: "man-water-upload"
            keyPassword = System.getenv("MAN_WATER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies { }
