import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.home.nvrviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.home.nvrviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"

        // VLC ships native code: only include what TVs use (keeps the app smaller).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.annotation:annotation:1.9.1")
    // Full-screen player for the 4K H.265 main stream
    implementation("org.videolan.android:libvlc-all:3.6.5")
}
