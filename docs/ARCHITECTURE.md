# 아키텍처

## 데이터 흐름

    은행/카드 앱 알림 ─┐
                        ├─ 금융 키워드·금액 선별
    이번 달 기존 SMS ──┤
    새로 도착한 SMS ───┘
                               │
                               ▼
                    Android Keystore AES-GCM
                               │
                               ▼
                   앱 전용 SQLite 캡처 보관함
                               │
                      확인 알림 / 앱 실행
                               │
                               ▼
                  허용 출처 WebMessage 브리지
                               │
                               ▼
                 기존 가계부의 확인 필요 거래

## 신뢰 경계

- WebView는 https://appassets.androidplatform.net/assets/web/ 의 번들 파일만 내부에서 엽니다.
- 외부 HTTP(S), 전화, 이메일 링크는 시스템 앱으로 전달합니다.
- JavaScript 브리지는 AndroidX WebKit의 addWebMessageListener를 사용하며 지정된 HTTPS 출처와 메인 프레임 메시지만 처리합니다.
- WebView 파일 접근, 콘텐츠 접근, 혼합 콘텐츠, 임의 팝업, 카메라·마이크 권한은 차단합니다.
- 브리지는 캡처 조회, 가져오기 완료 표시, 권한 설정, 명시적 원문 삭제 및 공통 계정 상태·로그인·로그아웃을 제공합니다. 계정 브리지는 토큰을 웹 화면에 전달하지 않습니다.

## Cloudflare 공통 로그인 및 장부 동기화

- Android WebView가 제공하는 계정 버튼은 네이티브 `SuiteAuthManager`로 요청을 전달합니다.
- 시스템 브라우저로 `https://softm-hannun-account.softm.workers.dev/connect`를 열고 Google·카카오·네이버 웹 로그인 및 약관을 처리합니다.
- 앱이 생성한 PKCE S256 verifier·request ID에 연결된 1회용 코드만 고정된 `https://softm.github.io/account/android/gagyebu/` App Link의 fragment로 복귀합니다. 앱은 반환 주소와 5분 제한 및 요청 ID를 검증합니다.
- Android는 `/api/connect/exchange`에서 Cloudflare 1시간짜리 가계부 전용 Bearer 세션을 발급받고 Android Keystore에 암호화 저장합니다. Firebase Auth는 사용하지 않습니다.
- WebMessage `ledgerSync` 요청은 네이티브에서 `POST /api/apps/gagyebu/ledger`로 전달되어 Cloudflare D1의 사용자별 AES-GCM 암호문과 동기화됩니다.
- WebView JavaScript에 Bearer 토큰이 노출되지 않습니다. 로그인 제공자 및 계정 연결은 Cloudflare 공통 웹 계정 화면에서 관리합니다.
- 기존 Firestore `suite-v1`의 원격 장부는 이전 완료 전까지 서버에 그대로 보존합니다. 기기 내 기존 Firebase UID 장부는 확인 후 선택적으로 복사합니다. 서버에만 남은 내역은 별도 인증된 마이그레이션이 필요합니다.

## 저장과 중복 방지

- 캡처 ID는 출처, 이벤트 ID, 정규화한 내용, 수신 날짜로 SHA-256 생성합니다.
- 제목, 원문, 발신자, 금액, 날짜, 사용처, 계좌 힌트, 거래 유형은 AES-GCM으로 암호화합니다.
- 캡처 상태와 출처 앱 패키지, 수신 시각만 데이터베이스 인덱싱을 위해 평문 메타데이터로 보관합니다.
- 웹 장부에는 원문 대신 파싱된 거래와 짧은 출처 설명만 저장합니다. `captureSource` 브리지는 `sourceImportKey`의 캡처 ID로 가져오기 완료 여부와 관계없이 한 건의 보관된 원문을 읽어 화면에 표시하며 장부 상태를 변경하지 않습니다. 원문이 없으면 거래 요약을 원문으로 대신 표시하지 않고 조회할 수 없음을 안내합니다.
- sourceImportKey로 재실행 후에도 같은 캡처가 다시 등록되지 않도록 합니다.
- SMS와 앱 알림이 같은 거래를 각각 알려도 시간·금액·자산·사용처가 일치하면 한 거래로 처리합니다.

## 안전한 기본값

- 자동 감지 거래는 항상 pending 상태이며 합계와 자산에 반영되지 않습니다.
- 출금/이체 알림은 실제 소비인지 계좌 이동인지 알 수 없으므로 사용자가 최종 유형을 선택합니다.
- 연결 가능한 원거래를 찾지 못한 취소·환불은 임시 수입 후보로 표시하여 사용자가 수정할 수 있게 합니다.
- 인증번호, OTP, 광고 문구, 금액이 없는 일반 알림은 보관하지 않습니다.

