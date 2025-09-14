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
    
    // ИСПРАВЛЕНО: правильный синтаксис для kotlinOptions
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.bcprov.jdk15on)
    
    // ИСПРАВЛЕНО: проверьте, что эти зависимости есть в libs.versions.toml
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Альтернативно, можете использовать прямые зависимости:
    // implementation("androidx.multidex:multidex:2.0.1")
    // implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
