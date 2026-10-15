package com.sonms.googlemediapipetest.emotion

import com.sonms.landmarkkit.FaceDetectionResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Emotions the tracker can estimate. Estimates only: blendshapes cannot tell similar emotions apart.
 *
 * 추정할 수 있는 감정입니다. Blendshape만으로는 비슷한 감정을 구분할 수 없으므로 어디까지나 추정입니다.
 */
enum class Emotion(val label: String) {
    Joy("즐거움"),
    Comfort("편안함"),
    Interest("흥미로움"),
    Discomfort("불편함"),
    Boredom("지루함/졸림"),
    Calm("평온"),
}

/**
 * Tunable starting points; frame counts assume ~8fps (1 frame ≈ 125ms).
 *
 * 조정용 시작값입니다. 프레임 수는 약 8fps(1프레임 ≈ 125ms)를 기준으로 합니다.
 */
object ExpressionConfig {
    const val CALIBRATION_FRAMES = 24 // 3s
    const val CALIBRATION_MIN_VALID = 20
    const val COOLDOWN_FRAMES = 24 // 3s per emotion

    const val SMILE_ON = 0.4f
    const val SMILE_OFF = 0.2f
    const val SMILE_FRAMES = 3

    const val BROW_UP_ON = 0.3f
    const val BROW_UP_OFF = 0.15f
    const val BROW_UP_FRAMES = 3
    const val YAW_MAX_DEG = 20f // interest requires facing the camera

    const val OPEN_MOUTH_ON = 0.3f // interest: mouth open while the eyes stay open
    const val OPEN_MOUTH_OFF = 0.15f
    const val OPEN_MOUTH_FRAMES = 4
    const val OPEN_MOUTH_EYE_MAX = 0.35f // opening the mouth narrows the eyes a little; yawn-sized openings are excluded

    const val COMFORT_SMILE = 0.3f // comfort: smiling with eyes closed
    const val COMFORT_BLINK = 0.5f
    const val COMFORT_FRAMES = 4
    const val COMFORT_HOLD_BLINK = 0.4f // above this with a smile, joy and boredom are suppressed

    const val BROW_DOWN_ON = 0.25f // weak frowns often peak at 0.3-0.5, so keep this low
    const val BROW_DOWN_OFF = 0.12f
    const val BROW_DOWN_FRAMES = 3

    const val YAWN_JAW_ON = 0.5f
    const val YAWN_JAW_OFF = 0.3f
    const val YAWN_FRAMES = 8 // 1s
    const val YAWN_BLINK_MIN = 0.1f // eyes must narrow too, otherwise it is just an open mouth

    const val DROOP_ON = 0.2f // half-closed eyes
    const val DROOP_OFF = 0.1f
    const val DROOP_FRAMES = 16 // 2s

    const val CLOSED_ON = 0.5f
    const val CLOSED_OFF = 0.3f
    const val CLOSED_FRAMES = 4 // 0.5s

    const val NOD_ON_DEG = 20f
    const val NOD_OFF_DEG = 10f
    const val NOD_FRAMES = 16 // 2s

    const val CALM_MAX = 0.15f
    const val CALM_FRAMES = 24 // 3s
}

/**
 * Per-frame expression values. [smile], [browUp], [browDown] and [blink] are left/right means; angles are degrees.
 *
 * 프레임별 표정 값입니다. [smile], [browUp], [browDown], [blink]는 좌우 평균이며 각도는 도(degree) 단위입니다.
 */
data class Signals(
    val smile: Float = 0f,
    val jaw: Float = 0f,
    val blink: Float = 0f,
    val browUp: Float = 0f,
    val browDown: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    operator fun minus(o: Signals) = Signals(
        smile - o.smile, jaw - o.jaw, blink - o.blink, browUp - o.browUp, browDown - o.browDown, yaw - o.yaw, pitch - o.pitch,
    )
}

enum class Phase { Idle, Calibrating, Ready, Failed }

/** ON at [on], OFF at [off] (hysteresis); fires once when the ON state has lasted [need] frames. */
private class Gate(val on: Float, val off: Float, val need: Int) {
    private var active = false
    private var streak = 0

    fun reset() {
        active = false
        streak = 0
    }

    fun update(v: Float): Boolean {
        if (v >= on) active = true else if (v <= off) active = false
        streak = if (active) streak + 1 else 0
        return streak == need
    }
}

