plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.google.ai.edge.litert.google_tensor_runtime"
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
    // Provides libLiteRtDispatch_GoogleTensor.so from Google Maven.
    implementation(libs.litert.npu.runtime.google.tensor)
}

