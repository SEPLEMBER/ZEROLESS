plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nemesis.droidcrypt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nemesis.droidcrypt"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = null // Убрана подпись для неподписанного APK
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // ✅ включаем биндинг
    buildFeatures {
        viewBinding = true
        dataBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.bcprov.jdk15on)

    // Теперь используем aliases вместо прямых зависимостей
    implementation(libs.multidex)
    implementation(libs.recyclerview)

    // Для фоновых вычислений
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
