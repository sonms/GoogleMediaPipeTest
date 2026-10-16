# GoogleMediaPipeTest 구조와 사용 안내

작성 기준일: 2026-10-02. 의사결정의 이유는 `decisions.md`를 참고하고, 이 문서는 "지금 코드가 어떻게 생겼고 어떻게 쓰는가"를 설명합니다.

## 1. 모듈 구성

```
GoogleMediaPipeTest
├─ landmarkkit/   코어. 배포 가능성이 있는 라이브러리 (프레임 입력, 랜드마크 출력)
├─ shared/        공통 모듈. 공통 Compose 화면, 기록과 측정 로직
├─ androidApp/    앱 모듈. CameraX, Android 진입점
├─ iosApp/        앱 모듈. AVCaptureSession(Swift), iOS 진입점
└─ .docs/         의사결정과 구조 문서
```

의존 방향은 `androidApp`/`iosApp` → `shared` → `landmarkkit`이며, 코어는 공통 모듈과 앱 모듈을 알지 못합니다.

### 코어 (`landmarkkit`)

| 파일 | 내용 |
|---|---|
| `commonMain/.../Frame.kt` | 공통 `Frame` 타입(너비, 높이, 회전, 타임스탬프, 플랫폼 네이티브 데이터) |
| `commonMain/.../Landmarks.kt` | `Landmark`, `Blendshape`, `Face`, `FaceDetectionResult`, `HandDetectionResult`(경계만) |
| `commonMain/.../Detectors.kt` | `FaceDetectorConfig`(MediaPipe 옵션 전부), `FaceDetector`/`HandDetector` 인터페이스, `PlatformContext`, `createFaceDetector` |
| `androidMain/.../Detectors.android.kt` | MediaPipe `FaceLandmarker`(VIDEO 모드) 구현(`internal`), `Bitmap.toFrame` |
| `iosMain/.../Detectors.ios.kt` | cinterop으로 `MPPFaceLandmarker` 호출(`internal`), `IosPlatformContext` |
| `iosMain/.../FrameHelpers.ios.kt` | `CMSampleBufferRef.toFrame` |

`FaceDetectorConfig`는 얼굴 수, 감지, 존재, 추적 신뢰도 임계값, 블렌드셰이프 출력, 변환 행렬 출력을 모두 노출하고 기본값은 MediaPipe 기본값입니다. 손(Hand)은 인터페이스와 결과 타입만 있고 구현은 없습니다.

### 공통 모듈 (`shared`)

| 파일 | 내용 |
|---|---|
| `App.kt` | 테스트 화면. 카메라 영역(3:4), 오버레이, 요약, 기록 시작과 종료 버튼, 비교 모드 버튼, 상태 문구 |
| `FaceOverlay.kt` | 랜드마크를 점으로 그리는 오버레이와 요약 문구 |
| `record/FrameEncoders.kt` | JSON, float32 바이너리, 양자화 바이너리 직렬화와 양자화 통계 |
| `record/PayloadMeter.kt` | 형식 7종의 크기와 CPU 시간 측정, 네트워크 프로파일별 업로드 시간 예측 |
| `record/QualityMeter.kt` | 감지 성공률, 블렌드셰이프 변동폭, 떨림, 지연 |
| `record/SessionRecorder.kt` | 단일 세션 기록(JSONL + 보고서) |
| `record/ComparisonRecorder.kt` | 병렬 감지기 비교 기록(레인별 기록 + 비교 파일) |
| `record/SessionJson.kt` | 메타데이터 줄과 보고서 JSON 작성, `EnvironmentInfo` |
| `iosMain/.../OverlayController.kt` | iOS용 투명 Compose 오버레이 컨트롤러 |

### 앱 모듈

