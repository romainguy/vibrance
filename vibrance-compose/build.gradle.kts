import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.dokka)
}

group = "dev.romainguy"
version = "0.1.0"

kotlin {
    jvm()

    iosArm64()
    iosSimulatorArm64()
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    macosArm64()

    @Suppress("OPT_IN_USAGE")
    wasmJs {
        browser()
    }

    js {
        browser()
    }

    androidLibrary {
        namespace = "dev.romainguy.vibrance.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources.enable = true

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":vibrance"))
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        val skikoMain by registering { dependsOn(commonMain.get()) }
        iosMain { dependsOn(skikoMain.get()) }
        macosMain { dependsOn(skikoMain.get()) }
        jvmMain { dependsOn(skikoMain.get()) }
        wasmJsMain { dependsOn(skikoMain.get()) }
        jsMain { dependsOn(skikoMain.get()) }
    }
}

mavenPublishing {
}
