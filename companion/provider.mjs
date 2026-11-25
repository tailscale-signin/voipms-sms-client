import { check, number, peerNumber } from './store.mjs';

const endpoint = 'https://voip.ms/api/v1/rest.php';
const easternDay = new Intl.DateTimeFormat('en-CA',{timeZone:'America/New_York',year:'numeric',month:'2-digit',day:'2-digit'});
const verification = /\b(?:verification|login|security|passcode|one[- ]time|auth(?:entication)?|verification code|code)\b/i;
export function displayBody(body) {
  return verification.test(body) && /\b\d{4,8}\b/.test(body) ? 'Verification code hidden on companion devices.' : body;
}
export function providerClient(email,password,fetchRequest=fetch) {
  const cache=new Map();
  async function request(method,args) {
    const form=new FormData();
    for (const [key,value] of Object.entries({api_username:email,api_password:password,method,...args})) form.set(key,value);
    const response=await fetchRequest(endpoint,{method:'POST',body:form,redirect:'error',signal:AbortSignal.timeout(12_000)});
    check(response.ok,502,'VoIP.ms is unavailable.');
    return response.json();
  }
  async function history(did) {
    const now=new Date();
    const data=await request('getMMS',{did,all_messages:'1',timezone:'-5',limit:'5000',
      from:easternDay.format(new Date(now.getTime()-29*86_400_000)),to:easternDay.format(now)});
    if (data.status==='no_sms' || data.status==='no_mms') return {messages:[],capped:false};
    check(data.status==='success' && data.sms && typeof data.sms==='object',502,'VoIP.ms history could not be loaded.');
    const rows=Array.isArray(data.sms)?data.sms:[data.sms];
    const messages=[];
    for (const row of rows) {
      if (!row || number(row.did)!==did || !peerNumber(row.contact) || !['0','1'].includes(String(row.type))) continue;
      if (!/^\d+$/.test(String(row.id)) || !/^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d$/.test(row.date)) continue;
      const media=[row.col_media1,row.col_media2,row.col_media3,...(Array.isArray(row.media)?row.media:[])];
      messages.push({id:String(row.id),peer:peerNumber(row.contact),incoming:String(row.type)==='1',date:row.date,
        body:displayBody(String(row.message??'')),attachments:new Set(media.filter(v=>typeof v==='string' && v.trim())).size});
    }
    messages.sort((a,b)=>a.date.localeCompare(b.date) || a.id.length-b.id.length || a.id.localeCompare(b.id));
    return {messages,capped:rows.length>=5000};
  }
  return {
    history(did) {
      const hit=cache.get(did);
      if (hit && hit.until>Date.now()) return hit.promise;
      const promise=history(did).catch(err=>{ if (cache.get(did)?.promise===promise) cache.delete(did); throw err; });
      cache.set(did,{until:Date.now()+12_000,promise});
      return promise;
    },
    async send(did,peer,body) {
      const result=await request('sendSMS',{did,dst:peer,message:body});
      if (result.status==='success' && /^\d+$/.test(String(result.sms)) && Number(result.sms)>0) {
        cache.delete(did);
        return {state:'accepted',providerId:String(result.sms)};
      }
      const declined=['invalid_credentials','invalid_did','invalid_dst','limit_reached','message_empty','missing_sms','sms_toolong','sms_failed'];
      return {state:declined.includes(result.status)?'declined':'unconfirmed',providerId:null};
    }
  };
}
