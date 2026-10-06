package com.sonms.googlemediapipetest

import com.sonms.googlemediapipetest.record.ComparisonRecorder
import com.sonms.googlemediapipetest.record.EnvironmentInfo
import com.sonms.landmarkkit.Blendshape
import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.FaceDetectorConfig
import com.sonms.landmarkkit.Landmark
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparisonTest {
    private val env = EnvironmentInfo("test", "1", "model", "1.0", "0.10.21", "back", "face.task", 1)

    private fun result(score: Float, x: Float) = FaceDetectionResult(
        listOf(
            Face(
                landmarks = List(5) { Landmark(x, 0.5f, 0f) },
                blendshapes = listOf(Blendshape(0, "jawOpen", score)),
                transformationMatrix = null,
            ),
        ),
    )

    @Test
    fun lanesProcessAtTheirOwnRateAndPairedDiffsAreComputed() {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "landmarkkit-compare-${kotlin.random.Random.nextInt()}"
        val recorder = ComparisonRecorder(dir.toString(), env, FaceDetectorConfig("face.task"), startedAtEpochMs = 1, fpsList = listOf(30, 10, 5))
        val processed = IntArray(recorder.laneCount)
        repeat(61) { n -> // two seconds of a 30fps camera
            val outcome = recorder.onFrame(n * 1000L / 30) { lane ->
                processed[lane]++
                if (lane == 0) result(0.5f, 0.5f) else result(0.4f, 0.52f) // lower lanes differ by 0.1 / 0.02
            }
            assertEquals(outcome.results[0] != null, true) // baseline processes every frame
        }
        assertEquals(61, processed[0])
        assertTrue(processed[1] in 19..22, "10fps lane processed ${processed[1]}")
        assertTrue(processed[2] in 9..12, "5fps lane processed ${processed[2]}")

        val files = recorder.stop()
        recorder.stop() // harmless
        assertEquals(3, files.size)
        files.forEach { assertTrue(FileSystem.SYSTEM.exists(it.dataPath.toPath()) && FileSystem.SYSTEM.exists(it.reportPath.toPath())) }

        val compare = FileSystem.SYSTEM.list(dir).first { it.name.endsWith(".compare.json") }
            .let { FileSystem.SYSTEM.read(it) { readUtf8() } }
        assertTrue(compare.contains("\"baselineFps\":30"))
        assertTrue(Regex("\"meanAbsBlendshapeDiff\":0\\.(09[0-9]*|1[0-9]*)").containsMatchIn(compare), compare)
        assertTrue(Regex("\"meanLandmarkDisplacement\":0\\.0[12][0-9]*").containsMatchIn(compare), compare)
        FileSystem.SYSTEM.deleteRecursively(dir)
    }
}
