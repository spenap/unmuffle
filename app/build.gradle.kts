plugins {
    alias(libs.plugins.android.application)
}

// Version from git, so every build says exactly what it was built from.
// versionCode must grow with each install: the commit count does.
fun git(vararg args: String) = providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()

android {
    namespace = "com.simonpena.unmuffle"
    compileSdk = 37
    ndkVersion = "27.2.12479018"  // NDK r27c — 16KB page size support

    defaultConfig {
        applicationId = "com.simonpena.unmuffle"
        minSdk = 26
        targetSdk = 37
        versionCode = git("rev-list", "--count", "HEAD").toInt()
        versionName = git("describe", "--tags", "--always", "--dirty")
        manifestPlaceholders["appLabel"] = "Unmuffle"
        testInstrumentationRunner = "com.simonpena.unmuffle.AwakeRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")  // arm64-only per CONTEXT.md locked decision
        }

        externalNativeBuild {
            cmake {
                // 16 KB-aligned libraries, ggml's included, for 16 KB-page devices
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                // -O3 in every build: debug builds unoptimised take minutes, and
                // release builds default to -O2.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Spike: -Pgpu=opencl adds ggml's OpenCL backend
                arguments += "-DUNMUFFLE_GPU=${providers.gradleProperty("gpu").getOrElse("off")}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Tagged releases only (scripts/release.sh): signed with the release key
    // when UNMUFFLE_KEYSTORE is set. Otherwise release builds use the debug key.
    val keystore = System.getenv("UNMUFFLE_KEYSTORE")?.let(::file)
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("UNMUFFLE_KEYSTORE_PASSWORD")
            keyAlias = "transchat"
            keyPassword = System.getenv("UNMUFFLE_KEYSTORE_PASSWORD")
        }
    }

    // No dependency list encrypted for Google Play: it differs on every build
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            // A separate app next to the release one, with its own model.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Unmuffle debug"
            isMinifyEnabled = false
        }
        release {
            // scripts/bench.sh: a release build next to the real app, debug-signed
            if (providers.gradleProperty("bench").isPresent) {
                // With -Pgpu, a third app: .bench.opencl
                val gpu = providers.gradleProperty("gpu").orNull
                applicationIdSuffix = ".bench" + (gpu?.let { "." + it.replace("-", "_") } ?: "")
                manifestPlaceholders["appLabel"] = "Unmuffle bench" + (gpu?.let { " $it" } ?: "")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ggml finds its CPU backends by listing the native library directory,
    // so the libraries are extracted on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libparakeet.so"  // whisper.cpp builds it; not used
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // android.util.Log in JVM tests returns defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 }
}
