/**
 * Firebase auth + per-user Firestore realtime sync for the static GitHub Pages app.
 * The Firebase SDK is loaded only if the project has been configured.
 * Secrets / external OAuth token exchanges are NEVER performed in the browser.
 */
import {firebaseConfig,socialConfig,authProviderConfig,cloudRegion} from './firebase-config.js';
import {initialState} from './domain.js';
import {changedDocuments,reconstructLedger,forCloudDocument,rebaseQueuedMutation} from './ledger-sync.js';

const SDK='12.4.0';
const oauthKey='gagyebu:oauth';
const timeoutMinutes=10;
const validConfig=()=>['apiKey','authDomain','projectId','appId'].every(key=>Boolean(firebaseConfig[key]?.trim()));
const dataUrlPattern=/^data:image\/(?:png|jpeg|webp);base64,/i;
const normalizeError=(error)=>{
  const code=String(error?.code||'');
  const names={
    'auth/unauthorized-domain':'Firebase 인증 허용 도메인에 이 웹사이트가 등록되지 않았습니다.',
    'auth/popup-blocked':'팝업이 차단되었습니다. 브라우저에서 팝업을 허용해 주세요.',
    'auth/popup-closed-by-user':'로그인 창이 닫혔습니다.',
    'auth/account-exists-with-different-credential':'이미 다른 로그인 방법으로 등록된 이메일입니다. 기존 방법으로 로그인해 주세요.',
    'permission-denied':'Firestore 보안 규칙에서 접근을 거부했습니다. UID 규칙을 확인해 주세요.',
    'functions/not-found':'SNS 로그인용 Cloud Functions가 아직 배포되지 않았습니다.',
    'failed-precondition':'클라우드 데이터가 다른 기기에서 변경되었습니다. 충돌 내용을 확인해 주세요.',
    'ledger/conflict':'같은 기록이 다른 기기에서도 변경되어 자동 덮어쓰기를 중단했습니다.',
    'unavailable':'네트워크가 끊겼습니다. 로컬 기록을 보존하며 다시 연결되면 동기화합니다.'
  };
  return names[code]||names[code.replace(/^firestore\//,'')]||error?.message||'클라우드 작업을 완료하지 못했습니다.';
};
const redirectUri=()=>new URL('./',window.location.href).toString();
const isReceiptPath=(uid,path)=>typeof path==='string'&&path.startsWith(`users/${uid}/receipts/`)&&path.length<240;
const base64Url=bytes=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
async function createPkce(){
  const verifier=base64Url(crypto.getRandomValues(new Uint8Array(48)));
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier));
  return {verifier,challenge:base64Url(new Uint8Array(digest))};
}
const canonical=value=>{
  if(Array.isArray(value))return value.map(canonical);
  if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])]));
  return value;
};
const sameDocument=(a,b)=>JSON.stringify(canonical(a??null))===JSON.stringify(canonical(b??null));

function normalizeLegacyEvent(event,index,uid,fallbackDate){
  const createdAt=(typeof event?.createdAt==='string'&&event.createdAt?event.createdAt:fallbackDate).slice(0,40);
  const rawId=typeof event?.id==='string'?event.id:'';
  const id=/^[A-Za-z0-9_-]{1,128}$/.test(rawId)?rawId:`legacy-${index}-${createdAt.replace(/\W/g,'').slice(0,32)}`;
  const entityType=['transaction','account','category','setting','budget','ledger'].includes(event?.entityType)?event.entityType:'transaction';
  const snapshot=value=>{
    if(!value||typeof value!=='object'||Array.isArray(value))return null;
    const output={};
    for(const [key,item] of Object.entries(value).slice(0,16))if(['string','number','boolean'].includes(typeof item)||item===null)output[key]=typeof item==='string'?item.slice(0,120):item;
    return output;
  };
  return {
    id,entityType,
    entityId:String(event?.entityId||'unknown').slice(0,128)||'unknown',
    action:['create','update','delete','import','restore'].includes(event?.action)?event.action:'update',
    actorUid:uid,deviceId:String(event?.deviceId||'legacy').slice(0,128)||'legacy',createdAt,
    changedFields:Array.isArray(event?.changedFields)?event.changedFields.filter(value=>typeof value==='string').slice(0,32):[],
    before:snapshot(event?.before),after:snapshot(event?.after)
  };
}

