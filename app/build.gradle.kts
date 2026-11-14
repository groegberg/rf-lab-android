plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rflab.scanner"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.rflab.scanner"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
