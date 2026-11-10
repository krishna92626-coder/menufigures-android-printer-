plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.menufigures.printer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.menufigures.printer"
        minSdk = 23
        targetSdk = 32
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_FILE")
            val storePass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("ANDROID_KEY_ALIAS")
            val keyPass = System.getenv("ANDROID_KEY_PASSWORD")
            if (storePath.isNullOrBlank() || storePass.isNullOrBlank() ||
                keyAliasValue.isNullOrBlank() || keyPass.isNullOrBlank()) {
                throw GradleException("Release signing secrets are required. Configure the ANDROID_* GitHub Actions secrets.")
            }
            storeFile = file(storePath)
            storePassword = storePass
            keyAlias = keyAliasValue
            keyPassword = keyPass
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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
