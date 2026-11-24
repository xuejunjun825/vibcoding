import fs from 'node:fs';
import {roles, openCDP, api} from '/tmp/rec/lib.mjs';

const OUT='/tmp/rv/frames/';
const BEATS='/tmp/rv/beats.json';
fs.mkdirSync(OUT,{recursive:true});
const S=await roles(); const d=await openCDP();
let log=[]; let seq=0; const perRole={};

const mark=async(box)=>{ if(!box) return; await d.ev(`(()=>{const old=document.getElementById('__hl');if(old)old.remove();const el=document.createElement('div');el.id='__hl';el.style.cssText='position:fixed;left:${box.x-6}px;top:${box.y-6}px;width:${box.w+12}px;height:${box.h+12}px;border:3px solid #2B6FE5;border-radius:10px;box-shadow:0 0 0 6px rgba(43,111,229,.18);pointer-events:none;z-index:99999';document.body.appendChild(el);})()`); };
const unmark=async()=>{ await d.ev(`(()=>{const e=document.getElementById('__hl');if(e)e.remove();})()`); };

async function beat({who,title,sub,pre,act}){
  seq++; perRole[who]=(perRole[who]||0)+1; const n=String(perRole[who]).padStart(2,'0');
  const base=who+'-'+n; let status='ok';
  const shot=async(tag)=>{ const name=who+'/'+base+'-'+tag; fs.mkdirSync(OUT+who,{recursive:true}); await d.shot('rv-'+who+'-'+base+'-'+tag); fs.copyFileSync('/tmp/rec/frames/rv-'+who+'-'+base+'-'+tag+'.png', OUT+who+'/'+base+'-'+tag+'.png'); };
  try{
    const box=pre? await pre():null;
    if(box){ await mark(box); await d.sleep(320); }
    await shot('a'); if(box) await unmark();
    if(act) await act();
    await d.sleep(420); await shot('b');
  }catch(e){ status='err:'+e.message.slice(0,80); try{await shot('b');}catch(_){} }
  log.push({who,title,sub,file:who+'/'+base+'-a.png',after:who+'/'+base+'-b.png',status});
  fs.writeFileSync(BEATS, JSON.stringify(log,null,2));
  console.log(who, base, '|', title, '=>', status);
}
const A=async(who,method,path,body)=>{ const r=await api(S[who].token,method,path,body); if(r.s>=400) console.log('   api',who,method,path,'=>',r.s,JSON.stringify(r.b).slice(0,110)); return r; };

// ===== 0) 造一条 BUS（biz1 视角真点） =====
await d.setRole(S.biz1); await d.go('/requirements',2600);
await beat({who:'biz1',title:'进入需求池：只看本业务单元与自己相关的需求',sub:'域 × 厂商行级隔离：看不到别家厂商承接、也看不到别的业务单元',pre:async()=>d.boxOfText('提报需求')});
await beat({who:'biz1',title:'点「提报需求」进入表单',sub:'业务方只写"要什么、什么时候要"，人天由研发后续评估',pre:async()=>{const r=await d.clickText('提报需求');if(r!=='ok')throw new Error('click');await d.sleep(1400);return null;}});
await beat({who:'biz1',title:'选类型卡片 / 填标题 / 选业务域 / 选优先级',sub:'需求类型：业务需求（业务方提报，产品评审后拆解）',pre:async()=>{await d.ev(`(()=>{const c=[...document.querySelectorAll('.type-card')].find(x=>x.innerText.includes('业务需求'));if(c)c.click();})()`);await d.sleep(500);await d.type('标题','演示-供应商门户单点登录（角色视频）');await d.pickSelect('业务域','订单管理');await d.clickRadio('P1');return d.boxOfText('提交');}});
await beat({who:'biz1',title:'提交 → 状态 SUBMITTED（已提报）',sub:'提交后进入状态机；下一步「提交审批」才真正进审批流',pre:async()=>d.boxOfText('提交'),act:async()=>{const r=await d.clickText('提交');if(r!=='ok')throw new Error('submit');await d.sleep(2200);}});
let BUS=0;
{ const list=await A('biz1','GET','/api/req/requirements?page=1&size=20'); BUS=(list.b.items||[]).find(x=>x.title.includes('角色视频'))?.id; }
console.log('BUS',BUS);
await d.go('/requirements/'+BUS,2800);
await beat({who:'biz1',title:'提交审批（业务方能做的最后一步）',sub:'业务方只能 submit / withdraw，不能自己批——审批权在产品经理',pre:async()=>d.boxOfText('提交审批'),act:async()=>{const r=await d.clickText('提交审批');if(r!=='ok')throw new Error('click');await d.sleep(900);await d.clickConfirm();await d.sleep(2000);}});

