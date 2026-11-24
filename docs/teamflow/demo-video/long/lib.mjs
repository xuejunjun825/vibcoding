import fs from 'node:fs';
export const GW='http://127.0.0.1:8080';
const FR='/tmp/rec/frames/';
const META='/tmp/rec/meta/';
let seq=0; export const beats=[];
async function tryFetch(url,opts,tries=4){let last;for(let i=0;i<tries;i++){try{return await fetch(url,opts);}catch(e){last=e;await new Promise(r=>setTimeout(r,800*(i+1)));}}throw last;}
async function login(u,p){const r=await tryFetch(GW+'/api/req/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tenantCode:'t1',username:u,password:p})});return r.json();}
export async function api(token,method,path,body){const r=await tryFetch(GW+path,{method,headers:{'Content-Type':'application/json','Authorization':'Bearer '+token,'X-Tenant-Id':'1'},body:body?JSON.stringify(body):undefined});let b=null;try{b=await r.json()}catch(e){};return {s:r.status,b};}
export async function roles(){const map={biz1:['biz1','Passw0rd!'],pm:['pm','Passw0rd!'],tl1:['tl1','Passw0rd!'],dev1:['dev1','Passw0rd!'],qa1:['qa1','Passw0rd!']};const S={};for(const k in map){const r=await login(...map[k]);S[k]={token:r.token,tenantId:String(r.tenantId),tenantCode:r.tenantCode,user:r.user,permissions:r.permissions.map(x=>x.code),defaultRoute:r.defaultRoute};}return S;}
export function openCDP(){
  return (async()=>{
    const ver=await (await fetch('http://127.0.0.1:9222/json/version')).json();
    const ws=new WebSocket(ver.webSocketDebuggerUrl);let id=0;const pend=new Map();
    ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}};
    await new Promise(r=>ws.onopen=r);
    const send=(m,p={},sid)=>new Promise(res=>{const i=++id;pend.set(i,res);ws.send(JSON.stringify({id:i,method:m,params:p,sessionId:sid}));});
    const {result:{targetId}}=await send('Target.createTarget',{url:'about:blank'});
    const {result:{sessionId}}=await send('Target.attachToTarget',{targetId,flatten:true});
    await send('Page.enable',{},sessionId);await send('Runtime.enable',{},sessionId);
    await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false},sessionId);
    const ev=async(expr)=>{const r=await send('Runtime.evaluate',{expression:expr,awaitPromise:true,returnByValue:true},sessionId);return r.result?.result?.value;};
    const sleep=ms=>new Promise(r=>setTimeout(r,ms));
    const setRole=async(S)=>{await send('Page.navigate',{url:'http://127.0.0.1:5173/login'},sessionId);await sleep(600);await ev(`sessionStorage.setItem('devops.session', ${JSON.stringify(JSON.stringify(S))})`);};
    const go=async(path,wait=2400)=>{await send('Page.navigate',{url:'http://127.0.0.1:5173'+path},sessionId);await sleep(wait);};
    const shot=async(name)=>{const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);fs.writeFileSync(FR+name+'.png',Buffer.from(data,'base64'));};
    const box=async(sel)=>ev(`(()=>{const el=document.querySelector(${JSON.stringify(sel)});if(!el)return null;const r=el.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};})()`);
    const boxOfText=async(text)=>ev(`(()=>{const cands=[...document.querySelectorAll('button,.el-button')].filter(b=>b.innerText&&b.innerText.trim()===${JSON.stringify(text)});const el=cands[cands.length-1];if(!el)return null;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};})()`);
    const clickText=async(text)=>{const r=await ev(`(()=>{const cands=[...document.querySelectorAll('button,.el-button')].filter(b=>b.innerText&&b.innerText.trim()===${JSON.stringify(text)});if(!cands.length)return 'notfound';const el=cands[cands.length-1];el.scrollIntoView({block:'center'});el.click();return 'ok';})()`);await sleep(900);return r;};
    const hasMsgBox=async()=>ev(`!!document.querySelector('.el-message-box, .el-dialog')`);
    const clickPrimary=async()=>{const r=await ev(`(()=>{const b=document.querySelector('.el-message-box__btns .el-button--primary')||document.querySelector('.el-dialog__footer .el-button--primary');if(!b)return 'notfound';b.click();return 'ok';})()`);await sleep(1400);return r;};
    const type=async(label,value)=>ev(`(()=>{const items=[...document.querySelectorAll('.el-form-item')];const it=items.find(i=>{const l=i.querySelector('.el-form-item__label');return l&&l.innerText.trim().replace(/[*：:]/g,'').trim()===${JSON.stringify(label)};});if(!it)return 'nolabel';const el=it.querySelector('textarea,input');if(!el)return 'noinput';const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(el,${JSON.stringify(value)});el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()`);
    const realClick=async(x,y)=>{const a=Math.round(x),b=Math.round(y);await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:a,y:b},sessionId);await sleep(60);await send('Input.dispatchMouseEvent',{type:'mousePressed',x:a,y:b,button:'left',clickCount:1},sessionId);await sleep(50);await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:a,y:b,button:'left',clickCount:1},sessionId);await sleep(120);};
    const realClickEl=async(sel)=>{const b=await ev(`(()=>{const e=document.querySelector(${JSON.stringify(sel)});if(!e)return null;const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:r.width,h:r.height};})()`);if(!b||!b.w)return 'noel';await realClick(b.x,b.y);return 'ok';};
    const pickSelect=async(label,option)=>{
      const b=await ev(`(()=>{const items=[...document.querySelectorAll('.el-form-item')];const it=items.find(i=>{const l=i.querySelector('.el-form-item__label');return l&&l.innerText.trim().replace(/[*：:]/g,'').trim()===${JSON.stringify(label)};});if(!it)return null;const s=it.querySelector('.el-select__wrapper')||it.querySelector('.el-select');if(!s)return null;s.scrollIntoView({block:'center'});const r=s.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
      if(!b)return 'noel';
      await realClick(b.x,b.y);
      await sleep(900);
      const ob=await ev(`(()=>{const opts=[...document.querySelectorAll('.el-select-dropdown__item')].filter(o=>{const r=o.getBoundingClientRect();return r.height>0&&r.width>0&&o.innerText.includes(${JSON.stringify(option)})});if(!opts.length)return null;const el=opts[0];const r=el.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
      if(!ob)return 'noopt';
      await realClick(ob.x,ob.y);
      await sleep(500);
      return 'ok';
    };
    const clickConfirm=async()=>{
      const b=await ev(`(()=>{
        const vis=[...document.querySelectorAll('.el-overlay,.el-message-box,.el-dialog')].filter(e=>{const r=e.getBoundingClientRect();const cs=getComputedStyle(e);return r.width>50&&r.height>30&&cs.display!=='none'&&cs.visibility!=='hidden';});
        for(const dlg of vis){
          const btns=[...dlg.querySelectorAll('button')].filter(x=>{const r=x.getBoundingClientRect();return r.width>0&&/确认|确定|发起|提交/.test(x.innerText||'');});
          if(btns.length){const el=btns[btns.length-1];const r=el.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,t:el.innerText.trim()};}
        }
        return null;})()`);
      if(!b)return 'nodialog';
      await realClick(b.x,b.y); await sleep(1400); return 'ok:'+b.t;
    };
    const clickRadio=async(text)=>ev(`(()=>{const r=[...document.querySelectorAll('.el-radio-button')].find(x=>x.innerText.trim()===${JSON.stringify(text)});if(!r)return 'notfound';r.click();return 'ok';})()`);
    const bodyText=async()=>ev('document.body.innerText.slice(0,2500)');
    const toast=async()=>ev(`(document.querySelector('.el-message')?.innerText||'')`);
    // beat = 记录一步：前(可选高亮) + 后
    const beat=async({role,title,sub,before,hot,after,note})=>{
      const i=String(++seq).padStart(2,'0');
      const b={i,role,title,sub:sub||'',hot:hot||null,note:note||'',files:[]};
      if(before){await shot(i+'a-'+(before));b.files.push(i+'a-'+before);}
      if(after){await shot(i+'b-'+(after));b.files.push(i+'b-'+after);}
      beats.push(b);fs.writeFileSync(META+'beats.json',JSON.stringify(beats,null,2));
      return b;
    };
    return {send,ev,sleep,setRole,go,shot,box,boxOfText,clickText,hasMsgBox,clickPrimary,type,pickSelect,clickRadio,realClick,realClickEl,clickConfirm,bodyText,toast,beat,close:()=>ws.close()};
  })();
}
