import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "dev.romainguy"
version = "0.1.0"

kotlin {
    jvm()

    linuxArm64()
    macosArm64()

    androidLibrary {
        namespace = "dev.romainguy.vibrance"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    iosArm64()
    iosSimulatorArm64()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    js {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "library", version.toString())

    pom {
        name = "Vibrance"
        description = "Better color mixing for Kotlin."
        inceptionYear = "2026"
        url = "https://github.com/romainguy/vibrance"
        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "romainguy"
                name = "Romain Guy"
                url = "https://www.romainguy.dev"
            }
        }
        scm {
            url = "https://github.com/romainguy/vibrance"
            connection = "scm:git:git://github.com/romainguy/vibrance.git"
            developerConnection = "scm:git:ssh://git@github.com/romainguy/vibrance.git"
        }
    }
}
