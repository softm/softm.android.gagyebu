# softm.android.gagyebu

기존 gagyebu 웹앱을 로컬 WebView 자산으로 포함하고, Android 알림 및 선택적 SMS 수신을 거래 후보로 연결하는 하이브리드 가계부입니다.

## 핵심 흐름

1. 사용자가 알림 접근 또는 이번 달 SMS 분석을 명시적으로 허용합니다.
2. 이번 달 1일 이후의 기존 SMS와 새 알림/SMS에서 금액과 금융 키워드가 함께 있는 내용만 선별합니다.
3. 원문과 파싱 결과를 Android Keystore AES-GCM 키로 암호화하여 앱 전용 SQLite에 보관합니다.
4. ‘가계부에 기록할까요?’ 알림의 **확인 후 기록**을 누르면 거래 편집 화면이 날짜·금액·사용처까지 채워져 열립니다.
5. 거래는 자동 확정하지 않고 ‘확인 필요’ 상태로 저장되어 사용자가 자산·분류·거래 유형을 검토합니다.
6. 화면 하단에는 UMP 동의 상태를 반영한 AdMob 네이티브 광고가 표시되며, 구성된 경우 AdFit 배너가 대체 광고로 동작합니다.
7. 영수증 사진은 ML Kit의 기기 내 한국어 OCR로 날짜·금액·상호를 추출해 입력 폼을 채우며 자동 저장하지 않습니다.

알림과 SMS 양쪽에서 같은 거래가 들어오는 경우 캡처 식별자와 거래 시간·금액·사용처를 이용해 중복 등록을 줄입니다.

## 프로젝트 구성

- app/src/main/java/softm/android/gagyebu/
  - FinancialNotificationListener: 은행·카드 앱 알림 수신
  - SmsReceiver, SmsHistoryImporter: 새 금융 SMS 수신 및 이번 달 기존 SMS 조회
  - FinancialMessageParser: 승인·취소·입금·출금 파싱
  - ReceiptTextParser: 영수증 OCR 문자열에서 날짜·금액·상호 추출
  - CaptureStore, LocalCipher: 암호화 로컬 보관과 상태 관리
  - CaptureNotification: 확인 후 기록/무시 알림
  - MainActivity: WebViewAssetLoader와 허용 출처 전용 WebMessage 브리지
- app/src/main/assets/web/: gagyebu 웹앱 스냅샷
- scripts/sync-web-assets.sh: 인접한 gagyebu 저장소에서 웹 자산 갱신

자세한 데이터 흐름은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), 개인정보 처리는 [docs/PRIVACY.md](docs/PRIVACY.md), 광고 구성은 [docs/ADS.md](docs/ADS.md), Play 배포 준비는 [docs/PLAY_POLICY.md](docs/PLAY_POLICY.md)를 참고하세요.

## 빌드

요구사항:

- JDK 17
- Android SDK 36 / Build Tools 36

    ./gradlew testDebugUnitTest lintDebug assembleDebug

디버그 APK:

    app/build/outputs/apk/debug/app-debug.apk

연결된 기기에 설치:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

릴리스 AAB는 별도의 `signing.properties`와 업로드 키를 지정해 만듭니다. 서명 파일과 비밀번호는 저장소에 포함하지 않습니다.

    ./gradlew -PsigningProperties=/절대경로/signing.properties bundleRelease

출력:

    app/build/outputs/bundle/release/app-release.aab

## 권한

- BIND_NOTIFICATION_LISTENER_SERVICE: 사용자가 시스템 설정에서 직접 활성화하는 알림 접근
- READ_SMS: 동의 후 이번 달 1일 이후의 기존 금융 문자만 분석
- RECEIVE_SMS: 동의 이후 새 금융 문자 수신
- POST_NOTIFICATIONS: 감지된 거래의 확인 알림 표시
- INTERNET: 한눈 공통 로그인·계정 관리, 선택적으로 구성되는 기존 Firebase 동기화
- AD_ID: AdMob/AdFit 광고 게재와 측정

연락처와 통화기록 권한은 요청하지 않습니다. SMS 원문은 단말에서만 분석·암호화 보관하며 광고 SDK나 서버로 전달하지 않습니다.

## 웹 자산 갱신

    ./scripts/sync-web-assets.sh ../gagyebu

스크립트는 Android 전용 브리지가 들어 있는 app.js와 styles.css를 덮어쓰지 않습니다. 두 파일의 upstream 변경은 수동 병합한 뒤 전체 검증을 다시 실행해야 합니다.

현재 웹 스냅샷의 출처는 [docs/WEB_SNAPSHOT.md](docs/WEB_SNAPSHOT.md)에 기록합니다.

## 저장소

예정 GitHub 저장소 이름: softm/softm.android.gagyebu



## 한눈 공통 계정

상단 계정 아이콘에서 기존 한눈 계정의 로그인·회원가입·계정 관리로 진입합니다. 인증은 외부 브라우저에서 진행하고 앱으로 복귀하며, 계정 장부는 PC와 동기화합니다. 기존 기기 장부는 합치기 전에 확인하며, 수집 원문은 보내지 않습니다. 구성·검증 및 운영 반영 상태는 [공통 계정 안내](docs/COMMON_ACCOUNT.md)를 확인하세요.
