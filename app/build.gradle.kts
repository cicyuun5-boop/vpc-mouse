plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.vpc.hidmouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.vpc.hidmouse"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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
}

// 不引入任何第三方依赖：只用 Android SDK 自带 API，减少编译失败面
dependencies {
}
