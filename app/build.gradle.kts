import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Release 签名。
 *
 * 密钥信息放在项目根目录的 keystore.properties（已在 .gitignore 里），
 * 文件不存在时退回未签名构建——让别人 clone 下来也能直接编译，不会因为缺密钥就构建失败。
 * Windows 上 file() 对相对路径会按模块目录解析，这里显式指回项目根目录。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
// 注意：这里不能用 java.util.Properties()，脚本里 `java` 指向 Gradle 的 JavaPluginExtension
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.myzuoye"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myzuoye"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // AGP 9 的新 DSL：enable = true 会同时打开 R8 与资源压缩
            optimization {
                enable = true
            }
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
}