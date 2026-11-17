#!/usr/bin/env node
/**
 * a7-estimate-gate.mjs — 工时评估门的准入断言（20261117-r26，access-verify.sh 第 8 段）。
 *
 * 关注点只有一个：**渲染看权限、禁用看门**。
 *   - 按钮不因门而消失（allowedActions 不返回 schedule/start_dev 时仍渲染），只 disabled + 说原因；
 *   - 门文案只有一处来源（web/src/views/req/estimate.ts），卡片与动作行逐字一致；
 *   - 权限文案「需要权限：<码>」与门文案物理分离，互不混用（AC-13）；
 *   - 点击置空 handler，零请求零跳转（静态可证）。
 * 静态一半 + 运行时一半：静态断言防「模板里拼字符串」的回归，运行时断言防「字段改名/门没接上」。
 * 计数行写 ${ACCESS_COUNTS}：a7 <pass> <total>，与 a0–a6 同格式（聚合器据此判下限与总数）。
 */
import { readFileSync, appendFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '../../..')

const DETAIL_VIEW = join(ROOT, 'web/src/views/req/RequirementDetailView.vue')
const OVERVIEW_TAB = join(ROOT, 'web/src/views/req/tabs/OverviewTab.vue')
const APPROVAL_TAB = join(ROOT, 'web/src/views/req/tabs/ApprovalTab.vue')
const CENTER_VIEW = join(ROOT, 'web/src/views/approval/ApprovalCenterView.vue')
const ESTIMATE_TS = join(ROOT, 'web/src/views/req/estimate.ts')
const TYPES_TS = join(ROOT, 'web/src/api/types.ts')

const rel = (p) => p.slice(ROOT.length + 1)
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '')

const results = []
function check(id, ok, detail) {
  results.push({ id, ok, detail })
  const tag = ok ? '\x1b[1;32mPASS\x1b[0m' : '\x1b[1;31mFAIL\x1b[0m'
  console.log(`  ${tag} ${id}${detail ? `：${detail}` : ''}`)
}

/* ---------------------------------------------------------------- 静态：文案来源唯一 */

