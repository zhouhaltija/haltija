import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// 必须显式 import：脚本里的 `java` 会解析成 Gradle 的 java 扩展，不是包名
import java.util.Properties

plugins {
    // 注意：AGP 9 自己会注册 `kotlin` 扩展，所以这里**不能**再应用
    // org.jetbrains.kotlin.jvm，否则报 "Cannot add extension with name 'kotlin'"
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名。密钥与口令放在 .toolchain/（已 gitignore），
// 所以克隆仓库的人能正常构建 debug，只是打不出签名 release —— 这是刻意的：
// 签名密钥不该进版本库。
val keystorePropertiesFile = rootProject.file("../.toolchain/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "app.haltija"

    // 本机 SDK 里装的是 android-36.1 与 build-tools 36.1.0（次版本号），
    // AGP 默认会去找 android-36 / 36.0.0 并尝试下载 —— 显式指定已装的版本，
    // 免得构建去动 SDK 目录（也不该动）。
    compileSdk = 36
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.haltija"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 先把混淆关掉：AGPL 项目发布时也建议保留可读的堆栈
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-prompt"))
    implementation(project(":core-provider"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // SAF 目录访问：让用户直接把数据根指到桌面 ST 的目录上
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // API Key 用 EncryptedSharedPreferences 存，不要明文落盘
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // 管线本身不碰 Android API，所以能用普通 JVM 单测覆盖，不必起模拟器。
    // 注意：Android 模块里 `kotlin("test")` 不生效，要显式写坐标。
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
