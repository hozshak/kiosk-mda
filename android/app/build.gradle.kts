plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kiosk.mda"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kiosk.mda"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "DEFAULT_CONFIG_URL", "\"http://192.168.115.177:3000/config/prod\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("kiosk-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                // takeIf isNotBlank wichtig: leere Env-Vars in CI würden sonst Default überschreiben
                storePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "kiosk123"
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "kiosk"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "kiosk123"
            }
        }
    }

    buildTypes {
        debug {
            // KEIN applicationIdSuffix - debug und release haben selbe ID,
            // damit Updates ohne Deinstallation funktionieren
            isDebuggable = true
            // Selber Keystore wie Release fuer konsistente Signatur ueber alle Builds
            val keystoreFile = file("kiosk-release.jks")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // Minifizierung vorerst aus - vermeidet R8-Issues mit Tink/Security-Crypto
            isMinifyEnabled = false
            isShrinkResources = false
            val keystoreFile = file("kiosk-release.jks")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp für WebSocket-Push
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
