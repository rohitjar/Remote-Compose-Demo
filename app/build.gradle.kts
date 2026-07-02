plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    //alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.remotecompose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.remotecompose"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha13")
    implementation("androidx.compose.remote:remote-creation-compose:1.0.0-alpha13")
    implementation("androidx.compose.remote:remote-player-compose:1.0.0-alpha13")
    implementation("androidx.compose.remote:remote-player-core:1.0.0-alpha13")
    implementation("androidx.compose.remote:remote-player-view:1.0.0-alpha13")
    implementation("com.github.bumptech.glide:glide:4.14.2")
    // Coil for the non-blocking RemoteCompose BitmapLoader (memory-cache lookup + async warm-up).
    implementation("io.coil-kt.coil3:coil:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}