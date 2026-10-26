plugins {
    id("com.android.application")
}

android {
    namespace = "com.xperia.leicalook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xperia.leicalook"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // LUT / model files are read with AssetManager.openFd or streamed; keep them uncompressed
        noCompress += listOf("lut", "bin", "onnx", "clut")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
