import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.clotube.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clotube.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true

            // Checks keystore.properties, gradle.properties, or environment variables for the developer's original keystore
            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties()
            if (keystorePropsFile.exists()) {
                keystoreProps.load(FileInputStream(keystorePropsFile))
            }
            val storeFilePath = keystoreProps.getProperty("storeFile")
                ?: project.findProperty("CLOTUBE_KEYSTORE_FILE")?.toString()
                ?: project.findProperty("CLOTUBE_RELEASE_STORE_FILE")?.toString()

            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: project.findProperty("CLOTUBE_KEYSTORE_PASSWORD")?.toString()
                    ?: project.findProperty("CLOTUBE_RELEASE_STORE_PASSWORD")?.toString() ?: ""
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: project.findProperty("CLOTUBE_KEY_ALIAS")?.toString()
                    ?: project.findProperty("CLOTUBE_RELEASE_KEY_ALIAS")?.toString() ?: ""
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: project.findProperty("CLOTUBE_KEY_PASSWORD")?.toString()
                    ?: project.findProperty("CLOTUBE_RELEASE_KEY_PASSWORD")?.toString() ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Safely bind to release signing if configured; otherwise allow manual Android Studio signing wizard
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig != null && releaseConfig.storeFile != null && releaseConfig.storeFile!!.exists()) {
                signingConfig = releaseConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            // CRITICAL: Do NOT append applicationIdSuffix here.
            // Package name must remain strictly "com.clotube.app" to match Google Cloud Console OAuth.
            applicationIdSuffix = null
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/*/*.so")
            // Prevent R8 from stripping debug symbols needed by LibVLC native decoders
            keepDebugSymbols += listOf("lib/*/*.so")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Jetpack Compose BOM & Core UI
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // LibVLC Native Player Engine
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("androidx.media:media:1.7.0")

    // Network & Image Loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Google Identity & Drive API
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Debugging & Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
