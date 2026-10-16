package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.FaceDetectionResult
import okio.Buffer
import okio.GzipSink
import okio.Sink
import okio.Timeout
import okio.buffer
import kotlin.time.TimeSource

/**
 * Assumed upload link used to predict transfer time; no real network is involved.
 *
 * 전송 시간을 예측하기 위해 가정한 업로드 회선입니다. 실제 네트워크는 사용하지 않습니다.
 *
 * @property mbps assumed upload speed in megabits per second. / 가정한 업로드 속도(Mbps)입니다.
 * @property rttMs assumed round-trip time in milliseconds (paid once per request).
 * / 가정한 왕복 지연 시간(ms)이며, 요청마다 한 번 발생합니다.
 */
data class NetworkProfile(val name: String, val mbps: Double, val rttMs: Double)

/**
 * Profiles used in every report: poor, LTE, Wi-Fi. Edit here to change the assumptions.
 * / 모든 보고서에 쓰는 프로파일(열악, LTE, Wi-Fi)입니다. 가정을 바꾸려면 여기를 수정하세요.
 */
val NetworkProfiles = listOf(
    NetworkProfile("Poor", 1.0, 300.0),
    NetworkProfile("LTE", 5.0, 80.0),
    NetworkProfile("Wi-Fi", 20.0, 20.0),
)

/**
 * Protocol overhead (headers, TLS framing) added on top of the payload.
 * / 페이로드에 더하는 프로토콜 오버헤드(헤더, TLS 프레이밍)입니다.
 */
const val PROTOCOL_OVERHEAD = 1.10

/**
 * Measured size and predicted transfer cost of one serialization format over a session.
 *
 * 한 세션에서 직렬화 형식 하나의 실측 크기와 예측 전송 비용입니다.
 *
 * @property name format label, e.g. "JSON 6 + gzip". / 형식 이름입니다. 예: "JSON 6 + gzip".
 * @property totalBytes bytes for the whole session (gzip rows: size of the single compressed stream).
 * / 세션 전체의 바이트 수입니다(gzip 행은 하나의 압축 스트림 크기).
 * @property bytesPerFrame [totalBytes] divided by the number of frames. / [totalBytes]를 프레임 수로 나눈 값입니다.
 * @property mbPerMinute megabytes (1,000,000 bytes) produced per minute of recording.
 * / 녹화 1분당 발생하는 메가바이트(1,000,000바이트)입니다.
 * @property gbPerHour gigabytes (1,000,000,000 bytes) produced per hour of recording.
 * / 녹화 1시간당 발생하는 기가바이트(1,000,000,000바이트)입니다.
 * @property encodeMsPerFrame average CPU time to encode one frame (gzip rows include compression).
 * / 프레임 하나를 인코딩하는 평균 CPU 시간(ms)입니다(gzip 행은 압축 시간 포함).
 * @property uploadSecondsPerMinute predicted seconds to upload one minute of data as a single request, keyed by
 * [NetworkProfile.name]. / 1분 분량을 요청 한 번으로 업로드할 때의 예상 소요 시간(초)이며,
 * [NetworkProfile.name]을 키로 합니다.
 */
data class FormatRow(
    val name: String,
    val totalBytes: Long,
    val bytesPerFrame: Double,
    val mbPerMinute: Double,
    val gbPerHour: Double,
    val encodeMsPerFrame: Double,
    val uploadSecondsPerMinute: Map<String, Double>,
)

/**
 * Payload report of one session.
 *
 * 한 세션의 페이로드 보고서입니다.
 *
 * @property frames number of processed frames. / 처리한 프레임 수입니다.
 * @property durationMs recording length used to scale per-minute figures. / 분당 수치를 환산하는 데 쓰는 녹화 길이입니다.
 * @property formats one row per serialization format. / 직렬화 형식마다 한 행입니다.
 * @property quant clamp counts and observed z range, used to validate the quantization range.
 * / 잘린 횟수와 관측한 z 범위로, 양자화 범위가 적절한지 검증하는 데 씁니다.
 */
data class PayloadReport(val frames: Int, val durationMs: Long, val formats: List<FormatRow>, val quant: QuantStats)

private class CountingSink : Sink {
    var count = 0L
    override fun write(source: Buffer, byteCount: Long) {
        count += byteCount
        source.skip(byteCount)
    }
    override fun flush() = Unit
    override fun timeout() = Timeout.NONE
    override fun close() = Unit
}

