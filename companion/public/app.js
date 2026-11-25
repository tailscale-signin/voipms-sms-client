'use strict';

const $ = id => document.getElementById(id);
const ui = {me:null,did:'',peer:'',version:0,saved:'',owner:null,view:0,sending:false};
const initial = new URLSearchParams(location.search);
// Navigation parameters are consumed immediately, including when initial pairing is needed.
history.replaceState(null,'','/');

function status(text) {
  $('status').textContent=text;
  const modal=document.querySelector('dialog[open]');
  if (modal) {
    let output=modal.querySelector('[data-status]');
    if (!output) { output=document.createElement('p'); output.dataset.status='1'; output.setAttribute('role','status'); modal.append(output); }
    output.textContent=text;
  }
}
function textElement(tag,text) {
  const node=document.createElement(tag);
  node.textContent=text;
  return node;
}
function catchError(err) { status(err.message || 'Request failed.'); }
const on=(id,event,action)=>$(id).addEventListener(event,e=>{
  if(event==='submit') e.preventDefault();
  Promise.resolve().then(()=>action(e)).catch(catchError);
});
function locked() { return ui.sending || (ui.peer && $('body').value!==ui.saved && !window.confirm('Leave this unsaved draft?')); }
function loginView() {
  ui.view++; ui.me=null; ui.peer=''; ui.saved=''; ui.attempt=null; $('body').value='';
  sessionStorage.removeItem('companion-send');
  $('messages').replaceChildren(); $('notes').replaceChildren(); $('threads').replaceChildren(); $('attempts').replaceChildren();
  $('peer').textContent=''; $('threadLine').textContent=''; $('line').replaceChildren();
  $('generated').textContent=''; $('deviceList').replaceChildren(); $('signedAs').textContent='';
  document.querySelectorAll('dialog[open]').forEach(d=>d.close());
  $('pairing').hidden=false; $('inbox').hidden=true; $('devices').hidden=true;
}
async function api(path,values,post=false) {
  const query=!post && values?'?'+new URLSearchParams(values):'';
  const response=await fetch('/api/'+path+query,post?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values??{})}:{});
  const data=await response.json();
  if (!response.ok) {
    if (response.status===401) loginView();
    if ([403,404].includes(response.status) && path==='thread' && ui.did===values?.did && ui.peer===values?.peer) clearThread();
    throw new Error(data.error??'Request failed.');
  }
  return data;
}
const role=()=>ui.me?.lines.find(l=>l.did===ui.did)?.role;
const context=()=>({did:ui.did,peer:ui.peer});
function scrollMessages(){ const panel=$('messages'); panel.scrollTop=panel.scrollHeight; }
async function threads() {
  const did=ui.did,ver=ui.view;
  const result=await api('threads',{did});
  if (ver!==ui.view || did!==ui.did) return;
  const list=$('threads'); list.replaceChildren();
  for(const entry of result.threads) {
    const button=document.createElement('button');
    button.type='button';button.className='contact'+(ui.peer===entry.peer?' active':'');
    button.append(textElement('strong',entry.peer),textElement('small',entry.body || 'Shared contact'));
    if (entry.owner) button.append(textElement('small','Claimed by '+entry.owner));
    button.addEventListener('click',()=>{ if (!locked()) openThread(entry.peer).catch(catchError); });
    list.append(button);
  }
  if (!result.threads.length) list.append(textElement('small','No shared conversations in this period.'));
  $('historyScope').textContent=result.capped?'Recent 30 days · provider result limit reached':'Recent 30 days';
}
function clearThread() {
  ui.view++; ui.peer=''; ui.saved=''; ui.version=0; $('body').value=''; $('note').value='';
  $('thread').hidden=true; $('empty').hidden=false; $('inbox').classList.remove('open');
  $('messages').replaceChildren(); $('notes').replaceChildren(); $('attempts').replaceChildren();
}
async function openThread(peer,background=false) {
  const did=ui.did,version=++ui.view;
  const data=await api('thread',{did,peer});
  if (ui.view!==version || ui.did!==did) return;
  if (background && (document.activeElement===$('body') || $('body').value!==ui.saved || $('note').value || ui.sending)) return;
  ui.peer=peer; ui.owner=data.owner; ui.saved=data.draft.body; ui.version=data.draft.version;
  $('peer').textContent=peer; $('threadLine').textContent='From '+did+(data.owner?' · Claimed by '+data.owner:'');
  $('messages').replaceChildren();
  for (const message of data.messages) {
    const bubble=document.createElement('article');
    bubble.className='bubble'+(message.incoming?'':' out');
    bubble.append(textElement('p',message.body));
    if(message.attachments) bubble.append(textElement('p',message.attachments+' media attachment'+(message.attachments===1?'':'s')));
    bubble.append(textElement('small',message.date+' ET'+(message.author?' · '+message.author:'')));
    $('messages').append(bubble);
  }
  if(!data.messages.length) $('messages').append(textElement('small','No messages in the last 30 days.'));
  $('attempts').replaceChildren();
  for (const attempt of data.attempts) {
    const states={accepted:'Accepted by VoIP.ms',unconfirmed:'Unconfirmed · check history before sending again',declined:'Declined by VoIP.ms'};
    $('attempts').append(textElement('p',new Date(attempt.created).toLocaleString()+' · '+attempt.user+' · '+states[attempt.state]));
  }
  $('notes').replaceChildren();
  for (const note of data.notes) {
    const row=document.createElement('article'); row.className='note';
    row.append(textElement('small',note.user+' · '+new Date(note.created).toLocaleString()),textElement('p',note.body));
    $('notes').append(row);
  }
  const writable=role()!=='read', available=!data.owner || data.owner===ui.me.user;
  $('body').value=ui.saved; $('note').value=''; $('compose').hidden=!writable;
  $('claim').hidden=!writable; $('claim').disabled=!available;
  $('claim').querySelector('span').textContent=data.owner===ui.me.user?'Release':'Claim';
  $('sendButton').disabled=!available || !/^\d{10}$/.test(peer);
  $('noteForm').hidden=!writable; $('unshare').hidden=role()!=='manage';
  $('empty').hidden=true; $('thread').hidden=false; $('inbox').classList.add('open'); scrollMessages();
  $('threads').querySelectorAll('button').forEach(b=>b.classList.toggle('active',b.querySelector('strong').textContent===peer));
}
async function start() {
  const me=await api('me');
  ui.me=me;
  $('pairing').hidden=true; $('inbox').hidden=false; $('devices').hidden=false;
  $('line').replaceChildren();
  me.lines.forEach(line=>{
    const option=textElement('option',line.did+(line.wholeLine?' · work line':' · selected contacts'));
    option.value=line.did; $('line').append(option);
  });
  ui.did=me.lines.some(l=>l.did===initial.get('did'))?initial.get('did'):me.lines[0].did;
  $('line').value=ui.did;
  clearThread(); await threads();
  if(initial.get('did')===ui.did && initial.get('peer')) {
    const peer=initial.get('peer'); initial.delete('peer'); await openThread(peer);
  }
}
on('pairForm','submit',async()=>{
  await api('pair',{code:$('code').value.trim(),label:$('label').value.trim()},true);
  $('code').value=''; status(''); await start();
});
on('line','change',async()=>{
  if(locked()){ $('line').value=ui.did; return; }
  ui.did=$('line').value; clearThread(); await threads();
});
on('back','click',async()=>{ if(!locked()) {clearThread();await threads();} });
on('refresh','click',async()=>{ if(locked())return; await threads(); if(ui.peer)await openThread(ui.peer); status('History refreshed.'); });
on('newContact','click',()=>{
  if(locked())return; $('contact').value='';$('share').checked=false;
  $('consent').hidden=role()!=='manage';$('contactDialog').showModal();
});
on('contactForm','submit',async()=>{
  const digits=$('contact').value.replace(/[()+\s.-]/g,'');
  const peer=digits.length===11 && digits.startsWith('1')?digits.slice(1):digits;
  if(role()==='manage' && $('share').checked) await api('share',{did:ui.did,peer,enabled:true},true);
  await openThread(peer); $('contactDialog').close();await threads();status('');
});
on('draft','click',async()=>{
  const ver=ui.view,body=$('body').value;
  const result=await api('draft',{...context(),version:ui.version,body},true);
  if(ver===ui.view){ui.version=result.version;ui.saved=body;status('Draft saved on your paired devices.');}
});
on('compose','submit',async()=>{
  if(ui.sending || !$('body').value.trim())return;
  ui.sending=true;$('sendButton').disabled=true;
  const body=$('body').value,ctx=context(),view=ui.view;
  // Preserve the same submission identifier after a transport error. Explicit new text creates a new request.
  try {
    const payload=new TextEncoder().encode(JSON.stringify([ui.me.user,ctx.did,ctx.peer,body]));
    const match=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',payload)),v=>v.toString(16).padStart(2,'0')).join('');
    try{ui.attempt=JSON.parse(sessionStorage.getItem('companion-send'));}catch{ui.attempt=null;}
    if(!ui.attempt || ui.attempt.match!==match) ui.attempt={match,nonce:crypto.randomUUID()};
    sessionStorage.setItem('companion-send',JSON.stringify(ui.attempt));
    const result=await api('send',{...ctx,body,nonce:ui.attempt.nonce,version:ui.version},true);
    if (view!==ui.view) return;
    if (result.state==='accepted') {
      ui.attempt=null;sessionStorage.removeItem('companion-send');await openThread(ctx.peer);await threads();status('VoIP.ms accepted the SMS.');
    } else {
      await openThread(ctx.peer);await threads();
      $('body').value=body;
      status(result.state==='declined'?'VoIP.ms declined this SMS. Edit the draft before retrying.':'Submission unconfirmed. Check provider history before another attempt.');
    }
  } finally {ui.sending=false;if(view===ui.view)$('sendButton').disabled=false;}
});
on('claim','click',async()=>{
  if(locked())return;
  await api('claim',{...context(),wanted:ui.owner!==ui.me.user},true);await openThread(ui.peer);await threads();
});
on('noteForm','submit',async()=>{
  if(locked())return;
  await api('note',{...context(),body:$('note').value},true);await openThread(ui.peer);status('Team note saved.');
});
on('unshare','click',async()=>{
  if(!window.confirm('Remove this contact from all companion devices on this line? Shared notes and drafts will be deleted.'))return;
  await api('share',{...context(),enabled:false},true);clearThread();await threads();status('Contact excluded.');
});
async function devices() {
  const me=await api('me'); $('deviceList').replaceChildren();
  $('signedAs').textContent='Signed in as '+me.user;
  for(const device of me.devices){
    const row=document.createElement('div');row.className='device';
    row.append(textElement('strong',device.label+(device.id===me.id?' (this device)':'')));
    const button=textElement('button','Revoke');
    button.addEventListener('click',async()=>{
      try {
        await api('revoke',{id:device.id},true);
        if(device.id===me.id){loginView();status('This device was signed out.');}
        else await devices();
      } catch(err){catchError(err);}
    }); row.append(button);$('deviceList').append(row);
  }
}
on('devices','click',async()=>{await devices();$('generated').textContent='';$('deviceDialog').showModal();});
on('newCode','click',async()=>{const {code}=await api('pair-code',{},true);$('generated').textContent=code+' · valid for 10 minutes, one device';});
document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.close).close()));
window.addEventListener('beforeunload',e=>{if(ui.peer && ui.saved!==$('body').value){e.preventDefault();e.returnValue='';}});
let updating=false;
setInterval(async()=>{
  if(!ui.me || document.hidden || document.querySelector('dialog[open]') || ui.sending || updating)return;
  updating=true;
  try {
    await threads();
    if(ui.peer && document.activeElement!==$('body') && $('body').value===ui.saved && !$('note').value) await openThread(ui.peer,true);
  } catch(err) {
    if(ui.me)status(err.message);
  } finally { updating=false; }
},30_000);
start().catch(err=>{if($('pairing').hidden)catchError(err);});
