# GoogleMediaPipeTest

Respond to the user in Korean. Write code, identifiers, and this document in English.

## Purpose
Test bed for Google MediaPipe on Android and iOS (Kotlin Multiplatform + Compose Multiplatform).
The reusable part (`landmarkkit`) may later be published and consumed by other Android/iOS projects as a dependency.

## Modules
| Module | Role | Rules |
|---|---|---|
| `landmarkkit` | Publishable core: frame in, landmarks out | Strict. See `landmarkkit/CLAUDE.md` |
| `shared` | Harness: common Compose test screens (result display, overlay) | No camera code, no platform camera deps |
| `androidApp` | Harness: CameraX capture, entry point | Camera code lives here |
| `iosApp` | Harness: AVCaptureSession (Swift), entry point | Camera code lives here |

Dependency direction: `androidApp`/`iosApp` -> `shared` -> `landmarkkit`. `landmarkkit` never depends on harness modules.

## Hard rules
1. Camera code (CameraX, AVCaptureSession, permissions, preview) must never enter `landmarkkit`.
2. Model files (`*.task`) are never committed to `landmarkkit`. The harness apps own test models.
3. Add dependencies only after user approval, especially to `landmarkkit`.
4. All versions live in `gradle/libs.versions.toml`. No version strings in build scripts.
5. Scope: Face and Hand boundaries are defined; only Face is implemented now. Hand stays types/interfaces only until requested.

## Verification (Windows environment)
- iOS compile, cinterop, and Xcode builds are impossible on Windows. Done = Android build + `commonMain`/`androidHostTest` pass.
- After touching iOS code, state "iOS unverified" and list the commands to run on a Mac, e.g. `./gradlew :landmarkkit:iosSimulatorArm64Test`. Never claim iOS verified without running it.
- Mac/CI availability: TBD.

## Commands
- Android app: `./gradlew :androidApp:assembleDebug`
- Android host tests: `./gradlew :shared:testAndroidHostTest` (core: `:landmarkkit:testAndroidHostTest`)
- iOS tests (Mac only): `./gradlew :shared:iosSimulatorArm64Test`

## Deferred
- Hooks to enforce rules 1-2 mechanically (add after rules stabilize).
- Git conventions (intentionally not set yet; do not commit or push unless asked).
