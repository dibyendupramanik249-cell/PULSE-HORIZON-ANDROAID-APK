plugins {
    id("com.android.application")
}

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.pulsehorizon.a8k9m2"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"
    }

    // Release signing is applied ONLY when CI provides the keystore.
    // With no keystore the release output stays unsigned.
    val releaseStorePath: String? = System.getenv("RELEASE_STORE_FILE")
    val haveReleaseKey = releaseStorePath != null && file(releaseStorePath).exists()

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    androidResources {
        // Keep only English resources. AppCompat otherwise ships ~80 unused
        // translations, which is a large part of the APK.
        localeFilters += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (haveReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // v33: document-start script registration (blob-revocation race fix)
    implementation("androidx.webkit:webkit:1.11.0")
    // v160: the QR scanner now calls Android's own camera (camera2, part of
    // the OS - zero bytes in the APK) and decodes with zxing:core, a single
    // pure-Java library of about 594 KB. No CameraX, no bundled ML Kit model,
    // no Play Services. Under 1 MB instead of about 11 MB.
    // v163: the tested camera library instead of my own camera2 code.
    // 149 KB, and it brings a preview that handles rotation, aspect, focus and
    // torch - plus getTransformedResultPoints(), which maps corner points into
    // view coordinates for us. zxing:core comes with it.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(libs.junit)
}
