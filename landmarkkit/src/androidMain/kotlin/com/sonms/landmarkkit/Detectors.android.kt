package com.sonms.landmarkkit

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

actual typealias PlatformContext = Context

actual fun createFaceDetector(context: PlatformContext, config: FaceDetectorConfig): FaceDetector =
    AndroidFaceDetector(context, config)

/**
 * Wraps [this] without copying. [rotationDegrees] is the clockwise rotation that makes it upright.
 * / 복사 없이 [this]를 감쌉니다. [rotationDegrees]는 바로 세우는 데 필요한 시계 방향 회전각입니다.
 */
fun Bitmap.toFrame(rotationDegrees: Int, timestampMs: Long): Frame =
    Frame(width, height, rotationDegrees, timestampMs, this)

internal class AndroidFaceDetector(context: Context, config: FaceDetectorConfig) : FaceDetector {
    private val landmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(config.modelPath).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(config.maxFaces)
            .setMinFaceDetectionConfidence(config.minFaceDetectionConfidence)
            .setMinFacePresenceConfidence(config.minFacePresenceConfidence)
            .setMinTrackingConfidence(config.minTrackingConfidence)
            .setOutputFaceBlendshapes(config.outputBlendshapes)
            .setOutputFacialTransformationMatrixes(config.outputTransformationMatrix)
            .build(),
    )

    override fun detect(frame: Frame): FaceDetectionResult {
        val image = BitmapImageBuilder(frame.native as Bitmap).build()
        val options = ImageProcessingOptions.builder().setRotationDegrees(frame.rotationDegrees).build()
        val result = landmarker.detectForVideo(image, options, frame.timestampMs)
        val blendshapes = result.faceBlendshapes().orElse(null)
        val matrices = result.facialTransformationMatrixes().orElse(null)
        return FaceDetectionResult(
            result.faceLandmarks().mapIndexed { i, face ->
                Face(
                    landmarks = face.map {
                        // MediaPipe reports x/y in the unrotated bitmap; rotate them to the upright frame.
                        val (x, y) = when (frame.rotationDegrees) {
                            90 -> 1f - it.y() to it.x()
                            180 -> 1f - it.x() to 1f - it.y()
                            270 -> it.y() to 1f - it.x()
                            else -> it.x() to it.y()
                        }
                        Landmark(x, y, it.z(), it.visibility().orElse(null), it.presence().orElse(null))
                    },
                    blendshapes = blendshapes?.getOrNull(i)?.map { Blendshape(it.index(), it.categoryName(), it.score()) },
                    transformationMatrix = matrices?.getOrNull(i)?.toList(),
                )
            },
        )
    }

    override fun close() = landmarker.close()
}
