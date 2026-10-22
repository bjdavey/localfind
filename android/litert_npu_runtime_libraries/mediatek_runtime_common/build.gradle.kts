plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.google.ai.edge.litert.mediatek_runtime.common"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    implementation(project(":app"))
    // Provides libLiteRtDispatch_MediaTek.so from Google Maven.
    implementation(libs.litert.npu.runtime.mediatek)
}

