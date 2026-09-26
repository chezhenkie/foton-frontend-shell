plugins {
    id("com.android.application")
}

val keystoreB64 = providers.environmentVariable("FOTON_KEYSTORE_B64").orNull
val keystorePass = providers.environmentVariable("FOTON_KEYSTORE_PASSWORD").orNull
val haveKey = !keystoreB64.isNullOrBlank()
val havePass = !keystorePass.isNullOrBlank()
require(haveKey == havePass) {
    "FOTON_KEYSTORE_B64 and FOTON_KEYSTORE_PASSWORD must be set together"
}

// CI signs with one pinned key so every APK updates the installed app in place.
// Without the secrets a local build falls back to the auto-generated debug key.
val pinnedKeystore = if (haveKey) {
    layout.buildDirectory.file("foton-dev.keystore").get().asFile.apply {
        writeBytes(java.util.Base64.getDecoder().decode(keystoreB64!!.trim()))
    }
} else {
    null
}

android {
    namespace = "com.foton.frontend"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foton.frontend"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.1"
    }

    signingConfigs {
        if (pinnedKeystore != null) {
            create("pinned") {
                storeFile = pinnedKeystore
                storePassword = keystorePass
                keyAlias = "foton-dev"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.findByName("pinned")
                ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}