function staticChecks() {
  console.log('\n\x1b[1;36m[静态] 门文案单一来源 + 渲染看权限/禁用看门\x1b[0m')

  const est = read(ESTIMATE_TS)
  const view = read(DETAIL_VIEW)
  const overview = read(OVERVIEW_TAB)
  const approval = read(APPROVAL_TAB)
  const center = read(CENTER_VIEW)
  const types = read(TYPES_TS)

  // 1) 六态 META 齐全，gateWord 与 PRD §5.4 逐字一致
  const gateWords = { NONE: '无', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', SUPERSEDED: '已作废', STALE: '已失效' }
  const missing = Object.entries(gateWords)
    .filter(([status, word]) => !new RegExp(`${status}\\s*:\\s*\\{[^}]*gateWord:\\s*'${word}'`, 's').test(est))
    .map(([s, w]) => `${s}=${w}`)
  check('S1 六态 META 与 gateWord 逐字齐全（无/待审核/已通过/已驳回/已作废/已失效）',
    missing.length === 0, missing.length ? `缺: ${missing.join(', ')}` : 'estimate.ts')

  // 2) 门文案只在 estimate.ts 出现：其它文件拼字符串必然与卡片漂移（AC-13 逐字一致）
  const needle = '需先通过工时评估'
  const carriers = [DETAIL_VIEW, OVERVIEW_TAB, APPROVAL_TAB, CENTER_VIEW]
    .filter((p) => read(p).includes(needle))
    .map(rel)
  check('S2 门文案只在 estimate.ts 生成，页面不得拼字符串',
    carriers.length === 0, carriers.length ? `重复来源: ${carriers.join(', ')}` : `仅 ${rel(ESTIMATE_TS)}`)

  // 3) 动作行：门未过时按钮仍渲染（v-if=pinnedGateAction）、disabled、title 取 gateActionTip
  const pinnedBtn = /v-if="pinnedGateAction"[\s\S]{0,400}?disabled[\s\S]{0,200}?:title="gateActionTip"/.test(view)
    && /:data-test="`action-\$\{pinnedGateAction\.action\}`"/.test(view)
  check('S3 schedule/start_dev 未过门时仍渲染且 disabled（渲染看权限、禁用看门）', pinnedBtn,
    pinnedBtn ? 'RequirementDetailView.vue' : '缺 pinnedGateAction 禁用按钮或 title=gateActionTip')

  // 4) 两类文案物理分离：权限三分支用 `需要权限：`，门分支调 estimateGateTip，且不互相包含
  const ternary = /pinned\.permitted\s*\?\s*estimateGateTip\(estimateStatus\.value\)\s*:\s*`需要权限：/.test(view)
  const permHasGateWord = /需要权限：[^`]*\$\{[^}]*gateWord/.test(view)
  check('S4 权限文案与门文案分函数生成、不混用（需要权限 里不得掺工时评估）',
    ternary && !permHasGateWord, ternary ? (permHasGateWord ? '权限文案引用了 gateWord' : '两条分支独立') : 'gateActionTip 三元式结构变了')

  // 5) 卡片门提示与动作行同源：两处都调 estimateGateTip，且模板里没有硬编码门文案
  const cardUsesFn = /const gateTip = computed\(\(\) => estimateGateTip\(estimateStatus\.value\)\)/.test(overview)
  const cardBlock = /data-test="estimate-gate-tip"[\s\S]{0,900}?<span class="estimate-gate__text">\{\{ gateTip \}\}<\/span>/.test(overview)
  check('S5 概览卡片门提示与动作行取同一函数（逐字一致的前提）', cardUsesFn && cardBlock,
    cardUsesFn && cardBlock ? 'OverviewTab.vue: estimate-gate-tip → gateTip' : '卡片改用了别处的文案')

  // 6) 点击零请求：pinned handler 必须是空实现（不发请求、不改 URL）
  const emptyHandler = /function onPinnedClick\(\)\s*(?::\s*void)?\s*\{\s*(?:\/\/[^\n]*\s*)*return\s*\}?/.test(view)
    || /const onPinnedClick = \(\)\s*=>\s*\{\s*(?:\/\/[^\n]*\s*)*\}/.test(view)
  check('S6 门禁用态点击零请求零跳转（handler 置空）', emptyHandler,
    emptyHandler ? 'onPinnedClick 空实现' : 'onPinnedClick 里出现了动作')

  // 7) 审批中心：bizKeyOf 的 WBS_ESTIMATE 显式分支必须排在 `raw.includes('REQ')` 之前
  //    （注释里也会出现 includes('REQ') 字样，只认带 raw. 前缀的真实调用，否则会被注释骗过）
  const keyFn = center.slice(center.indexOf('function bizKeyOf'))
  const estimateAt = keyFn.indexOf("'WBS_ESTIMATE'")
  const reqAt = keyFn.search(/\braw\.includes\('REQ'\)/)
  check('S7 审批中心 bizKeyOf 显式识别 WBS_ESTIMATE（放在 includes(REQ) 之前）',
    estimateAt > -1 && reqAt > -1 && estimateAt < reqAt,
    estimateAt > -1 && reqAt > -1 ? (estimateAt < reqAt ? '顺序正确' : '分支在 includes(REQ) 之后，筛不到工时评估') : '缺 WBS_ESTIMATE 分支')
  check('S8 审批中心有「工时评估」标签与筛选项', /ESTIMATE:\s*'工时评估'/.test(center) && /label="工时评估"/.test(center),
    /ESTIMATE:\s*'工时评估'/.test(center) ? '标签+筛选项' : '缺 BIZ_LABELS 或 el-option')

  // 9) 前端 DTO 与后端 EstimateView 字段对齐（改一处必须同步另一处）
  const fields = ['status', 'instanceId', 'instanceStatus', 'proposedDays', 'approvedDays', 'note', 'reason', 'approverId', 'submittedAt', 'decidedAt', 'required']
  const block = types.slice(types.indexOf('interface EstimateBlock'), types.indexOf('interface EstimateBlock') + 1200)
  const absent = fields.filter((f) => !new RegExp(`\\b${f}\\??:`).test(block))
  check('S9 EstimateBlock 字段与后端 EstimateView 一一对应', absent.length === 0,
    absent.length ? `缺: ${absent.join(', ')}` : 'types.ts')

  // 10) 需求「最近动态」必须含评估事件（AC-3/AC-4，US-2）：事件码与文案只在 estimate.ts，
  //     详情页合成、概览消费；否则驳回原因只活在审批页签，研发在需求页看不到「为什么」。
  const eventTable = /ESTIMATE_REJECTED:\s*'工时评估驳回'/.test(est)
  const viewBuilds = /ESTIMATE_EVENT\.REJECTED/.test(view)
    && /const timeline = computed/.test(view)
    && /:timeline="timeline"/.test(view)
  const overviewLabels = /:labels="ESTIMATE_EVENT_LABEL"/.test(overview)
  check('S10 需求动态渲染评估提交/通过/驳回原因（ESTIMATE_* 并入时间线）',
    eventTable && viewBuilds && overviewLabels,
    eventTable && viewBuilds && overviewLabels
      ? 'estimate.ts 事件表 → 详情页合成 → 概览 labels'
      : `eventTable=${eventTable} viewBuilds=${viewBuilds} overviewLabels=${overviewLabels}`)
}

/* ---------------------------------------------------------------- 运行时：门真的接上了 */

const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'

