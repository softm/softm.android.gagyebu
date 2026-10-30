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
 tx.oncomplete=()=>resolve(undefined);tx.onerror=()=>reject(tx.error||new Error('저장 실패'));tx.onabort=()=>reject(tx.error||new Error('저장 취소'));
});}
async function deleteEntries(keys){const db=await open();return new Promise((resolve,reject)=>{
 const tx=db.transaction(STORE,'readwrite'),store=tx.objectStore(STORE);for(const key of keys)store.delete(key);
 tx.oncomplete=()=>resolve(undefined);tx.onerror=()=>reject(tx.error||new Error('삭제 실패'));tx.onabort=()=>reject(tx.error||new Error('삭제 취소'));
});}
/** Guest ledger is always kept in the historical 'main' key. */
export async function loadState(){return await get('main')||initialState();}
export async function saveState(state){validateBackup(state);return putEntries([['main',state]]);}
const userKey=uid=>`user:${uid}`;
export async function loadUserState(uid){if(!uid)throw new Error('로그인 사용자 ID가 필요합니다.');return await get(userKey(uid))||initialState();}
export async function loadUserDirty(uid){return Boolean(await get(`dirty:${uid}`));}
export async function loadUserSynced(uid){return await get(`synced:${uid}`)||null;}
export async function loadDeviceId(){
 const current=await get('device:id');if(current)return current;
 const value=crypto.randomUUID();await putEntries([['device:id',value]]);return value;
}
/** A state + dirty flag are saved atomically, BEFORE sending cloud writes. */
export async function saveUserState(uid,state,{dirty=true}={}){
 if(!uid)throw new Error('로그인 사용자 ID가 필요합니다.');validateBackup(state);
 const entries=[[userKey(uid),state],[`dirty:${uid}`,dirty]];
 if(!dirty)entries.push([`synced:${uid}`,state]);
 return putEntries(entries);
}
export async function markUserSynced(uid,state){validateBackup(state);return putEntries([[`dirty:${uid}`,false],[`synced:${uid}`,state]]);}
export async function clearUserState(uid){if(!uid)throw new Error('로그인 사용자 ID가 필요합니다.');return deleteEntries([userKey(uid),`dirty:${uid}`,`synced:${uid}`]);}
