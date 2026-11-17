import {initialState,validateBackup} from './domain.js';
const DB_NAME='gagyebu-local-v1', STORE='ledger';
let dbPromise;
function open(){
 if(!('indexedDB' in window))return Promise.reject(new Error('이 브라우저는 IndexedDB를 지원하지 않습니다.'));
 if(dbPromise)return dbPromise;
 dbPromise=new Promise((resolve,reject)=>{
  const req=indexedDB.open(DB_NAME,1);
  req.onupgradeneeded=()=>{if(!req.result.objectStoreNames.contains(STORE))req.result.createObjectStore(STORE);};
  req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error||new Error('저장소 연결 실패'));
 });return dbPromise;
}
async function get(key){const db=await open();return new Promise((resolve,reject)=>{
 const tx=db.transaction(STORE,'readonly'),req=tx.objectStore(STORE).get(key);
 req.onsuccess=()=>resolve(req.result??null);req.onerror=()=>reject(req.error);
});}
async function putEntries(entries){const db=await open();return new Promise((resolve,reject)=>{
 const tx=db.transaction(STORE,'readwrite');const store=tx.objectStore(STORE);
 for(const [key,value] of entries)store.put(value,key);
 tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error||new Error('저장 실패'));tx.onabort=()=>reject(tx.error||new Error('저장 취소'));
});}
/** Guest ledger is always kept in the historical 'main' key. */
let suiteUid=null;
export const selectSuiteLedger=uid=>{suiteUid=uid;};
const suiteKey=uid=>uid?'suite-ledger:'+uid:'main';
/**
 * Discover old per-Firebase-UID ledger snapshots without changing or deleting
 * the old IndexedDB keys. The new Cloudflare identity has a sh_ prefix.
 * Actual transfer always requires explicit user confirmation.
 */
export async function listLegacyLocalLedgers(){
 const db=await open();
 const keys=await new Promise((resolve,reject)=>{
  const tx=db.transaction(STORE,'readonly'),req=tx.objectStore(STORE).getAllKeys();
  req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
 });
 const result=[];
 for(const key of keys){
  if(typeof key!=='string')continue;
  let uid;
  if(key.startsWith('suite-ledger:'))uid=key.slice('suite-ledger:'.length);
  else if(key.startsWith('user:'))uid=key.slice('user:'.length);
  else continue;
  if(!uid||uid.startsWith('sh_'))continue;
  const ledger=await get(key);
  try{
   if(!ledger||!Array.isArray(ledger.transactions))continue;
   validateBackup(ledger);
   if(ledger.transactions.length)result.push({key,uid,transactionCount:ledger.transactions.length,ledger});
  }catch{/* Ignore unrelated or invalid legacy records. */}
 }
 return result;
}
export const getSuiteCache=uid=>get('suite-base:'+uid);
export const setSuiteCache=(uid,cache)=>putEntries([['suite-base:'+uid,cache]]);
export const clearSuiteLedger=uid=>putEntries([[suiteKey(uid),null],['suite-base:'+uid,null]]);
export async function loadState(uid=suiteUid){return await get(suiteKey(uid))||initialState();}
export async function saveState(state,uid=suiteUid){validateBackup(state);return putEntries([[suiteKey(uid),state]]);}
const userKey=uid=>`user:${uid}`;
export async function loadUserState(uid){if(!uid)throw new Error('로그인 사용자 ID가 필요합니다.');return await get(userKey(uid))||initialState();}
export async function loadUserDirty(uid){return Boolean(await get(`dirty:${uid}`));}
export async function loadUserSynced(uid){return await get(`synced:${uid}`)||null;}
/** A state + dirty flag are saved atomically, BEFORE sending cloud writes. */
export async function saveUserState(uid,state,{dirty=true}={}){
 if(!uid)throw new Error('로그인 사용자 ID가 필요합니다.');validateBackup(state);
 const entries=[[userKey(uid),state],[`dirty:${uid}`,dirty]];
 if(!dirty)entries.push([`synced:${uid}`,state]);
 return putEntries(entries);
}
export async function markUserSynced(uid,state){validateBackup(state);return putEntries([[`dirty:${uid}`,false],[`synced:${uid}`,state]]);}
