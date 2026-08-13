plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.portalremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.portalremote"
        minSdk = 26
        targetSdk = 36
        // Overridable so a tagged CI build stamps the tag instead of the checked-in
        // default: ./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=7
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1.0"
    }

    // Release signing comes from the environment (CI secrets) when present. Without
    // it the release build falls back to the debug key below, so a local or PR build
    // still produces an installable APK — it just isn't the shipping identity.
    signingConfigs {
        val storeFilePath = System.getenv("ANDROID_KEYSTORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The server speaks plain HTTP on the LAN, allowed via the network
            // security config rather than a blanket cleartext opt-in.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Only for the browser's private tabs: WebView's cookie jar is process-global, so
    // real isolation needs the Profile API this adds. Feature-detected at runtime —
    // it needs WebView 114+, and the browser degrades rather than breaks without it.
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.accompanist.permissions)

    // JVM-only: the mirror's touch->desktop transform can't be exercised on a
    // device (injecting a pinch needs /dev/input access SELinux won't grant).
    testImplementation(kotlin("test"))
    // android.jar's org.json is a stub whose every method throws; the real one has
    // to come first on the test classpath for ShareEntry.fromPush to be testable.
    testImplementation(libs.json)
}
