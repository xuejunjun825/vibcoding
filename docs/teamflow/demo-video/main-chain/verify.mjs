const GW='http://127.0.0.1:8080';
const R=[]; const rec=(id,name,ok,detail)=>{R.push({id,name,ok,detail});console.log((ok?'OK  ':'FAIL')+' '+id+' '+name+' :: '+(detail||''));};
const j=async(u,p,o={})=>{const r=await fetch(GW+u,{...o,headers:{'Content-Type':'application/json',...(o.headers||{})}});let b=null;try{b=await r.json()}catch(e){};return {s:r.status,b};};
const login=async(u,pass)=>{const {b}=await j('/api/req/auth/login',null,{method:'POST',body:JSON.stringify({tenantCode:'t1',username:u,password:pass})});return b;};
const H=t=>({Authorization:'Bearer '+t,'X-Tenant-Id':'1'});
const T={};
for(const [u,p] of [['biz1','Passw0rd!'],['pm','Passw0rd!'],['tl1','Passw0rd!'],['dev1','Passw0rd!'],['qa1','Passw0rd!'],['admin','Admin@12345']]){const s=await login(u,p);T[u]=s.token;}
const todoOf=async u=>{const {b}=await j('/api/req/approvals/todo?page=1&size=50',null,{headers:H(T[u])});return b.items||[];};
const approve=async(u,bizId,bizType)=>{const items=await todoOf(u);const it=items.find(x=>x.bizId===bizId&&(!bizType||x.bizType===bizType));if(!it)throw new Error('no todo '+bizType+' '+bizId+' for '+u);const {s,b}=await j(`/api/req/approvals/tasks/${it.taskId}/actions`,null,{method:'POST',headers:H(T[u]),body:JSON.stringify({action:'APPROVE',comment:'v8 验证通过'})});if(s!==200)throw new Error('approve failed '+s+' '+JSON.stringify(b));return it;};

// ---------- S1 需求受理 ----------
let r=await j('/api/req/requirements',null,{method:'POST',headers:H(T.biz1),body:JSON.stringify({type:'BUS',title:'v8-主链验证-供应商自助对账',description:'验证 8 个子流程',domainIds:[1],priority:'P2',expectedDate:'2026-12-20'})});
const bus=r.b; rec('S1.1','业务方创建 BUS',r.s===201&&bus.status==='SUBMITTED',`${r.s} ${bus.code} ${bus.status}`);
r=await j('/api/req/requirements',null,{method:'POST',headers:H(T.biz1),body:JSON.stringify({type:'BUS',title:'v8-缺域'})});
rec('S1.2','缺业务域被拒',r.s===400&&r.b?.detail?.field==='domainIds',`${r.s} ${r.b?.code} field=${r.b?.detail?.field}`);

// ---------- S2 需求审批 ----------
r=await j(`/api/req/requirements/${bus.id}/actions`,null,{method:'POST',headers:H(T.biz1),body:JSON.stringify({action:'submit'})});
rec('S2.1','业务方提交→APPROVING',r.s===200&&r.b?.status==='APPROVING',`${r.s} ${r.b?.status} allowed=${JSON.stringify(r.b?.view?.allowedActions)}`);
r=await j(`/api/req/requirements/${bus.id}/actions`,null,{method:'POST',headers:H(T.biz1),body:JSON.stringify({action:'approve'})});
rec('S2.2','业务方不能自己批（越权）',r.s===403||r.s===409,`${r.s} ${r.b?.code||''}`);
await approve('pm',bus.id,'REQ_SUBMIT');
r=await j(`/api/req/requirements/${bus.id}`,null,{headers:H(T.pm)});
rec('S2.3','产品经理审批→PENDING_REVIEW',r.b?.view?.status==='PENDING_REVIEW',`${r.s} ${r.b?.view?.status}`);

