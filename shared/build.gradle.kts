import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinCocoapods)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // CocoaPods integration: the app links MediaPipe through the Podfile, and the pod
    // must also be declared here because landmarkkit's cinterop depends on it.
    cocoapods {
        summary = "Common UI for LandmarkKit"
        homepage = "https://example.invalid/landmarkkit"
        version = "1.0"
        ios.deploymentTarget = "17.0"
        framework {
            baseName = "Shared"
            isStatic = true
            export(project(":landmarkkit"))
        }
        pod("MediaPipeTasksVision") {
            version = libs.versions.mediapipe.get()
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

    android {
       namespace = "com.sonms.googlemediapipetest.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            api(project(":landmarkkit"))
            implementation(libs.okio)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
