
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("KEYSTORE_FILE")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")

val releaseSigningReady =
    !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.yadino.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yadino.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 8
        versionName = "2.6"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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

// اعتبارسنجی اطلاعات امضای انتشار فقط هنگام ساخت خروجی Release اجرا می‌شود.
// بنابراین ساخت Debug و بررسی‌های معمول CI به secretهای انتشار وابسته نیستند.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Checks that all release signing secrets and the keystore file are available."

    doLast {
        val missingSecrets = mutableListOf<String>()

        if (releaseStoreFile.isNullOrBlank()) missingSecrets.add("KEYSTORE_FILE")
        if (releaseStorePassword.isNullOrBlank()) missingSecrets.add("KEYSTORE_PASSWORD")
        if (releaseKeyAlias.isNullOrBlank()) missingSecrets.add("KEY_ALIAS")
        if (releaseKeyPassword.isNullOrBlank()) missingSecrets.add("KEY_PASSWORD")

        if (missingSecrets.isNotEmpty()) {
            throw GradleException(
                "Release signing secrets are missing: ${missingSecrets.joinToString(", ")}"
            )
        }

        val keystore = file(releaseStoreFile!!)
        if (!keystore.isFile) {
            throw GradleException(
                "Release keystore file was not found: ${keystore.absolutePath}"
            )
        }
    }
}

// فقط taskهای ساخت APK/AAB انتشار به اعتبارسنجی امضا وابسته هستند.
tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // 🔧 کتابخانه صفحه لودینگ
    implementation("androidx.core:core-splashscreen:1.0.1")
}
