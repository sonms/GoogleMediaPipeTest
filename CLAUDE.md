# GoogleMediaPipeTest

Respond to the user in Korean. Write code, identifiers, and this document in English.

## Purpose
Test bed for Google MediaPipe on Android and iOS (Kotlin Multiplatform + Compose Multiplatform).
The reusable part (`landmarkkit`) may later be published and consumed by other Android/iOS projects as a dependency.

## Modules
| Module | Role | Rules |
|---|---|---|
| `landmarkkit` | Publishable core: frame in, landmarks out | Strict. See `landmarkkit/CLAUDE.md` |
| `shared` | Common module: shared Compose test screens (result display, overlay) | No camera code, no platform camera deps |
| `androidApp` | App module: CameraX capture, entry point | Camera code lives here |
| `iosApp` | App module: AVCaptureSession (Swift), entry point | Camera code lives here |

Terminology: "harness" means harness engineering only (this CLAUDE.md, hooks, settings, skills). `shared` is the "common module"; `androidApp`/`iosApp` are "app modules".

Dependency direction: `androidApp`/`iosApp` -> `shared` -> `landmarkkit`. `landmarkkit` never depends on `shared` or the app modules.

## Hard rules
1. Camera code (CameraX, AVCaptureSession, permissions, preview) must never enter `landmarkkit`.
2. Model files (`*.task`) are never committed to `landmarkkit`. The app modules own test models.
3. Add dependencies only after user approval, especially to `landmarkkit`.
4. All versions live in `gradle/libs.versions.toml`. No version strings in build scripts.
5. Scope: Face and Hand boundaries are defined; only Face is implemented now. Hand stays types/interfaces only until requested.

## Verification (Windows environment)
- iOS compile, cinterop, and Xcode builds are impossible on Windows. Done = Android build + `commonMain`/`androidHostTest` pass.
- After touching iOS code, state "iOS unverified" and list the commands to run on a Mac, e.g. `./gradlew :landmarkkit:iosSimulatorArm64Test`. Never claim iOS verified without running it.
- Mac/CI availability: TBD.
- Current state: Android fully builds (`:androidApp:assembleDebug`). On Mac, iOS also builds and runs end-to-end (`xcodebuild ... -workspace iosApp/iosApp.xcworkspace -scheme iosApp`, installed and launched on simulator) as of 2026-10-06. The iOS first-run checklist below has been completed once; re-run it only if `Podfile`/cocoapods config changes.

## iOS first-run checklist (Mac only, done once — see note above)
1. `./gradlew :shared:podspec`, then in `iosApp/` run `pod install` and open `iosApp.xcworkspace`.
2. In Xcode, delete the "Run Script" build phase that calls `:shared:embedAndSignAppleFrameworkForXcode` (replaced by CocoaPods).
3. Cinterop name mismatches fixed: `MPPRunningMode`/`UIImageOrientation` are Kotlin enum classes (`EnumClass.CASE_NAME`, not a top-level import), `detectVideoFrame` (not `detectInVideoFrame`), `kotlinx.cinterop.get` must be imported explicitly for `CPointer` indexing.
4. Compose resources with CocoaPods may need the `syncComposeResourcesForIos` step if resources are used (currently none are).
5. Swift call sites must pass every Kotlin constructor/function parameter explicitly — Kotlin/Native's ObjC export does not expose default-parameter overloads, and `internal` Kotlin declarations (e.g. `newSessionId()`) aren't visible to Swift at all.
6. A C pointer typedef param (e.g. `CMSampleBufferRef`) shows up in Swift as `UnsafeMutableRawPointer`; bridge a Swift CF object with `Unmanaged.passUnretained(x).toOpaque()`.

## Commands
- Android app: `./gradlew :androidApp:assembleDebug`
- Android host tests: `./gradlew :shared:testAndroidHostTest` (core: `:landmarkkit:testAndroidHostTest`)
- iOS tests (Mac only): `./gradlew :shared:iosSimulatorArm64Test`

## Deferred
- Hooks to enforce rules 1-2 mechanically (add after rules stabilize).
- Git conventions (intentionally not set yet; do not commit or push unless asked).
