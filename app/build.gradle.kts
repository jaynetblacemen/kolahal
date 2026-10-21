import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Optional release signing: create keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.mtwabpin.kolahal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mtwabpin.kolahal"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The accessibility service (double-tap / `lock`) and notification listener are in the manifest (see lite).
        buildConfigField("boolean", "LOCK_SERVICE", "true")
        buildConfigField("boolean", "NOTIFICATION_ACCESS", "true")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Without keystore.properties, sign with the debug key so the optimized build is still installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // The release build without the accessibility service and notification listener (src/lite
        // removes them): Play Protect blocks sideloaded apps that declare either. No `lock` or
        // double-tap lock, no notifications in the log, no media controls.
        create("lite") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "LOCK_SERVICE", "false")
            buildConfigField("boolean", "NOTIFICATION_ACCESS", "false")
        }
    }

    // The same startup profile as release.
    sourceSets.getByName("lite").baselineProfiles.srcDir("src/release/generated/baselineProfiles")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.work.multiprocess)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // Scripting engines: native Lua 5.4 (LuaJava) and QuickJS (quickjs-kt)
    implementation(libs.luajava)
    implementation(libs.luajava.lua54)
    runtimeOnly(variantOf(libs.luajava.android) { classifier("lua54"); artifactType("aar") })
    implementation(libs.quickjs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
