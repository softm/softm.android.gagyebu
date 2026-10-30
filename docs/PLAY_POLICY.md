# Google Play 배포 준비

READ_SMS와 RECEIVE_SMS는 Google Play의 제한된 고위험 권한입니다. 가계부와 예산 추적은 정책의 “SMS-based money management” 예외 후보지만 자동 승인을 의미하지 않습니다.

배포 전 필수 작업:

1. 스토어 설명의 핵심 기능에 SMS 기반 자금관리와 사용자 이점을 명확히 기재합니다.
2. Play Console의 SMS/통화기록 권한 선언 양식을 제출합니다.
3. 수집 범위, 암호화 보관, 보관 기간, 삭제 방법을 개인정보처리방침에 반영합니다.
4. 심사 계정 없이도 권한 설명과 핵심 기능을 검토할 수 있게 준비합니다.
5. 승인을 받지 못한 배포 변형에서는 READ_SMS·RECEIVE_SMS와 SMS 조회/수신 컴포넌트를 제거하고 알림 접근 방식만 제공합니다.

알림 접근은 SMS 권한의 우회 수단이 아닙니다. 앱은 금융 거래로 보이는 알림만 사용하고 다른 알림 내용을 분석, 프로파일링, 광고에 사용하지 않아야 합니다.

공식 참고:

- [SMS 및 통화기록 권한 정책](https://support.google.com/googleplay/android-developer/answer/10208820)
- [민감한 권한 선언 절차](https://support.google.com/googleplay/android-developer/answer/9214102)
- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

