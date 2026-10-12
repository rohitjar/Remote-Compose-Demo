plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    //alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.remotecompose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.remotecompose"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // No longer managed by the BOM (final release 1.7.8); used for list/back icons.
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha21")
    implementation("androidx.compose.remote:remote-creation-compose:1.0.0-alpha21")
    implementation("androidx.compose.remote:remote-player-compose:1.0.0-alpha21")
    implementation("androidx.compose.remote:remote-player-core:1.0.0-alpha21")
    implementation("androidx.compose.remote:remote-player-view:1.0.0-alpha21")
    implementation("com.github.bumptech.glide:glide:4.14.2")
    // Lottie: rasterises vector animations frame-by-frame for the USER: slot demo (RCLottieFromFile).
    implementation("com.airbnb.android:lottie:6.4.0")
    // Coil for the non-blocking RemoteCompose BitmapLoader (memory-cache lookup + async warm-up).
    implementation("io.coil-kt.coil3:coil:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Wire models (ScreenManifest/ScreenData/ScreenRequest) — same serializer as the server.
    implementation(libs.kotlinx.serialization.json)
    // HTTP inspection: Chucker records every request into an in-app UI (debug builds only —
    // the no-op artifact strips it from release); the logging interceptor mirrors it to logcat.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    debugImplementation("com.github.chuckerteam.chucker:library:4.1.0")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.1.0")
}