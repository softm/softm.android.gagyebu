# Google Play 인앱 업데이트 — 가계부한눈 첫 적용

- 최초 적용 Android 저장소: `softm/softm.android.gagyebu`
- 공통 모듈: `softm.hannun.update.HannunInAppUpdateManager`, `HannunUpdatePolicy`
- Google Play In-App Update Java 라이브러리: `com.google.android.play:app-update:2.1.0`
- 앱 버전 기준: `versionCode 8`, `versionName 1.0.7` (출시 전 테스트 대상)
- 원문 가이드: https://developer.android.com/guide/playcore/in-app-updates/kotlin-java

## 작동

| 상황 | 처리 |
|---|---|
| Play 설치, 평범한 신규 앱 버전 | Flexible 다운로드 → 설치 및 재시작 확인 |
| Play에서 배포 우선순위 4~5로 설정 | 허용되는 경우 Immediate |
| 기기에 다운로드 완료 | 재시작 안내. 미선택 시 기존 앱 사용 가능 |
| 업데이트 없음 | `up_to_date` 반환 |
| APK 직접 설치/디버그 버전 | `not_play_install` 반환. 임의 APK 교체 없음 |
| Play 서비스 응답 오류 | `unavailable` 표시, 앱 사용 지속 |
| 설정 > 앱 업데이트 확인 | 현재 버전, Play 확인 상태, 마지막 확인 시각 표시 |

자동 확인은 앱을 전면으로 가져올 때마다 하여 다운로드가 중단된 업데이트를 복구합니다.
업데이트 설치를 제안하는 자동 팝업은 24시간 이내 반복하지 않습니다.
다운로드 완료 후 재시작 안내는 자동으로 6시간 이내 반복하지 않습니다.
수동 '업데이트 확인'은 이 제한과 무관하게 실행합니다.

Play의 priority는 **배포 시 Google Play Developer Publishing API**에서 `inAppUpdatePriority`로 지정해야 합니다.
Play Console 일반 릴리스 페이지에서 자동으로 긴급으로 분류되지 않습니다.

## 웹 리소스와 네이티브 업데이트의 차이

- `MainActivity`는 현재 `https://softm.github.io/gagyebu/`를 우선 로드합니다. 온라인 페이지는 서버 배포를 따라 갱신됩니다.
- 실패 시 `app/src/main/assets/web/`을 표시합니다. 내장 스냅샷은 Play를 통한 새 앱 설치 전까지 변경되지 않습니다.
- 원본 `softm/gagyebu/src/android-app.js`의 Android 설정 화면에 '업데이트 확인'을 연결합니다. 내장 스냅샷에도 같은 기능을 넣습니다.
- JS에서 `AndroidLedger` WebMessage 브리지의 `appInfo`와 `checkForUpdates`만 호출합니다. 인증/개인정보/장부 내용은 업데이트 모듈로 전달되지 않습니다.
- 앱 정보에서 웹 화면의 출처(온라인/내장)를 표시하며 정확한 웹 빌드 SHA는 현재 제공되지 않으므로 버전으로 오인해 표시하지 않습니다.

## 실제 동작 확인 절차

1. Play Console에서 같은 `applicationId`의 **이전 정상 Play 설치 버전**과 새로운 버전이 같은 테스트 트랙에 노출되는지 확인합니다.
2. 먼저 이 기능이 포함된 `versionCode=8`의 서명 AAB를 내부 테스트에 업로드합니다.
3. 테스터 계정으로 Play에서 해당 앱을 설치하여 설정 > 앱 업데이트 확인을 확인합니다. 디버그 APK/adb 설치로 Play 업데이트를 판정하지 않습니다.
4. 실제 Flexible 업데이트 흐름을 확인하려면 **더 높은 versionCode=9 이상의 AAB**를 같은 트랙으로 올립니다. v7에는 새 코드가 없으므로 v7 설치에서 새 인앱 UI가 나올 수는 없습니다.
5. 앱 실행과 설정의 수동 확인에서 Play 유연한 업데이트가 뜨는지, 다운로드 중 이용 가능한지, 다운로드 완료 후 재시작 안내가 뜨는지 확인합니다.
6. 긴급 Immediate는 실제 priority 4~5를 지정한 테스트 배포에서만 별도 검증합니다. 긴급 여부를 자동으로 추정하거나 Play 값을 조작하지 않습니다.
7. 업데이트 취소, 네트워크 차단·복귀, 로컬 장부·OCR·SMS·광고·로그인 동작을 회귀 점검합니다.
8. CI `testDebugUnitTest lintDebug assembleDebug` 성공 후 검토하고, Play 릴리스는 별도로 승인/업로드합니다.

**주의:** GitHub에 코드가 병합되거나 APK가 빌드되어도 Play 배포나 사용자 단말 업데이트가 완료된 것은 아닙니다.
