plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Version from git, so every build says exactly what it was built from.
// versionCode must grow with each install: the commit count does.
fun git(vararg args: String) = providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()

android {
    namespace = "com.simonpena.unmuffle"
    compileSdk = 35
    ndkVersion = "27.2.12479018"  // NDK r27c — 16KB page size support

    defaultConfig {
        applicationId = "com.simonpena.unmuffle"
        minSdk = 26
        targetSdk = 35
        versionCode = git("rev-list", "--count", "HEAD").toInt()
        versionName = git("describe", "--tags", "--always", "--dirty")
        manifestPlaceholders["appLabel"] = "Unmuffle"

        ndk {
            abiFilters += listOf("arm64-v8a")  // arm64-only per CONTEXT.md locked decision
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
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

    buildTypes {
        debug {
            // A separate app next to the release one, with its own model.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Unmuffle debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
}
