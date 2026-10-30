import {initialState, validateBackup} from './domain.js';

/** Pure conversion and reconciliation utilities. No Firebase / DOM dependencies. */
export const COLLECTIONS=['accounts','categories','transactions'];
export const CLIENT_ONLY_FIELDS=['attachment','attachmentUploadId'];
const SYNC_FIELDS=new Set(['revision','createdAt','updatedAt','sourceImportKey']);
const EVENT_LIMIT=1000;

const clone=value=>value===undefined?undefined:structuredClone(value);
const canonical=value=>{
  if(Array.isArray(value))return value.map(canonical);
  if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])]));
  return value;
};
const json=value=>JSON.stringify(canonical(value??null));
const equal=(a,b)=>json(a)===json(b);
const eventEntity={accounts:'account',categories:'category',transactions:'transaction'};
const eventPath=event=>({transaction:'transactions',account:'accounts',category:'categories',setting:'settings',budget:'budgets'}[event?.entityType]||event?.entityType)+`/${event?.entityId}`;

function ensureTombstones(state){
  if(!state.tombstones||typeof state.tombstones!=='object')state.tombstones={};
  for(const collection of COLLECTIONS)if(!state.tombstones[collection]||typeof state.tombstones[collection]!=='object')state.tombstones[collection]={};
  return state.tombstones;
}

export function forCloudDocument(record){
  if(record===undefined||record===null)return record;
  const copy=structuredClone(record);
  for(const key of CLIENT_ONLY_FIELDS)delete copy[key];
  return JSON.parse(JSON.stringify(copy)); // Firestore rejects undefined values.
}

function comparableRecord(record){
  if(!record)return record;
  const copy=forCloudDocument(record);
  for(const key of SYNC_FIELDS)delete copy[key];
  return copy;
}

function equivalentRecords(a,b){
  if(equal(a,b))return true;
  if(!a||!b)return false;
  const left=forCloudDocument(a),right=forCloudDocument(b);
  if(!left.attachmentPath&&a.attachmentUploadId&&String(right.attachmentPath||'').endsWith(`/${a.attachmentUploadId}`))left.attachmentPath=right.attachmentPath;
  if(!right.attachmentPath&&b.attachmentUploadId&&String(left.attachmentPath||'').endsWith(`/${b.attachmentUploadId}`))right.attachmentPath=left.attachmentPath;
  return equal(left,right);
}

function changedFieldNames(before,after){
  const names=[];
  for(const key of new Set([...Object.keys(before||{}),...Object.keys(after||{})])){
    if(CLIENT_ONLY_FIELDS.includes(key)||SYNC_FIELDS.has(key))continue;
    if(!equal(before?.[key],after?.[key]))names.push(key);
  }
  return names.slice(0,32);
}

function coreSnapshot(record){
  if(record===undefined)return null;
  const allowed=['kind','date','amount','title','status','accountId','toAccountId','categoryId','refundOf','name','openingBalance','type','icon','monthlyBudget','periodStartDay','theme'];
  const out={};
  for(const key of allowed){
    const value=record?.[key];
    if(value===undefined||value===null||value==='')continue;
    out[key]=typeof value==='string'?value.slice(0,120):value;
  }
  return out;
}

function buildEvent({idFactory,entityType,entityId,action,before,after,actorUid,deviceId,now}){
  return {
    id:idFactory(),entityType,entityId:String(entityId),action,
    actorUid:actorUid||'guest',deviceId:deviceId||'unknown',createdAt:now,
    changedFields:changedFieldNames(before,after),before:coreSnapshot(before),after:coreSnapshot(after)
  };
}

/**
 * Adds revision/timestamp metadata and append-only audit events for one local mutation.
 * Call before saving the new IndexedDB snapshot. Set recordEvents=false when merely
 * rebasing already-recorded local changes on top of a newer server snapshot.
 */
