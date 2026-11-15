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
        versionCode = 11
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    testImplementation("junit:junit:4.13.2")
}
