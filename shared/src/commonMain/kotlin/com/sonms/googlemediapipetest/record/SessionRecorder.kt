package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.FaceDetectorConfig
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

internal fun newSessionId(): String = Random.nextLong().toULong().toString(16)

/** Paths of the files a finished session produced. / 끝난 세션이 만든 파일의 경로입니다. */
class SessionFiles(val dataPath: String, val reportPath: String)

/**
 * Records one session: JSONL frames (first line is metadata) plus a report file written by [stop].
 * Not thread-safe: call every method from a single thread.
 *
 * 한 세션을 기록합니다. JSONL 프레임 파일(첫 줄은 메타데이터)과, [stop]이 쓰는 보고서 파일을 만듭니다.
 * 스레드에 안전하지 않으므로 모든 메서드를 한 스레드에서 호출해야 합니다.
 *
 * @param outputDir directory for the files; created if missing. Supplied by the app module.
 * / 파일을 저장할 디렉터리이며 없으면 만듭니다. 앱 모듈이 전달합니다.
 * @param env environment facts written into the metadata line. / 메타데이터 줄에 기록할 환경 정보입니다.
 * @param config detector options used for this session (written into the metadata line).
 * / 이 세션에서 쓴 감지기 옵션입니다(메타데이터 줄에 기록).
 * @param targetFps frame rate the session is meant to run at (metadata only; the caller decides which frames to pass).
 * / 세션이 목표로 하는 프레임 속도입니다(메타데이터 용도이며, 어떤 프레임을 넘길지는 호출하는 쪽이 정합니다).
 * @param startedAtEpochMs wall-clock start supplied by the app module (used in metadata and file names).
 * / 앱 모듈이 전달하는 시작 시각(epoch ms)이며 메타데이터와 파일 이름에 쓰입니다.
 * @param decimals decimal places of JSON numbers in the frame lines. / 프레임 줄의 JSON 숫자 소수 자릿수입니다.
 * @param maxDurationMs [onFrame] reports "limit reached" once the session is this long.
 * / 세션이 이 길이에 도달하면 [onFrame]이 "상한 도달"을 알립니다.
 * @param sessionId identifier shared by all files of one session; comparison lanes pass the same id.
 * / 한 세션의 모든 파일이 공유하는 식별자이며, 비교 레인은 같은 값을 넘깁니다.
 * @param label file-name suffix for a comparison lane, e.g. "10fps"; null for a normal session.
 * / 비교 레인의 파일 이름 접미사입니다(예: "10fps"). 일반 세션이면 null입니다.
 * @param compareMode whether this recorder is a lane of a comparison session (metadata only).
 * / 이 레코더가 비교 세션의 레인인지 여부입니다(메타데이터 용도).
 */
class SessionRecorder(
    outputDir: String,
    private val env: EnvironmentInfo,
    private val config: FaceDetectorConfig,
    private val targetFps: Int,
    startedAtEpochMs: Long,
    private val decimals: Int = 6,
    private val maxDurationMs: Long = 10 * 60 * 1000L,
    private val sessionId: String = newSessionId(),
    /** File-name suffix for a comparison lane, e.g. "10fps". */
    label: String? = null,
    private val compareMode: Boolean = false,
) {
    private val fs = FileSystem.SYSTEM
    private val baseName = "${startedAtEpochMs}_$sessionId" + (label?.let { ".$it" } ?: "")
    private val dataPath = outputDir.toPath() / "$baseName.jsonl"
    private val reportPath = outputDir.toPath() / "$baseName.report.json"
    private val sink = run {
        fs.createDirectories(outputDir.toPath())
        fs.sink(dataPath).buffer()
    }
    private val payload = PayloadMeter()
    private val quality = QualityMeter()
    private var firstTimestampMs = -1L
    private var lastTimestampMs = 0L
    private var stopped = false

    /** Number of frames recorded so far. / 지금까지 기록한 프레임 수입니다. */
    var frameCount = 0
        private set

    init {
        sink.writeUtf8(metaLine(sessionId, startedAtEpochMs, env, config, targetFps, decimals, maxDurationMs, compareMode)).writeUtf8("\n")
    }

    /**
     * Records one processed frame (pass an empty result when no face was found).
     *
     * 처리한 프레임 하나를 기록합니다(얼굴이 없으면 빈 결과를 넘기세요).
     *
     * @param timestampMs frame timestamp; the session length is measured from the first one.
     * / 프레임 타임스탬프이며, 세션 길이는 첫 프레임부터 잽니다.
     * @param latencyMs how long the detector call for this frame took. / 이 프레임의 감지기 호출에 걸린 시간(ms)입니다.
     * @return true once the duration limit is reached; the caller should then call [stop].
     * / 길이 상한에 도달하면 true이며, 이때 호출하는 쪽이 [stop]을 호출해야 합니다.
     */
    fun onFrame(timestampMs: Long, result: FaceDetectionResult, latencyMs: Double): Boolean {
        if (stopped) return true
        if (firstTimestampMs < 0) firstTimestampMs = timestampMs
        lastTimestampMs = timestampMs
        frameCount++
        sink.writeUtf8(encodeJson(timestampMs, result, decimals)).writeUtf8("\n")
        payload.add(timestampMs, result)
        quality.add(timestampMs, result, latencyMs)
        return timestampMs - firstTimestampMs >= maxDurationMs
    }

    /**
     * Time between the first and the latest recorded frame; 0 before any frame.
     * / 첫 프레임과 가장 최근 프레임 사이의 시간이며, 프레임이 없으면 0입니다.
     */
    fun elapsedMs(): Long = if (firstTimestampMs < 0) 0 else lastTimestampMs - firstTimestampMs

    /**
     * Closes the data file and writes the report file. Safe to call twice.
     * / 데이터 파일을 닫고 보고서 파일을 씁니다. 두 번 호출해도 안전합니다.
     */
    fun stop(): SessionFiles {
        if (!stopped) {
            stopped = true
            sink.close()
            val report = reportJson(sessionId, payload.report(elapsedMs()), quality.summary())
            fs.sink(reportPath).buffer().use { it.writeUtf8(report) }
        }
        return SessionFiles(dataPath.toString(), reportPath.toString())
    }
}
