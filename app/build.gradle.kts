plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val youtubedlAndroid = "0.18.1"

android {
    namespace = "com.aileo.medyaindirici"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aileo.medyaindirici"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") } // modern telefonlar; APK boyutunu küçültür
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } } // python/ffmpeg çalıştırılabilir dosyaları için şart
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
