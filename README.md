This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

## iOS 빠른 안내 (Mac 전용)

> **상태: iOS unverified.** iOS용 Kotlin 코드(cinterop), Swift 카메라, CocoaPods 연결은 Windows에서 작성했고

**요구 사항(타겟은 조절이 필요할 수 있음):** macOS, Xcode, CocoaPods, JDK 17 이상, 배포 대상 iOS 17.0 이상.
MediaPipe Tasks Vision `0.10.21`(`gradle/libs.versions.toml`에 고정)을 사용합니다.

### 빌드가 실패할 때 먼저 볼 곳
- cinterop 이름: `MPPFaceLandmarker`, `MPPImage` (`landmarkkit/src/iosMain/.../Detectors.ios.kt`)
- Swift에서 부르는 Kotlin 파사드 이름: `Detectors_iosKt`, `FrameHelpers_iosKt`. `iosMain`의 Kotlin 파일 이름은
  `landmarkkit`과 `shared` 전체에서 겹치면 안 됩니다(하나의 framework 안에서 Obj-C 파사드 이름이 되기 때문입니다).
- CocoaPods 환경에서 Compose 리소스를 추가하면 `syncComposeResourcesForIos` 단계가 필요할 수 있습니다(현재 리소스 없음).

### 주요 파일 위치
| 대상 | 위치 |
|---|---|
| 카메라 캡처(AVCaptureSession), 모델 경로 | `iosApp/iosApp/CameraController.swift` |
| 진입점, Compose 호스트 | `iosApp/iosApp/iOSApp.swift`, `ContentView.swift` |
| 테스트 모델(`landmarkkit`에는 커밋하지 않음) | `iosApp/iosApp/face_landmarker.task` |
| 감지 API(프레임 입력, 랜드마크 출력) | `landmarkkit` (iOS framework 이름 `LandmarkKit`) |
| 공통 Compose 화면과 감정 추정 로직 | `shared/src/commonMain` (`App.kt`, `emotion/ExpressionTracker.kt`) |

### 네이티브 iOS 앱으로 새로 만드는 경우
재사용 대상은 `landmarkkit`뿐입니다. `createFaceDetector` 팩토리로 감지기를 만들고, `Frame` 변환 도우미로 바꾼
`CMSampleBuffer`/`UIImage`를 전달한 뒤 `Face.landmarks`, `Face.blendshapes`, `Face.transformationMatrix`를 읽습니다
(`FaceDetectorConfig`에서 출력을 켜야 합니다). 모델 파일은 앱이 준비하며 라이브러리에는 포함되지 않습니다.
감정 추정 로직은 Compose에 의존하지 않는 순수 Kotlin이므로 `ExpressionTracker.kt`를 참고해 Swift로 옮길 수 있고,
임계값은 `ExpressionConfig`에 모여 있습니다.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
