package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import kotlin.math.sqrt

/**
 * Max head translation change between two frames (in transformation-matrix units) to still count as "still".
 * / 두 프레임 사이에서 "정지"로 인정하는 머리 이동량의 최대값입니다(변환 행렬 단위).
 */
const val STILL_TRANSLATION = 0.5

/**
 * Max head rotation change between two frames (Frobenius norm of the 3x3 difference) to still count as "still".
 * / 두 프레임 사이에서 "정지"로 인정하는 머리 회전 변화의 최대값입니다(3x3 차이의 프로베니우스 노름).
 */
const val STILL_ROTATION = 0.01

/**
 * How much one blendshape varied during a session. A near-zero spread means the expression was never captured.
 *
 * 한 세션 동안 블렌드셰이프 하나가 얼마나 변했는지 나타냅니다. 변동이 거의 없으면 그 표정이 포착되지 않은 것입니다.
 *
 * @property index blendshape index (MediaPipe's fixed order). / 블렌드셰이프 인덱스입니다(MediaPipe의 고정 순서).
 * @property name blendshape name, e.g. `jawOpen`. / 블렌드셰이프 이름입니다. 예: `jawOpen`.
 * @property min smallest score seen. / 관측한 점수의 최솟값입니다.
 * @property max largest score seen. / 관측한 점수의 최댓값입니다.
 * @property mean average score. / 점수의 평균입니다.
 * @property std sample standard deviation of the score. / 점수의 표본 표준편차입니다.
 */
class BlendshapeStat(val index: Int, val name: String, val min: Double, val max: Double, val mean: Double, val std: Double)

/**
 * Landmark noise while the head is still (jitter).
 *
 * 머리가 정지해 있을 때의 랜드마크 노이즈(떨림)입니다.
 *
 * @property stillPairs number of consecutive frame pairs that qualified as "still".
 * / "정지"로 인정된 연속 프레임 쌍의 개수입니다.
 * @property medianDisplacement median of the mean landmark displacement (normalized x,y units) between consecutive
 * processed frames. / 연속으로 처리한 프레임 사이에서 랜드마크 평균 변위(정규화된 x, y 단위)의 중앙값입니다.
 * @property medianDisplacementPerSecond the same value divided by the frame interval, comparable across different fps.
 * / 같은 값을 프레임 간격(초)으로 나눈 값이며, fps가 다른 결과끼리 비교할 수 있습니다.
 */
class JitterStat(
    val stillPairs: Int,
    val medianDisplacement: Double,
    val medianDisplacementPerSecond: Double,
)

/**
 * Proxy quality metrics of one session (there are no emotion labels, so quality is judged indirectly).
 *
 * 한 세션의 대리 품질 지표입니다(감정 라벨이 없으므로 품질을 간접적으로 판단합니다).
 *
 * @property frames processed frames, including those without a face. / 얼굴이 없는 프레임을 포함한 처리 프레임 수입니다.
 * @property detectedFrames processed frames in which at least one face was found.
 * / 얼굴을 하나 이상 찾은 프레임 수입니다.
 * @property detectionRate [detectedFrames] divided by [frames]; low values point to lighting or angle problems.
 * / [detectedFrames]를 [frames]로 나눈 값이며, 낮으면 조명이나 각도 문제를 의심할 수 있습니다.
 * @property blendshapes spread of each blendshape over detected frames (first face only).
 * / 감지된 프레임에서 블렌드셰이프별 변동 정도입니다(첫 번째 얼굴만).
 * @property jitter noise measurement; null when there were no still frame pairs or no transformation matrix.
 * / 노이즈 측정값입니다. 정지 구간 쌍이 없거나 변환 행렬이 없으면 null입니다.
 * @property meanLatencyMs average time of one detector call. / 감지기 호출 한 번의 평균 소요 시간입니다.
 * @property p95LatencyMs 95th percentile of the detector call time. / 감지기 호출 시간의 95번째 백분위수입니다.
 */
class QualitySummary(
    val frames: Int,
    val detectedFrames: Int,
    val detectionRate: Double,
    val blendshapes: List<BlendshapeStat>,
    val jitter: JitterStat?,
    val meanLatencyMs: Double,
    val p95LatencyMs: Double,
)

private class Welford {
    var n = 0
    var mean = 0.0
    var m2 = 0.0
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
        if (x < min) min = x
        if (x > max) max = x
    }

    fun std() = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
}

