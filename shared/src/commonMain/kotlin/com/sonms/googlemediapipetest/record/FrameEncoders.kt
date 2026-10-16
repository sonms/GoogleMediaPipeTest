package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.FaceDetectionResult
import okio.Buffer
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * z is relative depth with no fixed range; this is an assumption checked via [QuantStats].
 * / z는 고정된 범위가 없는 상대 깊이이므로 이 값은 가정이며, [QuantStats]로 검증합니다.
 */
const val Z_RANGE = 0.5f

/**
 * Counts values the quantizer had to clamp, and the observed z range, to validate [Z_RANGE].
 *
 * 양자화기가 잘라야 했던 값의 횟수와 관측한 z 범위를 기록해서 [Z_RANGE]가 적절한지 검증합니다.
 *
 * @property xyClamped x, y, or score values outside [0, 1] that were clamped.
 * / [0, 1]을 벗어나서 잘린 x, y 또는 점수 값의 횟수입니다.
 * @property zClamped z values outside ±[Z_RANGE] that were clamped; non-zero means the range is too small.
 * / ±[Z_RANGE]를 벗어나서 잘린 z 값의 횟수입니다. 0이 아니면 범위가 너무 작다는 뜻입니다.
 * @property zMin smallest z seen (before clamping). / 관측한 z의 최솟값입니다(자르기 전).
 * @property zMax largest z seen (before clamping). / 관측한 z의 최댓값입니다(자르기 전).
 */
class QuantStats {
    var xyClamped = 0L
    var zClamped = 0L
    var zMin = Float.MAX_VALUE
    var zMax = -Float.MAX_VALUE
}

/**
 * Maps a value in [0, 1] to `uint16` (0..65535); out-of-range values are clamped and counted in [stats].
 * / [0, 1] 범위의 값을 `uint16`(0..65535)으로 변환합니다. 범위를 벗어난 값은 잘라서 [stats]에 횟수를 기록합니다.
 */
fun quantizeUnit(value: Float, stats: QuantStats? = null): Int {
    val clamped = value.coerceIn(0f, 1f)
    if (clamped != value) stats?.let { it.xyClamped++ }
    return (clamped * 65535f).roundToInt()
}

/**
 * Inverse of [quantizeUnit]; error is at most half a step (about 0.0000076).
 * / [quantizeUnit]의 역변환입니다. 오차는 최대 반 단계(약 0.0000076)입니다.
 */
fun dequantizeUnit(q: Int): Float = q / 65535f

/**
 * Maps z in ±[Z_RANGE] to `int16`; out-of-range values are clamped, and the observed range is tracked in [stats].
 * / ±[Z_RANGE] 범위의 z를 `int16`으로 변환합니다. 범위를 벗어난 값은 자르고, 관측한 범위는 [stats]에 기록합니다.
 */
fun quantizeZ(value: Float, stats: QuantStats? = null): Int {
    stats?.let {
        if (value < it.zMin) it.zMin = value
        if (value > it.zMax) it.zMax = value
    }
    val clamped = value.coerceIn(-Z_RANGE, Z_RANGE)
    if (clamped != value) stats?.let { it.zClamped++ }
    return (clamped / Z_RANGE * 32767f).roundToInt()
}

/** Inverse of [quantizeZ]. / [quantizeZ]의 역변환입니다. */
fun dequantizeZ(q: Int): Float = q / 32767f * Z_RANGE

private val POW10 = LongArray(10) { i -> var p = 1L; repeat(i) { p *= 10 }; p }

/** Fixed-point formatting without trailing zeros, like a typical JSON writer. Non-finite values become `null`. */
internal fun StringBuilder.appendFixed(value: Float, decimals: Int) {
    if (!value.isFinite()) {
        append("null")
        return
    }
    val scale = POW10[decimals.coerceIn(0, 9)]
    val scaled = round(abs(value.toDouble()) * scale).toLong()
    if (value < 0 && scaled != 0L) append('-')
    append(scaled / scale)
    var frac = scaled % scale
    if (frac == 0L) return
    var digits = decimals
    while (frac % 10 == 0L) {
        frac /= 10
        digits--
    }
    val text = frac.toString()
    append('.')
    repeat(digits - text.length) { append('0') }
    append(text)
}

