plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.macromobile.inputmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macromobile.inputmirror"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        /**
         * debug 전용 고정 키.
         *
         * 이 키를 저장소에 두지 않으면 빌드할 때마다 임시 키가 새로 만들어져 서명이 달라지고,
         * 그러면 덮어쓰기 설치가 막혀 매번 앱을 지웠다 깔아야 한다. 비밀번호는 안드로이드
         * 표준값이라 숨길 것이 없다. 배포용 서명은 아래 release 설정에서 따로 받는다.
         */
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        create("release") {
            val storeFilePath = System.getenv("MIRROR_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("MIRROR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MIRROR_KEY_ALIAS")
                keyPassword = System.getenv("MIRROR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val hasKeystore = !System.getenv("MIRROR_KEYSTORE_FILE").isNullOrBlank()
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
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
        compose = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
