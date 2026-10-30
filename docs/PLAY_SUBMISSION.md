# Google Play 제출 현황

## 생성된 산출물

- 애플리케이션 ID: `softm.android.gagyebu`
- 버전: `1.0.5` (`versionCode 6`)
- 서명 AAB: `app/build/outputs/bundle/release/app-release.aab`
- 한국어 등록정보: `store-listing/ko-KR/`
- 앱 아이콘·그래픽: `store-assets/android/graphics/`
- 휴대전화 스크린샷: `store-assets/android/phone/`
- 데이터 보안 답변 초안: `store-listing/data-safety-draft.md`
- 개인정보처리방침 게시용 HTML: `store-listing/privacy-policy.html`

## 제출 전 외부 조건

1. 공개 HTTPS 개인정보처리방침 URL이 필요합니다. HTML 초안은 준비했지만 저장소 푸시/웹 배포는 별도 사용자 지시 없이 수행하지 않습니다.
2. `READ_SMS`와 `RECEIVE_SMS`는 제한된 권한이므로 Play Console의 SMS/통화 기록 권한 선언에서 핵심 기능인 “SMS 기반 자금 관리” 용도와 검토 동영상을 제출해야 합니다. 승인은 보장되지 않습니다.
3. 비공개/내부 테스트 트랙은 심사와 설치 검증에 사용할 수 있지만 AdFit Android 매체 등록에는 공개 Google Play URL이 필요합니다.
4. AdMob 앱도 Play 스토어 등록정보에 연결하고 검토를 받아야 운영 광고가 정상 게재됩니다.
5. Play Console에서 앱 콘텐츠, 광고 포함 여부, 대상 연령, 데이터 보안, 콘텐츠 등급과 개인정보처리방침을 모두 제출해야 합니다.

## 권한 선언 핵심 설명 초안

가계부의 핵심 기능은 사용자가 선택적으로 허용한 이번 달 금융 SMS와 새 금융 SMS에서 카드 결제·입금·출금 후보를 만들어 수동 확인 후 장부에 기록하는 것입니다. `READ_SMS` 조회는 이번 달 1일 이후로 제한하고 `RECEIVE_SMS`는 허용 이후 새 문자를 처리합니다. 감지 원문과 파싱 결과는 단말의 앱 전용 데이터베이스에 Android Keystore AES-GCM으로 암호화하여 보관하고 서버나 광고 사업자에게 전송하지 않습니다. SMS 권한을 거부해도 수동 기록과 금융 앱 알림 감지 기능을 사용할 수 있습니다.
