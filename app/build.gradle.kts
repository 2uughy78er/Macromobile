plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.macromobile.imagemacro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macromobile.imagemacro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // OpenCV 네이티브 라이브러리는 ABI 하나당 30MB 가까이 된다.
            // 실제 안드로이드 폰·태블릿이 쓰는 두 ABI 만 담아 APK 크기를 절반 이하로 줄인다.
            // (에뮬레이터에서 돌려보려면 "x86_64" 를 여기에 추가하면 된다.)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // CI 에서 서명 키가 제공되면 release APK 를 서명한다.
        // 키가 없으면 release 는 서명되지 않은 APK 로 빌드된다.
        create("release") {
            val storeFilePath = System.getenv("MACRO_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("MACRO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MACRO_KEY_ALIAS")
                keyPassword = System.getenv("MACRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // OpenCV / ML Kit 리플렉션 이슈를 피하기 위해 축소는 기본 비활성화한다.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val hasKeystore = !System.getenv("MACRO_KEYSTORE_FILE").isNullOrBlank()
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
            )
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
    implementation(libs.androidx.documentfile)

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

    implementation(libs.opencv)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.korean)

    testImplementation(libs.junit)
}
