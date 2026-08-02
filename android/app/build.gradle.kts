plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.patron.snaglite"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.patron.snaglite"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val ksPath  = System.getenv("SNAGLITE_KEYSTORE_PATH")
            val ksPass  = System.getenv("SNAGLITE_KEYSTORE_PASS")
            val alias   = System.getenv("SNAGLITE_KEY_ALIAS")
            val keyPass = System.getenv("SNAGLITE_KEY_PASS")
            val haveAll = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() &&
                          !alias.isNullOrBlank()  && !keyPass.isNullOrBlank() &&
                          file(ksPath).exists()
            if (haveAll) {
                storeFile     = file(ksPath!!)
                storePassword = ksPass
                keyAlias      = alias
                keyPassword   = keyPass
            } else {
                println("SnagLite: SNAGLITE_KEYSTORE_* env vars unset or keystore missing — release will fall back to debug signing.")
                val debugKs = file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (debugKs.exists()) {
                    storeFile     = debugKs
                    storePassword = "android"
                    keyAlias      = "androiddebugkey"
                    keyPassword   = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinker re-enabled now that the AsiExtraField keep
            // rule (and the broader keep set in proguard-rules.pro) is in
            // place — the prior launch crash on Android 14 traced to commons-
            // compress reflective loading being stripped.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