export class CloudService{
  constructor(handlers={}){
    this.handlers=handlers;this.sdk=null;this.app=null;this.auth=null;this.db=null;this.storage=null;
    this.user=null;this.started=false;this.ready=false;this.pending=0;this.queue=Promise.resolve();this.syncBlocked=false;
    this.unsubscribe=[];this.parts={};this.partMeta={};this.epoch=0;this.lastRemote=null;this.writeHead=null;this.lastError='';this.lastConfirmedAt=null;
  }
  isConfigured(){return validConfig();}
  getUid(){return this.user?.uid||null;}
  getSyncInfo(){return {pending:this.pending,lastConfirmedAt:this.lastConfirmedAt,ready:this.ready,status:this.lastError?'error':this.ready?'synced':'loading'};}
  getProviderAvailability(){
    const enabled=name=>Boolean(authProviderConfig?.[name]);
    return {
      kakao:{enabled:enabled('kakao')&&Boolean(socialConfig.kakaoRestApiKey),reason:'카카오 앱 키와 공급자 활성화가 필요합니다.'},
      naver:{enabled:enabled('naver')&&Boolean(socialConfig.naverClientId),reason:'네이버 Client ID와 공급자 활성화가 필요합니다.'},
      google:{enabled:enabled('google'),reason:'Firebase Google 공급자 활성화가 필요합니다.'},
      apple:{enabled:enabled('apple'),reason:'Firebase Apple 공급자와 Apple Developer 설정이 필요합니다.'}
    };
  }
  _status(kind,detail=''){this.handlers.onStatus?.(kind,detail);this.handlers.onSyncInfo?.(this.getSyncInfo());}
  _error(error){this.lastError=normalizeError(error);this._status('error',this.lastError);this.handlers.onError?.(this.lastError,error);}
  async start(){
    if(this.started)return;this.started=true;
    if(!validConfig()){this._status('unconfigured');return;}
    try{
      const base=`https://www.gstatic.com/firebasejs/${SDK}`;
      const [app,auth,firestore,functions,storage]=await Promise.all([
        import(`${base}/firebase-app.js`),import(`${base}/firebase-auth.js`),
        import(`${base}/firebase-firestore.js`),import(`${base}/firebase-functions.js`),
        import(`${base}/firebase-storage.js`)
      ]);
      this.sdk={app,auth,firestore,functions,storage};this.app=app.initializeApp(firebaseConfig);this.auth=auth.getAuth(this.app);
      try{await auth.setPersistence(this.auth,auth.browserLocalPersistence);}catch(error){console.warn('로그인 지속 설정:',error?.code||error);}
      try{this.db=firestore.initializeFirestore(this.app,{localCache:firestore.persistentLocalCache({tabManager:firestore.persistentMultipleTabManager()})});}
      catch{this.db=firestore.getFirestore(this.app);}
      if(firebaseConfig.storageBucket)this.storage=storage.getStorage(this.app);
      this.functions=functions.getFunctions(this.app,cloudRegion);this._status('signedout');
      this._authUnsub=auth.onAuthStateChanged(this.auth,user=>{this._onUser(user).catch(error=>this._error(error));},error=>this._error(error));
      await auth.getRedirectResult(this.auth).catch(error=>{throw new Error(normalizeError(error));});
      await this.consumeOAuthRedirect();
    }catch(error){this._error(error);}
  }
  async _onUser(user){
    const epoch=++this.epoch;
    this.unsubscribe.forEach(unsubscribe=>unsubscribe());this.unsubscribe=[];this.parts={};this.partMeta={};this.lastRemote=null;this.writeHead=null;
    this.user=user;this.ready=false;this.pending=0;this.queue=Promise.resolve();this.syncBlocked=false;this.lastError='';this.lastConfirmedAt=null;
    await this.handlers.onAuth?.(user);
    if(!user){this._status('signedout');return;}
    this._status('loading');this._watch(epoch,user.uid);
    const {firestore:f}=this.sdk,configRef=f.doc(this.db,'users',user.uid,'ledger','config');
    try{
      const meta=await f.getDocFromServer(configRef);
      if(epoch!==this.epoch)return;
      if(!meta.exists())await this._initializeLedger(user.uid,configRef);
      else await this._migrateLegacyEvents(user.uid,configRef,meta.data());
    }catch(error){
      if(epoch!==this.epoch)return;
      if(String(error?.code).includes('unavailable')){this._status('offline','서버에 연결되면 보관된 기기 변경사항을 자동 재전송합니다.');return;}
      this._error(error);
    }
  }
  async _initializeLedger(uid,configRef){
    const {firestore:f}=this.sdk,fresh=initialState(),now=new Date().toISOString();
    await f.runTransaction(this.db,async transaction=>{
      if((await transaction.get(configRef)).exists())return;
      transaction.set(configRef,{version:1,schemaVersion:2,settings:fresh.settings,budgets:{},initialized:true,revision:1,createdAt:now,updatedAt:now});
      for(const collection of ['accounts','categories'])for(const item of fresh[collection])transaction.set(
        f.doc(this.db,'users',uid,collection,item.id),forCloudDocument({...item,revision:1,createdAt:now,updatedAt:now})
      );
    });
  }
  async _migrateLegacyEvents(uid,configRef,meta){
    if(meta.schemaVersion===2&&!Object.hasOwn(meta,'events'))return;
    const {firestore:f}=this.sdk,legacy=Array.isArray(meta.events)?meta.events:[],fallbackDate=String(meta.createdAt||meta.updatedAt||'1970-01-01T00:00:00.000Z');
    for(let offset=0;offset<legacy.length;offset+=350){
      const batch=f.writeBatch(this.db);
      legacy.slice(offset,offset+350).forEach((event,index)=>{
        const value=normalizeLegacyEvent(event,offset+index,uid,fallbackDate);
        batch.set(f.doc(this.db,'users',uid,'events',value.id),value);
      });
      await batch.commit();
    }
    const now=new Date().toISOString();
    try{await f.updateDoc(configRef,{schemaVersion:2,revision:(Number(meta.revision)||0)+1,createdAt:meta.createdAt||now,updatedAt:now,events:f.deleteField()});}
    catch(error){
      const current=await f.getDocFromServer(configRef);
      if(current.exists()&&current.data().schemaVersion===2&&!Object.hasOwn(current.data(),'events'))return;
      throw error;
    }
  }
  _watch(epoch,uid){
    const {firestore:f}=this.sdk;
    const watch=(collection,reference,select)=>{
      const unsubscribe=f.onSnapshot(reference,{includeMetadataChanges:true},snapshot=>{
        if(epoch!==this.epoch)return;
        this.parts[collection]=select(snapshot);this.partMeta[collection]={fromCache:snapshot.metadata.fromCache,hasPendingWrites:snapshot.metadata.hasPendingWrites};
        if(['ledger','accounts','categories','transactions','events'].every(key=>Object.hasOwn(this.parts,key)))this._emitRemote(epoch);
      },error=>this._error(error));
      this.unsubscribe.push(unsubscribe);
    };
    watch('ledger',f.doc(this.db,'users',uid,'ledger','config'),snapshot=>snapshot.exists()?snapshot.data():null);
    for(const name of ['accounts','categories','transactions'])watch(name,f.collection(this.db,'users',uid,name),snapshot=>snapshot.docs.map(doc=>({...doc.data(),id:doc.id})));
    const recentEvents=f.query(f.collection(this.db,'users',uid,'events'),f.orderBy('createdAt','desc'),f.limit(500));
    watch('events',recentEvents,snapshot=>snapshot.docs.map(doc=>({...doc.data(),id:doc.id})));
  }
  _emitRemote(epoch){
    if(epoch!==this.epoch||!this.parts.ledger)return;
    try{
      const ledger=reconstructLedger(this.parts.ledger,this.parts);this.lastRemote=ledger;
      const serverConfirmed=Object.values(this.partMeta).every(meta=>!meta.fromCache&&!meta.hasPendingWrites);
      if(!serverConfirmed){this.ready=false;this._status(navigator.onLine?'loading':'offline');return;}
      this.ready=true;this.lastConfirmedAt=new Date().toISOString();
      if(this.pending){this._status('saving');return;}
      this.writeHead=ledger;
      this._status('synced');this.handlers.onLedger?.(ledger);
    }catch(error){this._error(error);}
  }
  async _fetchLedgerFromServer(uid){
    const {firestore:f}=this.sdk;
    const eventQuery=f.query(f.collection(this.db,'users',uid,'events'),f.orderBy('createdAt','desc'),f.limit(500));
    const [meta,...snapshots]=await Promise.all([
      f.getDocFromServer(f.doc(this.db,'users',uid,'ledger','config')),
      ...['accounts','categories','transactions'].map(name=>f.getDocsFromServer(f.collection(this.db,'users',uid,name))),
      f.getDocsFromServer(eventQuery)
    ]);
    if(!meta.exists())throw new Error('서버 장부 설정을 찾지 못했습니다.');
    const groups={};
    ['accounts','categories','transactions','events'].forEach((name,index)=>{groups[name]=snapshots[index].docs.map(document=>({...document.data(),id:document.id}));});
    return reconstructLedger(meta.data(),groups);
  }
  _requireProvider(name){const info=this.getProviderAvailability()[name];if(!info?.enabled)throw new Error(info?.reason||'이 로그인 제공자는 아직 설정되지 않았습니다.');}
  async loginGoogle(){
    this._requireProvider('google');if(!this.auth)throw new Error('Firebase 설정이 필요합니다. 설정 안내를 확인하세요.');
    const {auth}=this.sdk;try{await auth.signInWithPopup(this.auth,new auth.GoogleAuthProvider());}
    catch(error){if(error?.code==='auth/popup-blocked')await auth.signInWithRedirect(this.auth,new auth.GoogleAuthProvider());else throw new Error(normalizeError(error));}
  }
  async loginApple(){
    this._requireProvider('apple');if(!this.auth)throw new Error('Firebase 설정이 필요합니다.');
    const {auth}=this.sdk,provider=new auth.OAuthProvider('apple.com');provider.addScope('email');provider.addScope('name');
    try{await auth.signInWithPopup(this.auth,provider);}
    catch(error){if(error?.code==='auth/popup-blocked')await auth.signInWithRedirect(this.auth,provider);else throw new Error(normalizeError(error));}
  }
  async loginSocial(provider){
    this._requireProvider(provider);if(!this.auth)throw new Error('Firebase 설정을 먼저 완료해 주세요.');
    const key=provider==='kakao'?socialConfig.kakaoRestApiKey:provider==='naver'?socialConfig.naverClientId:'';
    const state=Array.from(crypto.getRandomValues(new Uint8Array(32)),value=>value.toString(16).padStart(2,'0')).join('');
    const redirect=redirectUri(),pkce=await createPkce();
    sessionStorage.setItem(oauthKey,JSON.stringify({provider,state,redirect,verifier:pkce.verifier,at:Date.now()}));
    const url=new URL(provider==='kakao'?'https://kauth.kakao.com/oauth/authorize':'https://nid.naver.com/oauth2.0/authorize');
    url.searchParams.set('response_type','code');url.searchParams.set('client_id',key);url.searchParams.set('redirect_uri',redirect);url.searchParams.set('state',state);
    url.searchParams.set('code_challenge',pkce.challenge);url.searchParams.set('code_challenge_method','S256');
    window.location.assign(url.toString());
  }
  async consumeOAuthRedirect(){
    const url=new URL(window.location.href),code=url.searchParams.get('code'),returnedState=url.searchParams.get('state'),error=url.searchParams.get('error');
    if(!code&&!error)return;
    for(const key of ['code','state','error','error_description'])url.searchParams.delete(key);
    window.history.replaceState({},'',url.toString());
    const raw=sessionStorage.getItem(oauthKey);sessionStorage.removeItem(oauthKey);
    if(!raw)throw new Error('로그인 상태를 확인할 수 없습니다. 다시 시도해 주세요.');
    let flow;try{flow=JSON.parse(raw);}catch{throw new Error('로그인 인증 정보가 올바르지 않습니다.');}
    if(!['kakao','naver'].includes(flow.provider)||!flow.state||flow.state!==returnedState||flow.redirect!==redirectUri()||Date.now()-flow.at>timeoutMinutes*60000||!/^[A-Za-z0-9_-]{43,128}$/.test(flow.verifier||''))throw new Error('로그인 요청이 만료되었거나 일치하지 않습니다. 다시 시도해 주세요.');
    if(error)throw new Error('SNS 로그인이 취소되었거나 거절되었습니다.');
    const fn=this.sdk.functions.httpsCallable(this.functions,'oauthExchange');
    const response=await fn({provider:flow.provider,code,redirectUri:flow.redirect,state:flow.state,codeVerifier:flow.verifier});
    if(!response.data?.token)throw new Error('인증 서버에서 로그인 토큰을 받지 못했습니다.');
    const credential=await this.sdk.auth.signInWithCustomToken(this.auth,response.data.token);
    if(response.data.displayName&&!credential.user.displayName)try{await this.sdk.auth.updateProfile(credential.user,{displayName:response.data.displayName.slice(0,80)});}catch{}
  }
  async logout(){if(this.auth)await this.sdk.auth.signOut(this.auth);}
  async _reauthenticateForDeletion(){
    if(!this.auth||!this.user)throw new Error('로그인이 필요합니다.');
    const {auth}=this.sdk,token=await auth.getIdTokenResult(this.user,true),authenticatedAt=Date.parse(token.authTime||'');
    if(Number.isFinite(authenticatedAt)&&Date.now()-authenticatedAt<240000)return;
    const providerId=this.user.providerData?.map(item=>item.providerId).find(id=>id==='google.com'||id==='apple.com');
    if(!providerId)throw new Error('카카오·네이버 계정은 보안을 위해 로그아웃 후 다시 로그인한 다음 5분 안에 삭제를 다시 요청해 주세요.');
    const provider=providerId==='google.com'?new auth.GoogleAuthProvider():new auth.OAuthProvider('apple.com');
    if(providerId==='apple.com'){provider.addScope('email');provider.addScope('name');}
    try{await auth.reauthenticateWithPopup(this.user,provider);}
    catch(error){throw new Error(normalizeError(error));}
  }
  async deleteAccountData(){
    if(!this.functions||!this.user)throw new Error('로그인과 Firebase 연결이 필요합니다.');
    await this._reauthenticateForDeletion();
    const uid=this.user.uid;
    const fn=this.sdk.functions.httpsCallable(this.functions,'deleteAccountData'),response=await fn({confirm:true});
    if(response.data?.deleted!==true)throw new Error('계정 삭제 완료를 확인하지 못했습니다.');
    return uid;
  }
  async getReceipt(path){
    if(!this.user||!isReceiptPath(this.user.uid,path)||!this.storage)throw new Error('영수증 저장소에 접근할 수 없습니다.');
    const {storage}=this.sdk,blob=await storage.getBlob(storage.ref(this.storage,path));
    return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(new Error('영수증 파일 읽기 실패'));reader.readAsDataURL(blob);});
  }
  async materializeReceipts(ledger){
    const copy=structuredClone(ledger);
    await Promise.all(copy.transactions.map(async transaction=>{if(transaction.attachmentPath&&!transaction.attachment)transaction.attachment=await this.getReceipt(transaction.attachmentPath);}));
    return copy;
  }
  /** Caller saves a user-specific IndexedDB copy FIRST, then invokes this queue. */
  queueDiff(before,after,{retry=false}={}){
    if(retry)this.syncBlocked=false;
    const uid=this.user?.uid,epoch=this.epoch;
    if(!uid||!this.ready)return Promise.reject(new Error('클라우드 서버 확인 전입니다. 변경사항은 기기에 보관되며 연결 후 자동 재전송됩니다.'));
    this.pending++;this._status('saving');
    const work=async()=>{
      if(uid!==this.user?.uid||epoch!==this.epoch)return null;
      if(this.syncBlocked)throw new Error('이전 동기화 오류로 업로드가 중지되었습니다. 설정에서 재전송하세요.');
      let writeBefore=before,writeAfter=after;
      if(this.writeHead){
        const rebased=rebaseQueuedMutation(before,after,this.writeHead);
        if(rebased.conflicts.length){const error=new Error(`대기열 기준 충돌: ${rebased.conflicts.join(', ')}`);error.code='ledger/conflict';throw error;}
        writeBefore=this.writeHead;writeAfter=rebased.ledger;
      }
      const updated=await this._writeDiff(uid,writeBefore,writeAfter);this.writeHead=updated;return updated;
    };
    const next=this.queue.catch(()=>{}).then(work);this.queue=next;
    return next.then(updated=>{
      if(epoch!==this.epoch)return updated;
      this.pending--;this.lastError='';this.lastConfirmedAt=new Date().toISOString();
      if(!this.pending){this._status('synced');this.handlers.onSynchronized?.(updated);}
      return updated;
    },async error=>{
      if(epoch===this.epoch){
        this.pending--;this.syncBlocked=true;this._error(error);
        if(error?.code==='ledger/conflict')try{
          const latest=await this._fetchLedgerFromServer(uid);
          if(epoch===this.epoch){this.lastRemote=latest;this.writeHead=latest;this.ready=true;queueMicrotask(()=>Promise.resolve(this.handlers.onLedger?.(latest)).catch(refreshError=>this._error(refreshError)));}
        }catch{}
      }
      throw error;
    });
  }
  async _writeDiff(uid,before,after){
    const {firestore:f,storage:s}=this.sdk,updated=structuredClone(after),previous=new Map(before.transactions.map(transaction=>[transaction.id,transaction]));
    if(changedDocuments(before,updated).length>450)throw new Error('한 번에 동기화할 변경이 450건을 넘습니다. 가져오기를 더 작은 묶음으로 나눠주세요.');
    const removePaths=[],uploadedPaths=[];let commitStarted=false;
    try{
      for(const transaction of updated.transactions){
        const prior=previous.get(transaction.id);
        if(prior?.attachmentPath&&!transaction.attachmentPath)removePaths.push(prior.attachmentPath);
        if(transaction.attachment&&dataUrlPattern.test(transaction.attachment)&&(!transaction.attachmentPath||(prior?.attachment&&prior.attachment!==transaction.attachment))){
          if(!this.storage)throw new Error('영수증 클라우드 저장에는 Firebase Storage 버킷이 필요합니다.');
          const uploadId=transaction.attachmentUploadId||crypto.randomUUID(),path=`users/${uid}/receipts/${uploadId}`,contentType=transaction.attachment.slice(5,transaction.attachment.indexOf(';')),reference=s.ref(this.storage,path);
          let exists=false;
          try{
            const metadata=await s.getMetadata(reference);
            if(metadata.customMetadata?.ownerUid!==uid||metadata.customMetadata?.transactionId!==transaction.id.slice(0,128))throw new Error('기존 영수증 업로드 식별자가 다른 거래와 충돌했습니다.');
            exists=true;
          }catch(error){if(error?.code!=='storage/object-not-found')throw error;}
          if(!exists){await s.uploadString(reference,transaction.attachment,'data_url',{contentType,customMetadata:{ownerUid:uid,transactionId:transaction.id.slice(0,128)}});uploadedPaths.push(path);}
          transaction.attachmentUploadId=uploadId;transaction.attachmentPath=path;
          if(prior?.attachmentPath&&!removePaths.includes(prior.attachmentPath))removePaths.push(prior.attachmentPath);
        }
      }
      const afterIds=new Set(updated.transactions.map(transaction=>transaction.id));
      for(const item of before.transactions)if(!afterIds.has(item.id)&&item.attachmentPath)removePaths.push(item.attachmentPath);
      const changes=changedDocuments(before,updated);
      if(changes.length>450)throw new Error('영수증 처리 후 동기화 변경이 450건을 넘습니다.');
      if(changes.length){commitStarted=true;await f.runTransaction(this.db,async transaction=>{
        const refs=changes.map(change=>f.doc(this.db,'users',uid,change.collection,change.id));
        const snapshots=await Promise.all(refs.map(reference=>transaction.get(reference)));
        const conflicts=[],identical=new Set();
        changes.forEach((change,index)=>{
          const snapshot=snapshots[index];
          if(snapshot.exists()&&sameDocument(snapshot.data(),change.data)){identical.add(index);return;}
          if(change.collection==='events'){
            if(snapshot.exists())conflicts.push(`events/${change.id}`);
            return;
          }
          if(snapshot.exists()!==change.expectedExists){conflicts.push(`${change.collection}/${change.id}`);return;}
          if(snapshot.exists()&&(Number(snapshot.data()?.revision)||0)!==change.expectedRevision)conflicts.push(`${change.collection}/${change.id}`);
        });
        if(conflicts.length){const error=new Error(`동시에 변경된 기록: ${conflicts.join(', ')}`);error.code='ledger/conflict';error.conflicts=conflicts;throw error;}
        changes.forEach((change,index)=>{if(!identical.has(index))transaction.set(refs[index],change.data);});
      },{maxAttempts:5});}
      await f.waitForPendingWrites(this.db);
    }catch(error){
      const rollbackSafe=!commitStarted||['ledger/conflict','permission-denied','invalid-argument','failed-precondition'].includes(String(error?.code||'').replace(/^firestore\//,''));
      if(rollbackSafe)for(const path of uploadedPaths)try{await s.deleteObject(s.ref(this.storage,path));}catch{}
      throw error;
    }
    for(const path of new Set(removePaths))if(this.storage&&isReceiptPath(uid,path))try{await s.deleteObject(s.ref(this.storage,path));}catch(error){console.warn('영수증 정리 지연',error?.code||error);}
    return updated;
  }
  stop(){this.epoch++;this.unsubscribe.forEach(unsubscribe=>unsubscribe());this.unsubscribe=[];this._authUnsub?.();}
}
