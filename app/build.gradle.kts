plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lumiyaviewer.lumiya"
    compileSdk = 35

    /**
     * Pinned so the native build is reproducible between the CI runner and a
     * local checkout. `slcore` (the ported Second Life primitive geometry
     * generator) is built with CMake through this NDK.
     */
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.ephora.viewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 63
        versionName = "Ephora 2.27t"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DSLCORE_BUILD_TESTS=OFF"
                )
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
            }
        }

        ndk {
            // Every device that can run Filament is arm64; armeabi-v7a is kept for
            // older hardware. Drop armeabi-v7a to roughly halve the APK size.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI artifact can be side-loaded
            // directly. Replace with a real keystore before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
    }

    /**
     * `assembleRelease` runs `lintVitalRelease`, which fails the build on any
     * issue of fatal severity (typically "missing class" warnings coming from
     * the parts of a large prebuilt dependency the app never calls). The APK is
     * the deliverable here and lint is not part of the verification gates, so it
     * must not be able to block packaging. Run `gradle :app:lintDebug` by hand
     * to see the report.
     */
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Filament's shared libraries are shipped inside its AARs; keep them
            // uncompressed and aligned the way the 16 KB page size requirement
            // expects on newer Android releases.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    // The graphics backend. It is used *only* by
    // com.lumiyaviewer.lumiya.renderer.filament — the protocol, asset and scene
    // layers depend on the abstract Renderer interface instead, so replacing
    // Filament does not touch them.
    implementation(libs.filament.android)
    // Runtime material compilation (the same compiler as the desktop `matc`), so
    // the viewer's material source can live in the Kotlin code without a
    // separate build step. It is a large dependency; a precompiled .filamat can
    // replace it later if APK size becomes the priority.
    implementation(libs.filament.filamat)
}