export function prepareLedgerMutation(before,after,{
  now=new Date().toISOString(),actorUid='guest',deviceId='unknown',
  idFactory=()=>crypto.randomUUID(),recordEvents=true
}={}){
  validateBackup(before);validateBackup(after);
  const output=structuredClone(after),events=[],outputTombstones=ensureTombstones(output);
  for(const collection of COLLECTIONS){
    const previous=new Map(before[collection].map(item=>[item.id,item])),knownTombstones=before.tombstones?.[collection]||{};
    for(const item of output[collection]){
      const old=previous.get(item.id),oldTombstone=old?null:knownTombstones[item.id];previous.delete(item.id);
      if(collection==='transactions'&&item.attachment&&!item.attachmentPath&&!item.attachmentUploadId)item.attachmentUploadId=crypto.randomUUID();
      // Local-only receipt bytes still count as a mutation even though they are
      // stripped before Firestore serialization; _writeDiff replaces them with a path.
      if(equal(old,item)){delete outputTombstones[collection][item.id];continue;}
      item.createdAt=old?.createdAt||oldTombstone?.createdAt||item.createdAt||now;
      item.updatedAt=now;
      item.revision=(Number(old?.revision)||Number(oldTombstone?.revision)||0)+1;
      delete outputTombstones[collection][item.id];
      if(recordEvents)events.push(buildEvent({idFactory,entityType:eventEntity[collection],entityId:item.id,action:old?'update':'create',before:old,after:item,actorUid,deviceId,now}));
    }
    for(const old of previous.values()){
      outputTombstones[collection][old.id]={
        id:old.id,deleted:true,revision:(Number(old.revision)||0)+1,
        createdAt:old.createdAt||now,updatedAt:now,
        ...(old.sourceImportKey?{sourceImportKey:old.sourceImportKey}:{})
      };
      if(recordEvents)events.push(buildEvent({idFactory,entityType:eventEntity[collection],entityId:old.id,action:'delete',before:old,after:undefined,actorUid,deviceId,now}));
    }
  }

  const metaChanged=!equal(before.settings,output.settings)||!equal(before.budgets,output.budgets);
  if(metaChanged){
    output.metaRevision=(Number(before.metaRevision)||0)+1;
    output.metaCreatedAt=before.metaCreatedAt||now;
    output.metaUpdatedAt=now;
    if(recordEvents){
      for(const name of ['settings','budgets'])for(const key of new Set([...Object.keys(before[name]||{}),...Object.keys(output[name]||{})])){
        if(equal(before[name]?.[key],output[name]?.[key]))continue;
        events.push(buildEvent({idFactory,entityType:name==='settings'?'setting':'budget',entityId:key,action:before[name]?.[key]===undefined?'create':output[name]?.[key]===undefined?'delete':'update',before:{[key]:before[name]?.[key]},after:{[key]:output[name]?.[key]},actorUid,deviceId,now}));
      }
    }
  }
  output.events=[...(output.events||[]),...events].slice(-EVENT_LIMIT);
  validateBackup(output);
  return output;
}

/** Generate document-level writes. Events are append-only and deletes become tombstones. */
export function changedDocuments(before,after,{now=new Date().toISOString()}={}){
  validateBackup(before);validateBackup(after);
  const changes=[];
  for(const collection of COLLECTIONS){
    const previous=new Map(before[collection].map(item=>[item.id,forCloudDocument(item)])),knownTombstones=before.tombstones?.[collection]||{};
    for(const item of after[collection]){
      const value=forCloudDocument(item),old=previous.get(item.id)||knownTombstones[item.id];
      if(!equal(old,value))changes.push({collection,action:'set',id:item.id,data:value,expectedRevision:Number(old?.revision)||0,expectedExists:Boolean(old)});
      previous.delete(item.id);
    }
    for(const [id,old] of previous){
      const tombstone=after.tombstones?.[collection]?.[id]||{
        id,deleted:true,revision:(Number(old.revision)||0)+1,createdAt:old.createdAt||now,updatedAt:now,
        ...(old.sourceImportKey?{sourceImportKey:old.sourceImportKey}:{})
      };
      changes.push({collection,action:'set',id,expectedRevision:Number(old.revision)||0,expectedExists:true,data:forCloudDocument(tombstone)});
    }
  }
  const beforeMeta={settings:before.settings,budgets:before.budgets,revision:Number(before.metaRevision)||0};
  const afterMeta={settings:after.settings,budgets:after.budgets,revision:Number(after.metaRevision)||0};
  if(!equal(beforeMeta,afterMeta))changes.push({
    collection:'ledger',action:'set',id:'config',expectedRevision:Number(before.metaRevision)||0,expectedExists:Boolean(before.metaExists),
    data:{version:1,schemaVersion:2,settings:after.settings,budgets:after.budgets,initialized:true,
      revision:Number(after.metaRevision)||1,createdAt:after.metaCreatedAt||now,updatedAt:after.metaUpdatedAt||now}
  });
  const knownEvents=new Set((before.events||[]).map(event=>event.id));
  for(const event of after.events||[]){
    if(!knownEvents.has(event.id))changes.push({collection:'events',action:'create',id:event.id,data:forCloudDocument(event),expectedRevision:null,expectedExists:false});
  }
  return changes;
}

