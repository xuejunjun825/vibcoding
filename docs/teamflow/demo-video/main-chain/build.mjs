import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const F='/tmp/video/frames/';
const LOG='/tmp/v8/prog.log';
const logp=(m)=>fs.appendFileSync(LOG,new Date().toISOString().slice(11,19)+' '+m+'\n');
logp('start');
const ONLY=process.env.ONLY||'';
const V=[
 {slug:'01-receive',name:'需求受理',title:{h1:'子流程 1 · 需求受理',p:'业务方 / 二级企业提报，服务端校验必填项',tag:'角色：业务方 · 二级企业'},
  frames:[
   {img:'01-biz-reqpool.png',role:'业务方',chap:'① 业务方登录 · 需求池',text:'只看得到本业务单元的需求（域 × 单位隔离生效）',sub:'二级企业 suite1、业务方 biz1 各自的池子不同',dur:7},
   {img:'02-biz-detail-submitted.png',role:'业务方',chap:'② 提报后状态 = 已提报',text:'必填：类型/业务域/优先级/期望交付；缺业务域直接 400 VALIDATION_FAILED',sub:'detail.field = domainIds（实测，不再 500 崩库）',dur:7}]},
 {slug:'02-approve',name:'需求审批',title:{h1:'子流程 2 · 需求审批',p:'提交 → 审批单 REQ_SUBMIT → 通过 / 驳回',tag:'角色：业务方 · 产品经理'},
  frames:[
   {img:'03-biz-detail-approving.png',role:'业务方',chap:'① 提交审批 → 审批中',text:'业务方只能提交或撤回，不能自己批（实测 403 FORBIDDEN）',sub:'审批进度：当前节点 n1 产品评审 · 待指派',dur:7},
   {img:'04-pm-approvals-todo.png',role:'产品经理',chap:'② 产品经理的审批待办',text:'待办里逐条给出单据/提交人/等待时长，可加签减签转办催办',sub:'审批流可配：会签 / 或签 / 条件分支',dur:7},
   {img:'05-bus-pending-review.png',role:'产品经理',chap:'③ 通过后进入待评审',text:'审批通过 → PENDING_REVIEW，下一步发起评审单',sub:'驳回则回 SUBMITTED，必须填原因',dur:6}]},
 {slug:'03-review',name:'需求评审入池',title:{h1:'子流程 3 · 评审与入池',p:'发起评审单 REQ_REVIEW → 会签通过 → 入池',tag:'角色：产品经理 · 会签方'},
  frames:[
   {img:'05-bus-pending-review.png',role:'产品经理',chap:'① 待评审状态',text:'未走评审就想入池 → 409 REVIEW_APPROVAL_REQUIRED',sub:'门禁在服务端，不是前端按钮藏起来',dur:7},
   {img:'06-bus-pooled.png',role:'产品经理',chap:'② 评审通过 → 已入池',text:'评审实例 APPROVED 后由回调自动置 POOLED（不需要再点一次）',sub:'入池后子需求才能拆解',dur:7}]},
 {slug:'04-decompose',name:'需求拆解',title:{h1:'子流程 4 · 三层拆解',p:'业务需求 → 产品需求 → 研发需求，父状态是前置门',tag:'角色：产品经理'},
  frames:[
   {img:'07-pdr-created.png',role:'产品经理',chap:'① 拆出产品需求 PDR',text:'父 BUS 必须已入池，否则 409 PARENT_NOT_POOLED',sub:'PDR 落在 PENDING_ANALYSIS，子需求挂在父下',dur:7},
   {img:'09-rd-created.png',role:'产品经理',chap:'② 拆出研发需求 RD',text:'父 PDR 必须已排期，否则 409 PARENT_NOT_SCHEDULED（r23 堵的旁路）',sub:'RD 落在 TODO，含估 6.5 人天',dur:7},
   {img:'bus-closed.png',role:'产品经理',chap:'③ 父需求自动汇总',text:'父的人天/进度/完成率由子需求实时汇总，父自己不可填人天',sub:'填了会 409 PARENT_DAYS_READONLY',dur:6}]},
 {slug:'05-estimate',name:'工时评估初审',title:{h1:'子流程 5 · 工时评估初审',p:'研发/PMO 提评估 → 产品经理初审 → 才允许排期与开发',tag:'角色：研发/PMO · 产品经理'},
  frames:[
   {img:'estimate-todo.png',role:'产品经理',chap:'① 评估单 WBS_ESTIMATE 进待办',text:'产品经理「只审不提」：自己提评估会 403（需 req:deliver）',sub:'实测：pm 提交 → 403；tl1(PMO) 提交 → 201',dur:7},
   {img:'rd-in-dev.png',role:'研发',chap:'② 工时评估已通过的开发卡片',text:'当前 6.5 / 评估 6.5 / 偏差 0%，才允许开始开发',sub:'人天改动超过 ±20% → 评估单转 STALE，排期与开发再次被拦',dur:7}]},
 {slug:'06-release-plan',name:'排期与版本计划',title:{h1:'子流程 6 · 排期与版本计划',p:'未过评估不能排期；排期后挂版本与里程碑',tag:'角色：产品经理 · 项目域负责人'},
  frames:[
   {img:'08-pdr-scheduled.png',role:'产品经理',chap:'① 排期 → SCHEDULED',text:'没过评估就排期 → 409 ESTIMATE_NOT_APPROVED',sub:'排期是「承诺」：之后改期要留审计',dur:7},
   {img:'tl1-releases.png',role:'项目域负责人',chap:'② 版本计划与工作项',text:'版本里能看到挂进来的需求、完成度、人天与准入情况',sub:'版本锁定后再改范围 → 409 RELEASE_LOCKED',dur:7}]},
 {slug:'07-deliver',name:'研发交付执行',title:{h1:'子流程 7 · 研发交付执行',p:'指派厂商 → 开发 → 任务 → 提测 → 测试 → 合入',tag:'角色：项目域负责人 · 研发 · 测试 · 厂商'},
  frames:[
   {img:'dev-task-board.png',role:'研发',chap:'① 任务拆到人 · 状态推进',text:'任务 TODO→DOING→DONE；实际人天 = 工时 ÷ 8 自动回写',sub:'厂商只看得到本厂商承接的任务',dur:7},
   {img:'rd-pending-test.png',role:'测试',chap:'② 提测 → PENDING_TEST',text:'测试执行 PASS 才允许「测试通过」，失败只能转缺陷回研发',sub:'⚠ 实测缺口：test:quick 也给了 DEV，研发可自己点通过',dur:7},
   {img:'rd-done.png',role:'研发',chap:'③ 合入 → 完成',text:'merge → finish，RD 进 DONE，交付登记补实际人天与 MR',sub:'合入/完成需 req:deliver 权限',dur:6}]},
 {slug:'08-accept',name:'交付验收关闭',title:{h1:'子流程 8 · 交付与验收关闭',p:'RD 完成 → PDR 交付/验收 → 业务需求关闭',tag:'角色：研发 · 项目域负责人 · 产品经理'},
  frames:[
   {img:'pdr-delivered.png',role:'项目域负责人',chap:'① PDR 交付 → DELIVERED',text:'PDR 需先进 IN_DEV 才能交付；父需求不手填人天',sub:'交付后由项目域负责人发起验收',dur:7},
   {img:'bus-closed.png',role:'产品经理',chap:'② 业务需求关闭 → CLOSED',text:'实测汇总：子需求 1 个、进度 100%、预估 3.0 / 实际 2.5 人天',sub:'rollup 由子需求实时算，父子一致',dur:7}]}
];
logp('fetch version');
const ver=await (await fetch('http://127.0.0.1:9222/json/version')).json();
logp('got version');
const ws=new WebSocket(ver.webSocketDebuggerUrl);let id=0;const pend=new Map();
ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}};
await new Promise(r=>ws.onopen=r);
logp('ws open');
const send=(m,p={},sid)=>new Promise(res=>{const i=++id;pend.set(i,res);ws.send(JSON.stringify({id:i,method:m,params:p,sessionId:sid}));});
const {result:{targetId}}=await send('Target.createTarget',{url:'about:blank'});
const {result:{sessionId}}=await send('Target.attachToTarget',{targetId,flatten:true});
await send('Page.enable',{},sessionId);await send('Runtime.enable',{},sessionId);
await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false},sessionId);
await send('Page.navigate',{url:'file:///tmp/video/slide.html'},sessionId);
logp('navigated');
await new Promise(r=>setTimeout(r,900));
for (const v of V.filter(x=>!ONLY||x.slug===ONLY)){
  const dir='/tmp/v8/out/'+v.slug; fs.mkdirSync(dir+'/f',{recursive:true});
  const items=[{kind:'title',...v.title,dur:5},...v.frames];
  let i=0; const list=[];
  for (const it of items){
    i++; const p={...it}; if(p.img) p.img='file://'+F+p.img;
    logp('render '+v.slug+' '+i);
    const rr=await send('Runtime.evaluate',{expression:`window.render(${JSON.stringify(p)})`,awaitPromise:true,returnByValue:true},sessionId);
    if(rr.error) console.log('RENDER ERR', JSON.stringify(rr.error).slice(0,200));
    console.log('frame',v.slug,i);
    await new Promise(r=>setTimeout(r,220));
    const {result:{data}}=await send('Page.captureScreenshot',{format:'png'},sessionId);
    fs.writeFileSync(`${dir}/f/${String(i).padStart(2,'0')}.png`,Buffer.from(data,'base64'));
    list.push(`file 'f/${String(i).padStart(2,'0')}.png'`); list.push('duration '+it.dur);
  }
  list.push(`file 'f/${String(items.length).padStart(2,'0')}.png'`);
  fs.writeFileSync(dir+'/list.txt',list.join('\n')+'\n');
  execFileSync('ffmpeg',['-y','-f','concat','-safe','0','-i','list.txt','-vf','fps=30,format=yuv420p','-c:v','libx264','-preset','medium','-crf','23','-movflags','+faststart','-loglevel','error',v.slug+'.mp4'],{cwd:dir});
  const st=fs.statSync(dir+'/'+v.slug+'.mp4');
  console.log(v.slug, (st.size/1024).toFixed(0)+'KB', items.length+'段');
}
ws.close();