private fun StringBuilder.appendArray(values: List<Float>, decimals: Int) {
    append('[')
    values.forEachIndexed { i, v ->
        if (i > 0) append(',')
        appendFixed(v, decimals)
    }
    append(']')
}

/**
 * One JSONL line (without the trailing newline): `{"t":<ms>,"faces":[{"lm":[x,y,z,...],"bs":[scores],"m":[16]}]}`.
 * `lm` is 3 values per landmark, `bs` and `m` are omitted when absent. Blendshape names are not repeated per frame
 * (see the report file). [decimals] is the number of decimal places; trailing zeros are trimmed.
 *
 * JSONL 한 줄(끝의 개행 제외)을 만듭니다. `lm`은 랜드마크당 값 3개이고, `bs`와 `m`은 값이 없으면 생략합니다.
 * 블렌드셰이프 이름은 프레임마다 반복하지 않고 보고서 파일에 기록합니다. [decimals]는 소수 자릿수이며 끝의 0은 제거합니다.
 */
fun encodeJson(timestampMs: Long, result: FaceDetectionResult, decimals: Int): String = buildString {
    append("{\"t\":").append(timestampMs).append(",\"faces\":[")
    result.faces.forEachIndexed { i, face ->
        if (i > 0) append(',')
        append("{\"lm\":")
        appendArray(face.landmarks.flatMap { listOf(it.x, it.y, it.z) }, decimals)
        face.blendshapes?.let { bs ->
            append(",\"bs\":")
            appendArray(bs.map { it.score }, decimals)
        }
        face.transformationMatrix?.let {
            append(",\"m\":")
            appendArray(it, decimals)
        }
        append('}')
    }
    append("]}")
}

/**
 * Binary layout (little-endian): int64 timestamp, uint8 faceCount, then per face:
 * uint8 flags (bit0 blendshapes, bit1 matrix), uint16 landmarkCount, landmarks, [uint8 count + scores], [16 x float32].
 * float32 variant: landmarks and scores are float32. Quantized variant: x,y uint16, z int16, scores uint16.
 *
 * 바이너리 배치(리틀 엔디언): int64 타임스탬프, uint8 얼굴 수, 이어서 얼굴마다
 * uint8 플래그(비트0 블렌드셰이프, 비트1 행렬), uint16 랜드마크 수, 랜드마크, [uint8 개수 + 점수], [float32 16개].
 * float32 변형은 랜드마크와 점수가 float32이고, 양자화 변형은 x, y가 uint16, z가 int16, 점수가 uint16입니다.
 */
fun encodeFloat32(timestampMs: Long, result: FaceDetectionResult): ByteArray =
    encodeBinary(timestampMs, result, quantized = false, stats = null)

/**
 * Same layout as [encodeFloat32] with 16-bit quantized values; clamps are counted in [stats].
 * / [encodeFloat32]와 같은 배치에서 값을 16비트로 양자화합니다. 잘린 값은 [stats]에 기록합니다.
 */
fun encodeQuantized(timestampMs: Long, result: FaceDetectionResult, stats: QuantStats? = null): ByteArray =
    encodeBinary(timestampMs, result, quantized = true, stats = stats)

private fun encodeBinary(timestampMs: Long, result: FaceDetectionResult, quantized: Boolean, stats: QuantStats?): ByteArray {
    val out = Buffer()
    out.writeLongLe(timestampMs)
    out.writeByte(result.faces.size)
    for (face in result.faces) {
        val bs = face.blendshapes
        val matrix = face.transformationMatrix
        out.writeByte((if (bs != null) 1 else 0) or (if (matrix != null) 2 else 0))
        out.writeShortLe(face.landmarks.size)
        for (lm in face.landmarks) {
            if (quantized) {
                out.writeShortLe(quantizeUnit(lm.x, stats))
                out.writeShortLe(quantizeUnit(lm.y, stats))
                out.writeShortLe(quantizeZ(lm.z, stats))
            } else {
                out.writeIntLe(lm.x.toRawBits())
                out.writeIntLe(lm.y.toRawBits())
                out.writeIntLe(lm.z.toRawBits())
            }
        }
        if (bs != null) {
            out.writeByte(bs.size)
            for (b in bs) {
                if (quantized) out.writeShortLe(quantizeUnit(b.score, stats)) else out.writeIntLe(b.score.toRawBits())
            }
        }
        matrix?.forEach { out.writeIntLe(it.toRawBits()) }
    }
    return out.readByteArray()
}
