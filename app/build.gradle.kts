import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 仓库里只放 base64 文本，构建时在这里还原成 keystore 文件
// （workflow 文件不允许外部改动，所以解码放在构建脚本里做）
// 注意：Kotlin DSL 里 `java` 会被解析成 Gradle 的 Java 扩展，
// 所以不能写 java.util.Base64，必须走文件顶部的 import。
run {
    val kf = file("../ci/signing.p12")
    if (!kf.exists()) {
        val src = file("../ci/signing.b64")
        if (src.exists()) {
            kf.parentFile?.mkdirs()
            kf.writeBytes(Base64.getDecoder().decode(src.readText().trim()))
        }
    }
}

android {
    namespace = "com.ran.eldervideo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ran.eldervideo"
        minSdk = 26
        targetSdk = 34
        versionCode = 105
        versionName = "1.0.5"
    }

    // 该分支自 main 拉出，残留了旧工程 com.ran.agent 源码（本身编译不过）。
    // 这里把源码目录收窄，只编译本项目。
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/java/com/ran/eldervideo"))
        }
    }

    // 固定签名：GitHub Actions 每次都是全新机器，默认 debug keystore 每次都不一样，
    // 结果就是每装一个新包都得先卸载旧包（老人的桌面设置、无障碍授权全没了）。
    // 这里用仓库里固定的一把钥匙，以后直接覆盖安装。
    signingConfigs {
        create("elder") {
            storeFile = file("../ci/signing.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "elder"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("elder")
        }
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
