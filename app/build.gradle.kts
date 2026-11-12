import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Address and public key of the community server (see supabase/README.md).
// Both are meant to be shipped inside the app; what a person may do is decided by the server.
val cloud = Properties().apply {
    val file = rootProject.file("cloud.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun cloudValue(name: String): String = "\"" + (cloud.getProperty(name) ?: "").trim().replace("\\", "").replace("\"", "") + "\""

// The key the published builds are signed with. It is not part of the repository: keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword) lies next to this project on the machine that
// publishes. Without that file the release variant is signed with the debug key, which is enough to
// try it on your own phone but not to publish: an update must carry the same signature as the app
// it replaces.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val ownKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystore.getProperty(it).isNullOrBlank() }

android {
    namespace = "jxpix.proger.mps"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "jxpix.proger.mps"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.4-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUD_URL", cloudValue("url"))
        buildConfigField("String", "CLOUD_KEY", cloudValue("key"))
        buildConfigField("String", "CLOUD_FALLBACK_URL", cloudValue("fallbackUrl"))
        buildConfigField("String", "SITE_URL", cloudValue("site"))
    }

    signingConfigs {
        if (ownKey) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile").trim())
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias").trim()
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // The release variant is the one to judge latency and CPU load with, so it has to be
            // installable straight from Android Studio even without a key of your own.
            signingConfig = signingConfigs.getByName(if (ownKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // plain JVM tests may run code that writes to the Android log
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        // the app is translated into these languages; texts of libraries in other languages are left out
        localeFilters += listOf("en", "ru")
    }
    bundle {
        // The language can be switched inside the app, so every translation has to be installed
        // even when the app is delivered as an app bundle.
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // MP3 export: a Java port of the LAME encoder (LGPL 2.1), used as an unmodified library
    implementation(libs.jump3r)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}