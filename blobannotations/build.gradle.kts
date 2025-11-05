//import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
//import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmTarget
//import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
//import org.gradle.api.attributes.TargetJvmEnvironment
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

android {
    namespace = "coredevices.blobannotations"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 26
        lint.targetSdk = compileSdk
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
    }

    kotlin {
        jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    }
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
    }

    jvm()

    val xcfName = "libpebble-annotations"

    iosX64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                // Your dependencies here
            }
        }
        commonTest {
            dependencies {
                // Include testing dependencies such as junit here.
            }
        }
        androidMain {

        }
        iosMain {

        }
        jvmMain {

        }
    }
}