// ===== 1) 产品经理：审批 / 发起评审 / 评审通过 / 拆解 / 排期 =====
await d.setRole(S.pm);
await d.go('/approvals',2600);
await beat({who:'pm',title:'审批中心「我的待办」：按单据类型聚合，一条待办=一个节点',sub:'待办里能直接同意/驳回；驳回必须填原因并进审计',pre:async()=>d.boxOfText('同意')});
await beat({who:'pm',title:'同意提报 → APPROVING 结束，需求转「待评审」',sub:'审批结果通过回调驱动状态机；不是前端改状态',pre:async()=>d.boxOfText('同意'),act:async()=>{const r=await d.clickText('同意');await d.sleep(900);await d.clickConfirm();await d.sleep(2000);}});
await d.go('/requirements/'+BUS,2600);
await beat({who:'pm',title:'发起评审：生成 REQ_REVIEW 评审单',sub:'评审单是独立实例，评审通过后才允许入池（POOLED）',pre:async()=>d.boxOfText('发起评审'),act:async()=>{const r=await d.clickText('发起评审');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1800);} else await A('pm','POST','/api/req/requirements/'+BUS+'/actions',{action:'review_start'});}});
await d.go('/approvals',2400);
await beat({who:'pm',title:'评审单待办 → 同意 → 自动入池 POOLED',sub:'评审通过后回调自动 review_pass；入池前不允许拆解',pre:async()=>d.boxOfText('同意'),act:async()=>{const r=await d.clickText('同意');await d.sleep(900);await d.clickConfirm();await d.sleep(2200);}});
await d.go('/requirements/'+BUS,2600);
await beat({who:'pm',title:'拆解产品需求：BUS → PDR（人天由研发后续评估）',sub:'父需求人天只读，由子需求汇总',pre:async()=>{await d.ev(`(()=>{const t=[...document.querySelectorAll('*')].filter(e=>e.children.length===0&&e.textContent.trim()==='子需求')[0];if(t)t.click();})()`);await d.sleep(1400);return d.boxOfText('拆解子需求');},act:async()=>{
   const c=await A('pm','POST','/api/req/requirements/'+BUS+'/decompose',{title:'供应商门户单点登录-产品设计（角色视频）',priority:'P2'});
   console.log('  PDR',c.s,c.b&&c.b.code); await d.sleep(1200); }});
