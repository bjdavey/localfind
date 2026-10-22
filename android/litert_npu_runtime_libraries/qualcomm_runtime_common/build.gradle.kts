plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.google.ai.edge.litert.qualcomm_runtime.common"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jni")
        }
    }
}

dependencies {
    implementation(project(":app"))
    // Provides libLiteRtDispatch_Qualcomm.so and libLiteRtCompilerPlugin_Qualcomm.so from Google Maven.
    implementation(libs.litert.npu.runtime.qualcomm)
}

