# 현재 검증 상태 — 0.7.0

검증일: 2026-10-03, Asia/Seoul. versionCode 14. 배포 형태는 검증용 debug APK이며 개인 키 release 서명은 별도 단계입니다.

| 항목 | 결과 |
|---|---|
| 빌드 | assembleDebug / assembleDebugAndroidTest 통과 |
| 단위 테스트 | 73개 통과 |
| 전체 Android 테스트 | 19개 통과: 실제 시스템 알림·알림창 분류/복귀, 인식·밝기·오버레이·저장·우선순위 |
| Lint | 오류 0, 경고 25 |
| APK 검증 | debug v2 서명, ZIP·네이티브 ELF 16KB 정렬 통과 |
| 런타임 인터넷 권한 | 없음 |
| 실기기 | Galaxy SM_S948N / Android 17, 사용자 0.6.4 정상 동작 확인 |
| 최종 버전 실기기 | AUTO 0.7.0 설치·앱 실행 확인; 최적화 후 장시간 게임 메모리 검증은 별도 |

상세 변경 검증은 [밝기·오버레이 기록](BRIGHTNESS-OVERLAY.md), [다중 매크로 기록](MULTI-MACRO.md), [OCR 수정 기록](OCR-START-FIX.md)을 참고하세요. 이 문서의 `outputs/` 참조는 로컬 산출물을 뜻하며 APK·진단 로그·스크린샷·빌드 보고서는 Git에 올리지 않습니다. 새로 빌드하면 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

---

아래는 초기 0.1.0 시점의 기록입니다. 아래의 ‘미실시’, 테스트 수, APK 해시는 초기 버전에만 해당합니다.

# 초기 0.1.0 검증 기록

최초 검증일: 2026-10-03, Asia/Seoul. 아래 최초 결과는 0.1.0 기준이며 현재 버전의 변경 검증은 별도 문서를 참조합니다. 배포 형태는 사용자가 결정한 검증용 debug APK입니다. **실기기 및 실제 마비노기 모바일 호환성 검증은 미실시**입니다.

## 가능성 판단

MediaProjection으로 프레임을 수집하고 OpenCV 템플릿 매칭 또는 ML Kit 한국어·라틴 OCR로 요소를 찾은 뒤 AccessibilityService.dispatchGesture로 중심에 터치를 전달하는 구현이 공개 API 범위에서 가능합니다. 실제 게임의 캡처 보호, 접근성 창 정보 제공, 제스처 수용은 코드와 에뮬레이터만으로 확정할 수 없습니다.

공식 참고:

- [MediaProjection 사용자 동의·서비스·세션 수명](https://developer.android.com/media/grow/media-projection)
- [AccessibilityService 제스처·창 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [ML Kit 모델을 APK에 포함하는 방식](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [AGP 8.13.2 호환 환경](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Android 16 대응 Espresso 3.7.0](https://developer.android.com/jetpack/androidx/releases/test)

## 개발 환경

| 항목 | 확인 내용 |
|---|---|
| 초기 프로젝트 | 빈 폴더에서 단일 app 모듈 생성 |
| Java | Eclipse Temurin 17.0.17 arm64 |
| SDK | 정식 Android 16 SDK 36.1 (코드명 없음) |
| 컴파일·대상·최소 | compile 36.1 / target 36 / min 36 |
| 빌드 | Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.10 |
| ABI | arm64-v8a, x86_64 |
| 에뮬레이터 | Medium_Phone_API_36.1, Android 16, 페이지 크기 4,096바이트 |
| 실제 연결 단말기 | 없음 |

최종 APK: `outputs/screen-macro-debug.apk` (142,360,062바이트). SHA-256: `c61de030ee023a7fffb8a3546ce43b6fb3a64a2a476005e3f1d79119135c24bf`.

검증 증빙: `outputs/test-results.json`, `outputs/permissions.txt`, `outputs/native-alignment.json`, `outputs/zipalign.txt`, `outputs/signature-verification.txt`, `outputs/lint-results.txt`.

Gradle wrapper 배포 SHA-256을 공식 체크섬으로 고정했습니다. SDK 경로는 개발 PC의 `local.properties`에만 들어 있으며 Git 제외 대상입니다.

## 구현된 기능

- 여러 매크로 생성·수정·삭제, Room 및 앱 전용 기준 이미지 저장.
- 매크로마다 이미지 또는 문구 조건 하나, 클릭 하나, 동시 실행 하나.
- 이미지 캡처·드래그 자르기·검색 영역, 특징 부족·크기 오류 거부.
- 한국어·영어 OCR, NFC·공백 정규화, 정확/포함 일치와 영어 대소문자 선택.
- 기본 3,000ms·최소 1,000ms 클릭 간격, 0.1초 입력, 전후 대기.
- 조건 유지 시 반복 클릭, 부재·복수 후보에는 클릭 없음, 클릭 전 대기 후 재인식.
- 새 캡처 세션, 전체 화면 크기 확인, 새 요청 프레임 한 장만 처리.
- 접근성 오버레이 실행·캡처·정지, 알림 정지 및 상태·최근 1,000건 메모리 로그.
- 대상 패키지·활성 창·해상도·회전·화면 잠금·좌표·프레임 나이 검증.
- 자신의 오버레이와 게임 창을 구분하고 게임 창 바깥/오버레이 영역 제외. 다른 앱 창·키보드·시스템 화면·다중 앱 화면에서 클릭 차단.
- 클릭 없는 인식 테스트와 검출 영역·예상 중심점 표시.
- 인터넷·네트워크 상태·전체 저장소·오버레이 일반 권한 없이 동작. 백업 및 기기 간 데이터 전송 제외.

## 자동 검증

실행 명령:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
python3 scripts/verify_apk.py outputs/screen-macro-debug.apk
```

최종 결과는 아래 산출물의 보고서와 일치하도록 기록합니다.

| 검증 | 결과 |
|---|---|
| debug APK 빌드 | 통과 |
| 핵심 JVM 단위 테스트 | 14개 통과, 실패·오류·skip 0 |
| Android 에뮬레이터 테스트 | 3개 통과, 실패·오류·skip 0 |
| Android lint | 오류 0, 경고 15; 남은 경고는 고정 버전 업데이트 안내·kapt·KTX 권고 등 |
| APK 인터넷·네트워크 상태 권한 | 최종 패키지에 없음 |
| 서명 검증 | debug APK v2 서명 검증 통과 |
| native ELF LOAD 정렬 | 포함된 양 ABI 라이브러리 모두 16KB 이상 |
| APK zipalign 16KB | 통과 |

JVM 테스트는 가짜 단조 증가 시계와 제스처 실행기로 다음을 확인했습니다: 요소 유지 반복, 기본 3초와 최소 1초 각각 50회 이상 전달 간격, 제스처 취소 후 간격, 요소 부재·복수 후보, 재출현 시 간격, 전후 대기, 느린 제스처 후 다음 시각, 정지 경계·중복 실행 차단, 무효 세션, 설정 입력·저장값 검증, 좌표 변환, 한국어 NFC·영어 대소문자·포함 일치, OCR 중복 위치 병합.

에뮬레이터 테스트는 실제 OpenCV 라이브러리로 합성 화면의 유일 이미지 위치, 중복 후보, 오버레이 제외, 단색 이미지 거부를 확인했습니다. 한국어·영어·혼합 문구를 합성 화면에서 APK 내장 ML Kit 모델로 읽었습니다. Wi-Fi와 모바일 데이터가 꺼진 상태 및 앱 인터넷 권한 없는 상태에서 수행했습니다. 이 결과를 게임 화면 정확도로 일반화하지 않습니다.

UI 테스트는 앱 실행, 권한 부재 확인, 0.9초 입력 거부, 3.0초 저장, Activity 재생성 후 Room 설정 복원, 편집·삭제를 확인했습니다. 스크린샷은 `outputs/screenshots/`에 있습니다.

패키지 검사에서 ML Kit의 간접 의존성이 INTERNET·ACCESS_NETWORK_STATE를 추가한 것을 확인하여 Manifest 병합에서 명시적으로 제거했습니다. 처음 발견된 Espresso의 Android 16 InputManager 호환성 문제는 공식 수정이 들어간 3.7.0으로 해결했습니다.

## 검증의 한계와 추가 제한

- 캡처 동의·접근성 활성화·실제 dispatchGesture 터치 흐름, 화면 잠금·회전·권한 해제 이후 실제 OS 동작은 실기기에서 아직 확인하지 않았습니다. 관련 코드는 구현되어 있으나 실기기 검증 완료로 표시하지 않습니다.
- 등록 화면과 이미지 실행 화면의 크기·방향이 같아야 합니다. 확대 기능이나 다중 앱 창이 있으면 실행을 거부합니다.
- 인식 결과가 수집 시점에서 1초 이상 오래되면 안전을 위해 실행을 중단합니다. 느린 기기나 OCR 최초 초기화에서 중단될 수 있습니다. 성능 튜닝은 기기 측정 후 진행합니다.
- 오버레이 위치는 상단 오른쪽 고정입니다. 오버레이 영역은 인식·클릭에서 제외합니다.
- 64비트 ABI만 포함했습니다. Android 16이라도 32비트 전용 기기는 지원하지 않습니다.
- 16KB ELF/ZIP 정렬 검사는 구조적 검사입니다. 16KB 페이지 크기를 사용하는 실제 OS에서의 런타임 검증과 같지 않습니다.
- 시간 입력은 24시간을 상한으로 두어 넘침·비정상적으로 큰 대기를 거부합니다.
- 로그는 메모리에만 유지하며 프로세스 종료 시 삭제합니다. 매크로 설정·기준 이미지는 유지합니다.
- release 개인 서명 키 및 release APK는 현재 사용자 결정에 따라 보류되었습니다.

## 실기기 수용 검증표 (모두 미실시)

다음 항목은 Android 16 이상 사용자 단말기를 연결한 후 수행합니다.

| 항목 | 기록할 결과 |
|---|---|
| 실제 기기·OS·화면 방향·ABI·페이지 크기 | 모델/빌드/크기/방향/getconf PAGESIZE |
| APK 설치·제한된 설정·접근성·알림·캡처 동의 | 승인/거부 흐름, 실제 정지 경로 |
| 오프라인 이미지·한국어·영어·혼합 OCR | 성공/실패/처리 시간 |
| 테스트 화면 유일 이미지·문구 각각 20회 | 위치·터치 성공/실패 |
| 비슷한 이미지·다른 문구·복수 후보·이동 요소 | 의도하지 않은 클릭 0건 여부 |
| 실제 전달 간격 기본 3초/최소 1초 각 50회 이상 | 설정 미만 전달 간격 0건 여부 |
| 정지·잠금·접근성 해제·캡처 종료·회전 | 이후 새 터치 없음 |
| 캡처 종료·재시작 3회 | 토큰 재사용 오류/자원 누수 없음 |
| 30분 감시 | 크래시·ANR·프레임 적체·메모리·발열 |
| 실제 마비노기 모바일 대표 흐름 10회 | 캡처/창 확인/인식/터치, 다른 앱 전환 차단 |

캡처 보호, 창 정보 미제공, 터치 거부 등 미지원 조건을 이 문서에 추가하고 원인을 구분해야 합니다.

## 0.6.5 메모리 최적화

미전달 프레임·IO 디코딩 비트맵의 취소 시 회수, OCR 시작 실패 시 입력 수명, 템플릿 Mat 중복 변환, 반복 로그·진단 문자열 할당을 개선했습니다. Full HD 이미지 40회 반복에서 네이티브 할당 약 20.3MiB 유지. [세부 검토와 한계](MEMORY-REVIEW.md)를 참고하세요. 실기기에서 0.6.4 정상 동작을 사용자 확인했으며 0.6.5 설치·앱 실행을 확인했습니다. 장시간 실제 게임 메모리 검증은 별도입니다.

## 0.7.0 AUTO 브랜딩 및 권한 아이콘

사용자가 선택한 짙은 네이비 배경·민트색 A/재생 모티프로 adaptive 아이콘과 테마용 monochrome 아이콘을 적용했습니다. 앱 라벨·상단 제목·오버레이·실행 알림은 AUTO로 통일했으며 패키지는 유지합니다. 알림의 small icon은 별도 단색 A/재생 벡터입니다.

접근성·알림·밝기·도움말을 한 줄 48dp 터치 영역 아이콘으로 표시합니다. 허용된 권한은 민트색, 미허용은 회색+빗금이고 도움말은 일반 표시입니다. 길게 누르면 tooltip, TalkBack에는 항목과 상태를 제공합니다. 기존 설정 동작 및 실행 중 잠금은 유지합니다. 알림 권한 응답과 앱 복귀 때 Compose 상태를 갱신합니다.

단위 테스트 73개, 전체 Android 테스트 19개 통과. 추가 UI 검증은 동일 행 배치, 권한 상태 갱신, 네 버튼 동작, 허용된 알림의 재요청 제한, 실제 앱 라벨 및 adaptive 아이콘 렌더링입니다. 에뮬레이터의 홈 화면·허용/미허용 아이콘 줄·런처 아이콘을 렌더링해 직접 확인했습니다. 첫 실행에서는 테스트 스크린샷의 외부 저장 경로를 만들 수 없어 저장 단계가 실패했으며 앱 내부 저장 경로로 변경 후 전체 테스트를 통과했습니다. Lint 오류 0·경고 25. APK 서명과 ZIP/ELF 16KB 정렬 통과.

증빙: 로컬 outputs/build-0.7.0.txt, emulator-regression-0.7.0.txt, auto-home-0.7.0.png, auto-launcher-icon-0.7.0.png, auto-permissions-granted-0.7.0.png. 실제 기기의 화면·게임 픽셀은 수집하지 않았습니다. AUTO 0.7.0(versionCode 14)은 실기기에 데이터 유지 덮어 설치 후 버전·앱 실행을 확인했습니다.
