import org.gradle.kotlin.dsl.androidTestImplementation
import org.gradle.kotlin.dsl.implementation
import org.gradle.kotlin.dsl.testImplementation

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.analysis"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.analysis"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)


    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.2.0")

    implementation("io.github.sceneview:sceneview:2.2.1")

    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // CameraX core library
    //def camerax_version = "1.4.2'
    implementation ("androidx.camera:camera-core:1.4.2")

    // CameraX Camera2 extensions
    implementation ("androidx.camera:camera-camera2:1.4.2")

    // CameraX Lifecycle library
    implementation ("androidx.camera:camera-lifecycle:1.4.2")

    // CameraX View class
    implementation ("androidx.camera:camera-view:1.4.2")

    // Instrumented testing
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")

    // MediaPipe Library
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    implementation("androidx.media3:media3-exoplayer:1.3.1")

}