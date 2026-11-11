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

android {
    namespace = "jxpix.proger.mps"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "jxpix.proger.mps"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUD_URL", cloudValue("url"))
        buildConfigField("String", "CLOUD_KEY", cloudValue("key"))
        buildConfigField("String", "CLOUD_FALLBACK_URL", cloudValue("fallbackUrl"))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Signed with the debug key so that the release variant can be installed straight from
            // Android Studio: it is the one to judge latency and CPU load with. Use your own key to publish.
            signingConfig = signingConfigs.getByName("debug")
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