import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.clotube.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clotube.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Include target ABIs for LibVLC native playback
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists() && keystoreProperties.containsKey("storeFile")) {
                val storePath = keystoreProperties.getProperty("storeFile")
                storeFile = file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword") ?: ""
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
                enableV1Signing = true
                enableV2Signing = true
            } else {
                val storeFilePath = project.findProperty("CLOTUBE_KEYSTORE_FILE")?.toString()
                    ?: project.findProperty("CLOTUBE_RELEASE_STORE_FILE")?.toString()

                if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                    storeFile = file(storeFilePath)
                    storePassword = project.findProperty("CLOTUBE_KEYSTORE_PASSWORD")?.toString()
                        ?: project.findProperty("CLOTUBE_RELEASE_STORE_PASSWORD")?.toString() ?: ""
                    keyAlias = project.findProperty("CLOTUBE_KEY_ALIAS")?.toString()
                        ?: project.findProperty("CLOTUBE_RELEASE_KEY_ALIAS")?.toString() ?: ""
                    keyPassword = project.findProperty("CLOTUBE_KEY_PASSWORD")?.toString()
                        ?: project.findProperty("CLOTUBE_RELEASE_KEY_PASSWORD")?.toString() ?: ""
                    enableV1Signing = true
                    enableV2Signing = true
                } else {
                    val debugConfig = signingConfigs.getByName("debug")
                    storeFile = debugConfig.storeFile
                    storePassword = debugConfig.storePassword
                    keyAlias = debugConfig.keyAlias
                    keyPassword = debugConfig.keyPassword
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }
    }

    buildTypes {
        release {
            // Keep code and resources 1:1 identical to debug build to prevent any runtime breakage
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false

            signingConfig = signingConfigs.getByName("release")
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
