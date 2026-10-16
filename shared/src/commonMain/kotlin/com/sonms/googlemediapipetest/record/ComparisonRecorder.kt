package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.FaceDetectorConfig
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Lane frame rates; the first lane is the baseline and must be the highest. Easy to change.
 * / 레인별 프레임 속도입니다. 첫 레인이 기준(baseline)이며 가장 높아야 합니다. 쉽게 바꿀 수 있습니다.
 */
val CompareFps = listOf(10, 8, 4, 2)

/**
 * Camera frame rate the scheduling tolerance is derived from.
 * / 스케줄링 허용 오차를 계산하는 기준이 되는 카메라 프레임 속도입니다.
 */
const val CAMERA_FPS = 30

/**
 * Result of one input frame.
 *
 * 입력 프레임 하나의 처리 결과입니다.
 *
 * @property limitReached true once any lane hit the duration limit; the caller should then stop the session.
 * / 어느 레인이든 길이 상한에 도달하면 true이며, 이때 호출하는 쪽이 세션을 종료해야 합니다.
 * @property results one entry per lane (index 0 is the baseline), null for lanes that skipped this frame.
 * / 레인마다 하나씩이며(0번이 기준 레인), 이 프레임을 건너뛴 레인은 null입니다.
 */
class FrameOutcome(val limitReached: Boolean, val results: List<FaceDetectionResult?>)

private class PairStat {
    var bothDetected = 0
    var onlyBaseline = 0
    var onlyLane = 0
    var blendshapeDiffSum = 0.0
    var blendshapePairs = 0
    var landmarkDispSum = 0.0
    var landmarkPairs = 0
}

private class Lane(val fps: Int, val recorder: SessionRecorder) {
    var lastProcessedMs = -1L
    val pair = PairStat()
}

/**
 * Runs several detector "lanes" over the same camera stream. Each lane only processes a frame when at least
 * its own interval has passed (by timestamp), so every lane sees real sparse input, not post-hoc decimation.
 * Frames are processed sequentially; the caller supplies one detector per lane via the `detect` lambda.
 * Not thread-safe: call from a single thread.
 *
 * 같은 카메라 스트림에 여러 감지기 "레인"을 돌립니다. 각 레인은 자신의 간격이 지났을 때만(타임스탬프 기준) 프레임을
 * 처리하므로, 사후에 솎아낸 결과가 아니라 실제로 듬성한 입력을 받습니다. 프레임은 순차 처리하며, 호출하는 쪽이
 * `detect` 람다로 레인마다 감지기를 제공합니다. 스레드에 안전하지 않으므로 한 스레드에서만 호출하세요.
 *
 * @param outputDir directory for all files of the session (see [SessionRecorder]).
 * / 세션의 모든 파일을 저장할 디렉터리입니다([SessionRecorder] 참고).
 * @param startedAtEpochMs wall-clock start supplied by the app module. / 앱 모듈이 전달하는 시작 시각(epoch ms)입니다.
 * @param fpsList frame rate of each lane; the first is the baseline the others are compared against.
 * / 레인별 프레임 속도입니다. 첫 번째가 나머지를 비교할 기준입니다.
 * @param decimals decimal places of JSON numbers in the frame lines. / 프레임 줄의 JSON 숫자 소수 자릿수입니다.
 * @param maxDurationMs session length limit, applied to every lane. / 모든 레인에 적용하는 세션 길이 상한입니다.
 */
