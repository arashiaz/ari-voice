plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arashiaz.arivoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arashiaz.arivoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Normal phone APK stays arm64-only; opt-in x86_64 build is for CI emulator smoke tests.
        ndk { abiFilters += if (providers.gradleProperty("emulatorBuild").isPresent) listOf("x86_64") else listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") }
        }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
