plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ramesh.nexus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ramesh.nexus"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
        }
    }

    // llama.cpp (third_party/llama.cpp submodule) + the JNI bridge for the on-device IRx model.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "28.2.13676358"

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The reference voice is read with WaveReader straight from assets.
        noCompress += "wav"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // arm64 only; the other ABIs' copies of libonnxruntime.so would collide.
            excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // sherpa-onnx's JNI calls the TTS callback as invoke([F)Ljava/lang/Integer;, which only
        // exists when lambdas compile to classes (Kotlin 2 defaults to invokedynamic).
        freeCompilerArgs.add("-Xlambdas=class")
    }
}

dependencies {
    // On-device speech recognition (Parakeet) and voice detection (Silero). This build links its own
    // ONNX Runtime statically, so it doesn't clash with the one below.
    implementation(files("libs/sherpa-onnx-static-2.0.0.aar"))
    // Ramesh's voice: Chatterbox Turbo q4.
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
}
