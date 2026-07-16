plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the SRS server address (see gradle.properties).
val srsHost: String = (project.findProperty("SRS_HOST") as String?) ?: "192.168.100.22:1985"

android {
    namespace = "com.example.screenwhip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenwhip"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Injected as @string/default_server (the app's default Server field).
        resValue("string", "default_server", srsHost)
    }

    buildTypes {
        release {
            // R8: strip unused code + resources. ~48MB APK is dominated by the
            // WebRTC .so, but this still trims dex/resources meaningfully.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use debug signing so the optimized release APK is installable for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Biggest win: ship one .so per ABI instead of all four in one APK.
    // Each per-ABI APK is ~1/4 the size (~13-15MB vs ~48MB). Install the ABI
    // that matches the target (arm64-v8a for phones, x86_64 for the emulator).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // Also emit a universal APK (all ABIs) that installs on ANY device —
            // use this if unsure which ABI a device needs.
            isUniversalApk = true
        }
    }

    // Drop packaging cruft that bloats the archive.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin/**",
                "**/*.txt"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

// Name the output APKs GabutPoC-<abi>-<buildType>.apk
base {
    archivesName.set("GabutPoC")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // WebRTC (org.webrtc API), maintained fork on Maven Central.
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // HTTP client for WHIP signaling.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Generate web-viewer/config.js from the same SRS_HOST, so the dashboard and
// viewer share one source of truth with the app. Runs before every build.
val generateWebConfig by tasks.registering {
    val host = srsHost
    val outFile = rootProject.file("../web-viewer/config.js")
    inputs.property("host", host)
    outputs.file(outFile)
    doLast {
        outFile.parentFile.mkdirs()
        outFile.writeText(
            "// AUTO-GENERATED from android/gradle.properties (SRS_HOST). Do not edit by hand.\n" +
                "window.SRS_HOST = '$host';\n"
        )
    }
}
tasks.named("preBuild") { dependsOn(generateWebConfig) }