- `androidApp/.../MainActivity.kt`: CameraX 프리뷰와 분석, 권한 요청, 감지기와 레코더 연결. 감지기와 레코더는 분석 스레드 한 곳에서만 접근합니다.
- `iosApp/iosApp/CameraController.swift`, `ContentView.swift`: AVCaptureSession 캡처, 감지, SwiftUI 화면. Compose 오버레이를 네이티브 프리뷰 위에 올립니다.
- 두 앱 모듈 모두 테스트용 `face_landmarker.task` 모델을 직접 보유합니다(Android는 assets, iOS는 앱 폴더).

## 2. 데이터 흐름

```
카메라(30fps) → Frame 변환 → 감지기(들) → FaceDetectionResult
                                          ├→ 오버레이 표시
                                          └→ 레코더 → 프레임 줄 기록
                                                   → 측정기(크기, 품질)
                                    종료 시 → 보고서 파일 기록
```

비교 모드에서는 감지기 3개가 같은 `Frame`을 각자의 간격으로 받습니다.

## 3. 산출 파일

저장 위치는 Android가 `getExternalFilesDir`(`Android/data/com.sonms.googlemediapipetest/files`), iOS가 앱의 Documents 폴더입니다.

### 단일 모드

| 파일 | 내용 |
|---|---|
| `<시작시각>_<세션ID>.jsonl` | 첫 줄은 `{"meta":{...}}`, 이후는 프레임당 한 줄 `{"t":<ms>,"faces":[{"lm":[x,y,z,...],"bs":[...],"m":[...]}]}` |
| `<시작시각>_<세션ID>.report.json` | 형식별 크기와 예측, 양자화 통계, 품질 지표 |

### 비교 모드 (레인마다 위 두 파일 + 비교 파일)

| 파일 | 내용 |
|---|---|
| `..._<세션ID>.10fps.jsonl`, `.8fps.jsonl`, `.4fps.jsonl`, `.2fps.jsonl` (`CompareFps` 목록에 따름) | 레인별 기록. 메타데이터에 `compareMode: true`와 레인의 `targetFps` |
| `..._<세션ID>.10fps.report.json` 등 | 레인별 보고서 |
| `..._<세션ID>.compare.json` | 레인별 처리 프레임 수, 파일 경로, 기준 레인과의 짝 비교(`vsBaseline`) |

### 메타데이터 항목 (`meta`)

세션 ID, 시작 시각(epoch ms), 플랫폼, OS 버전, 기기 모델명, 앱 버전, MediaPipe 버전, 카메라 방향, 모델 파일명과 크기, `FaceDetectorConfig` 전체, 목표 fps, JSON 소수 자릿수, z 양자화 범위, 정지 구간 임계값, 최대 길이, 비교 모드 여부. 기기 고유 식별자, 광고 ID, 위치, 사용자 정보는 기록하지 않습니다.

### 보고서 항목 (`report.json`)

- `formats`: 형식 7종(JSON 6자리, JSON 4자리, 각각 gzip, float32, 양자화, 양자화 + gzip)별 총 바이트, 프레임당 바이트, 분당 MB, 시간당 GB, 프레임당 인코딩 ms, 네트워크 프로파일별 "1분 분량 업로드 예상 초".
- `assumptions`: 프로토콜 오버헤드(10%)와 네트워크 프로파일(열악 1Mbps/RTT 300ms, LTE 5Mbps/80ms, Wi-Fi 20Mbps/20ms).
- `quantization`: z 관측 최솟값과 최댓값, 범위(±0.5)를 벗어나 잘린 횟수.
- `quality`: 감지 성공률, 평균과 p95 지연, 떨림(정지 구간 쌍의 수, 프레임 간 변위 중앙값, 초당 변위 중앙값. 정지 구간이 없으면 `null`), 블렌드셰이프별 최솟값, 최댓값, 평균, 표준편차와 이름.

### 바이너리 형식 (측정용, 리틀 엔디언)

`int64 타임스탬프, uint8 얼굴 수`, 얼굴마다 `uint8 플래그(비트0 블렌드셰이프, 비트1 행렬), uint16 랜드마크 수, 랜드마크, [uint8 개수 + 점수], [float32 x 16]`.
- float32 변형: 랜드마크와 점수가 float32.
- 양자화 변형: x, y는 `uint16`(0~1), z는 `int16`(±0.5로 자름), 점수는 `uint16`, 행렬은 float32.