// ---------- S3 评审入池 ----------
r=await j(`/api/req/requirements/${bus.id}/actions`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({action:'review_pass'})});
rec('S3.1','评审未通过就入池被拒',r.s===409&&(r.b?.code==='REVIEW_APPROVAL_REQUIRED'||r.b?.code==='ILLEGAL_TRANSITION'),`${r.s} ${r.b?.code}`);
r=await j(`/api/req/requirements/${bus.id}/actions`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({action:'review_start'})});
rec('S3.2','发起评审',r.s===200,`${r.s}`);
await approve('pm',bus.id,'REQ_REVIEW');
r=await j(`/api/req/requirements/${bus.id}`,null,{headers:H(T.pm)});
rec('S3.3','评审通过（回调自动入池）→POOLED',r.b?.view?.status==='POOLED',`${r.s} ${r.b?.view?.status}`);

// ---------- S4 拆解 ----------
r=await j(`/api/req/requirements/${bus.id}/decompose`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({title:'v8-PDR-对账页面'})});
const pdr=r.b; rec('S4.1','拆 PDR',r.s===201&&pdr?.type==='PDR',`${r.s} ${pdr?.code} ${pdr?.status}`);
r=await j(`/api/req/requirements/${pdr.id}/decompose`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({title:'v8-RD-对账接口',estimateDays:3.0})});
rec('S4.2','父 PDR 未排期就拆 RD 被拒',r.s===409&&r.b?.code==='PARENT_NOT_SCHEDULED',`${r.s} ${r.b?.code}`);

// ---------- S5 工时评估 ----------
r=await j(`/api/req/requirements/${pdr.id}/estimate`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({estimateDays:4.0,note:'产品经理提'})});
rec('S5.1','产品经理提评估被拒（只审不提）',r.s===403,`${r.s} ${r.b?.required||r.b?.code}`);
r=await j(`/api/req/requirements/${pdr.id}/estimate`,null,{method:'POST',headers:H(T.tl1),body:JSON.stringify({estimateDays:4.0,note:'PMO 提评估'})});
rec('S5.2','PMO 提交工时评估',r.s===201,`${r.s} ${r.b?.code||''}`);
await approve('pm',pdr.id,'WBS_ESTIMATE');
r=await j(`/api/req/requirements/${pdr.id}`,null,{headers:H(T.pm)});
rec('S5.3','评估单 APPROVED',r.b?.estimate?.status==='APPROVED',`${r.s} status=${r.b?.estimate?.status}`);

// ---------- S6 排期与版本 ----------
r=await j(`/api/req/requirements/${pdr.id}/actions`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({action:'schedule'})});
rec('S6.1','排期→SCHEDULED',r.s===200&&r.b?.status==='SCHEDULED',`${r.s} ${r.b?.status}`);
r=await j('/api/req/releases',null,{method:'POST',headers:H(T.pm),body:JSON.stringify({name:'v8-验证版本',domainId:1,startDate:'2026-12-01',endDate:'2026-12-15',plannedReleaseDate:'2026-12-16',gateMode:'STRICT',releaseLevel:'NORMAL'})});
const rel=r.b; rec('S6.2','建版本',r.s===201||r.s===200,`${r.s} ${rel?.code||''}`);
r=await j(`/api/req/releases/${rel.id}/requirements`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({requirementIds:[pdr.id]})});
rec('S6.3','需求挂版本',r.s===200||r.s===201,`${r.s}`);
r=await j(`/api/req/requirements/${pdr.id}`,null,{headers:H(T.pm)});
rec('S6.4','版本已写回需求',String(r.b?.view?.releaseId)===String(rel.id),`releaseId=${r.b?.view?.releaseId}`);

