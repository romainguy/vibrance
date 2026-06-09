plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.benchmark)
}

android {
    namespace = "dev.romainguy.vibrance.benchmark"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }
    lint {
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    testOptions {
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    testBuildType = "release"
    buildTypes {
        debug {
            // Since isDebuggable can't be modified by Gradle for library modules,
            // it must be done in a manifest - see src/androidTest/AndroidManifest.xml
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "benchmark-proguard-rules.pro"
            )
        }
        release {
            isDefault = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.junit.junit)
    androidTestImplementation(libs.benchmark.junit4)

    androidTestImplementation(project(":library"))
}
