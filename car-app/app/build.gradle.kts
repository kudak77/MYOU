import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.myou.car"
    compileSdk {
        version = release(37)
    }
    // 시동 상태(IGNITION_STATE)를 읽는 안드로이드 차량 표준 API
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.myou.car"
        minSdk = 28
        targetSdk = 34  // Pleos Connect 에뮬레이터(API 34) 기준
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 에뮬레이터 시연 기능(폰 동기화 서버, Virtual Console 시트 연동, 테스트 시동 버튼).
        // 공모전 심사가 에뮬레이터에서 release APK 로 진행되므로 release 에서도 켠다. 실차 배포 전에 false 로 바꾼다.
        buildConfigField("boolean", "DEMO_FEATURES", "true")
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
            // R8 최적화를 끈다: Pleos Vehicle SDK 가 실행 중에 Kotlin 어노테이션을 리플렉션으로 읽는데,
            // kotlin.** 을 줄이면 어노테이션 정보가 사라져 앱이 바로 꺼진다(IncompleteAnnotationException).
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // Pleos Connect SDK
    implementation(libs.pleos.vehicle)        // Seat, Door, Brake, HVAC, Light
    implementation(libs.pleos.fusedlocation)  // 위치, 휴게소 근접 감지
    implementation(libs.pleos.navihelper)     // 목적지/경로
    implementation(libs.pleos.tts)            // 음성 안내

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}