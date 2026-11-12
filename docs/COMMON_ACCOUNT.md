# 가계부 Android · Cloudflare 공통 계정

2026-11-13 코드 전환 및 1.0.6 배포 준비 기준. 런타임 Firebase Auth·Cloud Functions·Firestore SDK 호출을 제거하고 Cloudflare Worker/D1을 사용하도록 변경했습니다. **GitHub 소스 전환과 실제 사용자 데이터 이관은 별도 단계**입니다.

## 현재 코드 경로

- 사용자: 앱 내장 WebView의 공통 계정 메뉴를 누름
- Android `SuiteAuthManager`: 시스템 브라우저에서 `https://softm-hannun-account.softm.workers.dev/connect` 열기
- Worker: Google·카카오·네이버 제공자 지원 상태에 따라 웹 로그인, 약관 동의, 한 번만 유효한 PKCE 인증코드 발급
- 인증 복귀: 고정된 `https://softm.github.io/account/android/gagyebu/` App Link의 URL fragment (대체 `softm.android.gagyebu://auth/callback`)
- Android: 등록된 복귀 주소·요청 ID·PKCE 검증값·5분 제한 확인 후 `POST /api/connect/exchange` 요청
- 세션: 사용자 UID와 사이트 범위의 1시간짜리 Cloudflare Bearer Token, Android Keystore AES-GCM 암호화 저장, JS WebView에 토큰 미전달
- 장부: WebMessage 브리지의 `ledgerSync` → `POST /api/apps/gagyebu/ledger` → 전용 `gagyebu-api` Worker / `gagyebu-db` 사용자별 AES-GCM 암호문
- 로컬: IndexedDB 장부와 SQLite 금융 원문은 기존대로 유지. 문자·알림 원문, 영수증 원본, 확인대기 자동 후보는 서버 전송 제외
- 세션 만료 후에는 같은 Cloudflare 웹 로그인을 다시 해야 합니다. 앱 임의로 세션을 재발급하지 않습니다.

## 이전 데이터 보존

- 이전 Firestore `suite-v1/users/{기존UID}/sites/gagyebu/privateLedger` 서버 자료는 **삭제하지 않았습니다**.
- 앱 로컬 `suite-ledger:{기존UID}` 및 `user:{기존UID}` 저장 내용도 삭제하지 않았습니다.
- Cloudflare 계정의 최초 진입 시 **기존 Firebase UID의 로컬 장부가 한 개 발견되면 사용자 확인 후 복사**합니다. 사용자 확인 없이 합치지 않습니다. 여러 레거시 장부가 있을 경우 자동으로 선택하지 않습니다.
- **기기에 없고 Firestore 서버에만 남아 있는 장부**는 자동으로 이전되지 않습니다. 이전 Firebase 신원과 Cloudflare 신원을 검증해 대응시키는 별도 서버 이전 절차가 필요합니다.
- 기존 서버의 원격 암호화 장부 키를 삭제하거나 변경하면 복호화가 불가능해질 수 있으므로 Firebase 프로젝트, Secret Manager 및 원본 자료를 보존해야 합니다.
- 앱 데이터 삭제/재설치 전에 전체 JSON 백업을 확보해야 합니다. 앱 데이터 삭제 시 IndexedDB의 레거시 캐시도 사라집니다.

## 배포·검증 조건

1. 공통 계정 Worker `/health`와 가계부 전용 `gagyebu-api` Worker `/health`를 각각 확인하고, 실제 인증 계정으로 저장/조회 왕복 검사 (공통 Worker의 `features.gagyebuLedger`는 분리 후 false가 정상)
2. `softm.github.io/account/android/gagyebu/` 및 `.well-known/assetlinks.json` 배포 확인; Play 서명 인증서 SHA-256과 연결 확인
3. GitHub Android CI `testDebugUnitTest lintDebug assembleDebug` 통과
4. 실제 Android 기기에서 웹 로그인 → 앱 복귀 → Cloudflare UID 표시 확인
5. PC·Android 같은 UID에서 테스트 거래 저장 → 새로고침·재시작 후 서로 확인
6. 기존 Firestore 장부는 백업 및 이관 검증 전 삭제 금지

현재 코드·CI 검증만으로 실제 사용자 로그인의 성공이나 데이터 완전 이관을 보장하지 않습니다.

## 보안

- Android 브리지는 온라인 `https://softm.github.io/gagyebu/` 경로와 오프라인 `https://appassets.androidplatform.net/assets/web/` 경로만 허용합니다. 외부 사이트에서 네이티브 메시지를 실행할 수 없도록 출처와 경로를 모두 검증합니다.
- 토큰·PKCE 검증값을 GitHub, JS 브리지, 로그, URL에 저장하지 않습니다. 복귀 URL fragment에는 짧게 유효한 1회용 인증 코드만 전달합니다.
- D1 암호화는 서버에서 복호화 가능한 AES-GCM이며 단말 종단간 암호화(E2EE)는 아닙니다.
- 회원탈퇴/원격 보관소 삭제는 Firebase 기존 기록 이관과 삭제 설계가 끝날 때까지 비활성입니다.

## v1.0.5 → v1.0.6 앱 업데이트 주의

- **GitHub Release `v1.0.5`의 설치 APK는 구형 Firebase 로그인**을 실행하며, 외부 브라우저에 `이 계정으로 가계부 연결` 버튼을 띄웁니다. 이 화면은 구형 `suiteAuth` Cloud Function을 호출하므로 신규 Cloudflare 로그인과 호환되지 않습니다.
- **v1.0.6 (`versionCode 7`) 소스**는 Android 네이티브 `SuiteAuthManager`가 Cloudflare `/connect`를 직접 호출하고, 성공 시 자동으로 App Link를 통해 앱에 돌아오도록 변경했습니다. 이 화면에서는 구형 `이 계정으로 가계부 연결` 버튼을 사용하지 않습니다.
- `main` 소스 푸시와 Android CI 성공만으로 **설치된 1.0.5 APK가 업데이트되지는 않습니다**. 기존 v1.0.5에 사용한 **동일한 릴리스 서명키**로 v1.0.6 APK/AAB를 빌드·서명·배포해야 합니다.
- 빌드 예시: `./gradlew -PsigningProperties=/개인/안전한/경로/signing.properties assembleRelease bundleRelease` (키와 암호를 GitHub/채팅에 올리지 않음).
- **기존 앱을 제거하거나 데이터/저장 공간을 초기화하지 말 것.** 데이터 삭제 전 JSON 백업을 남기고, 동일 패키지명 `softm.android.gagyebu`에 동일 서명 인증서를 가진 상위 버전으로 덮어 설치합니다.
- Android CI의 `app-debug.apk`는 `softm.android.gagyebu.debug`라는 **별도의 패키지**로 설치되며 기존 앱의 기록을 공유하거나 기존 앱을 업데이트하지 않습니다. Android App Link가 기존 릴리스 앱으로 열릴 수 있으므로 디버그 빌드의 로그인 성공 여부를 실제 릴리스 검증으로 간주하지 않습니다.
- 실제 Cloudflare 로그인 → 앱 복귀 → 전용 Worker 장부 저장 → PC 동기화까지 성공하기 전에는 마이그레이션 완료 또는 정식 배포 완료라고 표시하지 않습니다.