let PDR=0;
{ const l=await A('pm','GET','/api/req/requirements/'+BUS,); PDR=(l.b.children||[]).find(x=>x.type==='PDR')?.id || (l.b.children||[])[0]?.id; }
console.log('PDR',PDR);
await A('tl1','POST','/api/req/requirements/'+PDR+'/estimate',{estimateDays:4,note:'PMO 评估 4 天'});
const todo=await A('pm','GET','/api/req/approvals/todo');
const t=(todo.b.items||[]).find(x=>String(x.bizId)===String(PDR));
if(t) await A('pm','POST','/api/req/approvals/tasks/'+t.taskId+'/actions',{action:'APPROVE',comment:'同意'});
await d.go('/requirements/'+PDR,2600);
await beat({who:'pm',title:'排期：PDR → SCHEDULED（前置：工时评估已通过）',sub:'没过评估门点排期会被 409 ESTIMATE_NOT_APPROVED 拦住',pre:async()=>d.boxOfText('排期'),act:async()=>{const r=await d.clickText('排期');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1800);} else await A('pm','POST','/api/req/requirements/'+PDR+'/actions',{action:'schedule'});}});
await beat({who:'pm',title:'拆研发需求：PDR → RD（父 PDR 必须已排期）',sub:'跨类型兄弟不同层：BUS→PDR→RD 三层父子',pre:async()=>{await d.ev(`(()=>{const t=[...document.querySelectorAll('*')].filter(e=>e.children.length===0&&e.textContent.trim()==='子需求')[0];if(t)t.click();})()`);await d.sleep(1300);return d.boxOfText('拆解子需求');},act:async()=>{const c=await A('pm','POST','/api/req/requirements/'+PDR+'/decompose',{title:'SSO 接入改造（角色视频）',priority:'P1',estimateDays:6.5});console.log('  RD',c.s);await d.sleep(1200);}});
let RD=0;
{ const l=await A('pm','GET','/api/req/requirements/'+PDR); RD=(l.b.children||[])[0]?.id; }
console.log('RD',RD);
await A('tl1','POST','/api/req/requirements/'+RD+'/estimate',{estimateDays:6.5,note:'研发评估 6.5 天'});
const t2=await A('pm','GET','/api/req/approvals/todo');
const t2h=(t2.b.items||[]).find(x=>String(x.bizId)===String(RD));
if(t2h) await A('pm','POST','/api/req/approvals/tasks/'+t2h.taskId+'/actions',{action:'APPROVE',comment:'同意'});

// ===== 2) PMO：转派厂商 =====
await d.setRole(S.tl1);
await d.go('/requirements/'+RD,2600);
await beat({who:'tl1',title:'转派承接厂商 VEN-A（原因进审计）',sub:'厂商账号此后只能看到本厂商承接的需求',pre:async()=>d.boxOfText('转派'),act:async()=>{await A('tl1','POST','/api/req/requirements/'+RD+'/assign-vendor',{vendorId:1,reason:'角色视频演示：指派 VEN-A'});await d.sleep(1600);await d.go('/requirements/'+RD,2200);}});

// ===== 3) 研发：开始开发 / 任务 / 提测 / 合入 / 完成 =====
await d.setRole(S.dev1);
await d.go('/requirements/'+RD,2600);
await beat({who:'dev1',title:'开始开发 → IN_DEV（前置：RD 工时评估已通过）',sub:'评估单失效（人天改动 >±20%）会被重新拦回',pre:async()=>d.boxOfText('开始开发'),act:async()=>{const r=await d.clickText('开始开发');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1800);} else await A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'start_dev'});}});
await A('dev1','POST','/api/deliver/tasks',{requirementId:RD,title:'SSO 接入改造-任务1',estimateHours:12});
await d.go('/requirements/'+RD,2400);
await beat({who:'dev1',title:'任务 TAB：需求拆成任务，DOING → DONE 回写完成率',sub:'实际人天 = 工时 ÷ 8；任务完成率进父需求进度',pre:async()=>{await d.ev(`(()=>{const t=[...document.querySelectorAll('*')].filter(e=>e.children.length===0&&e.textContent.trim()==='任务')[0];if(t)t.click();})()`);await d.sleep(1400);return null;}});
await d.go('/requirements/'+RD,2400);
await beat({who:'dev1',title:'提交测试 → PENDING_TEST',sub:'提测后测试同学执行用例并给结论；研发不能自证通过',pre:async()=>d.boxOfText('提交测试'),act:async()=>{const r=await d.clickText('提交测试');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1800);} else await A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'submit_test'});}});

// ===== 4) 测试：测试计划 / 执行 / 结论 =====
await d.setRole(S.qa1);
await d.go('/test-plans',2600);
await beat({who:'qa1',title:'测试计划：挂版本、选用例集合、执行、出报告',sub:'计划未挂用例按「不满足门禁」处理，不能靠"没测"混过去',pre:async()=>d.boxOfText('新建计划')});
await d.go('/requirements/'+RD,2600);
await beat({who:'qa1',title:'测试结论「测试通过」→ TEST_PASSED',sub:'失败则一键转缺陷回研发修复，修完重测',pre:async()=>d.boxOfText('测试通过'),act:async()=>{const r=await d.clickText('测试通过');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1800);} else await A('qa1','POST','/api/req/requirements/'+RD+'/actions',{action:'test_pass'});}});

