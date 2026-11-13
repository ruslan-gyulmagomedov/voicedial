plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.aleshka.voicedial"
    compileSdk = 35

    defaultConfig {
        // Свой постоянный идентификатор: приложение ставится отдельно от
        // оригинала и обновляется поверх предыдущих версий. Не менять.
        applicationId = "ru.gyulmagomedov.voicedial"
        minSdk = 33
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    // Подпись одна и та же для всех сборок: иначе Android не поставит новую
    // версию поверх старой (подписи не совпадут).
    signingConfigs {
        create("voicedial") {
            storeFile = rootProject.file("keystore/voicedial.jks")
            storePassword = "voicedial"
            keyAlias = "voicedial"
            keyPassword = "voicedial"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("voicedial")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("voicedial")
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
}