async function login(username, password) {
  const res = await fetch(`${GATEWAY}/api/req/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 't1', username, password }),
  })
  if (!res.ok) return null
  const body = await res.json()
  return { token: body.token, tenantId: String(body.tenantId) }
}

async function api(auth, path) {
  const res = await fetch(`${GATEWAY}${path}`, {
    headers: { Authorization: `Bearer ${auth.token}`, 'X-Tenant-Id': auth.tenantId },
  })
  let body = null
  try { body = await res.json() } catch { /* 非 JSON 响应保持 null */ }
  return { status: res.status, body }
}

async function runtimeChecks() {
  console.log('\n\x1b[1;36m[运行时] 评估单块真的出现在详情响应里（夹具 11 张 APPROVED）\x1b[0m')

  const admin = await login('admin', 'Admin@12345')
  if (!admin) {
    check('R1 管理员登录', false, `${GATEWAY} 未就绪或账号缺失（make platform-up && make platform-seed）`)
    return
  }

  const list = await api(admin, '/api/req/requirements?page=1&size=100')
  const items = list.body?.items ?? []
  const byCode = (code) => items.find((it) => (it.code ?? it.view?.code) === code)
  const pdr = byCode('DEMO-R-02')
  const rd = byCode('DEMO-R-03')
  check('R1 演示夹具在位（DEMO-R-02 / DEMO-R-03）', !!pdr && !!rd,
    pdr && rd ? `id ${pdr.id} / ${rd.id}` : '缺种子需求 → 先跑 make platform-demo-reset')

  if (pdr) {
    const d = await api(admin, `/api/req/requirements/${pdr.id}`)
    const est = d.body?.estimate
    check('R2 详情带 estimate 块，且种子 PDR 评估单为 APPROVED',
      d.status === 200 && est?.status === 'APPROVED' && Number(est?.approvedDays) > 0,
      d.status === 200 ? `status=${est?.status} approved=${est?.approvedDays} proposed=${est?.proposedDays}` : `HTTP ${d.status}`)
    // 快照与落值必须同源：approvedDays 是审批那一刻写下的单据人天，不能被后来的人天变动改写。
    // （需求当前人天可能被其它套件新建的子需求 rollup 改动，所以不拿它跟 approvedDays 比 ——
    //  人天偏差改判 STALE 的场景由 negative 4c 段覆盖。）
    check('R3 approvedDays 与单据快照一致（审批落值不被后续改动污染）',
      Number(est?.approvedDays) === Number(est?.proposedDays) && !!est?.decidedAt,
      `approvedDays=${est?.approvedDays} proposedDays=${est?.proposedDays} decidedAt=${est?.decidedAt}`)
  }

  if (rd) {
    const d = await api(admin, `/api/req/requirements/${rd.id}`)
    check('R4 RD 详情同样带 estimate 块（PDR/RD 都能提评估）',
      d.status === 200 && !!d.body?.estimate && d.body?.estimate?.instanceId > 0,
      d.status === 200 ? `instance=${d.body?.estimate?.instanceId} status=${d.body?.estimate?.status}` : `HTTP ${d.status}`)
    // AC-13 的原子性前提：门状态与 allowedActions 同一次响应返回，前端才能一次请求定渲染与禁用
    check('R5 estimate 与 allowedActions 同一响应返回',
      Array.isArray(d.body?.allowedActions), `allowedActions=${JSON.stringify(d.body?.allowedActions)}`)
  }

  // 厂商账号读自己的需求时也拿得到 estimate 块（数据范围裁剪不能把评估块裁掉）
  const vpm = await login('vpm1', 'Passw0rd!')
  if (vpm) {
    const vlist = await api(vpm, '/api/req/requirements?page=1&size=100')
    const own = (vlist.body?.items ?? []).find((it) => (it.code ?? it.view?.code) === 'DEMO-R-09')
    if (own) {
      const d = await api(vpm, `/api/req/requirements/${own.id}`)
      check('R6 厂商视角读自己的需求仍带 estimate 块', d.status === 200 && !!d.body?.estimate,
        `HTTP ${d.status} status=${d.body?.estimate?.status}`)
    } else {
      check('R6 厂商视角读自己的需求仍带 estimate 块', false, 'vpm1 看不到 DEMO-R-09（VEN-A 夹具）')
    }
  } else {
    check('R6 厂商视角读自己的需求仍带 estimate 块', false, 'vpm1 登录失败')
  }
}

/* ---------------------------------------------------------------- main */

staticChecks()
try {
  await runtimeChecks()
} catch (err) {
  check('R* 运行时断言未执行完', false, String(err && err.message ? err.message : err))
}

const pass = results.filter((r) => r.ok).length
const total = results.length
console.log(`\n  a7 工时评估门：${pass}/${total}`)
if (process.env.ACCESS_COUNTS) {
  appendFileSync(process.env.ACCESS_COUNTS, `a7 ${pass} ${total}\n`)
}
if (pass !== total) {
  console.error('\n\x1b[1;31ma7 未通过：门文案必须单一来源，按钮必须「渲染看权限、禁用看门」\x1b[0m')
  process.exit(1)
}
