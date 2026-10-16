# landmarkkit (publishable core)

Package `com.sonms.landmarkkit`, iOS framework `LandmarkKit`. Do not use "MediaPipe" in public names (avoid implying an official Google library).

## Public API
- `commonMain` exposes only interfaces and data types (e.g. `FaceDetector`, `Frame`, result/landmark types).
- Platform implementations (`androidMain`, `iosMain`) are `internal`.
- Consumers create detectors via one `expect/actual` factory per task (`createFaceDetector(context, config)`). The only public `expect` class is `PlatformContext` (Android `Context` typealias; iOS `IosPlatformContext()`).
- iOS file names: top-level declarations in `iosMain` become Obj-C facades named after the file and are merged into the `Shared` framework. Keep file names unique across `landmarkkit` and `shared` (e.g. `Detectors.ios.kt`, `FrameHelpers.ios.kt`; never `Platform.ios.kt`).
- Public API changes need user approval; the API must stay stable for external consumers.

## Frame
- Common `Frame` type: width, height, rotation, timestamp, plus platform-held native data.
- Provide conversion helpers only: Android `Bitmap.toFrame` (the app module converts `ImageProxy.toBitmap()`; the core must not depend on CameraX), iOS `CMSampleBufferRef.toFrame` (MediaPipe iOS accepts `UIImage`/`CMSampleBuffer`, not `CVPixelBuffer`). Avoid per-frame copies.
- Never add capture code.

## Models
- Core never bundles or downloads models. The consumer prepares the file beforehand and passes its location in `config` (assets name/path on Android, bundle/file path on iOS).
- No network, storage, or download code in the core.

## iOS
- Call MediaPipe from `iosMain` via Kotlin cinterop (KMP CocoaPods plugin, `MediaPipeTasksVision`). Consumers may need to install the pod too.
- If cinterop is technically blocked: stop, report to the user, and agree on a fallback (Swift implementation injected through the interface) before changing approach.

## Dependencies
- Allowed: Kotlin stdlib, coroutines (if needed), per-platform MediaPipe. Forbidden: Compose, networking, DI frameworks.
- No alpha/beta dependencies in this module.
- Pin the MediaPipe version; upgrade as a separate task and confirm Android/iOS result types still match.

## Adding a task (Hand, etc.)
Follow the Face pattern: interface + result types in `commonMain`, `internal` actuals per platform, model path via config.

## Before remote publishing
Local consumption first (`mavenLocal`, local XCFramework/pod). Before any remote release: check name conflicts on Maven Central, CocoaPods, and Swift Package Index; re-confirm `LandmarkKit` is free.