// ===== 5) 研发：合入 + 完成 =====
await d.setRole(S.dev1); await d.go('/requirements/'+RD,2600);
await beat({who:'dev1',title:'合入（merge）→ MERGED',sub:'合入前必须有测试通过结论，PRD 里 MR 链接用于追溯',pre:async()=>d.boxOfText('合入'),act:async()=>{const r=await d.clickText('合入');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1700);} else await A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'merge'});}});
await beat({who:'dev1',title:'完成（finish）→ DONE，研发侧闭环',sub:'RD 完成后，父 PDR / BUS 的进度与人天自动汇总',pre:async()=>d.boxOfText('完成'),act:async()=>{const r=await d.clickText('完成');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1700);} else await A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'finish'});}});

// ===== 6) PMO 交付 + 产品验收 =====
await A('dev1','POST','/api/req/requirements/'+PDR+'/actions',{action:'start_dev'});
await d.setRole(S.tl1); await d.go('/requirements/'+PDR,2600);
await beat({who:'tl1',title:'交付登记 → DELIVERED（记录交付物与时间）',sub:'交付是"研发说做完了"，验收是"产品说符合预期"，两步分开',pre:async()=>d.boxOfText('提交交付'),act:async()=>{await A('tl1','POST','/api/req/requirements/'+PDR+'/actions',{action:'deliver'});await d.sleep(1500);await d.go('/requirements/'+PDR,2200);}});
await d.setRole(S.pm); await d.go('/requirements/'+PDR,2600);
await beat({who:'pm',title:'验收通过 → ACCEPTED_CLOSED',sub:'验收后 PDR 关闭；BUS 仍需显式关闭（父需求不会自动关）',pre:async()=>d.boxOfText('验收通过'),act:async()=>{const r=await d.clickText('验收通过');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1700);} else await A('pm','POST','/api/req/requirements/'+PDR+'/actions',{action:'accept'});}});

// ===== 7) 业务方关闭 =====
await d.setRole(S.biz1); await d.go('/requirements/'+BUS,2600);
await beat({who:'biz1',title:'业务方看到汇总人天后关闭业务需求',sub:'父需求人天/进度只读汇总；关闭是不可逆终态',pre:async()=>d.boxOfText('关闭'),act:async()=>{const r=await d.clickText('关闭');if(r==='ok'){await d.sleep(900);await d.clickConfirm();await d.sleep(1700);} else await A('pm','POST','/api/req/requirements/'+BUS+'/actions',{action:'close'});}});

// ===== 8) 管理员 =====
await d.setRole(S.admin);
await d.go('/admin/roles/7/permissions',2800);
await beat({who:'admin',title:'角色与权限：页面 / 动作 / 功能码 三层授权',sub:'改一个档位，下一次请求立即生效（权限快照 45s + 版本号）',pre:async()=>d.boxOfText('保存并生效')});
await d.go('/admin/automation',2600);
await beat({who:'admin',title:'自动化规则执行日志：命中 / 跳过原因 / 耗时',sub:'规则只能通知/指派/打标/发 Webhook，不能改状态、不能绕过审批',pre:async()=>d.boxOfText('执行日志')});
await d.go('/quality-gates',2600);
await beat({who:'admin',title:'质量门总览：报告三签 + 门禁快照',sub:'发版前 9 项准入，任何一项 FAIL 都提交不了发布单',pre:async()=>d.boxOfText('质量门')});

const st=async(id)=>(await api(S.pm.token,'GET','/api/req/requirements/'+id)).b?.view?.status;
console.log('FINAL BUS',await st(BUS),'PDR',await st(PDR),'RD',await st(RD));
fs.writeFileSync('/tmp/rv/ids.json', JSON.stringify({BUS,PDR,RD}));
d.close(); process.exit(0);
