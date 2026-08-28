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
        versionCode = 17
        versionName = "1.4.3"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies { }
