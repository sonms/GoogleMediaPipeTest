package com.sonms.googlemediapipetest

import com.sonms.googlemediapipetest.record.EnvironmentInfo
import com.sonms.googlemediapipetest.record.QualityMeter
import com.sonms.googlemediapipetest.record.SessionRecorder
import com.sonms.googlemediapipetest.record.median
import com.sonms.googlemediapipetest.record.percentile
import com.sonms.landmarkkit.Blendshape
import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.FaceDetectorConfig
import com.sonms.landmarkkit.Landmark
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    // Row-major identity: translation sits at indices 3, 7, 11.
    private fun matrix(tx: Float) = List(16) { if (it % 5 == 0) 1f else 0f }.toMutableList().also { it[3] = tx }

    private fun frame(x: Float, tx: Float, score: Float = 0.5f) = FaceDetectionResult(
        listOf(
            Face(
                landmarks = List(10) { Landmark(x, 0.5f, 0f) },
                blendshapes = listOf(Blendshape(0, "jawOpen", score)),
                transformationMatrix = matrix(tx),
            ),
        ),
    )

    @Test
    fun medianAndPercentile() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, median(listOf(4.0, 1.0, 2.0, 3.0)))
        assertTrue(median(emptyList()).isNaN())
        assertEquals(10.0, percentile((1..10).map { it.toDouble() }, 1.0))
    }

    @Test
    fun jitterOnlyCountsStillFramePairs() {
        val meter = QualityMeter()
        meter.add(0, frame(0.50f, 5f), 4.0)
        meter.add(100, frame(0.51f, 5f), 6.0) // still: landmarks moved 0.01 over 0.1 s
        meter.add(200, frame(0.80f, 25f), 5.0) // head moved: excluded
        val s = meter.summary()
        val jitter = assertNotNull(s.jitter)
        assertEquals(1, jitter.stillPairs)
        assertTrue(kotlin.math.abs(jitter.medianDisplacement - 0.01) < 1e-6)
        assertTrue(kotlin.math.abs(jitter.medianDisplacementPerSecond - 0.1) < 1e-6)
        assertEquals(5.0, s.meanLatencyMs)
    }

    @Test
    fun noStillFramesMeansJitterNotMeasurable() {
        val meter = QualityMeter()
        meter.add(0, frame(0.5f, 5f), 1.0)
        meter.add(100, frame(0.5f, 50f), 1.0)
        assertNull(meter.summary().jitter)
    }

    @Test
    fun detectionRateAndBlendshapeSpread() {
        val meter = QualityMeter()
        meter.add(0, frame(0.5f, 5f, score = 0.0f), 1.0)
        meter.add(33, FaceDetectionResult(emptyList()), 1.0)
        meter.add(66, frame(0.5f, 5f, score = 1.0f), 1.0)
        meter.add(99, FaceDetectionResult(emptyList()), 1.0)
        val s = meter.summary()
        assertEquals(0.5, s.detectionRate)
        val jaw = s.blendshapes.single()
        assertEquals("jawOpen", jaw.name)
        assertEquals(0.0, jaw.min)
        assertEquals(1.0, jaw.max)
        assertEquals(0.5, jaw.mean)
    }

    @Test
    fun recorderWritesMetaFramesAndReport() {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "landmarkkit-test-${kotlin.random.Random.nextInt()}"
        val env = EnvironmentInfo("test", "1", "model", "1.0", "0.10.21", "back", "face.task", 123)
        val recorder = SessionRecorder(dir.toString(), env, FaceDetectorConfig("face.task"), 10, startedAtEpochMs = 1000, maxDurationMs = 150)
        assertEquals(false, recorder.onFrame(0, frame(0.5f, 5f), 3.0))
        assertEquals(false, recorder.onFrame(100, frame(0.5f, 5f), 3.0))
        assertEquals(true, recorder.onFrame(200, FaceDetectionResult(emptyList()), 3.0))
        val files = recorder.stop()
        recorder.stop() // second call must be harmless

        val lines = FileSystem.SYSTEM.read(files.dataPath.toPath()) { readUtf8() }.trim().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("{\"meta\":{\"sessionId\":"))
        assertTrue(lines[3].contains("\"faces\":[]"))
        val report = FileSystem.SYSTEM.read(files.reportPath.toPath()) { readUtf8() }
        assertTrue(report.contains("\"detectionRate\":") && report.contains("\"formats\":["))
        FileSystem.SYSTEM.deleteRecursively(dir)
    }
}
