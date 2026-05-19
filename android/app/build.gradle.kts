plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.patron.snaglite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.patron.snaglite"
        minSdk = 26
        targetSdk = 34
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
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

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
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
