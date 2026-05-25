plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ippoan.hcreader"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            // PKCS12 (JDK 標準) は仕様上 keyPassword == storePassword 強制。
            // -keypass を別値で指定しても keytool が silently 無視するため、
            // ここで明示的に同じ env var (`RELEASE_STORE_PASSWORD`) を両方に
            // 割り当てる (= secret は 1 個で運用)。
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = "hcreader"
            keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.ippoan.hcreader"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health.connect:connect-client:1.1.0-rc02")
}
