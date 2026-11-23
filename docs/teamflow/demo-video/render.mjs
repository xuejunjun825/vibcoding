import fs from 'node:fs';
const caps=JSON.parse(fs.readFileSync('/tmp/video/captions.json','utf8'));
const ver=await (await fetch('http://127.0.0.1:9222/json/version')).json();
const ws=new WebSocket(ver.webSocketDebuggerUrl);let id=0;const pend=new Map();
ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}};
await new Promise(r=>ws.onopen=r);
const send=(m,p={},sid)=>new Promise(res=>{const i=++id;pend.set(i,res);ws.send(JSON.stringify({id:i,method:m,params:p,sessionId:sid}));});
const {result:{targetId}}=await send('Target.createTarget',{url:'about:blank'});
const {result:{sessionId}}=await send('Target.attachToTarget',{targetId,flatten:true});
await send('Page.enable',{},sessionId);await send('Runtime.enable',{},sessionId);
await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false},sessionId);
await send('Page.navigate',{url:'file:///tmp/video/slide.html'},sessionId);
await new Promise(r=>setTimeout(r,900));
fs.mkdirSync('/tmp/video/final',{recursive:true});
let i=0;
for (const c of caps) {
  i++;
  const payload={...c};
  if (payload.img) payload.img='file:///tmp/video/'+payload.img;
  await send('Runtime.evaluate',{expression:`window.render(${JSON.stringify(payload)})`,awaitPromise:true,returnByValue:true},sessionId);
  await new Promise(r=>setTimeout(r,250));
  const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);
  const f=`/tmp/video/final/${String(i).padStart(2,'0')}.png`;
  fs.writeFileSync(f,Buffer.from(data,'base64'));
  console.log(f, c.chap||c.h1);
}
ws.close();
