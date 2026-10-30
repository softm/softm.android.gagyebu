# 광고 구성

앱 화면 아래의 별도 영역에 광고를 표시합니다. 광고는 WebView 위에 겹치지 않으며, 한 번에 한 광고망만 노출합니다.

## 운영 계획

1. **1순위 — Google AdMob 네이티브:** `autorunnavi@gmail.com` 계정의 가계부 전용 광고 단위를 사용합니다.
2. **2순위 — Kakao AdFit 배너:** AdMob 요청이 실패하고 앱 전용 AdFit 광고 단위가 구성된 경우에만 320×50 배너를 요청합니다.
3. 거래 입력을 방해하는 전면·보상형 광고는 사용하지 않습니다. 광고는 화면 하단의 고정 영역에만 배치합니다.
4. 금융 알림, SMS 원문, 거래 금액·사용처·분류는 광고 요청이나 맞춤 타기팅에 전달하지 않습니다.
5. 출시 후에는 광고 요청 성공률, 노출 수, 수익, 비정상 클릭 징후와 앱 오류율을 광고 콘솔에서 점검합니다. 거래 데이터 기반 세분화는 하지 않습니다.

## 개인정보 동의

- 앱 시작 시 Google UMP에서 동의 정보를 갱신합니다.
- UMP가 광고 요청을 허용하기 전에는 광고 SDK를 초기화하거나 광고를 요청하지 않습니다.
- UMP가 개인정보 옵션 진입점을 요구하는 지역에서는 설정 화면에 **광고 개인정보 설정** 버튼을 표시합니다.
- 금융 알림, SMS 원문, 거래 내역은 광고 타기팅 데이터로 전달하지 않습니다.

## AdMob

운영 리소스(`app/src/main/res/values/strings.xml`)는 `autorunnavi@gmail.com` AdMob 계정에서 발급했습니다.

- 앱 ID: `ca-app-pub-4219853075548260~3340019675`
- 하단 네이티브 광고 단위: `ca-app-pub-4219853075548260/4541245696`

디버그 빌드는 `app/src/debug/res/values/ads.xml`의 Google 공식 테스트 ID를 사용하므로 개발 중 실제 노출·클릭을 만들지 않습니다.

AdMob 계정의 결제 정보 설정과 앱 검토가 완료되어야 운영 광고가 게재됩니다.

## Kakao AdFit

AdMob 네이티브 광고 요청이 실패하면 AdFit 배너를 대체 광고로 요청합니다. 앱 전용 광고 단위가 전달되지 않은 빌드에서는 대체 요청을 안전하게 비활성화합니다.

AdFit의 Android 앱 매체 등록에는 공개된 Google Play 앱 URL이 필요합니다. 첫 Play 배포가 공개된 뒤 다음 순서로 활성화합니다.

1. AdFit에서 Android 앱 매체를 만들고 Google Play URL을 등록합니다.
2. 320×50 배너 광고 단위를 만듭니다.
3. 발급된 `DAN-...` 값을 소스에 저장하지 않고 Gradle 속성 `adfitBannerAdUnitId`로 전달합니다.
4. 디버그 빌드는 `testMode=true`, 릴리스 빌드는 운영 모드로 동작하는지 확인합니다.
5. `lintDebug`, 단위 테스트, 디버그/릴리스 빌드를 다시 수행합니다.

AdFit 배너는 Activity의 `onResume`, `onPause`, `onDestroy` 생명주기에 맞춰 재개·정지·해제됩니다.

로컬 검증:

    ./gradlew -PadfitBannerAdUnitId=DAN-발급값 testDebugUnitTest lintDebug assembleDebug

릴리스 번들:

    ./gradlew \
      -PadfitBannerAdUnitId=DAN-발급값 \
      -PsigningProperties=/절대경로/signing.properties \
      bundleRelease

`DAN-...` 값은 앱 매체 심사 후 발급된 **softm.android.gagyebu 전용 단위**만 사용합니다. 다른 앱의 광고 단위를 복사하지 않습니다.

## 검증 명령

    ./gradlew testDebugUnitTest lintDebug assembleDebug
    ./gradlew -PsigningProperties=/절대경로/signing.properties bundleRelease

