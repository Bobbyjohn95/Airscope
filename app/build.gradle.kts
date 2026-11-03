plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k.airscope"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.k.airscope"
        minSdk = 29
        targetSdk = 35
        // GitHub Actions sets this to the run number, which is also the release tag (build-N).
        // The in-app updater compares it with the newest release to decide whether to update.
        val build = System.getenv("AIRSCOPE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "2.$build"
        // "stable" when signed with the key derived from the AIRSCOPE_SIGNING_SEED secret (can self-update),
        // "onetime" otherwise. The Updates screen shows this so you know whether auto-update can work.
        manifestPlaceholders["signingKind"] = if (System.getenv("AIRSCOPE_STABLE_KEY") == "1") "stable" else "onetime"
    }

    // Signing key comes from environment variables (set by the GitHub workflow from encrypted
    // repository secrets). No key or password is ever stored in the repository.
    val ksPath: String? = System.getenv("AIRSCOPE_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("personal") {
                storeFile = file(ksPath)
                storeType = "PKCS12"
                storePassword = System.getenv("AIRSCOPE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AIRSCOPE_KEY_ALIAS") ?: "airscope"
                keyPassword = System.getenv("AIRSCOPE_KEY_PASSWORD") ?: System.getenv("AIRSCOPE_KEYSTORE_PASSWORD")
                enableV1Signing = false // not needed for minSdk 29
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Local Android Studio builds without the env vars fall back to the debug key.
            signingConfig = signingConfigs.findByName("personal") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Personal sideloaded build: don't fail the build on Play-policy lint checks
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No third-party libraries: everything uses the Android framework only.
