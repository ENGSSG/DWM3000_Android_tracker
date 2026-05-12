plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dwm3000.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dwm3000.tracker"
        minSdk = 33  // UWB APIs require API 33+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress += listOf("tflite", "onnx")
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
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // UWB - AndroidX UWB library
    implementation("androidx.core.uwb:uwb:1.0.0-alpha08")
    implementation("androidx.core.uwb:uwb-rxjava3:1.0.0-alpha08")

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // BlazeFace face detection through MediaPipe Tasks.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // YuNet face detection through OpenCV.
    implementation("org.opencv:opencv:4.10.0")

    // Guava - Explicitly adding listenablefuture to resolve the "Cannot access class" error.
    implementation("com.google.guava:guava:33.0.0-android")
    implementation("com.google.guava:listenablefuture:1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
