plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val releaseKeystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propertyName: String, envName: String): String? {
    return releaseKeystoreProperties.getProperty(propertyName)
        ?: providers.gradleProperty("notifyhook.signing.$propertyName").orNull
        ?: System.getenv(envName)
}

android {
    namespace = "com.notifyhook.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notifyhook.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "1.0.36"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                releaseSigningValue("storeFile", "NOTIFYHOOK_STORE_FILE")
                    ?: "../notifyhook-release.jks"
            )
            releaseSigningValue("storePassword", "NOTIFYHOOK_STORE_PASSWORD")?.let {
                storePassword = it
            }
            keyAlias = releaseSigningValue("keyAlias", "NOTIFYHOOK_KEY_ALIAS") ?: "notifyhook"
            releaseSigningValue("keyPassword", "NOTIFYHOOK_KEY_PASSWORD")?.let {
                keyPassword = it
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    // 전송 경로(헤더 부착, 리다이렉트 차단, 재시도 분류)를 실제 HTTP 서버에 대고 검증한다. OkHttp와 같은 버전을 쓴다.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