/**
 * Turns detection results into emotion estimates: baseline-relative deltas, hysteresis, consecutive-frame gates.
 * A frame without a face (or without blendshapes) is "undecidable": it resets all gates and never counts as a neutral face.
 *
 * 감지 결과를 감정 추정으로 바꿉니다. 기준선 대비 변화량, hysteresis, 연속 프레임 조건을 적용합니다.
 * 얼굴이나 Blendshape가 없는 프레임은 "판정 불가"이며 모든 조건을 초기화하고, 무표정으로 취급하지 않습니다.
 */
class ExpressionTracker {
    var phase = Phase.Idle
        private set

    /** Current minus baseline; null while undecidable. / 현재 값에서 기준선을 뺀 값이며, 판정 불가이면 null입니다. */
    var delta: Signals? = null
        private set
    var last: Emotion? = null
        private set
    val calibrationProgress: Float get() = calibFrames / ExpressionConfig.CALIBRATION_FRAMES.toFloat()

    private var baseline = Signals()
    private val samples = mutableListOf<Signals>()
    private var calibFrames = 0

    private val c = ExpressionConfig
    private val smile = Gate(c.SMILE_ON, c.SMILE_OFF, c.SMILE_FRAMES)
    private val interest = Gate(c.BROW_UP_ON, c.BROW_UP_OFF, c.BROW_UP_FRAMES)
    private val openMouth = Gate(c.OPEN_MOUTH_ON, c.OPEN_MOUTH_OFF, c.OPEN_MOUTH_FRAMES)
    private val comfort = Gate(1f, 0.5f, c.COMFORT_FRAMES) // fed min(smile, blink) normalised to 1.0 at their thresholds
    private val discomfort = Gate(c.BROW_DOWN_ON, c.BROW_DOWN_OFF, c.BROW_DOWN_FRAMES)
    private val yawn = Gate(c.YAWN_JAW_ON, c.YAWN_JAW_OFF, c.YAWN_FRAMES)
    private val droop = Gate(c.DROOP_ON, c.DROOP_OFF, c.DROOP_FRAMES)
    private val closed = Gate(c.CLOSED_ON, c.CLOSED_OFF, c.CLOSED_FRAMES)
    private val nod = Gate(c.NOD_ON_DEG, c.NOD_OFF_DEG, c.NOD_FRAMES)
    private val gates = listOf(smile, interest, openMouth, comfort, discomfort, yawn, droop, closed, nod)

    private var calmStreak = 0
    private var calmArmed = false // calm fires once after some other emotion, never right after calibration
    private val cooldown = mutableMapOf<Emotion, Int>()

    fun startCalibration() {
        phase = Phase.Calibrating
        samples.clear()
        calibFrames = 0
        delta = null
        last = null
        resetGates()
    }

    /** Feeds one frame; returns the emotion that triggered on it, if any. / 프레임 하나를 처리하고, 이 프레임에서 발생한 감정이 있으면 반환합니다. */
    fun update(result: FaceDetectionResult?): Emotion? {
        val sig = result?.let(::signalsOf)
        when (phase) {
            Phase.Idle, Phase.Failed -> return null
            Phase.Calibrating -> {
                if (sig != null) samples += sig
                if (++calibFrames >= c.CALIBRATION_FRAMES) finishCalibration()
                return null
            }
            Phase.Ready -> Unit
        }
        if (sig == null) {
            delta = null
            resetGates()
            return null
        }
        val d = sig - baseline
        delta = d
        cooldown.keys.toList().forEach { cooldown[it] = (cooldown[it]!! - 1).coerceAtLeast(0) }

        // squinting also raises eyeBlink, so eye-based boredom signals are ignored while the brows are down (discomfort)
        val frowning = d.browDown >= c.BROW_DOWN_OFF
        // eyes closed while smiling is comfort, not joy or drowsiness
        val closedSmile = d.smile >= c.SMILE_OFF && d.blink >= c.COMFORT_HOLD_BLINK
        // eyes narrow slightly when the mouth opens, which is not drowsiness (a real yawn still goes through the yawn gate)
        val mouthOpen = d.jaw >= c.OPEN_MOUTH_OFF
        val yawnSized = d.jaw >= c.YAWN_JAW_ON && d.blink >= c.YAWN_BLINK_MIN
        val blink = if (frowning || closedSmile || mouthOpen) 0f else d.blink
        val fired = listOf(
            Emotion.Comfort to comfort.update(minOf(d.smile / c.COMFORT_SMILE, d.blink / c.COMFORT_BLINK)),
            Emotion.Joy to smile.update(if (closedSmile) 0f else d.smile),
            Emotion.Interest to listOf(
                interest.update(if (abs(d.yaw) <= c.YAW_MAX_DEG) d.browUp else 0f),
                openMouth.update(if (!yawnSized && d.blink < c.OPEN_MOUTH_EYE_MAX) d.jaw else 0f),
            ).any { it },
            Emotion.Discomfort to discomfort.update(if (d.smile >= c.SMILE_OFF) 0f else d.browDown),
            Emotion.Boredom to listOf(
                yawn.update(if (d.blink >= c.YAWN_BLINK_MIN) d.jaw else 0f),
                droop.update(if (blink < c.CLOSED_ON) blink else 0f),
                closed.update(blink),
                // ponytail: pitch sign unverified on device, so |delta| covers both nod directions
                nod.update(abs(d.pitch)),
            ).any { it },
        )
        val still = maxOf(d.smile, d.jaw, d.blink, d.browUp, d.browDown) < c.CALM_MAX
        calmStreak = if (still) calmStreak + 1 else 0

        val emotion = fired.firstOrNull { (e, f) -> f && (cooldown[e] ?: 0) == 0 }?.first
            ?: Emotion.Calm.takeIf { calmArmed && calmStreak >= c.CALM_FRAMES }
        when (emotion) {
            null -> return null
            Emotion.Calm -> calmArmed = false
            else -> {
                calmArmed = true
                calmStreak = 0
                cooldown[emotion] = c.COOLDOWN_FRAMES
            }
        }
        last = emotion
        return emotion
    }

