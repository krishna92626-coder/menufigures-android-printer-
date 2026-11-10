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
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
