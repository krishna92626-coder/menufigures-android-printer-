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
        versionCode = 3
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
