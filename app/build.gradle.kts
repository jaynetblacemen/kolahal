import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Signing keys, from keystore.properties (git-ignored) or the environment (GitHub Actions):
// - the release key signs the APKs on GitHub: storeFile, storePassword, keyAlias, keyPassword, or
//   KOLAHAL_RELEASE_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD;
// - the upload key signs the bundle for Google Play: uploadStoreFile… or KOLAHAL_UPLOAD_….
// tools/make-keys.sh makes both.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** File, store password, alias and key password of a key, or null when any is missing. */
fun signingKey(prefix: String, env: String): List<String>? {
    val values = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").map { name ->
        val prop = if (prefix.isEmpty()) name else prefix + name.replaceFirstChar(Char::uppercase)
        val variable = "KOLAHAL_${env}_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()
        keystoreProps.getProperty(prop) ?: System.getenv(variable)?.takeIf { it.isNotEmpty() }
    }
    return if (values.all { it != null }) values.map { it!! } else null
}
val releaseKey = signingKey("", "RELEASE")
val uploadKey = signingKey("upload", "UPLOAD")

android {
    namespace = "com.mtwabpin.kolahal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mtwabpin.kolahal"
        minSdk = 28
        targetSdk = 37
        versionCode = 8
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The accessibility service (double-tap / `lock`), notification listener and call log are in
        // the manifest (see lite and play).
        buildConfigField("boolean", "LOCK_SERVICE", "true")
        buildConfigField("boolean", "NOTIFICATION_ACCESS", "true")
        buildConfigField("boolean", "CALL_LOG", "true")
    }

    signingConfigs {
        releaseKey?.let { (file, storePass, alias, keyPass) ->
            create("release") {
                storeFile = rootProject.file(file)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
        uploadKey?.let { (file, storePass, alias, keyPass) ->
            create("upload") {
                storeFile = rootProject.file(file)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without keystore.properties, sign with the debug key so the optimized build is still installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            // -Pcoverage: JaCoCo for the test runs (tools/coverage.pl reads the reports). Off by
            // default: it instruments the debug build and slows it down.
            val coverage = providers.gradleProperty("coverage").isPresent
            enableUnitTestCoverage = coverage
            enableAndroidTestCoverage = coverage
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
        // Google Play: the lite build without the call log too (src/play removes it), which Play
        // allows only to phone, SMS and assistant apps. `bundlePlay` signs it with the upload key;
        // without one the bundle stays unsigned, never debug-signed (Play refuses those).
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.findByName("upload")
            buildConfigField("boolean", "LOCK_SERVICE", "false")
            buildConfigField("boolean", "NOTIFICATION_ACCESS", "false")
            buildConfigField("boolean", "CALL_LOG", "false")
        }
    }

    // The same startup profile as release.
    sourceSets.getByName("lite").baselineProfiles.srcDir("src/release/generated/baselineProfiles")
    sourceSets.getByName("play").baselineProfiles.srcDir("src/release/generated/baselineProfiles")

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
    // Compose UI tests of the home screen (the test activity comes with ui-test-manifest, debug only).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
