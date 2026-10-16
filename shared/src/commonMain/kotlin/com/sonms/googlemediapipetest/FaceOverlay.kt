package com.sonms.googlemediapipetest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.sonms.landmarkkit.FaceDetectionResult

/**
 * Draws every landmark as a dot. Assumes the overlay and the camera preview share the same bounds.
 * / 모든 랜드마크를 점으로 그립니다. 오버레이와 카메라 프리뷰의 영역이 같다고 가정합니다.
 */
@Composable
fun FaceOverlay(result: FaceDetectionResult?, modifier: Modifier = Modifier.fillMaxSize(), mirror: Boolean = false) {
    Canvas(modifier) {
        result?.faces?.forEach { face ->
            face.landmarks.forEach { landmark ->
                val x = if (mirror) 1f - landmark.x else landmark.x
                drawCircle(Color.Green, radius = 2f, center = Offset(x * size.width, landmark.y * size.height))
            }
        }
    }
}

/**
 * One-line summary for the screen: face count and the first face's landmark and blendshape counts.
 * / 화면에 표시할 한 줄 요약입니다. 얼굴 수와 첫 번째 얼굴의 랜드마크, 블렌드셰이프 개수를 보여 줍니다.
 */
fun FaceDetectionResult?.summary(): String {
    val faces = this?.faces.orEmpty()
    val first = faces.firstOrNull()
    return "faces: ${faces.size}, landmarks: ${first?.landmarks?.size ?: 0}, blendshapes: ${first?.blendshapes?.size ?: 0}"
}
