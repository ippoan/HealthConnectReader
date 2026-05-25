import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// `local.properties` (gitignored) または env var から build-time config を読む。
// CI では env var を `HCREADER_RELEASE_WORKER_URL` (org variable) /
// `HCREADER_RELEASE_UPLOAD_TOKEN` (org secret) で渡す — 他の HCREADER_*
// secret と命名揃え (Refs #6)。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

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

        // WebView が load する worker URL。custom domain `hcreader.ippoan.org`
        // を default に。release.yml が org variable HCREADER_RELEASE_WORKER_URL
        // で上書き可能 (= staging で別 URL を当てたい時用)。
        buildConfigField(
            "String",
            "WORKER_URL",
            "\"${cfg("HCREADER_RELEASE_WORKER_URL", "https://hcreader.ippoan.org/")}\"",
        )
        // POST /api/upload Bearer。secrets-inventory MCP が CF Secrets Store と
        // GCP Secret Manager に投入する `hcreader-upload-token` と同じ値を
        // org secret HCREADER_RELEASE_UPLOAD_TOKEN 経由で APK ビルドに埋め込む
        // (現状 MVP: build-in token、Refs #6)。
        buildConfigField(
            "String",
            "UPLOAD_TOKEN",
            "\"${cfg("HCREADER_RELEASE_UPLOAD_TOKEN", "")}\"",
        )
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health.connect:connect-client:1.1.0-rc02")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
