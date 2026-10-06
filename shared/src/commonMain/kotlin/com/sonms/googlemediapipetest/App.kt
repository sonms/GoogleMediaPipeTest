package com.sonms.googlemediapipetest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sonms.googlemediapipetest.record.CompareFps
import com.sonms.landmarkkit.FaceDetectionResult

/**
 * What the recorder controls on screen show.
 *
 * 화면의 기록 컨트롤이 보여 줄 상태입니다.
 *
 * @property recording a session is being recorded. / 세션을 기록하는 중입니다.
 * @property finished the last session has ended (shows "종료되었습니다"). / 직전 세션이 끝났습니다("종료되었습니다" 표시).
 * @property elapsedSec seconds recorded so far. / 지금까지 기록한 시간(초)입니다.
 * @property compareMode the next session runs the [CompareFps] comparison; locked while [recording].
 * / 다음 세션에서 [CompareFps] 비교를 실행합니다. [recording] 중에는 바꿀 수 없습니다.
 */
data class RecorderUi(
    val recording: Boolean = false,
    val finished: Boolean = false,
    val elapsedSec: Long = 0,
    val compareMode: Boolean = false,
)

/**
 * Test screen: a platform camera preview in a 3:4 box with the landmark overlay on top, then the detection summary
 * and the recorder controls.
 *
 * 테스트 화면입니다. 3:4 영역에 플랫폼 카메라 프리뷰와 그 위의 랜드마크 오버레이를 보여 주고, 그 아래에 감지 요약과
 * 기록 컨트롤을 둡니다.
 *
 * @param result latest detection result to draw and summarize; null before the first frame.
 * / 그리고 요약할 최신 감지 결과입니다. 첫 프레임 전에는 null입니다.
 * @param recorder state of the recorder controls. / 기록 컨트롤의 상태입니다.
 * @param onToggleRecording start or stop a recording session. / 기록 세션을 시작하거나 종료합니다.
 * @param onToggleCompare switch comparison mode (ignored while recording). / 비교 모드를 전환합니다(기록 중에는 무시).
 * @param mirrorOverlay flip the overlay horizontally (front camera preview is mirrored). / 오버레이를 좌우로 뒤집습니다(전면 카메라 프리뷰는 거울처럼 반전됩니다).
 * @param camera platform camera preview content. / 플랫폼 카메라 프리뷰 내용입니다.
 */
@Composable
fun App(
    result: FaceDetectionResult?,
    recorder: RecorderUi,
    onToggleRecording: () -> Unit,
    onToggleCompare: () -> Unit,
    mirrorOverlay: Boolean = false,
    camera: @Composable () -> Unit,
) {
    MaterialTheme {
        Column(Modifier.safeContentPadding().fillMaxSize()) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                camera()
                FaceOverlay(result, mirror = mirrorOverlay)
            }
            Text(result.summary())
            Button(onClick = onToggleRecording) { Text(if (recorder.recording) "기록 종료" else "기록 시작") }
            Button(onClick = onToggleCompare, enabled = !recorder.recording) {
                Text(if (recorder.compareMode) "비교 모드: 켜짐 (${CompareFps.joinToString("/")}fps)" else "비교 모드: 꺼짐")
            }
            when {
                recorder.recording -> Text("기록 중 ${recorder.elapsedSec}초")
                recorder.finished -> Text("종료되었습니다")
            }
        }
    }
}
