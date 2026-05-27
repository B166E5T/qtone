plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qtone.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qtone.app"
        minSdk = 24
        targetSdk = 35
        // Versioning policy for in-app updates:
        //   versionCode: monotonically increasing integer. The update checker
        //     compares this against the server JSON's "versionCode" field.
        //     If server > installed, an update prompt appears.
        //   versionName: human-readable label shown in About / Settings and in
        //     the update prompt's "Version X is available" text.
        // BUMP versionCode by 1 EVERY release. Never reuse a value.
        versionCode = 109
        versionName = "1.0.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Generates a BuildConfig class with VERSION_CODE / VERSION_NAME at
        // compile time. UpdateChecker reads BuildConfig.VERSION_CODE to compare
        // against the remote manifest. Default Android Gradle 8+ disables this
        // for build speed — we opt back in because we need it for self-update.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Build types. The default `debug` is what Android Studio's "Run" button
    // installs by default. It is dramatically slower than the release build
    // because:
    //   - Kotlin code is not optimized (no inlining, no dead-code elimination)
    //   - R8 is not enabled (no minification, no class shrinking)
    //   - Debug assertions and logging are active
    //   - The JIT compiler has to warm up your scrolling hot paths from scratch
    //
    // For real perf testing on Fire TV, ALWAYS install the release build:
    //
    //     ./gradlew installRelease
    //
    // The release build is typically 3-5x faster on the same hardware. If
    // your scrolling feels clunky in debug, that is mostly the debug build
    // itself, not your code.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release builds must be signed. For local testing we sign with
            // the debug keystore so `./gradlew installRelease` "just works"
            // without manual signing config. Replace this with a real
            // release keystore before shipping to the Amazon Appstore /
            // Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Debug build stays as the default — fast incremental builds,
            // useful for development. Just remember it is NOT a perf
            // benchmark.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // TV Compose — Google's official TV-optimized components.
    // Provides Surface with built-in focus animations (scale, glow, border)
    // that run on the render thread for buttery-smooth navigation.
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.code.gson:gson:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
