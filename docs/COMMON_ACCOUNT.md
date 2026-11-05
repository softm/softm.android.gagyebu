# 한눈 공통 로그인

가계부 Android는 한눈 공통 계정 `softm-hannun-api`를 사용한다. 로그인하면 공통 UID의 계정 장부를 열고 PC·Android 사이에서 동기화한다. 비로그인 장부는 계정과 독립적으로 유지한다.

## 구성

- Firebase Android 앱: `1:166232180116:android:81eb282c96c8619662bb81`, 패키지 `softm.android.gagyebu`.
- 공개 SDK 설정: `app/src/main/res/values/suite_firebase.xml`. 개인키나 OAuth Secret을 포함하지 않는다.
- 인증 UI 원본: `/Users/softm/makeme/softm.github.io/shared/platform/v1/account-menu.mjs`, `account-menu.css`.
- 오프라인 번들: `app/src/main/assets/web/shared/platform/v1/`의 같은 파일. 공통 원본 수정 시 두 사본을 함께 갱신한다.
- 외부 계정 허브: `https://softm.github.io/account/`.
- App Link: `https://softm.github.io/account/android/gagyebu/`.
- App Link를 처리하지 못한 브라우저의 명시적 앱 열기 버튼: `softm.android.gagyebu://auth/callback`.
- 임의 복귀 URL은 받지 않는다. 공통 registry에 지정된 주소만 허용하고 앱에서 PKCE·요청 ID·기한을 검증한다. 인증 토큰은 링크나 웹 브리지에 노출하지 않는다.
- 계정 관리 및 회원탈퇴는 외부 허브에서 수행한다. 사용자 동의·제공자 연결을 앱에서 자동 처리하지 않는다.

## 운영 반영 단계

2026-11-05: 공통 Firebase 프로젝트에 Android 앱 등록 완료. 공통 허브·registry·App Link 및 Android 연결 구현 완료. 운영 공통 웹/서버 배포와 실계정 검증은 별도이며 미완료 상태를 적용 완료로 표시하지 않는다.

필요한 운영 변경은 `softm.github.io`의 `account/`, `shared/platform/v1/`, `.well-known/assetlinks.json` 배포와 `suiteAuth`의 갱신된 registry 배포이다. `suiteSocialAuth`, 기존 로그인 제공자, Firestore 규칙·자료에는 변경이 필요하지 않다. 원격 푸시는 사용자 승인 후 진행한다.

Play 배포의 앱 서명이 로컬 공통 배포 인증서와 다를 경우 실제 Play 앱 서명 인증서를 추가 확인한 뒤 assetlinks를 확장해야 한다. 현재 assetlinks는 확인된 공통 배포 인증서만 포함한다.

## 검증

- `SuiteAuthSessionTest`: 정확한 복귀 주소·요청 ID·PKCE 벡터, 만료·재사용·변조 경계.
- `scripts/test-common-account.cjs`: 테스트 장부의 로그인·로그아웃·취소·오류·복원, 네트워크 업로드 없음, 320/393/768/1280px 메뉴.
- 공통 저장소 `native-handoff.test.mjs`, `native-handoff-ui.cjs`: 등록 Origin, 고정 앱 링크, 기존 계정 명시적 연결, 취소·잘못된 Origin 거부.
- 공통 기존 `account-handoff-ui.cjs`, `account-menu-ui.cjs`: 기존 웹 로그인 및 메뉴 회귀 검사.
- 실제 서명된 Release APK 설치 검증 결과와 산출물 SHA-256은 `captures/common-login-20261105/`에 보관한다.