private class Track(val name: String, val gzip: Boolean) {
    val counter = CountingSink()
    val gz = if (gzip) GzipSink(counter).buffer() else null
    var raw = 0L
    var encodeNs = 0L

    fun add(bytes: ByteArray, newline: Boolean) {
        val mark = TimeSource.Monotonic.markNow()
        if (gz != null) gz.write(bytes).also { if (newline) gz.writeByte('\n'.code) } else raw += bytes.size + if (newline) 1 else 0
        encodeNs += mark.elapsedNow().inWholeNanoseconds
    }

    fun size(): Long = if (gz != null) counter.count else raw
}

/**
 * Encodes every frame in all candidate formats and tallies size and CPU time.
 * Gzip rows compress the whole session as one stream (batch upload), not each frame.
 * Call [report] once at the end of a session.
 *
 * 모든 프레임을 후보 형식으로 각각 인코딩해서 크기와 CPU 시간을 집계합니다.
 * gzip 행은 프레임별이 아니라 세션 전체를 하나의 스트림으로 압축합니다(묶음 업로드를 가정).
 * 세션이 끝날 때 [report]를 한 번 호출하세요.
 */
class PayloadMeter {
    private val quant = QuantStats()
    private val json6 = Track("JSON (6 decimals)", false)
    private val json4 = Track("JSON (4 decimals)", false)
    private val json6Gz = Track("JSON 6 + gzip", true)
    private val json4Gz = Track("JSON 4 + gzip", true)
    private val float32 = Track("Binary float32", false)
    private val quantized = Track("Binary quantized", false)
    private val quantizedGz = Track("Binary quantized + gzip", true)
    private val tracks = listOf(json6, json4, json6Gz, json4Gz, float32, quantized, quantizedGz)
    private var frames = 0

    /**
     * Encodes [result] in every format and adds its size and encode time to the totals.
     * / [result]를 모든 형식으로 인코딩하고 크기와 인코딩 시간을 합계에 더합니다.
     */
    fun add(timestampMs: Long, result: FaceDetectionResult) {
        frames++
        timed(json6) { encodeJson(timestampMs, result, 6).encodeToByteArray() }
        timed(json4) { encodeJson(timestampMs, result, 4).encodeToByteArray() }
        timed(json6Gz) { encodeJson(timestampMs, result, 6).encodeToByteArray() }
        timed(json4Gz) { encodeJson(timestampMs, result, 4).encodeToByteArray() }
        timed(float32) { encodeFloat32(timestampMs, result) }
        timed(quantized) { encodeQuantized(timestampMs, result, quant) }
        timed(quantizedGz) { encodeQuantized(timestampMs, result) }
    }

    private fun timed(track: Track, encode: () -> ByteArray) {
        val mark = TimeSource.Monotonic.markNow()
        val bytes = encode()
        val encodeNs = mark.elapsedNow().inWholeNanoseconds
        track.add(bytes, newline = track === json6 || track === json4 || track === json6Gz || track === json4Gz)
        track.encodeNs += encodeNs
    }

    /**
     * Finishes the gzip streams and builds the report; call once, after the last [add].
     * / gzip 스트림을 마무리하고 보고서를 만듭니다. 마지막 [add] 이후에 한 번만 호출하세요.
     */
    fun report(durationMs: Long): PayloadReport {
        tracks.forEach { it.gz?.close() }
        val minutes = durationMs / 60_000.0
        val rows = tracks.map { t ->
            val total = t.size()
            val perMinute = if (minutes > 0) total / minutes else 0.0
            FormatRow(
                name = t.name,
                totalBytes = total,
                bytesPerFrame = if (frames > 0) total.toDouble() / frames else 0.0,
                mbPerMinute = perMinute / 1_000_000.0,
                gbPerHour = perMinute * 60 / 1_000_000_000.0,
                encodeMsPerFrame = if (frames > 0) t.encodeNs / 1_000_000.0 / frames else 0.0,
                uploadSecondsPerMinute = NetworkProfiles.associate { p ->
                    p.name to perMinute * 8 * PROTOCOL_OVERHEAD / (p.mbps * 1_000_000.0) + p.rttMs / 1000.0
                },
            )
        }
        return PayloadReport(frames, durationMs, rows, quant)
    }
}
