import fs from 'node:fs';
const beats=JSON.parse(fs.readFileSync('/tmp/rec/long/beats.json','utf8'));
const FR='/tmp/rec/long/frames/';
const items=[{kind:'title',h1:'一条需求的完整旅程',p:'从业务方提报到验收关闭 · 6 个角色真实操作',tag:'24 步 · 全流程在线',dur:4.5}];
for (const b of beats){
  const a=FR+b.file, after=FR+b.after;
  const hasAfter = b.after && fs.existsSync(after) && b.after!==b.file;
  items.push({kind:'stage',img:a,role:b.role,chap:'第 '+b.i+' 步',text:b.title.replace(/^[①-⑳㉑-㉟]+\s*/,''),sub:b.sub||'',dur:2.2});
  if (hasAfter) items.push({kind:'stage',img:after,role:b.role,chap:'第 '+b.i+' 步 · 结果',text:b.title.replace(/^[①-⑳㉑-㉟]+\s*/,''),sub:b.sub||'',dur:2.2});
}
items.push({kind:'title',h1:'需求闭环：CLOSED',p:'父需求人天与进度由子需求实时汇总',tag:'业务方 · 产品经理 · PMO · 研发 · 测试',dur:4.5});
fs.writeFileSync('/tmp/v23/items.json',JSON.stringify(items,null,1));
console.log('items',items.length);
const ver=await (await fetch('http://127.0.0.1:9222/json/version')).json();
const ws=new WebSocket(ver.webSocketDebuggerUrl); let id=0; const pend=new Map();
ws.onmessage=e=>{const m=JSON.parse(e.data); if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}};
await new Promise(r=>ws.onopen=r);
const send=(m,p={},sid)=>new Promise(res=>{const i=++id;pend.set(i,res);ws.send(JSON.stringify({id:i,method:m,params:p,sessionId:sid}));});
const {result:{targetId}}=await send('Target.createTarget',{url:'about:blank'});
const {result:{sessionId}}=await send('Target.attachToTarget',{targetId,flatten:true});
await send('Page.enable',{},sessionId); await send('Runtime.enable',{},sessionId);
await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false},sessionId);
await send('Page.navigate',{url:'file:///tmp/video/slide.html'},sessionId);
await new Promise(r=>setTimeout(r,1200));
fs.mkdirSync('/tmp/v23/out',{recursive:true});
for (let i=0;i<items.length;i++){
  const n=String(i+1).padStart(3,'0');
  const r=await send('Runtime.evaluate',{expression:`window.render(${JSON.stringify(items[i])})`,awaitPromise:true,returnByValue:true},sessionId);
  if(r.result?.exceptionDetails) console.log('EXC',i+1,JSON.stringify(r.result.exceptionDetails).slice(0,150));
  await new Promise(x=>setTimeout(x,150));
  const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);
  fs.writeFileSync('/tmp/v23/out/'+n+'.png', Buffer.from(data,'base64'));
  if((i+1)%20===0) console.log('frame',i+1,'/',items.length);
}
ws.close(); process.exit(0);
