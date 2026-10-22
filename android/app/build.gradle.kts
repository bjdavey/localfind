plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.localfind"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.localfind"
        minSdk = 31
        targetSdk = 37
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
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite")
    }

    // Google Play AI Pack for SoC-targeted .litertlm delivery (fast-follow)
    assetPacks.add(":ai_pack:embedding_model")

    // Play Feature Delivery modules for SoC-targeted NPU native runtime libraries
    dynamicFeatures.add(":litert_npu_runtime_libraries:mediatek_runtime_common")
    dynamicFeatures.add(":litert_npu_runtime_libraries:mediatek_runtime_v8")
    dynamicFeatures.add(":litert_npu_runtime_libraries:mediatek_runtime_v9")
    dynamicFeatures.add(":litert_npu_runtime_libraries:google_tensor_runtime")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_common")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_htp")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v73")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v75")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
    dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v81")

    bundle {
        deviceTargetingConfig = file("device_targeting_configuration.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "other"
        }
    }
}

// Wire SM8650 (Hexagon V75) NPU runtime libraries for standalone local debug APK builds
apply(from = "../litert_npu_runtime_libraries/debug_sm8650_npu.gradle")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Strings for NPU Play Feature Delivery runtime modules
    implementation(project(":litert_npu_runtime_libraries:runtime_strings"))
    // Google Play AI Pack delivery API for locating fast-follow extracted model packs
    implementation(libs.android.play.ai.delivery)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mediapipe.tasks.retrieval)
    implementation(libs.litertlm.android)
    implementation(libs.pdfbox.android)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.pdf.viewer.fragment)
    implementation(libs.androidx.fragment.compose)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
