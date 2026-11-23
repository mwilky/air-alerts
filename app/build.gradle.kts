import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.triplet.play")
}

android {
    namespace = "com.mwilky.airalerts"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mwilky.airalerts"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.9.0"
    }

    // The upload key, from ~/.gradle/gradle.properties so no secret lives in the repo. Without
    // the AIRALERTS_* properties, release builds stay unsigned.
    fun signingProperty(name: String) = providers.gradleProperty("AIRALERTS_$name")
    val uploadStoreFile = signingProperty("STORE_FILE").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("release") {
                storeFile = file(uploadStoreFile)
                storePassword = signingProperty("STORE_PASSWORD").get()
                keyAlias = signingProperty("KEY_ALIAS").get()
                keyPassword = signingProperty("KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug build with R8 on, for checking the shrunk app on device without a release signing
        // step. Pick it from Build Variants in Android Studio; plain "debug" stays unminified.
        create("debugMinified") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            matchingFallbacks += listOf("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // BouncyCastle's post-quantum tables and message bundles; only its DTLS 1.2 PSK server is used.
        resources.excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Releases straight to everyone on production, with the notes in src/main/play/release-notes,
// once Play's review passes. The service account key is the file AIRALERTS_PLAY_CREDENTIALS names
// in gradle.properties.
play {
    providers.gradleProperty("AIRALERTS_PLAY_CREDENTIALS").orNull?.let { serviceAccountCredentials.set(file(it)) }
    defaultToAppBundles.set(true)
    track.set("production")
    releaseStatus.set(ReleaseStatus.COMPLETED)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    // The Fitbit Air's secure channel is DTLS 1.2 with a pre-shared key, with the phone as the server.
    implementation(libs.bctls)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
