plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "it.andreavio.spesavocale"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    defaultConfig {
        applicationId = "it.andreavio.spesavocale"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototipo"
    }
}
