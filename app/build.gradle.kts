import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}

android {
    namespace = "com.jmwl.gostudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jmwl.gostudio"
        minSdk = 26
        targetSdk = 28
        versionCode = 109
        versionName = "1.0.9"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    lint {
        disable.add("ExpiredTargetSdkVersion")
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    signingConfigs {
        // 从 local.properties 读取签名信息（该文件已被 .gitignore 排除，不会入库）
        // 缺失任何一项时，不创建 release 签名配置 —— release 将降级为 debug 签名
        val props = rootProject.file("local.properties")
        val signProps = if (props.exists()) {
            Properties().apply { props.inputStream().use { load(it) } }
        } else null

        val storePath = signProps?.getProperty("GOSTUDIO_KEYSTORE_PATH")
        val keyAlias = signProps?.getProperty("GOSTUDIO_KEY_ALIAS")
        val keyPassword = signProps?.getProperty("GOSTUDIO_KEY_PASSWORD")
        val storePassword = signProps?.getProperty("GOSTUDIO_STORE_PASSWORD")

        if (storePath != null && keyAlias != null && keyPassword != null && storePassword != null) {
            create("release") {
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                this.storePassword = storePassword
                storeFile = rootProject.file(storePath)
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        getByName("release") {
            // 仅在 release 签名配置存在时才启用签名，否则使用 debug 签名（降级）
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // 导入项目：SAF 目录选择结果的遍历读取（DocumentFile）
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.android.tools.build:apksig:8.5.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.apache.commons.compress)
    implementation(libs.xz)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    
    implementation(libs.permissions.dispatcher)
    kapt(libs.permissions.dispatcher.processor)
    
    implementation(project(":modules:terminal-view"))
    implementation(project(":modules:editor-core"))
    implementation(project(":modules:project-file-tree"))
    implementation(project(":modules:toolchain-runtime"))
    implementation(project(":modules:gopls-lsp"))
    implementation(project(":modules:sora-editor"))
    implementation(project(":modules:sora-language-textmate"))
    implementation(project(":modules:sora-oniguruma-native"))
    
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("io.getstream:stream-chat-android-compose:7.3.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // AI 助手:加密存储 API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
