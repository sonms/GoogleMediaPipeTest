package com.sonms.landmarkkit

/**
 * Options of the face detector. Mirrors every option of MediaPipe Face Landmarker; defaults equal MediaPipe's defaults.
 *
 * 얼굴 감지기의 옵션입니다. MediaPipe Face Landmarker의 모든 옵션을 그대로 노출하며, 기본값은 MediaPipe 기본값과 같습니다.
 *
 * @property modelPath location of the `.task` model, prepared by the consumer beforehand (the core never bundles or
 * downloads models). Android: asset path. iOS: absolute file path (e.g. from `Bundle.main.path`).
 * / `.task` 모델의 위치로, 소비하는 쪽이 미리 준비합니다(코어는 모델을 번들하거나 내려받지 않습니다).
 * Android는 assets 경로, iOS는 절대 파일 경로(예: `Bundle.main.path`)입니다.
 * @property maxFaces maximum number of faces detected per frame. / 프레임당 감지할 최대 얼굴 수입니다.
 * @property minFaceDetectionConfidence minimum confidence [0, 1] for the initial face detection to count as successful.
 * / 처음 얼굴 감지를 성공으로 인정하는 최소 신뢰도 [0, 1]입니다.
 * @property minFacePresenceConfidence minimum confidence [0, 1] that a face is still present; below it, detection
 * re-runs. / 얼굴이 계속 존재한다고 보는 최소 신뢰도 [0, 1]이며, 이보다 낮으면 감지를 다시 실행합니다.
 * @property minTrackingConfidence minimum confidence [0, 1] for tracking a face across frames; below it, detection
 * re-runs. / 프레임 사이에서 얼굴을 추적하는 최소 신뢰도 [0, 1]이며, 이보다 낮으면 감지를 다시 실행합니다.
 * @property outputBlendshapes fill [Face.blendshapes]; costs extra computation.
 * / [Face.blendshapes]를 채웁니다. 추가 연산 비용이 듭니다.
 * @property outputTransformationMatrix fill [Face.transformationMatrix]; costs extra computation.
 * / [Face.transformationMatrix]를 채웁니다. 추가 연산 비용이 듭니다.
 */
data class FaceDetectorConfig(
    val modelPath: String,
    val maxFaces: Int = 1,
    val minFaceDetectionConfidence: Float = 0.5f,
    val minFacePresenceConfidence: Float = 0.5f,
    val minTrackingConfidence: Float = 0.5f,
    val outputBlendshapes: Boolean = false,
    val outputTransformationMatrix: Boolean = false,
)

/**
 * Options of the hand detector (boundary only, not implemented yet).
 *
 * 손 감지기의 옵션입니다(경계만 정의했고 아직 구현하지 않았습니다).
 *
 * @property modelPath see [FaceDetectorConfig.modelPath]. / [FaceDetectorConfig.modelPath]를 참고하세요.
 * @property maxHands maximum number of hands detected per frame. / 프레임당 감지할 최대 손 수입니다.
 */
data class HandDetectorConfig(
    val modelPath: String,
    val maxHands: Int = 2,
)

/**
 * Detects faces in camera frames. Create one with [createFaceDetector]; a detector keeps tracking state between
 * frames, so use one instance per camera stream and call it from a single thread.
 *
 * 카메라 프레임에서 얼굴을 감지합니다. [createFaceDetector]로 만들며, 감지기는 프레임 사이의 추적 상태를 유지하므로
 * 카메라 스트림마다 인스턴스 하나를 쓰고 한 스레드에서만 호출해야 합니다.
 */
interface FaceDetector {
    /**
     * Runs detection in video mode: tracks faces across consecutive frames, so [Frame.timestampMs] must increase
     * monotonically between calls. Blocks until done; call from a background thread.
     *
     * 비디오 모드로 감지합니다. 연속된 프레임에서 얼굴을 추적하므로 호출 사이에 [Frame.timestampMs]가 단조 증가해야 합니다.
     * 끝날 때까지 블로킹되므로 백그라운드 스레드에서 호출하세요.
     *
     * @return the faces found in [frame]; [FaceDetectionResult.faces] is empty when none was found.
     * / [frame]에서 찾은 얼굴입니다. 없으면 [FaceDetectionResult.faces]가 비어 있습니다.
     */
    fun detect(frame: Frame): FaceDetectionResult

    /**
     * Releases native resources. The detector must not be used afterwards.
     * / 네이티브 자원을 해제합니다. 이후에는 이 감지기를 사용하면 안 됩니다.
     */
    fun close()
}

/**
 * Detects hands in camera frames. Boundary only: no implementation yet (see CLAUDE.md scope).
 *
 * 카메라 프레임에서 손을 감지합니다. 경계만 정의했고 아직 구현이 없습니다(CLAUDE.md의 범위 참고).
 */
interface HandDetector {
    /** Same contract as [FaceDetector.detect], for hands. / [FaceDetector.detect]와 같은 규칙을 손에 적용합니다. */
    fun detect(frame: Frame): HandDetectionResult

    /** Releases native resources. / 네이티브 자원을 해제합니다. */
    fun close()
}

/**
 * Platform handle needed to create detectors: Android `Context`, nothing on iOS (`IosPlatformContext()`).
 *
 * 감지기를 만들 때 필요한 플랫폼 핸들입니다. Android는 `Context`이고, iOS는 필요한 값이 없습니다(`IosPlatformContext()`).
 */
expect abstract class PlatformContext

/**
 * Creates a face detector backed by MediaPipe Face Landmarker.
 *
 * MediaPipe Face Landmarker 기반의 얼굴 감지기를 만듭니다.
 *
 * @param context Android `Context` (the application context is enough); on iOS pass `IosPlatformContext()`.
 * / Android `Context`입니다(애플리케이션 컨텍스트면 충분합니다). iOS에서는 `IosPlatformContext()`를 넘깁니다.
 * @param config detector options, including where the consumer placed the model file.
 * / 감지기 옵션이며, 소비하는 쪽이 모델 파일을 둔 위치도 포함합니다.
 */
expect fun createFaceDetector(context: PlatformContext, config: FaceDetectorConfig): FaceDetector
