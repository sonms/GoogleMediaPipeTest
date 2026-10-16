package com.sonms.landmarkkit

/**
 * One camera frame handed to a detector.
 *
 * Instances are created by the platform helpers (`Bitmap.toFrame` on Android,
 * `CMSampleBufferRef.toFrame` on iOS); the native payload is held without copying where possible.
 *
 * 감지기에 전달하는 카메라 프레임 한 장입니다. 플랫폼 헬퍼(Android `Bitmap.toFrame`, iOS `CMSampleBufferRef.toFrame`)로
 * 만들며, 네이티브 데이터는 가능한 한 복사하지 않고 그대로 보관합니다.
 *
 * @property width pixel width as delivered by the camera (before applying [rotationDegrees]).
 * / 카메라가 전달한 픽셀 너비입니다([rotationDegrees]를 적용하기 전 값).
 * @property height pixel height as delivered by the camera (before applying [rotationDegrees]).
 * / 카메라가 전달한 픽셀 높이입니다([rotationDegrees]를 적용하기 전 값).
 * @property rotationDegrees clockwise rotation (0, 90, 180, 270) needed to make the frame upright.
 * / 프레임을 바로 세우는 데 필요한 시계 방향 회전각(0, 90, 180, 270)입니다.
 * @property timestampMs must increase monotonically across frames fed to one detector.
 * / 한 감지기에 넣는 프레임 사이에서 단조 증가해야 합니다.
 * @property native platform payload (Android `Bitmap`, iOS `IosFrameData`); only the platform implementation reads it.
 * / 플랫폼 데이터(Android `Bitmap`, iOS `IosFrameData`)이며, 플랫폼 구현체만 읽습니다.
 */
class Frame internal constructor(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMs: Long,
    internal val native: Any,
)
