package com.sonms.googlemediapipetest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import com.sonms.landmarkkit.FaceDetectionResult
import platform.UIKit.UIViewController

/** Transparent Compose overlay drawn above the native (Swift) camera preview. Call [update] on the main thread. */
class OverlayController {
    private var result by mutableStateOf<FaceDetectionResult?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    val viewController: UIViewController = ComposeUIViewController(configure = { opaque = false }) {
        FaceOverlay(result)
    }

    fun update(result: FaceDetectionResult?) {
        this.result = result
    }
}
