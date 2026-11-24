import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const beats=JSON.parse(fs.readFileSync('/tmp/rv/beats.json','utf8'));
const ROLE={biz1:'业务方 · biz1',pm:'产品经理 · pm',tl1:'项目域负责人 / PMO · tl1',dev1:'研发工程师 · dev1',qa1:'测试工程师 · qa1',admin:'平台管理员 · admin'};
const TITLE={biz1:['业务方怎么提需求','从需求池到提交审批，业务方只做 4 件事'],pm:['产品经理怎么管需求','审批 → 评审入池 → 拆解 → 排期 → 验收'],tl1:['PMO 怎么编排交付','工时评估、转派厂商、交付登记'],dev1:['研发怎么做交付','开始开发 → 任务 → 提测 → 合入 → 完成'],qa1:['测试怎么把关','测试计划、执行、结论'],admin:['管理员怎么治理','角色权限、自动化规则、质量门']};
const groups={};
for(const b of beats){ (groups[b.who]=groups[b.who]||[]).push(b); }
const theme={biz1:'#2B6FE5',pm:'#7A5AF8',tl1:'#0E9F6E',dev1:'#E08A00',qa1:'#D6367B',admin:'#3A4A5E'};

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

async function render(items, dir){
  fs.mkdirSync(dir,{recursive:true});
  for(let i=0;i<items.length;i++){
    const n=String(i+1).padStart(3,'0');
    const r=await send('Runtime.evaluate',{expression:`window.render(${JSON.stringify(items[i])})`,awaitPromise:true,returnByValue:true},sessionId);
    if(r.result?.exceptionDetails) console.log('EXC',n,JSON.stringify(r.result.exceptionDetails).slice(0,120));
    await new Promise(x=>setTimeout(x,140));
    const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);
    fs.writeFileSync(dir+'/'+n+'.png', Buffer.from(data,'base64'));
  }
}
function assemble(items, dir, out){
  const lines=[];
  items.forEach((it,i)=>{ lines.push(`file '${dir}/${String(i+1).padStart(3,'0')}.png'`); lines.push('duration '+(it.kind==='title'?4.0:2.6)); });
  lines.push(`file '${dir}/${String(items.length).padStart(3,'0')}.png'`);
  fs.writeFileSync(dir+'/list.txt', lines.join('\n')+'\n');
  execFileSync('ffmpeg',['-y','-f','concat','-safe','0','-i',dir+'/list.txt','-vf','fps=30,format=yuv420p','-c:v','libx264','-preset','medium','-crf','23','-movflags','+faststart',out],{stdio:'ignore'});
}

const FR='/tmp/rv/frames/';
// 每个角色一条
for(const who of Object.keys(groups)){
  const bs=groups[who];
  const items=[{kind:'title',h1:TITLE[who][0],p:TITLE[who][1],tag:'节点 '+bs.length+' 个 · '+ROLE[who],dur:4.0}];
  for(const b of bs){
    const title=b.title;
    items.push({kind:'stage',img:FR+b.file,role:ROLE[who],chap:'操作前',text:title,sub:b.sub||'',dur:2.6});
    items.push({kind:'stage',img:FR+b.after,role:ROLE[who],chap:'操作后',text:title,sub:b.sub||'',dur:2.6});
  }
  const dir='/tmp/rv/out/'+who;
  await render(items,dir);
  assemble(items,dir,'/tmp/rv/out/'+who+'.mp4');
  console.log('built',who,bs.length,'nodes');
}
// 合一条全流程
const all=[{kind:'title',h1:'一条需求的全流程 · 分角色操作',p:'业务方 → 产品经理 → PMO → 研发 → 测试 → 管理员',tag:'26 个操作节点 · 6 个角色',dur:4.0}];
for(const b of beats){
  all.push({kind:'stage',img:FR+b.file,role:ROLE[b.who],chap:'操作前',text:b.title,sub:b.sub||'',dur:2.4});
  all.push({kind:'stage',img:FR+b.after,role:ROLE[b.who],chap:'操作后',text:b.title,sub:b.sub||'',dur:2.4});
}
await render(all,'/tmp/rv/out/all');
assemble(all,'/tmp/rv/out/all','/tmp/rv/out/all.mp4');
console.log('built all', beats.length);
ws.close(); process.exit(0);