// ---------- S7 研发交付 ----------
r=await j(`/api/req/requirements/${pdr.id}/decompose`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({title:'v8-RD-对账接口',estimateDays:3.0})});
const rd=r.b; rec('S7.1','拆 RD',r.s===201,`${r.s} ${rd?.code} ${rd?.status}`);
r=await j(`/api/req/requirements/${rd.id}/assign-vendor`,null,{method:'POST',headers:H(T.tl1),body:JSON.stringify({vendorId:1,reason:'v8 验证指派 VEN-A'})});
rec('S7.2','指派厂商（原因入审计）',r.s===200,`${r.s}`);
r=await j(`/api/req/requirements/${rd.id}/estimate`,null,{method:'POST',headers:H(T.tl1),body:JSON.stringify({estimateDays:3.0,note:'v8 RD 评估'})});
rec('S7.3','RD 工时评估提交',r.s===201,`${r.s}`);
await approve('pm',rd.id,'WBS_ESTIMATE');
r=await j(`/api/req/requirements/${rd.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'start_dev'})});
rec('S7.4','开始开发→IN_DEV',r.s===200&&r.b?.status==='IN_DEV',`${r.s} ${r.b?.status}`);
r=await j('/api/deliver/tasks',null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({requirementId:rd.id,title:'v8-接口编码',estimateHours:16})});
const task=r.b; rec('S7.5','建任务',r.s===201,`${r.s} task=${task?.id||''}`);
await j(`/api/deliver/tasks/${task.id}/move`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({status:'DOING'})});
r=await j(`/api/deliver/tasks/${task.id}/move`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({status:'DONE'})});
rec('S7.6','任务 DOING→DONE',r.s===200,`${r.s}`);
r=await j(`/api/req/requirements/${rd.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'submit_test'})});
rec('S7.7','研发提测→PENDING_TEST',r.s===200&&r.b?.status==='PENDING_TEST',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${rd.id}/quick-test`,null,{method:'POST',headers:H(T.qa1),body:JSON.stringify({result:'PASS',note:'v8 快速测试'})});
rec('S7.8','测试执行 PASS（qa1）',r.s===201,`${r.s} exec=${r.b?.executionId||''}`);
r=await j(`/api/req/requirements/${rd.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'test_pass'})});
rec('S7.9','测试结论→TEST_PASSED（⚠ 由研发执行成功：test:quick 给了 DEV，缺口见报告）',r.s===200&&r.b?.status==='TEST_PASSED',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${rd.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'merge'})});
rec('S7.11','合入→MERGED',r.s===200&&r.b?.status==='MERGED',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${rd.id}/deliver`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({actualDays:2.5,mrUrl:'https://git.example.com/mr/v8'})});
rec('S7.12','交付登记（实际人天/MR，MERGED 阶段）',r.s===200,`${r.s} ${r.b?.actualDays||''}`);
r=await j(`/api/req/requirements/${rd.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'finish'})});
rec('S7.13','完成→DONE',r.s===200&&r.b?.status==='DONE',`${r.s} ${r.b?.status}`);

// ---------- S8 验收关闭 ----------
r=await j(`/api/req/requirements/${pdr.id}/actions`,null,{method:'POST',headers:H(T.dev1),body:JSON.stringify({action:'start_dev'})});
rec('S8.1','PDR 进入开发→IN_DEV',r.s===200&&r.b?.status==='IN_DEV',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${pdr.id}/actions`,null,{method:'POST',headers:H(T.tl1),body:JSON.stringify({action:'deliver'})});
rec('S8.2','PDR 交付→DELIVERED',r.s===200&&r.b?.status==='DELIVERED',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${pdr.id}/actions`,null,{method:'POST',headers:H(T.tl1),body:JSON.stringify({action:'accept'})});
rec('S8.3','PDR 验收→ACCEPTED_CLOSED',r.s===200&&r.b?.status==='ACCEPTED_CLOSED',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${bus.id}/actions`,null,{method:'POST',headers:H(T.pm),body:JSON.stringify({action:'close'})});
rec('S8.4','BUS 关闭→CLOSED',r.s===200&&r.b?.status==='CLOSED',`${r.s} ${r.b?.status}`);
r=await j(`/api/req/requirements/${bus.id}`,null,{headers:H(T.pm)});
const v=r.b?.view||{}; const ro=v.rollup||{};
rec('S8.5','父需求汇总（人天/进度/子需求）',Number(ro.progressPercent)===100&&Number(ro.childCount)===1&&Number(ro.doneCount)===1&&Number(ro.estimateDays)===3&&Number(ro.actualDays)===2.5,`rollup=${JSON.stringify(ro)}`);
const ok=R.filter(x=>x.ok).length;
console.log(`\n=== 主链验证 ${ok}/${R.length} 通过 ===`);
import fs from 'node:fs'; fs.writeFileSync('/tmp/v8/report.json', JSON.stringify({ok,total:R.length,rows:R},null,2));
