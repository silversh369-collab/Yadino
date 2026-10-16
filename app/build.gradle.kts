
plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.yadino.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yadino.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
