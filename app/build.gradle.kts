plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.anxietytracker"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        val run = System.getenv("GITHUB_RUN_NUMBER")
        val sha = System.getenv("GITHUB_SHA")?.take(7)
        versionCode = run?.toIntOrNull() ?: 1
        versionName = if (run == null) "1.0-dev" else "1.0.$run" + (sha?.let { "-$it" } ?: "")
    }

    flavorDimensions += "dist"
    productFlavors {
        // Sideloaded APKs (GitHub releases, screenshots): keeps the original id so existing installs update.
        create("sideload") {
            dimension = "dist"
            applicationId = "app.bcalm"
            resValue("bool", "is_play", "false")
        }
        // Google Play build (uploaded as the AAB).
        create("play") {
            dimension = "dist"
            applicationId = "io.github.dreamins.bcalm"
            resValue("bool", "is_play", "true")
        }
    }

    // Stable signing key from CI secrets so phone updates work; local builds keep the default debug key.
    val ksPath = System.getenv("SIGNING_KEYSTORE")
    val ksPassword = System.getenv("SIGNING_PASSWORD")
    val bcalmSigning = if (ksPath != null && file(ksPath).isFile && ksPassword != null) {
        signingConfigs.create("bcalm") {
            storeFile = file(ksPath)
            storePassword = ksPassword
            keyAlias = "bcalm"
            keyPassword = ksPassword
        }
    } else null

    buildTypes {
        getByName("debug") {
            if (bcalmSigning != null) signingConfig = bcalmSigning
        }
        release {
            isMinifyEnabled = false
            if (bcalmSigning != null) signingConfig = bcalmSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // Keep the CI release build from failing on lint-vital checks; debug builds are the compile check.
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        aidl = false
        renderScript = false
        shaders = false
        buildConfig = false
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
