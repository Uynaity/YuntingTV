plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.radio.tv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.radio.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.11"

    }

    // CI 签名：从环境变量读 keystore，本地未设置则回退为不签名（不影响本地 debug/构建）。
    val ciStoreFile = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (ciStoreFile != null) {
            create("release") {
                storeFile = file(ciStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8：代码压缩 + 资源压缩 + 优化 + 混淆，
            // 减小 DEX/APK 体积、降低方法数与内存占用，改善弱性能 TV 冷启动。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (ciStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 需要 BuildConfig.DEBUG 来按构建类型开关日志拦截器
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 播放器 Media3 (HLS)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // 图片
    implementation(libs.coil.compose)
    implementation(libs.palette.ktx)

    // 持久化
    implementation(libs.androidx.datastore.preferences)
}