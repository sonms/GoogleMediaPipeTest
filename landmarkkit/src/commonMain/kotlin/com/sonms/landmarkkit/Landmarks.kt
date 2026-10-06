package com.sonms.landmarkkit

/**
 * Normalized landmark: x and y are in [0, 1] relative to the (upright) frame, z is relative depth.
 * [visibility] and [presence] are null when the model does not provide them (Face Landmarker usually does not).
 *
 * 정규화된 랜드마크입니다. x, y는 (바로 세운) 프레임 기준 [0, 1] 범위이고, z는 상대 깊이입니다.
 * [visibility]와 [presence]는 모델이 제공하지 않으면 null입니다(Face Landmarker는 보통 제공하지 않습니다).
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float? = null,
    val presence: Float? = null,
)

/**
 * One of the 52 face blendshape coefficients that describe facial expression.
 *
 * 표정을 나타내는 52개 얼굴 블렌드셰이프 계수 중 하나입니다.
 *
 * @property index position in MediaPipe's fixed blendshape order (stable across frames).
 * / MediaPipe의 고정된 블렌드셰이프 순서에서의 위치입니다(프레임이 바뀌어도 같습니다).
 * @property name MediaPipe's category name, e.g. `jawOpen`, `mouthSmileLeft`.
 * / MediaPipe가 부여한 이름입니다. 예: `jawOpen`, `mouthSmileLeft`.
 * @property score activation of this expression in [0, 1]; model-estimated, not ground truth.
 * / 이 표정의 활성도 [0, 1]입니다. 모델이 추정한 값이며 정답이 아닙니다.
 */
data class Blendshape(val index: Int, val name: String, val score: Float)

/**
 * One detected face.
 *
 * 감지된 얼굴 하나입니다.
 *
 * @property landmarks 478 landmarks (468 face mesh + 10 iris).
 * / 랜드마크 478개입니다(얼굴 메시 468개 + 홍채 10개).
 * @property blendshapes null unless [FaceDetectorConfig.outputBlendshapes] is true.
 * / [FaceDetectorConfig.outputBlendshapes]가 true일 때만 값이 있고, 아니면 null입니다.
 * @property transformationMatrix 4x4 head pose matrix (16 values) in the order MediaPipe returns them; the
 * row/column-major layout is not guaranteed here, detect it via the affine [0,0,0,1] row or column.
 * Null unless [FaceDetectorConfig.outputTransformationMatrix] is true.
 * / 머리 자세를 나타내는 4x4 행렬(16개 값)이며 MediaPipe가 반환한 순서 그대로입니다. 행 우선인지 열 우선인지는
 * 보장되지 않으므로 아핀 행 또는 열 [0,0,0,1]의 위치로 판별해야 합니다.
 * [FaceDetectorConfig.outputTransformationMatrix]가 true일 때만 값이 있고, 아니면 null입니다.
 */
data class Face(
    val landmarks: List<Landmark>,
    val blendshapes: List<Blendshape>?,
    val transformationMatrix: List<Float>?,
)

/**
 * Every detected face. Empty when no face was found.
 * / 감지된 모든 얼굴입니다. 얼굴이 없으면 비어 있습니다.
 */
data class FaceDetectionResult(val faces: List<Face>)

/**
 * Landmarks of every detected hand. Empty when no hand was found. Boundary only; not implemented yet.
 * / 감지된 모든 손의 랜드마크입니다. 손이 없으면 비어 있습니다. 경계만 정의했고 아직 구현하지 않았습니다.
 */
data class HandDetectionResult(val hands: List<List<Landmark>>)