class ComparisonRecorder(
    private val outputDir: String,
    env: EnvironmentInfo,
    config: FaceDetectorConfig,
    private val startedAtEpochMs: Long,
    fpsList: List<Int> = CompareFps,
    decimals: Int = 6,
    maxDurationMs: Long = 10 * 60 * 1000L,
) {
    private val sessionId = newSessionId()
    private val lanes = fpsList.map { fps ->
        Lane(fps, SessionRecorder(outputDir, env, config, fps, startedAtEpochMs, decimals, maxDurationMs, sessionId, "${fps}fps", compareMode = true))
    }
    private var stopped = false

    /**
     * Number of lanes; the caller needs one detector per lane.
     * / 레인 수입니다. 호출하는 쪽은 레인마다 감지기가 하나씩 필요합니다.
     */
    val laneCount get() = lanes.size

    /** Elapsed time of the baseline lane. / 기준 레인의 경과 시간입니다. */
    fun elapsedMs(): Long = lanes.first().recorder.elapsedMs()

    /**
     * Processes the frame at [timestampMs] on every lane that is due.
     *
     * 처리할 차례가 된 모든 레인에서 [timestampMs]의 프레임을 처리합니다.
     *
     * @param detect runs the detector of the given lane on the current frame (the caller owns the detectors and the frame).
     * / 주어진 레인의 감지기를 현재 프레임에 실행합니다(감지기와 프레임은 호출하는 쪽이 관리합니다).
     */
    fun onFrame(timestampMs: Long, detect: (laneIndex: Int) -> FaceDetectionResult): FrameOutcome {
        val results = arrayOfNulls<FaceDetectionResult>(lanes.size)
        var limitReached = false
        lanes.forEachIndexed { i, lane ->
            if (!isDue(lane, timestampMs)) return@forEachIndexed
            val mark = TimeSource.Monotonic.markNow()
            val result = detect(i)
            val latencyMs = mark.elapsedNow().inWholeNanoseconds / 1_000_000.0
            lane.lastProcessedMs = timestampMs
            results[i] = result
            if (lane.recorder.onFrame(timestampMs, result, latencyMs)) limitReached = true
        }
        val baseline = results[0]
        if (baseline != null) {
            for (i in 1 until lanes.size) results[i]?.let { accumulate(lanes[i].pair, baseline, it) }
        }
        return FrameOutcome(limitReached, results.toList())
    }

    private fun isDue(lane: Lane, timestampMs: Long): Boolean {
        if (lane.lastProcessedMs < 0) return true
        val toleranceMs = 500L / CAMERA_FPS // half a camera frame, so jittery arrival still hits the target rate
        return timestampMs - lane.lastProcessedMs >= 1000L / lane.fps - toleranceMs
    }

    private fun accumulate(pair: PairStat, baseline: FaceDetectionResult, lane: FaceDetectionResult) {
        val a = baseline.faces.firstOrNull()
        val b = lane.faces.firstOrNull()
        when {
            a != null && b != null -> {
                pair.bothDetected++
                blendshapeDiff(a, b)?.let { pair.blendshapeDiffSum += it; pair.blendshapePairs++ }
                landmarkDisplacement(a, b)?.let { pair.landmarkDispSum += it; pair.landmarkPairs++ }
            }
            a != null -> pair.onlyBaseline++
            b != null -> pair.onlyLane++
        }
    }

    private fun blendshapeDiff(a: Face, b: Face): Double? {
        val bs = b.blendshapes?.associate { it.index to it.score } ?: return null
        val diffs = a.blendshapes?.mapNotNull { x -> bs[x.index]?.let { abs((x.score - it).toDouble()) } }
        return if (diffs.isNullOrEmpty()) null else diffs.sum() / diffs.size
    }

    private fun landmarkDisplacement(a: Face, b: Face): Double? {
        if (a.landmarks.isEmpty() || a.landmarks.size != b.landmarks.size) return null
        var sum = 0.0
        for (i in a.landmarks.indices) {
            val dx = (a.landmarks[i].x - b.landmarks[i].x).toDouble()
            val dy = (a.landmarks[i].y - b.landmarks[i].y).toDouble()
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / a.landmarks.size
    }

    /**
     * Stops every lane and writes `<start>_<id>.compare.json`. Safe to call twice.
     * / 모든 레인을 종료하고 `<시작시각>_<ID>.compare.json`을 씁니다. 두 번 호출해도 안전합니다.
     */
    fun stop(): List<SessionFiles> {
        val files = lanes.map { it.recorder.stop() }
        if (!stopped) {
            stopped = true
            val path = outputDir.toPath() / "${startedAtEpochMs}_$sessionId.compare.json"
            FileSystem.SYSTEM.sink(path).buffer().use { it.writeUtf8(compareJson(files)) }
        }
        return files
    }

    private fun compareJson(files: List<SessionFiles>): String = buildString {
        append("{\"sessionId\":\"").append(sessionId).append("\",\"baselineFps\":").append(lanes[0].fps)
        append(",\"note\":\"pairs compare each lane with the baseline lane on the same input frame\",\"lanes\":[")
        lanes.forEachIndexed { i, lane ->
            if (i > 0) append(',')
            val p = lane.pair
            append("{\"fps\":").append(lane.fps)
            append(",\"framesProcessed\":").append(lane.recorder.frameCount)
            append(",\"dataFile\":\"").append(files[i].dataPath.replace("\\", "\\\\")).append('"')
            append(",\"reportFile\":\"").append(files[i].reportPath.replace("\\", "\\\\")).append('"')
            if (i > 0) {
                append(",\"vsBaseline\":{\"bothDetected\":").append(p.bothDetected)
                append(",\"onlyBaselineDetected\":").append(p.onlyBaseline)
                append(",\"onlyLaneDetected\":").append(p.onlyLane)
                append(",\"meanAbsBlendshapeDiff\":").append(mean(p.blendshapeDiffSum, p.blendshapePairs))
                append(",\"meanLandmarkDisplacement\":").append(mean(p.landmarkDispSum, p.landmarkPairs)).append('}')
            }
            append('}')
        }
        append("]}")
    }

    private fun mean(sum: Double, n: Int): String = if (n == 0) "null" else (sum / n).toString()
}
