import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinCocoapods)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.sonms.landmarkkit"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest { }
    }

    cocoapods {
        summary = "Face/Hand landmark detection core for Android and iOS"
        homepage = "https://example.invalid/landmarkkit"
        version = "0.1.0"
        ios.deploymentTarget = "17.0"
        framework {
            baseName = "LandmarkKit"
            isStatic = true
        }
        pod("MediaPipeTasksVision") {
            version = libs.versions.mediapipe.get()
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.mediapipe.tasksVision)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
