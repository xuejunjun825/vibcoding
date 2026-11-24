import fs from 'node:fs';
import {roles, openCDP, api} from './lib.mjs';
const FR='/tmp/rec/long/frames/'; fs.mkdirSync(FR,{recursive:true});
for (const f of fs.readdirSync(FR)) fs.unlinkSync(FR+f);
const S=await roles(); const d=await openCDP();
const beats=[]; let seq=0;
const mark=async(box)=>{ if(!box) return; await d.ev(`(()=>{const o=document.getElementById('__hl');if(o)o.remove();const el=document.createElement('div');el.id='__hl';el.style.cssText='position:fixed;left:${box.x-6}px;top:${box.y-6}px;width:${box.w+12}px;height:${box.h+12}px;border:3px solid #2B6FE5;border-radius:10px;box-shadow:0 0 0 6px rgba(43,111,229,.18);pointer-events:none;z-index:99999';document.body.appendChild(el);})()`); };
const unmark=async()=>{ await d.ev(`(()=>{const e=document.getElementById('__hl');if(e)e.remove();})()`); };
const shotAs=async(n,tag)=>{ const f=n+'-'+tag+'.png'; await d.shot(f.replace(/\.png$/,'')); fs.copyFileSync('/tmp/rec/frames/'+f, FR+f); return f; };
const beat=async({role,title,sub,pre,act})=>{ seq++; const n=String(seq).padStart(2,'0'); let status='ok'; const files=[];
  try{ const box=pre?await pre():null; if(box){await mark(box); await d.sleep(350);} files.push(await shotAs(n,'a')); if(box) await unmark();
       if(act) await act(); await d.sleep(400); files.push(await shotAs(n,'b')); }
  catch(e){ status='err:'+e.message.slice(0,80); try{files.push(await shotAs(n,'b'));}catch(_){} }
  beats.push({i:seq,role,title,sub,file:files[0],after:files[1],status}); fs.writeFileSync('/tmp/rec/long/beats.json',JSON.stringify(beats,null,2));
  console.log(n,role,'|',title,'=>',status); };
const A=async(w,mm,p,b)=>{ const r=await api(S[w].token,mm,p,b); if(r.s>=400) console.log('   api-err',w,mm,p,r.s,JSON.stringify(r.b).slice(0,110)); return r; };
const tab=async(name)=>{ await d.ev(`(()=>{const t=[...document.querySelectorAll('.el-tabs__item')].find(x=>x.innerText.trim()===${JSON.stringify(name)});if(t)t.click();})()`); await d.sleep(1300); };
const clickOr=async(label,fallback)=>{ const r=await d.clickText(label); if(r==='ok'){ await d.sleep(900); await d.clickConfirm(); await d.sleep(1600); return 'ui'; } return fallback(); };

// ①-④ 业务方
await d.setRole(S.biz1); await d.go('/requirements',2200);
await beat({role:'业务方 biz1',title:'① 业务方在需求池点「提报需求」',sub:'业务方只能提报与撤回，不能自己审批',
  pre:()=>d.boxOfText('提报需求'), act:async()=>{ await d.clickText('提报需求'); await d.sleep(1400); }});
await beat({role:'业务方 biz1',title:'② 选类型、填标题、选业务域与优先级',sub:'需求类型是卡片；业务域来自字典（ORD · 订单管理）',
  pre:async()=>d.boxOfText('提交'), act:async()=>{ await d.ev(`(()=>{const c=[...document.querySelectorAll('.type-card')].find(x=>x.innerText.includes('业务需求'));if(c)c.click();})()`); await d.sleep(300);
    await d.type('标题','演示-供应商门户单点登录（长视频）'); await d.type('描述','供应商门户接入集团 SSO，减少多套账号');
    await d.pickSelect('业务域','订单管理'); await d.clickRadio('P1'); await d.sleep(400); }});
