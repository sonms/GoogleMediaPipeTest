# .docs 안내

| 문서 | 내용 |
|---|---|
| [decisions.md](decisions.md) | 확정한 결정과 그 이유, 이론 추정, 구현 상태, 미결정 항목, 알려진 위험 |
| [architecture.md](architecture.md) | 모듈 구성, 데이터 흐름, 산출 파일 형식, 실행 방법, iOS 체크리스트, 버전 |
| [measurements.md](measurements.md) | Android 실기기 측정 결과(처리 성능, fps별 결과, 전송 규모 예측, 표정 구분 시험, 자원 사용), 한계와 확인하지 못한 항목 |

규칙 자체(Claude Code가 따를 지침)는 각 모듈의 `CLAUDE.md`에 있습니다: 루트, `landmarkkit/`, `shared/`, `androidApp/`, `iosApp/`.

용어: "하네스"는 하네스 엔지니어링(CLAUDE.md, 훅, 설정, 스킬)만 가리키고, `shared`는 "공통 모듈", `androidApp`과 `iosApp`은 "앱 모듈"이라고 부릅니다.