    private fun finishCalibration() {
        if (samples.size < c.CALIBRATION_MIN_VALID) {
            phase = Phase.Failed
            return
        }
        val n = samples.size
        baseline = Signals(
            samples.sumOf { it.smile.toDouble() }.toFloat() / n,
            samples.sumOf { it.jaw.toDouble() }.toFloat() / n,
            samples.sumOf { it.blink.toDouble() }.toFloat() / n,
            samples.sumOf { it.browUp.toDouble() }.toFloat() / n,
            samples.sumOf { it.browDown.toDouble() }.toFloat() / n,
            samples.sumOf { it.yaw.toDouble() }.toFloat() / n,
            samples.sumOf { it.pitch.toDouble() }.toFloat() / n,
        )
        phase = Phase.Ready
        calmArmed = false
    }

    private fun resetGates() {
        gates.forEach { it.reset() }
        calmStreak = 0
    }
}

/** Null when there is no face or no blendshapes (undecidable). / 얼굴이나 Blendshape가 없으면 null(판정 불가)입니다. */
internal fun signalsOf(result: FaceDetectionResult): Signals? {
    val face = result.faces.firstOrNull() ?: return null
    val b = face.blendshapes?.takeIf { it.isNotEmpty() }?.associate { it.name to it.score } ?: return null
    fun v(name: String) = b[name] ?: 0f
    val (yaw, pitch) = headPose(face.transformationMatrix)
    return Signals(
        smile = (v("mouthSmileLeft") + v("mouthSmileRight")) / 2,
        jaw = v("jawOpen"),
        blink = (v("eyeBlinkLeft") + v("eyeBlinkRight")) / 2,
        // max, not mean: eyeWide rarely exceeds ~0.4, so a mean almost never reaches the threshold
        browUp = maxOf(v("browInnerUp"), (v("eyeWideLeft") + v("eyeWideRight")) / 2),
        // nose scrunch accompanies a hard frown, so it counts as a frown too
        browDown = maxOf((v("browDownLeft") + v("browDownRight")) / 2, (v("noseSneerLeft") + v("noseSneerRight")) / 2),
        yaw = yaw,
        pitch = pitch,
    )
}

/**
 * (yaw, pitch) in degrees from a 4x4 matrix whose row/column-major layout is detected from the affine [0,0,0,1] row.
 * Scale cancels in atan2, so a scaled rotation is fine. Returns (0, 0) when there is no matrix.
 *
 * 4x4 행렬에서 (yaw, pitch)를 도 단위로 계산합니다. 행 우선인지 열 우선인지는 아핀 행 [0,0,0,1]로 판별합니다.
 */
internal fun headPose(m: List<Float>?): Pair<Float, Float> {
    if (m == null || m.size != 16) return 0f to 0f
    val rowMajor = abs(m[12]) < 1e-4f && abs(m[13]) < 1e-4f && abs(m[14]) < 1e-4f && abs(m[15] - 1f) < 1e-4f
    fun r(row: Int, col: Int) = if (rowMajor) m[row * 4 + col] else m[col * 4 + row]
    val toDeg = (180 / PI).toFloat()
    val pitch = atan2(-r(1, 2), r(2, 2)) * toDeg
    val yaw = atan2(r(0, 2), hypot(r(1, 2), r(2, 2))) * toDeg
    return yaw to pitch
}