let BUS=null;
await beat({role:'业务方 biz1',title:'③ 提交后状态「已提报」，内容仍可改',sub:'提交审批后业务方只能撤回',
  pre:()=>d.boxOfText('提交'), act:async()=>{ await d.clickText('提交'); await d.sleep(2400);
    BUS=await d.ev(`(()=>{const m=location.pathname.match(/\\/requirements\\/(\\d+)/);return m?Number(m[1]):null;})()`);
    if(!BUS){ const l=await A('biz1','GET','/api/req/requirements?page=1&size=100'); BUS=(l.b.items||[]).find(x=>x.title.includes('长视频'))?.id; await d.go('/requirements/'+BUS,2200); } }});
await beat({role:'业务方 biz1',title:'④ 点「提交审批」→ 审批中',sub:'右侧审批进度显示当前卡在「产品评审」',
  pre:()=>d.boxOfText('提交审批'), act:async()=>{ await clickOr('提交审批',async()=>A('biz1','POST','/api/req/requirements/'+BUS+'/actions',{action:'submit'})); }});
// ⑤-⑨ 产品经理
await d.setRole(S.pm); await d.go('/approvals?tab=todo',2400);
await beat({role:'产品经理 pm',title:'⑤ 切到产品经理：审批中心出现待办',sub:'谁能批由审批流决定',
  pre:async()=>{ await d.ev(`(()=>{const row=[...document.querySelectorAll('.el-table__row,.card-item')].find(x=>x.innerText.includes('供应商门户单点登录'));if(row)row.click();})()`); await d.sleep(1000); return d.boxOfText('同意'); }, act:async()=>{ }});
await beat({role:'产品经理 pm',title:'⑥ 点「同意」，确认后单据流转',sub:'驳回必须填原因，写进流转记录与审计',
  pre:()=>d.boxOfText('同意'), act:async()=>{ await clickOr('同意',async()=>{ const t=(await A('pm','GET','/api/req/approvals/todo')).b.items.find(x=>JSON.stringify(x).includes(String(BUS))); if(t) await A('pm','POST','/api/req/approvals/tasks/'+t.taskId+'/actions',{action:'APPROVE',comment:'同意'}); }); }});
await beat({role:'产品经理 pm',title:'⑦ 待评审：产品经理发起评审',sub:'评审是独立单据（REQ_REVIEW），通过后自动入池',
  pre:async()=>{ await d.go('/requirements/'+BUS,2400); return d.boxOfText('发起评审'); },
  act:async()=>{ await clickOr('发起评审',async()=>A('pm','POST','/api/req/requirements/'+BUS+'/actions',{action:'review_start'})); }});
await beat({role:'产品经理 pm',title:'⑧ 同意评审单 → 自动入池',sub:'评审通过由回调推进 POOLED，无需再手动点',
  pre:async()=>{ await d.go('/approvals?tab=todo',2200); await d.ev(`(()=>{const row=[...document.querySelectorAll('.el-table__row,.card-item')].find(x=>x.innerText.includes('供应商门户单点登录'));if(row)row.click();})()`); await d.sleep(1000); return d.boxOfText('同意'); },
  act:async()=>{ await clickOr('同意',async()=>{ const t=(await A('pm','GET','/api/req/approvals/todo')).b.items.find(x=>JSON.stringify(x).includes(String(BUS))); if(t) await A('pm','POST','/api/req/approvals/tasks/'+t.taskId+'/actions',{action:'APPROVE',comment:'通过'}); }); }});
await beat({role:'产品经理 pm',title:'⑨ 已入池（POOLED）',sub:'未入池时「拆解子需求」是禁用的',
  pre:async()=>{ await d.go('/requirements/'+BUS,2600); return null; }, act:async()=>{ }});
// ⑩-⑭ 拆解 / 评估 / 排期
const PDR=(await A('pm','POST','/api/req/requirements/'+BUS+'/decompose',{title:'供应商门户单点登录-产品设计（长视频）',priority:'P1'})).b.id;
await beat({role:'产品经理 pm',title:'⑩ 业务需求拆成产品需求（PDR）',sub:'父需求必须 POOLED 才能拆（409 PARENT_NOT_POOLED）',
  pre:async()=>{ await d.go('/requirements/'+PDR,2600); return null; }, act:async()=>{ }});
