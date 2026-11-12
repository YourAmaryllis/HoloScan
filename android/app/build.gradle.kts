plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.youramaryllis.holoscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youramaryllis.holoscan"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        // 64-bit ARM covers current phones and the emulator on Apple silicon;
        // each extra ABI adds ~35 MB of ONNX Runtime.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing comes from Gradle properties supplied at build time
    // (holoscanUploadStoreFile, holoscanUploadStorePassword, optional
    // holoscanUploadKeyAlias). Without them, release builds are debug-signed.
    val uploadStore = providers.gradleProperty("holoscanUploadStoreFile").orNull
    signingConfigs {
        if (uploadStore != null) create("upload") {
            storeFile = file(uploadStore)
            storePassword = providers.gradleProperty("holoscanUploadStorePassword").get()
            keyAlias = providers.gradleProperty("holoscanUploadKeyAlias").orElse("upload").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // The ONNX models (~110 MB) ship in the install-time asset pack ":models", so the
    // Play base download stays small; they're still regular assets at run time.
    // Debug APKs (adb install, no Play) get the same files directly.
    assetPacks += listOf(":models")
    sourceSets.getByName("debug").assets.srcDir("../models/src/main/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The model and index are read via memory-mapped/streamed asset access.
        noCompress += listOf("onnx", "f16")
    }
    testOptions {
        unitTests.all { it.jvmArgs("-Xmx2g") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.onnxruntime.jvm)
}
