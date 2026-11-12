import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ~/Documents 는 iCloud 로 동기화되어 빌드 결과물에 "… 2.class" 사본이 생기고 빌드가 깨진다.
// iCloud 는 이름이 .nosync 로 끝나는 폴더를 동기화하지 않으므로 빌드 출력을 그쪽에 둔다.
layout.buildDirectory = file("build.nosync")

android {
    namespace = "com.myou.phone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.myou.phone"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // 서명 정보는 저장소 밖에 둔다: keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
    val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
