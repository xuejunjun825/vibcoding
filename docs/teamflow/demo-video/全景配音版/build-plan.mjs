import fs from 'node:fs';
const beats=JSON.parse(fs.readFileSync('/tmp/rv/beats.json','utf8'));
const DIAG='/Users/xuejunjun/Documents/deepseek-harness/project-1/docs/teamflow/diagrams/requirement-flow.png';
const ROLE={biz1:'业务方 biz1',pm:'产品经理 pm',tl1:'项目域负责人 / PMO tl1',dev1:'研发工程师 dev1',qa1:'测试工程师 qa1',admin:'平台管理员 admin'};
const ORDER={biz1:1,pm:2,tl1:3,dev1:4,qa1:5};
const narr=[
 {t:'title',h1:'需求全流程 · 全景演示',p:'从业务方提报到验收关闭 · 6 个角色 · 30 个操作节点',tag:'真实运行的平台 · 演示数据',say:'这套平台把一条需求从提报到关闭的全过程放在线上。下面用一条演示需求，把六个角色要做的事走一遍。'},
 {t:'figure',img:DIAG,chap:'全景图',text:'一条需求的完整旅程：18 个操作节点、6 个角色',sub:'红虚线是异常回退：驳回、测试不通过',say:'先看全景。一条需求走完要经过十八个操作节点，涉及业务方、产品经理、项目域负责人、研发、测试五个角色，红色虚线是两条异常回退：审批驳回，和测试不通过打回研发。'},
 {t:'figure',img:DIAG,chap:'阶段一 · 提报与审批',text:'业务方提报 → 提交审批 → 产品经理审批',sub:'业务方只能提交与撤回，不能自己批',hl:{x:290,y:250,w:800,h:120},say:'第一阶段是提报和审批。业务方在需求池里提报，只写要什么、什么时候要，人天不在这一步填；提交后进入产品经理的审批待办。'},
 {t:'figure',img:DIAG,chap:'阶段二 · 评审、拆解与排期',text:'评审入池 → 拆产品需求 → 工时评估 → 排期 → 拆研发需求',sub:'入池前不能拆解；没过工时评估不能排期',hl:{x:140,y:480,w:1620,h:130},say:'第二阶段是关键：评审通过才入池，入池后才能拆成产品需求；产品需求要先过工时评估、再排期，然后才能拆研发需求。这几个顺序在服务端是硬约束，跳步会被直接拒绝。'},
 {t:'figure',img:DIAG,chap:'阶段三 · 研发交付与验收',text:'指派厂商 → 任务执行 → 提测 → 测试结论 → 合入 → 交付 → 验收 → 关闭',sub:'研发不能自证测试通过；父需求人天由子需求汇总',hl:{x:140,y:700,w:1620,h:130},say:'第三阶段是研发交付：指派厂商、任务执行、提测、测试给结论、合入完成、交付登记、产品验收，最后由业务方关闭。测试结论必须由测试给，研发不能自己说通过。'},
];
// 依真实链路顺序插入节点（admin 放最后）
const nodes=[...beats.filter(b=>b.who!=='admin'),...beats.filter(b=>b.who==='admin')];
let i=0;
for(const b of nodes){
  i++;
  const idx=String(i).padStart(2,'0');
  const title=b.title.replace(/^[①②③④⑤⑥⑦⑧⑨⑩]/,'');
  narr.push({
    t:'node', file:b.file, after:b.after, role:ROLE[b.who], idx,
    title,
    sub:b.sub||'',
    say:`第${i}个节点，${title.replace(/[：:（(].*$/,'')}。${(b.sub||'').replace(/[。；].*$/,'')}。`
  });
}
narr.push({t:'title',h1:'一条需求，一次闭环',p:'业务方 → 产品经理 → PMO → 研发 → 测试 → 管理员',tag:'30 个操作节点 · 全程留痕可追溯',say:'这就是一条需求的完整闭环。每一步都有状态、有权限、有审计，堵点跑不掉，也绕不过去。'});
fs.writeFileSync('/tmp/nv/plan.json', JSON.stringify(narr,null,1));
console.log('plan items', narr.length, 'nodes', i);
