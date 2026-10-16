import AVFoundation
import Shared
import UIKit

/// App-module camera: AVCaptureSession frames -> LandmarkKit face detectors -> Compose overlay + session recorders.
final class CameraController: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    let overlay = OverlayController()
    @Published var summary = "faces: 0"
    @Published var recording = false
    @Published var finished = false
    @Published var elapsedSec: Int64 = 0
    @Published var compareMode = false

    // Keep in sync with gradle/libs.versions.toml (mediapipe); written into every session file.
    private let mediapipeVersion = "0.10.21"
    private let modelName = "face_landmarker"

    private let queue = DispatchQueue(label: "camera.analysis")
    // Touched only on `queue`. One detector per comparison lane; index 0 is also the single-mode detector.
    private var detectors: [FaceDetector] = []
    private var recorder: SessionRecorder?
    private var comparison: ComparisonRecorder?
    private var compareRequested = false // snapshot of `compareMode` for `queue`

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { granted in
            guard granted else { return }
            self.queue.async {
                self.configure()
                self.session.startRunning()
            }
        }
    }

    func toggleRecording() {
        let compare = compareMode
        queue.async {
            if self.recorder == nil && self.comparison == nil {
                self.compareRequested = compare
                self.startRecording()
            } else {
                self.stopRecording()
            }
        }
    }

    func toggleCompare() {
        if !recording { compareMode.toggle() }
    }

    private var modelPath: String? { Bundle.main.path(forResource: modelName, ofType: "task") }

    private func makeConfig(path: String) -> FaceDetectorConfig {
        FaceDetectorConfig(
            modelPath: path,
            maxFaces: 1,
            minFaceDetectionConfidence: 0.5,
            minFacePresenceConfidence: 0.5,
            minTrackingConfidence: 0.5,
            outputBlendshapes: true,
            outputTransformationMatrix: true
        )
    }

    private func ensureDetectors(count: Int, path: String) {
        while detectors.count < count {
            detectors.append(Detectors_iosKt.createFaceDetector(context: IosPlatformContext(), config: makeConfig(path: path)))
        }
    }

    private func startRecording() {
        guard let path = modelPath, let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int64) ?? 0
        let env = EnvironmentInfo(
            platform: "ios",
            osVersion: UIDevice.current.systemVersion,
            deviceModel: machineIdentifier(),
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            mediapipeVersion: mediapipeVersion,
            cameraLens: "back",
            modelFileName: "\(modelName).task",
            modelSizeBytes: size
        )
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let maxDurationMs: Int64 = 10 * 60 * 1000
        if compareRequested {
            comparison = ComparisonRecorder(
                outputDir: documents.path,
                env: env,
                config: makeConfig(path: path),
                startedAtEpochMs: startedAt,
                fpsList: ComparisonRecorderKt.CompareFps,
                decimals: 6,
                maxDurationMs: maxDurationMs
            )
        } else {
            recorder = SessionRecorder(
                outputDir: documents.path,
                env: env,
                config: makeConfig(path: path),
                targetFps: 30,
                startedAtEpochMs: startedAt,
                decimals: 6,
                maxDurationMs: maxDurationMs,
                sessionId: String(UInt64.random(in: UInt64.min...UInt64.max), radix: 16),
                label: nil,
                compareMode: false
            )
        }
        DispatchQueue.main.async {
            self.recording = true
            self.finished = false
            self.elapsedSec = 0
        }
    }

    private func stopRecording() {
        _ = recorder?.stop()
        _ = comparison?.stop()
        recorder = nil
        comparison = nil
        DispatchQueue.main.async {
            self.recording = false
            self.finished = true
        }
    }

    /// Hardware identifier such as "iPhone15,2" (a model name, not a device-unique ID).
    private func machineIdentifier() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { String(cString: $0) }
        }
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        // Deliver upright portrait buffers so the frame rotation passed to LandmarkKit is 0.
        output.connection(with: .video)?.videoRotationAngle = 90
        session.commitConfiguration()
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer), let path = modelPath else { return }
        let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        let timestampMs = Int64(seconds * 1000)
        let frame = FrameHelpers_iosKt.toFrame(
            Unmanaged.passUnretained(sampleBuffer).toOpaque(),
            width: Int32(CVPixelBufferGetWidth(pixelBuffer)),
            height: Int32(CVPixelBufferGetHeight(pixelBuffer)),
            rotationDegrees: 0,
            timestampMs: timestampMs
        )

        var shown: FaceDetectionResult?
        if let compared = comparison {
            ensureDetectors(count: Int(compared.laneCount), path: path)
            let outcome = compared.onFrame(timestampMs: timestampMs) { lane in
                self.detectors[Int(truncating: lane)].detect(frame: frame)
            }
            shown = outcome.results.first as? FaceDetectionResult
            report(elapsedMs: compared.elapsedMs())
            if outcome.limitReached { stopRecording() }
        } else {
            ensureDetectors(count: 1, path: path)
            let start = CACurrentMediaTime()
            let result = detectors[0].detect(frame: frame)
            let latencyMs = (CACurrentMediaTime() - start) * 1000
            shown = result
            if let active = recorder {
                let limitReached = active.onFrame(timestampMs: timestampMs, result: result, latencyMs: latencyMs)
                report(elapsedMs: active.elapsedMs())
                if limitReached { stopRecording() }
            }
        }

        guard let result = shown else { return }
        DispatchQueue.main.async {
            self.overlay.update(result: result)
            let first = result.faces.first
            self.summary = "faces: \(result.faces.count), landmarks: \(first?.landmarks.count ?? 0), blendshapes: \(first?.blendshapes?.count ?? 0)"
        }
    }

    private func report(elapsedMs: Int64) {
        let sec = elapsedMs / 1000
        DispatchQueue.main.async { self.elapsedSec = sec }
    }
}
