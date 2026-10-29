plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val abis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "app.serene"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.helmi.browser"
        minSdk = 26
        targetSdk = 36
        // CI run number, so each build installs over the previous one. The release workflow counts
        // its own runs, so releases go 1, 2, 3 (under their own app id, see buildTypes).
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get().toInt()
        // The release tag without its v (v0.2.0 is 0.2.0), set by the release workflow.
        versionName = providers.environmentVariable("HELMI_VERSION").orElse("0.1.0").get()
        manifestPlaceholders["appLabel"] = "Helmi"
        ndk { abiFilters += abis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"${providers.environmentVariable("GITHUB_SHA").orElse("local").get().take(7)}\"")
    }

    signingConfigs {
        // Committed on purpose: every debug APK (CI or local) is signed with the same key, so a
        // new build updates the installed app instead of needing an uninstall. Debug only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Releases: the private key the release workflow takes from the repo's secrets
        // (docs/releases.md). Without it, as in a local build, the release APK comes out unsigned.
        providers.environmentVariable("HELMI_KEYSTORE").orNull?.let { keystore ->
            create("release") {
                val password = providers.environmentVariable("HELMI_KEYSTORE_PASSWORD").get()
                storeFile = file(keystore)
                storePassword = password
                keyAlias = providers.environmentVariable("HELMI_KEY_ALIAS").get()
                keyPassword = password // a PKCS12 keystore (keytool's default) has one password
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Its own app id and name: the release is signed with another key, so neither can update
            // the other, and this way a PR build installs next to the release instead.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Helmi Debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // No R8 for now: LiteRT-LM, the JNI rules engine and kotlinx.serialization would need
            // keep rules, and only the debug build runs the emulator tests.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The content script is the engine prototype's file, unchanged: one source of truth.
            assets.directories += "../../engine/src/content"
            // autoconsent.js and its rules + config, built by `npm run build` in engine/ and committed.
            assets.directories += "../../engine/dist"
            // The model eval's labelled overlays, for the on-device benchmark in settings.
            assets.directories += "../../engine/test/data"
            jniLibs.directories += layout.buildDirectory.dir("rustJniLibs").get().asFile.path
        }
        // The prototype's fixture pages, for the on-device end-to-end test.
        getByName("androidTest") { assets.directories += "../../engine/test/fixtures" }
    }

    packaging {
        // Compressed native libs: a much smaller APK to download and sideload. The Qualcomm NPU
        // libraries need them extracted too: the DSP loads its half (the Skel) from a file path.
        jniLibs.useLegacyPackaging = true
        // The QNN runtime ships every Hexagon generation, the DSP and GPU backends and the HTP's
        // on-device compiler (HtpPrepare, 85 MB, for models not compiled ahead of time). Serene's
        // NPU models are compiled ahead of time (android/npu-model), so it keeps the HTP backend
        // and the halves for the chips there is a model for: V79, the Snapdragon 8 Elite (SM8750).
        // A model for another chip (ModelCatalog) needs that generation's Stub and Skel kept.
        jniLibs.excludes += listOf(
            "**/libQnnDsp*.so",
            "**/libQnnGpu.so",
            "**/libQnnHtpPrepare.so",
            "**/libQnnHtpV68*.so",
            "**/libQnnHtpV69*.so",
            "**/libQnnHtpV73*.so",
            "**/libQnnHtpV75*.so",
            "**/libQnnHtpV81*.so",
        )
    }
}

// adblock-rust over JNI: build android/rules-jni with cargo-ndk for each ABI.
val cargoBuild by tasks.registering(Exec::class) {
    val crate = rootProject.file("rules-jni")
    val out = layout.buildDirectory.dir("rustJniLibs")
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    inputs.dir(crate.resolve("src"))
    inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"))
    outputs.dir(out)
    workingDir = crate
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.path) }
    val targets = abis.flatMap { listOf("-t", it) }
    commandLine(listOf("cargo", "ndk") + targets + listOf("-P", "26", "-o", out.get().asFile.path, "build", "--release"))
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.guava)
    implementation(libs.litertlm)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.qnn.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
