package com.sonms.googlemediapipetest

import com.sonms.googlemediapipetest.emotion.Emotion
import com.sonms.googlemediapipetest.emotion.ExpressionConfig
import com.sonms.googlemediapipetest.emotion.ExpressionTracker
import com.sonms.googlemediapipetest.emotion.Phase
import com.sonms.googlemediapipetest.emotion.headPose
import com.sonms.landmarkkit.Blendshape
import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionTrackerTest {
    private fun frame(vararg scores: Pair<String, Float>) = FaceDetectionResult(
        listOf(Face(emptyList(), scores.mapIndexed { i, (n, s) -> Blendshape(i, n, s) }.ifEmpty { listOf(Blendshape(0, "x", 0f)) }, null)),
    )

    private val neutral = frame("mouthSmileLeft" to 0.1f, "mouthSmileRight" to 0.1f)
    private val smiling = frame("mouthSmileLeft" to 0.8f, "mouthSmileRight" to 0.8f)

    private fun calibrated() = ExpressionTracker().apply {
        startCalibration()
        repeat(ExpressionConfig.CALIBRATION_FRAMES) { update(neutral) }
        assertEquals(Phase.Ready, phase)
    }

    @Test
    fun smileTriggersOnlyAfterConsecutiveFrames() {
        val t = calibrated()
        assertNull(t.update(smiling))
        assertNull(t.update(smiling))
        assertEquals(Emotion.Joy, t.update(smiling))
        assertNull(t.update(smiling)) // fires once per activation
    }

    @Test
    fun faceLossResetsStreakAndIsNotTreatedAsNeutral() {
        val t = calibrated()
        t.update(smiling)
        t.update(smiling)
        assertNull(t.update(FaceDetectionResult(emptyList())))
        assertNull(t.delta)
        assertNull(t.update(smiling)) // streak restarted
    }

    @Test
    fun calibrationFailsWithoutEnoughFaces() {
        val t = ExpressionTracker().apply { startCalibration() }
        repeat(ExpressionConfig.CALIBRATION_FRAMES) { t.update(null) }
        assertEquals(Phase.Failed, t.phase)
    }

    @Test
    fun squintingWithBrowsDownIsDiscomfortNotBoredom() {
        val t = calibrated()
        val squint = frame("eyeBlinkLeft" to 0.4f, "eyeBlinkRight" to 0.4f, "browDownLeft" to 0.8f, "browDownRight" to 0.8f)
        val fired = List(ExpressionConfig.DROOP_FRAMES + 4) { t.update(squint) }.filterNotNull()
        assertEquals(listOf(Emotion.Discomfort), fired)
    }

    @Test
    fun raisedBrowsAloneTriggerInterest() {
        val t = calibrated()
        val raised = frame("browInnerUp" to 0.6f)
        val fired = List(5) { t.update(raised) }.filterNotNull()
        assertEquals(listOf(Emotion.Interest), fired)
    }

    @Test
    fun openMouthWithOpenEyesIsInterest() {
        val t = calibrated()
        val fired = List(6) { t.update(frame("jawOpen" to 0.6f)) }.filterNotNull()
        assertEquals(listOf(Emotion.Interest), fired)
    }

    @Test
    fun yawnWithNarrowedEyesIsNotInterest() {
        val t = calibrated()
        val yawn = frame("jawOpen" to 0.7f, "eyeBlinkLeft" to 0.3f, "eyeBlinkRight" to 0.3f)
        val fired = List(ExpressionConfig.YAWN_FRAMES + 2) { t.update(yawn) }.filterNotNull()
        assertEquals(listOf(Emotion.Boredom), fired)
    }

    @Test
    fun smallMouthWithSlightlyNarrowedEyesIsInterestNotBoredom() {
        val t = calibrated()
        val f = frame("jawOpen" to 0.35f, "eyeBlinkLeft" to 0.25f, "eyeBlinkRight" to 0.25f)
        val fired = List(ExpressionConfig.DROOP_FRAMES + 4) { t.update(f) }.filterNotNull()
        assertEquals(listOf(Emotion.Interest), fired)
    }

    @Test
    fun smilingWithEyesClosedIsComfortOnly() {
        val t = calibrated()
        val f = frame(
            "mouthSmileLeft" to 0.8f, "mouthSmileRight" to 0.8f, "eyeBlinkLeft" to 0.9f, "eyeBlinkRight" to 0.9f,
        )
        val fired = List(ExpressionConfig.DROOP_FRAMES + 4) { t.update(f) }.filterNotNull()
        assertEquals(listOf(Emotion.Comfort), fired)
    }

    @Test
    fun weakFrownTriggersDiscomfort() {
        val t = calibrated()
        val fired = List(5) { t.update(frame("browDownLeft" to 0.35f, "browDownRight" to 0.35f)) }.filterNotNull()
        assertEquals(listOf(Emotion.Discomfort), fired)
    }

    @Test
    fun noseScrunchTriggersDiscomfort() {
        val t = calibrated()
        val fired = List(5) { t.update(frame("noseSneerLeft" to 0.5f, "noseSneerRight" to 0.5f)) }.filterNotNull()
        assertEquals(listOf(Emotion.Discomfort), fired)
    }

    @Test
    fun smilingSuppressesDiscomfort() {
        val t = calibrated()
        val f = frame("mouthSmileLeft" to 0.8f, "mouthSmileRight" to 0.8f, "noseSneerLeft" to 0.5f, "noseSneerRight" to 0.5f)
        assertEquals(listOf(Emotion.Joy), List(5) { t.update(f) }.filterNotNull())
    }

    @Test
    fun nothingTriggersBeforeCalibration() {
        assertNull(ExpressionTracker().update(smiling))
    }

    @Test
    fun calmFiresOnlyAfterAnotherEmotion() {
        val t = calibrated()
        repeat(ExpressionConfig.CALM_FRAMES + 5) { assertNull(t.update(neutral)) }
        repeat(3) { t.update(smiling) }
        var calm = 0
        repeat(ExpressionConfig.CALM_FRAMES + 5) { if (t.update(neutral) == Emotion.Calm) calm++ }
        assertEquals(1, calm)
    }

    @Test
    fun headPoseIsLayoutIndependent() {
        val a = 30 * PI / 180
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // real matrices always carry a translation; with none, the two layouts are indistinguishable
        val rowMajor = listOf(1f, 0f, 0f, 5f, 0f, c, -s, 6f, 0f, s, c, 7f, 0f, 0f, 0f, 1f)
        val colMajor = listOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 5f, 6f, 7f, 1f)
        for (m in listOf(rowMajor, colMajor)) {
            val (yaw, pitch) = headPose(m)
            assertTrue(abs(pitch - 30f) < 0.1f && abs(yaw) < 0.1f, "$yaw $pitch")
        }
    }
}
