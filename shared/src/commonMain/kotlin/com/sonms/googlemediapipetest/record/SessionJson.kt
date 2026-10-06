package com.sonms.googlemediapipetest.record

import com.sonms.landmarkkit.FaceDetectorConfig

/**
 * Environment facts supplied by the app module. Must not contain personal or device-unique identifiers.
 *
 * 앱 모듈이 전달하는 환경 정보입니다. 개인 정보나 기기 고유 식별자를 넣으면 안 됩니다.
 *
 * @property platform "android" or "ios". / "android" 또는 "ios"입니다.
 * @property osVersion OS version string, e.g. "15". / OS 버전 문자열입니다. 예: "15".
 * @property deviceModel hardware model name, e.g. "Pixel 8" or "iPhone15,2" (not a unique device ID).
 * / 하드웨어 모델명입니다. 예: "Pixel 8", "iPhone15,2"(기기 고유 ID가 아닙니다).
 * @property appVersion version of the app module. / 앱 모듈의 버전입니다.
 * @property mediapipeVersion MediaPipe Tasks version, to reproduce results. / 결과를 재현하기 위한 MediaPipe Tasks 버전입니다.
 * @property cameraLens "back" or "front". / "back" 또는 "front"입니다.
 * @property modelFileName name of the `.task` model in use. / 사용 중인 `.task` 모델의 파일 이름입니다.
 * @property modelSizeBytes size of that model file. / 그 모델 파일의 크기(바이트)입니다.
 */
class EnvironmentInfo(
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val appVersion: String,
    val mediapipeVersion: String,
    val cameraLens: String,
    val modelFileName: String,
    val modelSizeBytes: Long,
)

private fun StringBuilder.str(value: String) {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        else -> append(c)
    }
    append('"')
}

private fun StringBuilder.num(value: Double) {
    if (value.isFinite()) append(value) else append("null")
}

/** First line of the session JSONL file. / 세션 JSONL 파일의 첫 줄(메타데이터)입니다. */
internal fun metaLine(
    sessionId: String,
    startedAtEpochMs: Long,
    env: EnvironmentInfo,
    config: FaceDetectorConfig,
    targetFps: Int,
    decimals: Int,
    maxDurationMs: Long,
    compareMode: Boolean,
): String = buildString {
    append("{\"meta\":{\"sessionId\":")
    str(sessionId)
    append(",\"startedAtEpochMs\":").append(startedAtEpochMs)
    append(",\"platform\":")
    str(env.platform)
    append(",\"osVersion\":")
    str(env.osVersion)
    append(",\"deviceModel\":")
    str(env.deviceModel)
    append(",\"appVersion\":")
    str(env.appVersion)
    append(",\"mediapipeVersion\":")
    str(env.mediapipeVersion)
    append(",\"cameraLens\":")
    str(env.cameraLens)
    append(",\"modelFileName\":")
    str(env.modelFileName)
    append(",\"modelSizeBytes\":").append(env.modelSizeBytes)
    append(",\"config\":{\"maxFaces\":").append(config.maxFaces)
    append(",\"minFaceDetectionConfidence\":").append(config.minFaceDetectionConfidence)
    append(",\"minFacePresenceConfidence\":").append(config.minFacePresenceConfidence)
    append(",\"minTrackingConfidence\":").append(config.minTrackingConfidence)
    append(",\"outputBlendshapes\":").append(config.outputBlendshapes)
    append(",\"outputTransformationMatrix\":").append(config.outputTransformationMatrix)
    append("},\"targetFps\":").append(targetFps)
    append(",\"jsonDecimals\":").append(decimals)
    append(",\"zRange\":").append(Z_RANGE)
    append(",\"stillTranslation\":").append(STILL_TRANSLATION)
    append(",\"stillRotation\":").append(STILL_ROTATION)
    append(",\"maxDurationMs\":").append(maxDurationMs)
    append(",\"compareMode\":").append(compareMode).append("}}")
}

internal fun reportJson(sessionId: String, payload: PayloadReport, quality: QualitySummary): String = buildString {
    append("{\"sessionId\":")
    str(sessionId)
    append(",\"frames\":").append(payload.frames)
    append(",\"durationMs\":").append(payload.durationMs)
    append(",\"assumptions\":{\"protocolOverhead\":").append(PROTOCOL_OVERHEAD)
    append(",\"profiles\":[")
    NetworkProfiles.forEachIndexed { i, p ->
        if (i > 0) append(',')
        append("{\"name\":")
        str(p.name)
        append(",\"mbps\":").append(p.mbps).append(",\"rttMs\":").append(p.rttMs).append('}')
    }
    append("]},\"formats\":[")
    payload.formats.forEachIndexed { i, f ->
        if (i > 0) append(',')
        append("{\"name\":")
        str(f.name)
        append(",\"totalBytes\":").append(f.totalBytes)
        append(",\"bytesPerFrame\":")
        num(f.bytesPerFrame)
        append(",\"mbPerMinute\":")
        num(f.mbPerMinute)
        append(",\"gbPerHour\":")
        num(f.gbPerHour)
        append(",\"encodeMsPerFrame\":")
        num(f.encodeMsPerFrame)
        append(",\"uploadSecondsPerMinuteOfData\":{")
        f.uploadSecondsPerMinute.entries.forEachIndexed { j, (name, seconds) ->
            if (j > 0) append(',')
            str(name)
            append(':')
            num(seconds)
        }
        append("}}")
    }
    append("],\"quantization\":{\"zRange\":").append(Z_RANGE)
    append(",\"zMin\":")
    num(payload.quant.zMin.toDouble())
    append(",\"zMax\":")
    num(payload.quant.zMax.toDouble())
    append(",\"zClamped\":").append(payload.quant.zClamped)
    append(",\"xyClamped\":").append(payload.quant.xyClamped)
    append("},\"quality\":{\"detectionRate\":")
    num(quality.detectionRate)
    append(",\"detectedFrames\":").append(quality.detectedFrames)
    append(",\"meanLatencyMs\":")
    num(quality.meanLatencyMs)
    append(",\"p95LatencyMs\":")
    num(quality.p95LatencyMs)
    append(",\"jitter\":")
    val j = quality.jitter
    if (j == null) {
        append("null")
    } else {
        append("{\"stillPairs\":").append(j.stillPairs)
        append(",\"medianDisplacement\":")
        num(j.medianDisplacement)
        append(",\"medianDisplacementPerSecond\":")
        num(j.medianDisplacementPerSecond)
        append('}')
    }
    append(",\"blendshapes\":[")
    quality.blendshapes.forEachIndexed { i, b ->
        if (i > 0) append(',')
        append("{\"index\":").append(b.index).append(",\"name\":")
        str(b.name)
        append(",\"min\":")
        num(b.min)
        append(",\"max\":")
        num(b.max)
        append(",\"mean\":")
        num(b.mean)
        append(",\"std\":")
        num(b.std)
        append('}')
    }
    append("]}}")
}