await beat({role:'项目域负责人 tl1',title:'⑪ PMO/研发提交工时评估（6.5 天）',sub:'提交评估要 req:deliver：产品经理只审、不提',
  pre:async()=>{ await d.setRole(S.tl1); await d.go('/requirements/'+PDR,2600); return d.boxOfText('提交评估'); },
  act:async()=>{ const r=await d.clickText('提交评估'); if(r!=='ok'){ await A('tl1','POST','/api/req/requirements/'+PDR+'/estimate',{estimateDays:6.5,note:'研发评估 6.5 天'}); return; }
    await d.sleep(1000); await d.type('评估人天','6.5'); await d.type('说明','长视频演示：按 6.5 天评估'); await d.sleep(300); await d.clickConfirm(); await d.sleep(1800); }});
await beat({role:'产品经理 pm',title:'⑫ 产品经理同意工时评估',sub:'评估未通过时，排期与开始开发都会被 409 拦住',
  pre:async()=>{ await d.setRole(S.pm); await d.go('/approvals?tab=todo',2400); await d.ev(`(()=>{const row=[...document.querySelectorAll('.el-table__row,.card-item')].find(x=>x.innerText.includes('工时评估'));if(row)row.click();})()`); await d.sleep(1000); return d.boxOfText('同意'); },
  act:async()=>{ await clickOr('同意',async()=>{ const t=(await A('pm','GET','/api/req/approvals/todo')).b.items.find(x=>JSON.stringify(x).includes(String(PDR))); if(t) await A('pm','POST','/api/req/approvals/tasks/'+t.taskId+'/actions',{action:'APPROVE',comment:'同意'}); }); }});
await beat({role:'产品经理 pm',title:'⑬ 排期 → 产品需求进入 SCHEDULED',sub:'排期后版本计划才能挂这条需求',
  pre:async()=>{ await d.go('/requirements/'+PDR,2400); return d.boxOfText('排期'); },
  act:async()=>{ await clickOr('排期',async()=>A('pm','POST','/api/req/requirements/'+PDR+'/actions',{action:'schedule'})); }});
const RD=(await A('pm','POST','/api/req/requirements/'+PDR+'/decompose',{title:'SSO 接入改造（长视频）',priority:'P1',estimateDays:6.5})).b.id;
await beat({role:'产品经理 pm',title:'⑭ 再拆成研发需求（RD），预估 6.5 人天',sub:'RD 是研发任务、工时与提测的载体',
  pre:async()=>{ await d.go('/requirements/'+RD,2600); return null; }, act:async()=>{ }});
fs.writeFileSync('/tmp/rec/long/ids.json',JSON.stringify({BUS,PDR,RD}));
// ⑮ 转派
await A('tl1','POST','/api/req/requirements/'+RD+'/assign-vendor',{vendorId:1,reason:'长视频演示：指派 VEN-A 甲方科技'});
await A('tl1','POST','/api/req/requirements/'+RD+'/estimate',{estimateDays:6.5,note:'研发评估 6.5 天'});
{ const t=(await A('pm','GET','/api/req/approvals/todo')).b.items.find(x=>JSON.stringify(x).includes(String(RD))); if(t) await A('pm','POST','/api/req/approvals/tasks/'+t.taskId+'/actions',{action:'APPROVE',comment:'同意'}); }
await beat({role:'项目域负责人 tl1',title:'⑮ 转派承接厂商 VEN-A（原因写审计）',sub:'厂商账号只能看到本厂商承接的需求',
  pre:async()=>{ await d.setRole(S.tl1); await d.go('/requirements/'+RD,2600); return null; }, act:async()=>{ }});
