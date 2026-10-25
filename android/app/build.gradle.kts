import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional override from deploy-bundle scripts when targeting an x86_64 emulator.
val targetAbi = providers.gradleProperty("targetAbi").orNull
val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

android {
    namespace = "com.localfind"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.localfind"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NPU hardware acceleration targets 64-bit ARM; x86_64 supports Android Emulator
        ndk {
            if (targetAbi != null) {
                abiFilters.add(targetAbi)
            } else if (isBundleBuild) {
                // App Bundle (.aab) requires base and all NPU dynamic-feature modules to share the same ABI set
                abiFilters.add("arm64-v8a")
            } else {
                // Standalone APK builds include both arm64-v8a (physical devices) and x86_64 (emulators)
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    // Upload-key signing for Play releases. Credentials live in android/keystore.properties (gitignored, see
    // release.ps1); without that file release builds stay unsigned, so debug/CI builds are unaffected.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        noCompress += "litertlm"
    }

    // Google Play AI Packs: SoC-targeted .litertlm delivered with the app (install-time), plus the universal
    // CPU/GPU model fetched on demand when the NPU model cannot run (RecoveryModel)
    assetPacks.add(":ai_pack:embedding_model")
    assetPacks.add(":ai_pack:universal_model")

    // Play Feature Delivery modules for SoC-targeted NPU native runtime libraries (ARM64 only)
    if (targetAbi == null || targetAbi == "arm64-v8a") {
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
    }

    bundle {
        deviceTargetingConfig = file("device_targeting_configuration.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "other"
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Strings for NPU Play Feature Delivery runtime modules
    implementation(project(":litert_npu_runtime_libraries:runtime_strings"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
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
    // Play AI Pack on-demand delivery: fetches the universal CPU/GPU model when the NPU model cannot run (Play downloads it; the app makes no network calls)
    implementation(libs.play.ai.delivery)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
