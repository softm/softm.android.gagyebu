/** SOFTM-ADFIT-CONFIG START 날짜:20261022 : 실제 가계부 매체의 광고단위만 연결하고 장부 데이터와 설정을 분리 */
export const AD_CONFIG = Object.freeze({
  enabled: true,
  scriptUrl: 'https://t1.kakaocdn.net/kas/static/ba.min.js',
  allowedOrigins: ['https://softm.github.io'],
  pathPrefix: '/gagyebu/',
  storageKey: 'gagyebu.adfit.collapsed.v1',
  bottom: {
    mobile: {unitId: 'DAN-fIXGhve8Z72xQH1H', width: 320, height: 50},
    desktop: {unitId: 'DAN-DcWN2G6To8ZzG5Fv', width: 728, height: 90},
  },
  analytics: {unitId: 'DAN-9oJPVnQodcKlE2xz', width: 320, height: 100},
});
/** SOFTM-ADFIT-CONFIG END */