export function reconstructLedger(meta,groups){
  const state=initialState();
  if(meta){
    if(meta.version!==1||![undefined,1,2].includes(meta.schemaVersion))throw new Error('지원하지 않는 클라우드 가계부 버전입니다.');
    state.settings={...state.settings,...meta.settings};
    state.budgets=meta.budgets||{};
    state.metaRevision=Number(meta.revision)||0;
    state.metaExists=true;
    state.metaCreatedAt=meta.createdAt||null;
    state.metaUpdatedAt=meta.updatedAt||null;
  }
  ensureTombstones(state);
  for(const collection of COLLECTIONS){
    state[collection]=[];
    for(const item of groups[collection]??[]){
      if(item.deleted)state.tombstones[collection][item.id]=item;
      else state[collection].push(item);
    }
  }
  const eventMap=new Map();
  for(const event of [...(meta?.events||[]),...(groups.events||[])])if(event?.id)eventMap.set(event.id,event);
  state.events=[...eventMap.values()].sort((a,b)=>String(a.createdAt).localeCompare(String(b.createdAt))).slice(-EVENT_LIMIT);
  validateBackup(state);
  return state;
}

const importKey=(source,collection,record)=>`${source}:${collection}:${record.id}:${record.createdAt||''}`;

/**
 * Import into a signed-in ledger without replacing cloud records. sourceImportKey
 * makes repeated previews/imports idempotent even when an ID collision required a new ID.
 */
export function mergeGuestLedger(remote,guest,newId=()=>crypto.randomUUID(),source='guest-main-v1'){
  validateBackup(remote);validateBackup(guest);
  const result=structuredClone(remote),mapping={accounts:new Map(),categories:new Map()};
  ensureTombstones(result);
  const summary={accounts:0,categories:0,transactions:0,skipped:0};
  for(const collection of ['accounts','categories']){
    const existing=new Map(result[collection].map(value=>[value.id,value]));
    for(const record of guest[collection]){
      const key=importKey(source,collection,record);
      const already=result[collection].find(value=>value.sourceImportKey===key);
      if(already){mapping[collection].set(record.id,already.id);summary.skipped++;continue;}
      const deleted=Object.values(result.tombstones[collection]).find(value=>value.sourceImportKey===key);
      if(deleted){mapping[collection].set(record.id,deleted.id);summary.skipped++;continue;}
      let nextId=record.id;
      if(existing.has(nextId)||result.tombstones[collection][nextId]){
        if(equal(comparableRecord(existing.get(nextId)),comparableRecord(record))){mapping[collection].set(record.id,nextId);continue;}
        nextId=newId();
      }
      const copy={...structuredClone(record),id:nextId,sourceImportKey:key};
      if(nextId!==record.id)copy.name=`${copy.name} (기기 가져옴)`.slice(0,32);
      result[collection].push(copy);existing.set(nextId,copy);mapping[collection].set(record.id,nextId);summary[collection]++;
    }
  }

  const transactionIds=new Set([...result.transactions.map(transaction=>transaction.id),...Object.keys(result.tombstones.transactions)]);
  const importedMap=new Map();
  for(const transaction of guest.transactions){
    const key=importKey(source,'transactions',transaction);
    const already=result.transactions.find(value=>value.sourceImportKey===key);
    if(already){importedMap.set(transaction.id,already.id);summary.skipped++;continue;}
    const deleted=Object.values(result.tombstones.transactions).find(value=>value.sourceImportKey===key);
    if(deleted){importedMap.set(transaction.id,deleted.id);summary.skipped++;continue;}
    let nextId=transaction.id;
    if(transactionIds.has(nextId)){
      const old=result.transactions.find(value=>value.id===nextId);
      if(equal(comparableRecord(old),comparableRecord(transaction))){importedMap.set(transaction.id,nextId);summary.skipped++;continue;}
      nextId=newId();
    }
    importedMap.set(transaction.id,nextId);transactionIds.add(nextId);
  }
  for(const transaction of guest.transactions){
    const targetId=importedMap.get(transaction.id);
    if(!targetId||result.transactions.some(value=>value.id===targetId))continue;
    const copy={...structuredClone(transaction),id:targetId,sourceImportKey:importKey(source,'transactions',transaction)};
    copy.accountId=mapping.accounts.get(copy.accountId)||copy.accountId;
    if(copy.toAccountId)copy.toAccountId=mapping.accounts.get(copy.toAccountId)||copy.toAccountId;
    if(copy.categoryId)copy.categoryId=mapping.categories.get(copy.categoryId)||copy.categoryId;
    if(copy.refundOf)copy.refundOf=importedMap.get(copy.refundOf)||copy.refundOf;
    result.transactions.push(copy);summary.transactions++;
  }
  validateBackup(result);
  return {ledger:result,imported:summary.transactions,skipped:summary.skipped,summary};
}

