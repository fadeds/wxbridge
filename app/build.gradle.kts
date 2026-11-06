plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号注入。
 *
 * CI 里用 `-PversionName=1.2.3 -PversionCode=42` 传进来；
 * 本地不传就用下面的默认值，保证 Android Studio 直接点 Run 也能过。
 * 环境变量是给不方便传参数的环境兜底用的。
 *
 * 显式传 Project 而不是依赖脚本的隐式接收者 —— 后者在 Kotlin DSL 里
 * 偶尔会因为脚本类结构变化解析失败，踩过一次就够了。
 */
fun propOrEnv(p: org.gradle.api.Project, name: String, env: String): String? =
    (p.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val buildVersionName: String = propOrEnv(project, "versionName", "VERSION_NAME") ?: "0.1.0"
val buildVersionCode: Int = propOrEnv(project, "versionCode", "VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "io.wxbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.wxbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // 模块要装到手机上调试，release 也用 debug 签名，省一次 keystore 配置。
            // 如果要上架应用商店，换成自己的 signingConfig。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 要用 BuildConfig.VERSION_NAME 在界面上显示版本
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Xposed API：只在编译期可见，绝对不能打进 APK
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // SQLCipher for Android（新坐标，老 net.sqlcipher 已废弃）
    implementation("net.zetetic:sqlcipher-android:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // 刻意不引入 ML Kit / 任何端侧 OCR：
    // 图片理解统一走视觉模型（DeepSeek deepseek-flash 等原生多模态），
    // 一条通路同时解决「读字」和「看画面」，APK 也因此小了 15MB。
    // 代价是图片必须联网识别；离线场景见 README「图片理解」。
}
