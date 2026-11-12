plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ran.eldervideo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ran.eldervideo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-test"
    }

    // 该分支自 main 拉出，残留了旧工程 com.ran.agent 源码（本身编译不过）。
    // 这里把源码目录收窄，只编译本项目。
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/java/com/ran/eldervideo"))
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
