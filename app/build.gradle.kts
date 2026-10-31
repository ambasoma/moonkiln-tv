import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time secrets live in local.properties (not in git): the bearer token of the mini endpoint and
// where to find it on the LAN. The TV only ever talks to the mini over the home network.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val todayToken: String = localProps.getProperty("mk.todayToken", "")
val miniBase: String = localProps.getProperty("mk.miniBase", "http://192.168.0.14:9470")

android {
    namespace = "com.moonkiln.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moonkiln.tv"
        minSdk = 30
        targetSdk = 30
        versionCode = 2
        versionName = "0.2"
        // The OnePlus TV Y Series is a 32-bit MediaTek MT5621: armeabi-v7a only keeps the APK small.
        ndk { abiFilters += listOf("armeabi-v7a") }
        buildConfigField("String", "TODAY_TOKEN", "\"$todayToken\"")
        buildConfigField("String", "MINI_BASE", "\"$miniBase\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
    implementation("androidx.compose.animation:animation:1.10.6")
    implementation("androidx.compose.runtime:runtime:1.10.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.2")
}
