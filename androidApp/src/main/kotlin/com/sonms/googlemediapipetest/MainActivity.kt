package com.sonms.googlemediapipetest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sonms.googlemediapipetest.record.ComparisonRecorder
import com.sonms.googlemediapipetest.record.EnvironmentInfo
import com.sonms.googlemediapipetest.record.SessionRecorder
import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.FaceDetector
import com.sonms.landmarkkit.FaceDetectorConfig
import com.sonms.landmarkkit.createFaceDetector
import com.sonms.landmarkkit.toFrame
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var result by mutableStateOf<FaceDetectionResult?>(null)
    private var recorderUi by mutableStateOf(RecorderUi())
    private var compareMode by mutableStateOf(false)
    private var cameraGranted by mutableStateOf(false)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Touched only on analysisExecutor. One detector per comparison lane; lane 0 is also the single-mode detector.
    private val detectors = ArrayList<FaceDetector>()
    private var recorder: SessionRecorder? = null
    private var comparison: ComparisonRecorder? = null

    private val config = FaceDetectorConfig(MODEL_ASSET, outputBlendshapes = true, outputTransformationMatrix = true)

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            if (cameraGranted) {
                App(
                    result,
                    recorderUi.copy(compareMode = compareMode),
                    onToggleRecording = { analysisExecutor.execute(::toggleRecording) },
                    onToggleCompare = { if (!recorderUi.recording) compareMode = !compareMode },
                    mirrorOverlay = true,
                ) {
                    AndroidView(
                        factory = { context -> PreviewView(context).also(::bindCamera) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Text("Camera permission is required.")
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            providerFuture.get().run {
                unbindAll()
                bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val timestampMs = SystemClock.uptimeMillis()
            val frame = image.toBitmap().toFrame(image.imageInfo.rotationDegrees, timestampMs)
            val compared = comparison
            if (compared != null) {
                ensureDetectors(compared.laneCount)
                val outcome = compared.onFrame(timestampMs) { lane -> detectors[lane].detect(frame) }
                outcome.results[0]?.let { result = it }
                updateElapsed(compared.elapsedMs())
                if (outcome.limitReached) stopRecording()
                return
            }
            ensureDetectors(1)
            val startNs = SystemClock.elapsedRealtimeNanos()
            val detected = detectors[0].detect(frame)
            val latencyMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            result = detected
            recorder?.let {
                val limitReached = it.onFrame(timestampMs, detected, latencyMs)
                updateElapsed(it.elapsedMs())
                if (limitReached) stopRecording()
            }
        }
    }

    private fun ensureDetectors(count: Int) {
        while (detectors.size < count) detectors += createFaceDetector(applicationContext, config)
    }

    private fun updateElapsed(elapsedMs: Long) {
        val elapsedSec = elapsedMs / 1000
        if (elapsedSec != recorderUi.elapsedSec) recorderUi = recorderUi.copy(elapsedSec = elapsedSec)
    }

    private fun toggleRecording() {
        if (recorder == null && comparison == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val outputDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        val env = EnvironmentInfo(
            platform = "android",
            osVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
            mediapipeVersion = BuildConfig.MEDIAPIPE_VERSION,
            cameraLens = "front",
            modelFileName = MODEL_ASSET,
            modelSizeBytes = assets.open(MODEL_ASSET).use { it.readBytes().size.toLong() },
        )
        val startedAt = System.currentTimeMillis()
        if (compareMode) {
            comparison = ComparisonRecorder(outputDir, env, config, startedAt)
        } else {
            recorder = SessionRecorder(outputDir, env, config, targetFps = 30, startedAtEpochMs = startedAt)
        }
        recorderUi = RecorderUi(recording = true)
    }

    private fun stopRecording() {
        recorder?.stop()
        comparison?.stop()
        recorder = null
        comparison = null
        recorderUi = RecorderUi(finished = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute {
            recorder?.stop()
            comparison?.stop()
            detectors.forEach { it.close() }
        }
        analysisExecutor.shutdown()
    }

    private companion object {
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