function reconcile(baseline,local,remote,resolutions={}){
  validateBackup(baseline);validateBackup(local);validateBackup(remote);
  const output=structuredClone(remote),conflicts=[],conflictDetails=[];ensureTombstones(output);
  const choose=(path,old,mine,theirs,applyMine)=>{
    const choice=resolutions[path];
    if(choice==='mine'){applyMine();return;}
    if(choice==='server')return;
    conflicts.push(path);conflictDetails.push({path,base:clone(old),mine:clone(mine),server:clone(theirs)});
  };
  for(const collection of COLLECTIONS){
    const baseMap=new Map(baseline[collection].map(item=>[item.id,item]));
    const localMap=new Map(local[collection].map(item=>[item.id,item]));
    const remoteMap=new Map(remote[collection].map(item=>[item.id,item]));
    const outMap=new Map(output[collection].map(item=>[item.id,item]));
    for(const id of new Set([...baseMap.keys(),...localMap.keys()])){
      const old=baseMap.get(id),mine=localMap.get(id),theirs=remoteMap.get(id);
      if(equal(old,mine))continue;
      if(equivalentRecords(mine,theirs)){
        if(theirs===undefined)outMap.delete(id);else outMap.set(id,clone(theirs));
        continue;
      }
      const applyMine=()=>{
        if(mine===undefined){outMap.delete(id);const deleted=local.tombstones?.[collection]?.[id];if(deleted)output.tombstones[collection][id]=clone(deleted);}
        else {outMap.set(id,clone(mine));delete output.tombstones[collection][id];}
      };
      if(!equal(old,theirs)&&!equal(mine,theirs))choose(`${collection}/${id}`,old,mine,theirs,applyMine);
      else applyMine();
    }
    output[collection]=[...outMap.values()];
  }
  for(const name of ['settings','budgets']){
    for(const key of new Set([...Object.keys(baseline[name]||{}),...Object.keys(local[name]||{})])){
      const old=baseline[name]?.[key],mine=local[name]?.[key],theirs=remote[name]?.[key];
      if(equal(old,mine))continue;
      const applyMine=()=>mine===undefined?delete output[name][key]:output[name][key]=clone(mine);
      if(!equal(old,theirs)&&!equal(mine,theirs))choose(`${name}/${key}`,old,mine,theirs,applyMine);
      else applyMine();
    }
  }
  const events=new Map((remote.events||[]).map(event=>[event.id,event]));
  const baselineEvents=new Set((baseline.events||[]).map(event=>event.id));
  for(const event of local.events||[])if(!events.has(event.id)){
    // The server listener is intentionally bounded. An event present in the last
    // confirmed baseline but absent from the recent window is already persisted.
    if(baselineEvents.has(event.id))continue;
    if(resolutions[eventPath(event)]==='server')continue;
    events.set(event.id,event);
  }
  output.events=[...events.values()].sort((a,b)=>String(a.createdAt).localeCompare(String(b.createdAt))).slice(-EVENT_LIMIT);
  validateBackup(output);
  return {ledger:conflicts.length&&!Object.keys(resolutions).length?null:output,conflicts,conflictDetails};
}

/** Three-way offline reconciliation; unresolved same-field/entity edits never overwrite. */
export function rebaseOfflineChanges(baseline,local,remote){return reconcile(baseline,local,remote);}

/**
 * Rebase a later same-device queued edit over the acknowledged result of the
 * preceding write. This preserves a Storage path assigned after the caller had
 * already saved its next local snapshot.
 */
export function rebaseQueuedMutation(baseline,local,acknowledged){
  const base=structuredClone(baseline),mine=structuredClone(local);
  const acknowledgedTransactions=new Map(acknowledged.transactions.map(item=>[item.id,item]));
  const mineTransactions=new Map(mine.transactions.map(item=>[item.id,item]));
  for(const old of base.transactions){
    const server=acknowledgedTransactions.get(old.id),next=mineTransactions.get(old.id);
    if(!server||!next||old.attachmentPath||!server.attachmentPath||!old.attachment||server.attachment!==old.attachment)continue;
    old.attachmentPath=server.attachmentPath;
    if(!next.attachmentPath&&next.attachment===old.attachment)next.attachmentPath=server.attachmentPath;
  }
  return rebaseOfflineChanges(base,mine,acknowledged);
}

/** Apply the user's explicit per-conflict mine/server choices. */
export function resolveLedgerConflicts(baseline,local,remote,resolutions){
  const result=reconcile(baseline,local,remote,resolutions);
  if(result.conflicts.length)throw new Error('모든 충돌에 대한 선택이 필요합니다.');
  return result.ledger;
}
