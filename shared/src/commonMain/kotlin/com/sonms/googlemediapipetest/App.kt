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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.sonms.googlemediapipetest.emotion.Emotion
import com.sonms.googlemediapipetest.emotion.ExpressionTracker
import com.sonms.googlemediapipetest.emotion.Phase
import com.sonms.googlemediapipetest.emotion.Signals
import com.sonms.googlemediapipetest.record.CompareFps
import com.sonms.landmarkkit.FaceDetectionResult
import kotlinx.coroutines.launch

private fun Float.f() = (kotlin.math.round(this * 100) / 100).toString()

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
 * @param showCamera show the preview and overlay; when false, detection still runs but nothing is drawn. / 프리뷰와 오버레이를 표시합니다. false여도 감지는 계속 실행되며 그리지만 않습니다.
 * @param onToggleCamera switch [showCamera] (ignored while recording). / [showCamera]를 전환합니다(기록 중에는 무시).
 * @param camera platform camera preview content. / 플랫폼 카메라 프리뷰 내용입니다.
 */
@Composable
fun App(
    result: FaceDetectionResult?,
    recorder: RecorderUi,
    onToggleRecording: () -> Unit,
    onToggleCompare: () -> Unit,
    mirrorOverlay: Boolean = false,
    showCamera: Boolean = true,
    onToggleCamera: () -> Unit = {},
    camera: @Composable () -> Unit,
) {
    val tracker = remember { ExpressionTracker() }
    var phase by remember { mutableStateOf(tracker.phase) }
    var progress by remember { mutableStateOf(0f) }
    var delta by remember { mutableStateOf<Signals?>(null) }
    var last by remember { mutableStateOf<Emotion?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(result) {
        val emotion = tracker.update(result)
        phase = tracker.phase
        progress = tracker.calibrationProgress
        delta = tracker.delta
        last = tracker.last
        if (emotion != null) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("${emotion.label} 추정", duration = SnackbarDuration.Short)
        }
    }
    MaterialTheme {
        if (phase == Phase.Calibrating) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("기준선 보정") },
                text = { Text("무표정으로 정면을 봐 주세요. 진행 ${(progress * 100).toInt()}%") },
            )
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(Modifier.padding(inner).safeContentPadding().fillMaxSize().verticalScroll(rememberScrollState())) {
            if (showCamera) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    camera()
                    FaceOverlay(result, mirror = mirrorOverlay)
                }
            } else {
                Text("카메라 화면 꺼짐 (감지와 기록은 계속 실행됩니다)")
            }
            Text(result.summary())
            Button(onClick = onToggleCamera, enabled = !recorder.recording) {
                Text(if (showCamera) "카메라 화면: 켜짐" else "카메라 화면: 꺼짐")
            }
            Button(onClick = onToggleRecording) { Text(if (recorder.recording) "기록 종료" else "기록 시작") }
            Button(onClick = onToggleCompare, enabled = !recorder.recording) {
                Text(if (recorder.compareMode) "비교 모드: 켜짐 (${CompareFps.joinToString("/")}fps)" else "비교 모드: 꺼짐")
            }
            when {
                recorder.recording -> Text("기록 중 ${recorder.elapsedSec}초")
                recorder.finished -> Text("종료되었습니다")
            }
            Button(onClick = { tracker.startCalibration(); phase = tracker.phase; progress = 0f }) {
                Text("기준선 보정 시작")
            }
            Text(
                when (phase) {
                    Phase.Idle -> "감정 추정: 보정 필요"
                    Phase.Calibrating -> "감정 추정: 보정 중"
                    Phase.Failed -> "보정 실패: 얼굴이 충분히 검출되지 않았습니다. 다시 시작하세요"
                    Phase.Ready -> "감정 추정: ${last?.label ?: "-"}" + if (delta == null) " (얼굴 없음: 판정 불가)" else ""
                },
            )
            delta?.let { d ->
                Text(
                    "Δ 웃음 ${d.smile.f()} 입 ${d.jaw.f()} 눈감 ${d.blink.f()} 눈썹↑ ${d.browUp.f()} " +
                        "눈썹↓ ${d.browDown.f()} yaw ${d.yaw.f()} pitch ${d.pitch.f()}",
                )
            }
        }
        }
    }
}
