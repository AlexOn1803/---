plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.screentranslator"
        // AccessibilityService.takeScreenshot() з'явився в Android 11 (API 30)
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Тільки ARM (усі телефони) — APK удвічі менший, без бібліотек для x86-емуляторів
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Розпізнавання тексту (латиниця), модель вбудована в APK — працює офлайн
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Переклад на пристрої (модель англ→укр ~30 МБ завантажується один раз)
    implementation("com.google.mlkit:translate:17.0.3")
}
