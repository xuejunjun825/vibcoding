import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const plan=JSON.parse(fs.readFileSync('/tmp/nv/plan.json','utf8'));
const durs=JSON.parse(fs.readFileSync('/tmp/nv/durs.json','utf8'));
const FR='/tmp/rv/frames/';
const frames=[]; // {img, kind, chap, text, sub, hl, dur}

plan.forEach((p,i)=>{
  const d=durs[i]+0.35;
  if(p.t==='title') frames.push({kind:'title',h1:p.h1,p:p.p,tag:p.tag,dur:d});
  else if(p.t==='figure') frames.push({kind:'figure',img:'file://'+p.img,chap:p.chap,text:p.text,sub:p.sub,hl:p.hl,dur:d});
  else {
    const half=d/2;
    frames.push({kind:'stage',img:'file://'+FR+p.file,role:p.role,chap:'第 '+Number(p.idx)+' 个节点 · 操作前',text:p.title,sub:p.sub,dur:half});
    frames.push({kind:'stage',img:'file://'+FR+p.after,role:p.role,chap:'第 '+Number(p.idx)+' 个节点 · 操作后',text:p.title,sub:p.sub,dur:half});
  }
});
fs.writeFileSync('/tmp/nv/frames.json', JSON.stringify(frames,null,1));
console.log('frames', frames.length, 'total', frames.reduce((a,b)=>a+b.dur,0).toFixed(1)+'s');

const ver=await (await fetch('http://127.0.0.1:9222/json/version')).json();
const ws=new WebSocket(ver.webSocketDebuggerUrl); let id=0; const pend=new Map();
ws.onmessage=e=>{const m=JSON.parse(e.data); if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}};
await new Promise(r=>ws.onopen=r);
const send=(m,p={},sid)=>new Promise(res=>{const i=++id;pend.set(i,res);ws.send(JSON.stringify({id:i,method:m,params:p,sessionId:sid}));});
const {result:{targetId}}=await send('Target.createTarget',{url:'about:blank'});
const {result:{sessionId}}=await send('Target.attachToTarget',{targetId,flatten:true});
await send('Page.enable',{},sessionId); await send('Runtime.enable',{},sessionId);
await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false},sessionId);
await send('Page.navigate',{url:'file:///tmp/nv/base.html'},sessionId);
await new Promise(r=>setTimeout(r,1200));
const dir='/tmp/nv/render'; fs.mkdirSync(dir,{recursive:true});
for(let i=0;i<frames.length;i++){
  const n=String(i+1).padStart(3,'0');
  const r=await send('Runtime.evaluate',{expression:`window.render(${JSON.stringify(frames[i])})`,awaitPromise:true,returnByValue:true},sessionId);
  if(r.result?.exceptionDetails) console.log('EXC',n,JSON.stringify(r.result.exceptionDetails).slice(0,140));
  await new Promise(x=>setTimeout(x,150));
  const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);
  fs.writeFileSync(dir+'/'+n+'.png', Buffer.from(data,'base64'));
  if((i+1)%25===0) console.log('frame',i+1,'/',frames.length);
}
// 视频 concat
const lines=[];
frames.forEach((f,i)=>{ lines.push(`file '${dir}/${String(i+1).padStart(3,'0')}.png'`); lines.push('duration '+f.dur.toFixed(3)); });
lines.push(`file '${dir}/${String(frames.length).padStart(3,'0')}.png'`);
fs.writeFileSync('/tmp/nv/vlist.txt', lines.join('\n')+'\n');
// 音频 concat（每段后 0.35s 静音，与画面节奏一致）
const sil='/tmp/nv/audio/sil.wav';
execFileSync('ffmpeg',['-y','-f','lavfi','-i','anullsrc=r=44100:cl=mono','-t','0.35','-c:a','pcm_s16le',sil],{stdio:'ignore'});
const alist=[];
plan.forEach((p,i)=>{ const n=String(i+1).padStart(2,'0'); alist.push(`file '/tmp/nv/audio/${n}.aiff'`); alist.push(`file '${sil}'`); });
fs.writeFileSync('/tmp/nv/alist.txt', alist.join('\n')+'\n');
console.log('lists written');
ws.close(); process.exit(0);
