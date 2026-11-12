/**
 * Android Cloudflare cutover.
 *
 * The native SuiteAuthManager owns Cloudflare authentication and encrypted
 * D1 ledger exchange. account-ledger.js calls it via AndroidLedger.
 * This compatibility shell deliberately performs no Firebase requests.
 * Older Firebase-backed local records remain in IndexedDB for explicit export.
 */
export class CloudService {
 constructor(callbacks={}) { this.callbacks=callbacks; this.pending=false; }
 isConfigured(){return false;}
 getProviderAvailability(){return Object.fromEntries(['google','kakao','naver','apple'].map(provider=>[provider,{enabled:false,reason:'한눈 공통 계정 메뉴에서 로그인하세요.'}]));}
 getSyncInfo(){return {pending:0,lastConfirmedAt:null};}
 async start(){return;}
 async queueDiff(){throw Error('Firebase 동기화는 종료되었습니다. 한눈 Cloudflare 공통 계정으로 연결하세요.');}
 async getReceipt(){throw Error('예전 Firebase 영수증은 백업에서 복원해야 합니다.');}
 async materializeReceipts(state){return structuredClone(state);}
 async loginGoogle(){throw Error('한눈 공통 계정 메뉴를 사용하세요.');}
 async loginApple(){throw Error('한눈 공통 계정 메뉴를 사용하세요.');}
 async loginSocial(){throw Error('한눈 공통 계정 메뉴를 사용하세요.');}
 async logout(){throw Error('한눈 공통 계정 메뉴를 사용하세요.');}
 async deleteAccountData(){throw Error('Cloudflare 계정 삭제는 이관 검증 후 가능합니다.');}
}
