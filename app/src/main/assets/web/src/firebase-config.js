/**
 * Firebase web config is a public project identifier (NOT a service-account secret).
 * Copy the Web app config from Firebase Console > Project settings > Your apps.
 * Leave blank for local-only mode. Never store Kakao/Naver client secrets here.
 */
export const firebaseConfig={
  apiKey:'',
  authDomain:'',
  projectId:'',
  appId:'',
  storageBucket:''
};
export const socialConfig={
  kakaoRestApiKey:'',  // Public Kakao REST API key, NOT client_secret.
  naverClientId:''      // Public Naver Client ID, NOT Client Secret.
};
/** Set true only after the corresponding provider is enabled and configured. */
export const authProviderConfig={google:false,apple:false,kakao:false,naver:false};
export const cloudRegion='asia-northeast3';
