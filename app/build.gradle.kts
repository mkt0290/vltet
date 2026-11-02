plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Phiên bản: GitHub Actions truyền -PappVersionName=1.1 -PappVersionCode=<số lần chạy>
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "1").toInt()


android {
    namespace = "vn.jxauto.volamidle"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.jxauto.volamidle"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
    buildFeatures {
        buildConfig = true
    }
    // file JX Auto lớn (~2 MB) — không nén để nạp nhanh
    androidResources {
        noCompress += "js"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
