# 0.2.0 — 특정 패키지 제한 제거

검증일: 2026-10-03, Asia/Seoul. 사용자 요청에 따라 특정 게임·프로세스로 실행을 제한하던 조건을 제거했습니다.

## 원인

연결된 단말기에 설치된 마비노기 모바일은 `com.nexon.devcat.mmgalaxy`입니다. 기존 0.1.0은 Google Play 배포판 `com.nexon.devcat.mm`만 설치 검사와 접근성 창 검사에서 허용했기 때문에, 실행 중인 Galaxy Store 배포판을 찾지 못했습니다. 문제가 된 팝업은 활성 창 검사가 아니라 캡처 요청 전 설치·활성화 확인에서 발생했습니다.

사용자는 특정 프로세스 제한 자체를 제거하도록 결정했습니다. 배포판 패키지를 추가하는 대신 일반 앱 화면을 실행 대상으로 사용할 수 있도록 수정했습니다.

## 변경

- 특정 패키지 설치 확인, 게임 실행 버튼, Manifest의 게임 패키지 queries, 고정 패키지 허용 조건을 제거했습니다.
- 매크로에는 앱 패키지 조건을 저장하거나 적용하지 않습니다.
- 기존 Room version-1 DB의 `targetPackage` 열은 호환을 위해 유지하되 모델 변환·유효성 검사·실행에서 읽지 않습니다. DB 삭제나 재생성을 사용하지 않습니다.
- 사용자가 대상 앱으로 이동한 뒤 오버레이의 ‘현재 앱에서 실행’ 또는 ‘현재 앱에서 인식 테스트’를 누르면 해당 활성 앱·창에 현재 세션을 연결합니다.
- 다른 앱·창으로 전환하면 정지합니다. 인식 당시 창 정보와 클릭 직전 창 정보도 비교합니다.
- 자신의 앱, 시스템 설정·권한 화면, 키보드, 다른 앱의 겹친 창, 오버레이 영역에 대한 차단과 좌표·화면 잠금·회전·캡처 종료 검증은 유지합니다.
- 앱과 안내 문구를 특정 게임에 의존하지 않도록 변경했습니다.
- 버전: versionName 0.2.0 / versionCode 2.

사용 흐름: 매크로 ‘시작’ 또는 ‘인식 테스트’ → 캡처 동의 → 대상 앱으로 직접 이동 → 오버레이 실행/테스트 버튼. 기준 이미지 등록도 대상 앱 화면에서 오버레이 ‘기준 이미지 캡처’로 진행합니다.

## 검증

| 항목 | 결과 |
|---|---|
| APK 빌드 | 통과 |
| JVM 단위 테스트 | 17개 통과 |
| lint | 오류 0, 권고 경고 15 |
| Android 16 에뮬레이터 | UI·이미지·OCR·DB 호환성 테스트 4개 통과 |
| 연결 단말기 | SM_S948N, Android 17 / API 37 |
| 실기기 업데이트 | 같은 debug 키의 0.2.0을 `adb install -r`로 설치 성공; 앱·설정 삭제 없음 |
| 실기기 비파괴 호환성 테스트 | 격리된 메모리 DB에서 테스트 1개 통과 |
| 실기기 앱 시작 | MainActivity 정상 시작 확인; 확인한 앱 로그에 Room 스키마 오류·크래시 없음 |
| 권한 | INTERNET·ACCESS_NETWORK_STATE·QUERY_ALL_PACKAGES 없음 |
| APK 서명 및 16KB 정렬 | 서명 검증, ZIP 정렬, 양 ABI ELF LOAD 정렬 통과 |

추가 단위 테스트는 Play판·Galaxy판·임의 일반 앱 허용, 자신의 앱·시스템 권한 화면 차단, 세션의 앱·창 전환 정지를 확인했습니다. 실기기 호환성 테스트는 사용자의 실제 매크로 DB와 권한을 수정하지 않고 별도의 메모리 DB에서 기존 패키지 열이 어떤 값이든 매크로를 그대로 읽고 검증할 수 있음을 확인했습니다. 테스트용 APK는 실기기에서 제거했고 사용자용 앱은 유지했습니다.

실기기의 화면 캡처 동의·인식·실제 터치와 해당 게임 호환성 전체를 검증한 결과는 아닙니다. 그 검증은 사용자가 캡처 세션을 승인한 뒤 별도로 수행해야 합니다. 이번 실기기 결과를 Android 17 전체 호환성 보장으로 일반화하지 않습니다.

## 산출물

- `outputs/screen-macro-debug.apk`: 141,660,342바이트.
- APK SHA-256: `0e19dca8f54df9d17760683898efda97889f09642d35a5c41ae103136f8ed4a6`.
- 소스: `outputs/screen-macro-source.zip`.
- 테스트 증빙: `outputs/test-results.json`, `outputs/emulator-any-app-tests.txt`, `outputs/device-package-restriction-test.txt`.
- 패키지 검사: `outputs/permissions.txt`, `outputs/signature-verification.txt`, `outputs/native-alignment.json`, `outputs/zipalign.txt`.
- UI 검증 화면: `outputs/screenshots/`.

기존 0.1.0 전체 검증 기록은 [VERIFICATION.md](VERIFICATION.md)에 보존했습니다.