// ⑯-⑳ 研发交付
await beat({role:'研发工程师 dev1',title:'⑯ 研发点「开始开发」→ IN_DEV',sub:'评估已通过，start_dev 才被放行',
  pre:async()=>{ await d.setRole(S.dev1); await d.go('/requirements/'+RD,2600); return d.boxOfText('开始开发'); },
  act:async()=>{ await clickOr('开始开发',async()=>A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'start_dev'})); }});
{ const tr=await A('dev1','POST','/api/deliver/tasks',{requirementId:RD,title:'SSO 接入：网关与令牌校验',estimateHours:16}); if(tr.b?.id){ await A('dev1','POST','/api/deliver/tasks/'+tr.b.id+'/move',{status:'DOING'}); await A('dev1','POST','/api/deliver/tasks/'+tr.b.id+'/move',{status:'DONE'}); } }
await beat({role:'研发工程师 dev1',title:'⑰ 任务拆到看板：DOING → DONE',sub:'任务完成回写需求完成率；实际人天 = 工时 ÷ 8',
  pre:async()=>{ await d.go('/requirements/'+RD,2400); await tab('任务'); return null; }, act:async()=>{ }});
await beat({role:'研发工程师 dev1',title:'⑱ 提测 → PENDING_TEST',sub:'提测后测试同学才能执行用例并给结论',
  pre:async()=>{ await d.go('/requirements/'+RD,2400); return d.boxOfText('提交测试'); },
  act:async()=>{ await clickOr('提交测试',async()=>A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'submit_test'})); }});
await beat({role:'测试工程师 qa1',title:'⑲ 测试执行通过（TEST_PASSED）',sub:'失败会一键转缺陷回研发修复',
  pre:async()=>{ await d.setRole(S.qa1); await d.go('/requirements/'+RD,2600); return d.boxOfText('测试通过'); },
  act:async()=>{ await clickOr('测试通过',async()=>A('qa1','POST','/api/req/requirements/'+RD+'/actions',{action:'test_pass'})); }});
await beat({role:'研发工程师 dev1',title:'⑳ 合入 → 完成（RD DONE）',sub:'TEST_PASSED → MERGED → DONE',
  pre:async()=>{ await d.setRole(S.dev1); await d.go('/requirements/'+RD,2600); return d.boxOfText('合入'); },
  act:async()=>{ await clickOr('合入',async()=>A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'merge'})); await d.sleep(600); await clickOr('完成',async()=>A('dev1','POST','/api/req/requirements/'+RD+'/actions',{action:'finish'})); }});
// ㉑-㉓ 交付 / 验收 / 关闭
await A('dev1','POST','/api/req/requirements/'+PDR+'/actions',{action:'start_dev'});
await beat({role:'项目域负责人 tl1',title:'㉑ 交付登记 → 产品需求 DELIVERED',sub:'交付记录写审计，与后续验收关联',
  pre:async()=>{ await d.setRole(S.tl1); await d.go('/requirements/'+PDR,2600); return d.boxOfText('提交交付'); },
  act:async()=>{ await A('tl1','POST','/api/req/requirements/'+PDR+'/actions',{action:'deliver'}); await d.sleep(1200); }});
await beat({role:'产品经理 pm',title:'㉒ 产品经理验收 → ACCEPTED_CLOSED',sub:'验收后需求闭环，父需求人天/进度汇总落定',
  pre:async()=>{ await d.setRole(S.pm); await d.go('/requirements/'+PDR,2600); return d.boxOfText('验收通过'); },
  act:async()=>{ await A('pm','POST','/api/req/requirements/'+PDR+'/actions',{action:'accept'}); await d.sleep(1200); }});
await beat({role:'业务方 biz1',title:'㉓ 业务方看汇总人天，关闭业务需求',sub:'父需求人天/进度由子需求实时汇总',
  pre:async()=>{ await d.setRole(S.biz1); await d.go('/requirements/'+BUS,2600); return d.boxOfText('关闭'); },
  act:async()=>{ await A('pm','POST','/api/req/requirements/'+BUS+'/actions',{action:'close'}); await d.sleep(1200); }});
const fin=async(id)=>(await A('pm','GET','/api/req/requirements/'+id)).b?.view?.status;
console.log('FINAL', await fin(BUS), await fin(PDR), await fin(RD));
d.close(); process.exit(0);
