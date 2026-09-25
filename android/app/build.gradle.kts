plugins {
    id("com.android.application")
}

android {
    namespace = "com.foton.frontend"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foton.frontend"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}