package com.sonms.googlemediapipetest

import com.sonms.googlemediapipetest.record.PayloadMeter
import com.sonms.googlemediapipetest.record.QuantStats
import com.sonms.googlemediapipetest.record.Z_RANGE
import com.sonms.googlemediapipetest.record.appendFixed
import com.sonms.googlemediapipetest.record.dequantizeUnit
import com.sonms.googlemediapipetest.record.dequantizeZ
import com.sonms.googlemediapipetest.record.encodeFloat32
import com.sonms.googlemediapipetest.record.encodeJson
import com.sonms.googlemediapipetest.record.encodeQuantized
import com.sonms.googlemediapipetest.record.quantizeUnit
import com.sonms.googlemediapipetest.record.quantizeZ
import com.sonms.landmarkkit.Blendshape
import com.sonms.landmarkkit.Face
import com.sonms.landmarkkit.FaceDetectionResult
import com.sonms.landmarkkit.Landmark
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayloadTest {
    private fun fixed(v: Float, d: Int) = buildString { appendFixed(v, d) }

    private fun result(): FaceDetectionResult = FaceDetectionResult(
        listOf(
            Face(
                landmarks = List(478) { Landmark(it / 478f, 1f - it / 478f, (it % 10) / 100f - 0.05f) },
                blendshapes = List(52) { Blendshape(it, "b$it", it / 52f) },
                transformationMatrix = List(16) { it.toFloat() },
            ),
        ),
    )

    @Test
    fun fixedFormatTrimsZerosAndKeepsSign() {
        assertEquals("0.05", fixed(0.05f, 6))
        assertEquals("-0.123457", fixed(-0.1234567f, 6))
        assertEquals("0.1235", fixed(0.123456f, 4))
        assertEquals("0", fixed(-0.0000001f, 6))
        assertEquals("1", fixed(1f, 6))
        assertEquals("null", fixed(Float.NaN, 6))
    }

    @Test
    fun jsonLineHasExpectedShape() {
        val json = encodeJson(42, result(), 4)
        assertTrue(json.startsWith("{\"t\":42,\"faces\":[{\"lm\":["))
        assertTrue(json.contains("\"bs\":[") && json.contains("\"m\":[0,1,2"))
        assertEquals(478 * 3 - 1, json.substringAfter("\"lm\":[").substringBefore("]").count { it == ',' })
    }

    @Test
    fun quantizationRoundTripStaysWithinHalfStep() {
        for (i in 0..1000) {
            val v = i / 1000f
            assertTrue(abs(dequantizeUnit(quantizeUnit(v)) - v) <= 0.5f / 65535f + 1e-7f)
        }
        for (z in listOf(-0.4f, -0.01f, 0f, 0.25f)) {
            assertTrue(abs(dequantizeZ(quantizeZ(z)) - z) <= Z_RANGE / 32767f)
        }
    }

    @Test
    fun clampsAreCountedAndRangeObserved() {
        val stats = QuantStats()
        quantizeZ(0.9f, stats)
        quantizeZ(-0.1f, stats)
        quantizeUnit(1.2f, stats)
        assertEquals(1, stats.zClamped)
        assertEquals(1, stats.xyClamped)
        assertEquals(-0.1f, stats.zMin)
        assertEquals(0.9f, stats.zMax)
    }

    @Test
    fun binarySizesMatchLayout() {
        val r = result()
        val header = 8 + 1
        val faceFixed = 1 + 2 + 1 + 64
        assertEquals(header + faceFixed + 478 * 12 + 52 * 4, encodeFloat32(0, r).size)
        assertEquals(header + faceFixed + 478 * 6 + 52 * 2, encodeQuantized(0, r).size)
    }

    @Test
    fun meterReportsCompressionAndUploadTime() {
        val meter = PayloadMeter()
        repeat(30) { meter.add(it * 33L, result()) }
        val report = meter.report(durationMs = 1000)
        val byName = report.formats.associateBy { it.name }
        assertEquals(30, report.frames)
        assertTrue(byName.getValue("JSON 6 + gzip").totalBytes < byName.getValue("JSON (6 decimals)").totalBytes)
        assertTrue(byName.getValue("JSON (4 decimals)").totalBytes < byName.getValue("JSON (6 decimals)").totalBytes)
        assertTrue(byName.getValue("Binary quantized").totalBytes < byName.getValue("Binary float32").totalBytes)
        val json = byName.getValue("JSON (6 decimals)")
        assertTrue(json.uploadSecondsPerMinute.getValue("Poor") > json.uploadSecondsPerMinute.getValue("Wi-Fi"))
    }
}
