@file:OptIn(ExperimentalForeignApi::class)

package com.sonms.landmarkkit

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.CMSampleBufferRef
import platform.UIKit.UIImageOrientation

/**
 * Wraps [this] without copying. [rotationDegrees] is the clockwise rotation that makes it upright.
 * / 복사 없이 [this]를 감쌉니다. [rotationDegrees]는 바로 세우는 데 필요한 시계 방향 회전각입니다.
 */
fun CMSampleBufferRef.toFrame(width: Int, height: Int, rotationDegrees: Int, timestampMs: Long): Frame =
    Frame(width, height, rotationDegrees, timestampMs, IosFrameData(this, rotationDegrees.toOrientation()))

internal class IosFrameData(val sampleBuffer: CMSampleBufferRef, val orientation: UIImageOrientation)

// UIImageOrientation: 0 Up, 1 Down, 2 Left, 3 Right.
private fun Int.toOrientation(): UIImageOrientation = when (((this % 360) + 360) % 360) {
    90 -> UIImageOrientation.UIImageOrientationRight
    180 -> UIImageOrientation.UIImageOrientationDown
    270 -> UIImageOrientation.UIImageOrientationLeft
    else -> UIImageOrientation.UIImageOrientationUp
}
