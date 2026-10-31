plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.moonkiln.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moonkiln.tv"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1"
        // The OnePlus TV Y Series is a 32-bit MediaTek MT5621: armeabi-v7a only keeps the APK small.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildTypes {
        release {
            // Signed with the debug key for now: this is a sideloaded home launcher, not a Play upload.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }

    lint {
        // Sideloaded home launcher for one Android 11 set, never a Play upload: targetSdk 30 is deliberate.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui:1.10.6")
    implementation("androidx.compose.foundation:foundation:1.10.6")
    implementation("androidx.compose.runtime:runtime:1.10.6")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