## 4. 사용 방법

### Android 실행과 기록

1. `./gradlew :androidApp:assembleDebug`로 빌드하고 기기에 설치합니다.
2. 앱을 열고 카메라 권한을 허용합니다.
3. 필요하면 "비교 모드" 버튼을 켭니다(기록 중에는 바꿀 수 없음).
4. "기록 시작"을 누르고 원하는 동작을 합니다. 10분이 지나면 자동으로 종료되며, 직접 "기록 종료"를 눌러도 됩니다. 종료되면 화면에 "종료되었습니다"가 표시됩니다.
5. 파일을 꺼냅니다: `adb pull /sdcard/Android/data/com.sonms.googlemediapipetest/files/ ./sessions`

### 테스트 실행

- Android 호스트 테스트: `./gradlew :shared:testAndroidHostTest` (현재 14개, 모두 통과. 합성 데이터 기준).
- iOS 테스트(Mac 전용): `./gradlew :shared:iosSimulatorArm64Test`

### iOS 첫 실행 체크리스트 (Mac 전용, 아직 한 번도 컴파일하지 않았음)

1. `./gradlew :shared:podspec`을 실행한 뒤 `iosApp/`에서 `pod install`을 실행하고 `iosApp.xcworkspace`를 엽니다.
2. Xcode에서 `:shared:embedAndSignAppleFrameworkForXcode`를 호출하는 "Run Script" 빌드 단계를 삭제합니다(CocoaPods가 대신함).
3. 컴파일 오류를 고칩니다. 가능성이 높은 곳: cinterop 이름(`MPPFaceLandmarker`, `MPPImage`, `MPPTransformMatrix` 등), Swift에서 보이는 이름(`Detectors_iosKt`, `FrameHelpers_iosKt`, `ComparisonRecorderKt.compareFps`, `SessionRecorderKt.newSessionId()`), Kotlin 컬렉션과 Boolean의 브리징.
4. 모델 파일(`iosApp/iosApp/face_landmarker.task`)이 앱 번들에 포함되는지 확인합니다(폴더 동기화 그룹이므로 자동 포함 예정).
5. Files 앱이나 Finder에서 앱의 Documents 폴더를 열어 기록 파일을 꺼냅니다(`Info.plist`에 파일 공유 항목 설정됨).

## 5. 버전과 의존성

| 항목 | 값 | 비고 |
|---|---|---|
| Kotlin | 2.4.20 | |
| AGP | 9.1.1 | |
| MediaPipe | 0.10.21 | Android와 iOS 동일 고정. iOS 포드 0.10.33 이상은 링크 문제 보고 |
| CameraX | 1.5.0 | 앱 모듈에만 |
| Okio | 3.18.2 | 공통 모듈에만(gzip, 파일 쓰기) |
| Kotlin CocoaPods 플러그인 | Kotlin과 동일 | 유지보수 모드. 장기적으로 SPM 전환 검토 |

버전은 `gradle/libs.versions.toml`에서만 관리합니다. MediaPipe 버전 문자열은 Android 빌드 설정을 통해 메타데이터에 들어가며, iOS는 `CameraController.swift`의 상수와 수동으로 일치시켜야 합니다.

## 6. 현재 알려진 상태

- Android: 코어, 공통 모듈, 앱 모듈이 모두 빌드되고 단위 테스트가 통과합니다. 실기기에서 감지와 기록이 실제로 동작하는지는 아직 확인하지 않았습니다.
- iOS: 코드는 모두 작성했지만 컴파일한 적이 없습니다.
- 실측값은 아직 없습니다. 이 문서와 `decisions.md`의 용량 수치는 이론 추정입니다.
- 첫 실기기 기록 후 확인할 가정: 변환 행렬 배치, z 범위(±0.5)의 적절성, 정지 구간 임계값, 레코더의 이중 인코딩 부하, 비교 모드에서의 프레임 누락.
