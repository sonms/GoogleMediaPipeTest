@file:OptIn(ExperimentalForeignApi::class)

package com.sonms.landmarkkit

import cocoapods.MediaPipeTasksVision.MPPCategory
import cocoapods.MediaPipeTasksVision.MPPClassifications
import cocoapods.MediaPipeTasksVision.MPPFaceLandmarker
import cocoapods.MediaPipeTasksVision.MPPFaceLandmarkerOptions
import cocoapods.MediaPipeTasksVision.MPPImage
import cocoapods.MediaPipeTasksVision.MPPNormalizedLandmark
import cocoapods.MediaPipeTasksVision.MPPRunningModeVideo
import cocoapods.MediaPipeTasksVision.MPPTransformMatrix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError

actual abstract class PlatformContext

/**
 * Concrete iOS handle; pass `IosPlatformContext()` to [createFaceDetector].
 * / iOS용 구체 핸들입니다. [createFaceDetector]에 `IosPlatformContext()`를 넘기세요.
 */
class IosPlatformContext : PlatformContext()

actual fun createFaceDetector(context: PlatformContext, config: FaceDetectorConfig): FaceDetector =
    IosFaceDetector(config)

internal class IosFaceDetector(config: FaceDetectorConfig) : FaceDetector {
    private val landmarker: MPPFaceLandmarker

    init {
        val options = MPPFaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = config.modelPath
        options.runningMode = MPPRunningModeVideo
        options.numFaces = config.maxFaces.toLong()
        options.minFaceDetectionConfidence = config.minFaceDetectionConfidence
        options.minFacePresenceConfidence = config.minFacePresenceConfidence
        options.minTrackingConfidence = config.minTrackingConfidence
        options.outputFaceBlendshapes = config.outputBlendshapes
        options.outputFacialTransformationMatrixes = config.outputTransformationMatrix
        landmarker = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            MPPFaceLandmarker(options = options, error = error.ptr)
                ?: error("LandmarkKit: failed to create FaceLandmarker: ${error.value?.localizedDescription}")
        }
    }

    override fun detect(frame: Frame): FaceDetectionResult {
        val data = frame.native as IosFrameData
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val image = MPPImage(sampleBuffer = data.sampleBuffer, orientation = data.orientation, error = error.ptr)
                ?: error("LandmarkKit: failed to wrap frame: ${error.value?.localizedDescription}")
            val result = landmarker.detectInVideoFrame(
                image,
                timestampInMilliseconds = frame.timestampMs,
                error = error.ptr,
            ) ?: error("LandmarkKit: detection failed: ${error.value?.localizedDescription}")
            val blendshapes = result.faceBlendshapes as List<*>
            val matrices = result.facialTransformationMatrixes as List<*>
            FaceDetectionResult(
                result.faceLandmarks.mapIndexed { i, face ->
                    Face(
                        landmarks = (face as List<*>).map {
                            val landmark = it as MPPNormalizedLandmark
                            Landmark(landmark.x, landmark.y, landmark.z, landmark.visibility?.floatValue, landmark.presence?.floatValue)
                        },
                        blendshapes = (blendshapes.getOrNull(i) as? MPPClassifications)?.categories?.map {
                            val category = it as MPPCategory
                            Blendshape(category.index.toInt(), category.categoryName.orEmpty(), category.score)
                        },
                        transformationMatrix = (matrices.getOrNull(i) as? MPPTransformMatrix)?.let { m ->
                            List((m.rows * m.columns).toInt()) { m.data!![it] }
                        },
                    )
                },
            )
        }
    }

    // MPPFaceLandmarker has no explicit close; resources are released when the object is deallocated.
    override fun close() = Unit
}
