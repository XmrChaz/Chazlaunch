plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The native Wayland compositor needs a cross-compiled libwayland-server under
// src/main/cpp/third_party (see cpp/README.md). That blob isn't committed, so
// the native build is wired in only when it's present — or forced with
// -Pchaz.native=true. This lets CI validate the whole Kotlin/Compose/resource/
// manifest pipeline (assembleDebug) without the vendored dependency.
val nativeEnabled =
    file("src/main/cpp/third_party/wayland").isDirectory ||
        (project.findProperty("chaz.native") == "true")

android {
    namespace = "com.chazlaunch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chazlaunch"
        minSdk = 29          // Android 10 — scoped storage + modern SurfaceControl APIs
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The release workflow passes the hosted rootfs tarball URL here
        // (-PchazRootfsUrl=...); empty falls back to InstallManager's default.
        val rootfsUrl = (project.findProperty("chazRootfsUrl") as String?).orEmpty()
        buildConfigField("String", "ROOTFS_URL", "\"$rootfsUrl\"")

        // Optional nested full-desktop session (-PchazSession=hyprland|sway|labwc).
        // "none" = the default per-window-as-task model only.
        val session = (project.findProperty("chazSession") as String?)?.ifBlank { "none" } ?: "none"
        buildConfigField("String", "SESSION", "\"$session\"")

        ndk {
            // The Wayland compositor is native and only ships for 64-bit ARM,
            // which is what NixOS-aarch64 under PRoot targets anyway.
            abiFilters += listOf("arm64-v8a")
        }

        if (nativeEnabled) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
        }
    }

    if (nativeEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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
        compose = true
        buildConfig = true
    }

    lint {
        // Keep lint informational in CI (report is uploaded as an artifact);
        // assembleDebug is the gating build step.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // proot and its loader must stay as real files (PRoot execs them),
            // so keep them uncompressed and extracted on install.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
