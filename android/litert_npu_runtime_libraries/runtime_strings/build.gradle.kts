plugins {
    id("com.android.library")
}

android {
    namespace = "com.google.ai.edge.litert.runtime_strings"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }
}

