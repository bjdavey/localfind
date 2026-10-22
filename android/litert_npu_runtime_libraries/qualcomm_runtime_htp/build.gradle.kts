plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.google.ai.edge.litert.qualcomm_runtime.htp"
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
}