internal fun median(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

internal fun percentile(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    return s[((s.size - 1) * p).toInt()]
}

/**
 * Proxy quality metrics for unlabeled data, computed on the first face of each processed frame.
 * The matrix layout is detected per matrix: the affine row/column is [0,0,0,1].
 *
 * 라벨이 없는 데이터를 위한 대리 품질 지표를 처리한 프레임의 첫 번째 얼굴 기준으로 계산합니다.
 * 행렬 배치는 행렬마다 판별하며, 아핀 행 또는 열이 [0,0,0,1]입니다.
 */
class QualityMeter {
    private var frames = 0
    private var detected = 0
    private val latencies = ArrayList<Double>()
    private val blendshapes = HashMap<Int, Welford>()
    private val names = HashMap<Int, String>()
    private val displacements = ArrayList<Double>()
    private val displacementsPerSecond = ArrayList<Double>()
    private var previous: Face? = null
    private var previousTimestampMs = 0L

    /**
     * Adds one processed frame.
     *
     * 처리한 프레임 하나를 추가합니다.
     *
     * @param result detector output for this frame (empty faces counts as a miss).
     * / 이 프레임의 감지 결과입니다(얼굴이 없으면 미감지로 셉니다).
     * @param latencyMs time the detector call took. / 감지기 호출에 걸린 시간(ms)입니다.
     */
    fun add(timestampMs: Long, result: FaceDetectionResult, latencyMs: Double) {
        frames++
        latencies += latencyMs
        val face = result.faces.firstOrNull()
        if (face == null) {
            previous = null
            return
        }
        detected++
        face.blendshapes?.forEach {
            names[it.index] = it.name
            blendshapes.getOrPut(it.index) { Welford() }.add(it.score.toDouble())
        }
        val before = previous
        val m0 = before?.transformationMatrix
        val m1 = face.transformationMatrix
        if (before != null && m0 != null && m1 != null && before.landmarks.size == face.landmarks.size && isStill(m0, m1)) {
            val d = meanDisplacement(before, face)
            displacements += d
            val dt = (timestampMs - previousTimestampMs) / 1000.0
            if (dt > 0) displacementsPerSecond += d / dt
        }
        previous = face
        previousTimestampMs = timestampMs
    }

    /** Computes the metrics over everything added so far. / 지금까지 추가한 모든 프레임의 지표를 계산합니다. */
    fun summary(): QualitySummary = QualitySummary(
        frames = frames,
        detectedFrames = detected,
        detectionRate = if (frames > 0) detected.toDouble() / frames else 0.0,
        blendshapes = blendshapes.keys.sorted().map {
            val w = blendshapes.getValue(it)
            BlendshapeStat(it, names[it].orEmpty(), w.min, w.max, w.mean, w.std())
        },
        jitter = if (displacements.isEmpty()) null else JitterStat(displacements.size, median(displacements), median(displacementsPerSecond)),
        meanLatencyMs = if (latencies.isEmpty()) Double.NaN else latencies.sum() / latencies.size,
        p95LatencyMs = percentile(latencies, 0.95),
    )

    private fun meanDisplacement(a: Face, b: Face): Double {
        var sum = 0.0
        for (i in a.landmarks.indices) {
            val dx = (a.landmarks[i].x - b.landmarks[i].x).toDouble()
            val dy = (a.landmarks[i].y - b.landmarks[i].y).toDouble()
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / a.landmarks.size
    }

    private fun isStill(a: List<Float>, b: List<Float>): Boolean {
        // Column-major when the affine zeros sit at 3,7,11 (translation at 12..14); otherwise row-major (translation at 3,7,11).
        val columnMajor = a[3] == 0f && a[7] == 0f && a[11] == 0f
        val translation = if (columnMajor) intArrayOf(12, 13, 14) else intArrayOf(3, 7, 11)
        val affine = if (columnMajor) intArrayOf(3, 7, 11, 15) else intArrayOf(12, 13, 14, 15)
        var t = 0.0
        var r = 0.0
        for (i in 0 until 16) {
            val d = (a[i] - b[i]).toDouble()
            when (i) {
                in translation -> t += d * d
                in affine -> Unit
                else -> r += d * d
            }
        }
        return sqrt(t) < STILL_TRANSLATION && sqrt(r) < STILL_ROTATION
    }
}
