plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.usemagpie.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.usemagpie.dashboard"
        // LX04 (小爱触屏音箱) ships Android 8.1 = API 27.
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
