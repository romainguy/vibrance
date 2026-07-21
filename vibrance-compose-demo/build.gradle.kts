import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrainsCompose)
}

kotlin {
    android {
        namespace = "dev.romainguy.vibrance.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.app.minSdk.get().toInt()
        
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    
    jvm()
    
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":vibrance-compose"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.jetbrains.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
