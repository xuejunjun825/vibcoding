#!/usr/bin/env node
/**
 * a8-detail-optional-probe.mjs — 需求详情页「审批探测静默」的准入断言（20261124-r45，access-verify.sh 第 9 段）。
 *
 * 关注点只有一个：**审批可见性 ≠ 页面准入**。
 *   审批实例详情是页内辅助信息 —— 读不到（403）只能在审批页签里就地降级成一句说明，
 *   **不能让整页被 /403 替换**；而主数据（getRequirement）必须保持越权即落 /403。
 *
 * 为什么静态 + 运行时都要：
 *   - 静态（S1–S4）钉住「调用点是否声明了 silentForbidden」「403 是否只收敛成布尔」「文案是否唯一」；
 *     只做运行时的话，一旦用例挑到的需求恰好是参与人，403 分支根本走不到，门禁会假绿。
 *   - 运行时（R1–R6）钉住三角色各有可见需求且详情 200（页面能打开的前提），
 *     负向（N1–N3）钉住「非参与实例仍 403 FORBIDDEN 且不泄漏内容」（降级不是把权限模型静默放宽）。
 *   缺夹具判红、不 skip：没有夹具时「没找到非参与实例」不等于「权限模型正确」。
 *
 * 计数行写 ${ACCESS_COUNTS}：`a8 <pass> <total>`，与 a0–a7 同格式（聚合器据此判分段下限与总数）。
 * 13 条 = 静态 4（S1–S4）+ 运行时 6（三角色 × 列表/详情）+ 负向 3（三角色各一条）。
 */
import { readFileSync, appendFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve, extname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '../../..')
// A8_WEB_SRC 只用于负向自检（把树复制到 logs/ 下、抽掉一处 silent，断言本条必须变红），
// 正常运行不传，一律读真实 web/src。
const WEB_SRC = process.env.A8_WEB_SRC ? resolve(process.env.A8_WEB_SRC) : join(ROOT, 'web/src')

const REQ_TS = join(WEB_SRC, 'api/req.ts')
const CLIENT_TS = join(WEB_SRC, 'api/client.ts')
const DETAIL_VIEW = join(WEB_SRC, 'views/req/RequirementDetailView.vue')
const APPROVAL_TAB = join(WEB_SRC, 'views/req/tabs/ApprovalTab.vue')

const HINT = '无权查看审批进度（仅发起人/审批人/抄送人可见）'

const rel = (p) => p.slice(ROOT.length + 1)
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '')

/** 剔掉纯注释行：注释里出现的 `listApprovalMine()` / `getApprovalInstance(` 不算调用点。 */
function codeOnly(src) {
  return src
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n')
}

const countOf = (src, re) => (src.match(new RegExp(re.source, 'g')) || []).length

const results = []
function check(id, ok, detail) {
  results.push({ id, ok, detail })
  const tag = ok ? '\x1b[1;32mPASS\x1b[0m' : '\x1b[1;31mFAIL\x1b[0m'
  console.log(`  ${tag} ${id}${detail ? `：${detail}` : ''}`)
}

/* ---------------------------------------------------------------- 静态：调用点必须声明 silent */

function walk(dir, exts, acc = []) {
  if (!existsSync(dir)) return acc
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, exts, acc)
    else if (exts.includes(extname(name))) acc.push(p)
  }
  return acc
}

function staticChecks() {
  console.log('\n\x1b[1;36m[静态] 探测静默 / 403 收敛成布尔 / 文案唯一 / 注释锚点\x1b[0m')

  const reqTs = read(REQ_TS)
  const detail = read(DETAIL_VIEW)
  const detailCode = codeOnly(detail)
  const approvalTab = read(APPROVAL_TAB)
  const clientTs = read(CLIENT_TS)

  // —— S1：api 封装必须留出 extra 透传口，且 403 静默落在同一次 http 调用上 ——
  const sigOk = /export function getApprovalInstance\(\s*id: number \| string,\s*extra\?: Partial<RequestOptions>,[\s\S]{0,160}?Promise<ApprovalInstanceDetail>/.test(reqTs)
  const bodyOk = /http\.get<ApprovalInstanceDetail>\(`\$\{REQ\}\/approvals\/instances\/\$\{id\}`, undefined, extra\)/.test(reqTs)
  const mineOk = /export function listApprovalMine\([\s\S]{0,200}?extra\?: Partial<RequestOptions>,[\s\S]{0,160}?http\.get<PageResponse<ApprovalTodoRow>>\(`\$\{REQ\}\/approvals\/mine`, params, extra\)/.test(reqTs)
  check('S1 审批实例/我的待办封装透传 extra（第三参 undefined, extra；默认 undefined 不改旧行为）',
    sigOk && bodyOk && mineOk,
    `${sigOk && bodyOk ? 'getApprovalInstance(id, extra?)' : '签名或函数体形态变了'}；${mineOk ? 'listApprovalMine(params, extra?)' : 'listApprovalMine 未透传 extra'}`)

  // —— S2：详情页所有审批探测点都声明 silentForbidden，主数据请求绝不允许声明 ——
  // 判据是「计数相等」而不是 grep 一个字符串：删掉任意一处 silentForbidden 就立刻不平衡。
  const instCalls = countOf(detailCode, /getApprovalInstance\(/)
  const instSilent = countOf(detailCode, /getApprovalInstance\([^)]*\{ silentForbidden: true \}/)
  const mineCalls = countOf(detailCode, /listApprovalMine\(/)
  const mineSilent = countOf(detailCode, /listApprovalMine\([^)]*\{ silentForbidden: true \}/)
  const mainCall = /getRequirement\(id\.value\)/.test(detailCode)
  const mainBare = !/getRequirement\([^)]*silentForbidden/.test(detailCode)
  check('S2 详情页审批探测 3+1 处全部 silent，getRequirement 保持裸调用（主数据不静默）',
    instCalls === 3 && instSilent === 3 && mineCalls === 1 && mineSilent === 1 && mainCall && mainBare,
    `getApprovalInstance ${instSilent}/${instCalls} 带 silent，listApprovalMine ${mineSilent}/${mineCalls}，` +
    `getRequirement ${mainCall ? (mainBare ? '裸调用' : '被声明 silent（越权会被吞掉）') : '调用点消失'}`)

  // —— S3：403 只收敛成布尔（不塞 error 字符串），且文案全 web/src 唯一 ——
  const assign403 = countOf(detailCode, /Forbidden\.value = status === 403/)
  const resetOk = countOf(detailCode, /Forbidden\.value = false/) >= 2
  const silentErr = /approvalForbidden\.value \? '' : messageOf\(error\)/.test(detailCode)
    && /estimateForbidden\.value \? '' : messageOf\(error\)/.test(detailCode)
  const carriers = walk(WEB_SRC, ['.ts', '.vue']).filter((p) => read(p).includes(HINT))
  const uniqueOk = carriers.length === 1 && resolve(carriers[0]) === resolve(APPROVAL_TAB)
    && countOf(approvalTab, /无权查看审批进度（仅发起人\/审批人\/抄送人可见）/) === 1
  const hintBeforeError = approvalTab.indexOf('v-else-if="forbidden"') > -1
    && approvalTab.indexOf('v-else-if="forbidden"') < approvalTab.indexOf('v-else-if="error"')
  const estHintBeforeError = approvalTab.indexOf('v-else-if="estimateForbidden"') > -1
    && approvalTab.indexOf('v-else-if="estimateForbidden"') < approvalTab.indexOf('v-else-if="estimateError"')
  // —— 请求层约定可被断言 + 详情页绝不自己跳 /403 ——
  const optIdx = clientTs.indexOf('silentForbidden?: boolean')
  const blockStart = optIdx > -1 ? clientTs.lastIndexOf('/**', optIdx) : -1
  const comment = optIdx > -1 && blockStart > -1 ? clientTs.slice(blockStart, optIdx) : ''
  const anchors = ['lint L23', 'access a8', 'getRequirement'].filter((k) => !comment.includes(k))
  const noNav = !/navigate\((['"`])\/403\1\)/.test(detail)
  const noLiteral = !detail.includes('/403')
  check('S3 403 收敛成 approvalForbidden/estimateForbidden 布尔（error 置空、两分支复位）',
    assign403 === 2 && resetOk && silentErr,
    `403 赋值 ${assign403}/2，复位 ${resetOk ? '有' : '缺'}，error 置空 ${silentErr ? '两处' : '缺'}`)
  check('S4 403 文案全 web/src 唯一 + forbidden 排在 error 之前 + client.ts 注释锚点齐 + 详情页无 /403',
    uniqueOk && hintBeforeError && estHintBeforeError && anchors.length === 0 && noNav && noLiteral,
    `${uniqueOk ? '文案仅 ApprovalTab.vue 一份' : `文案载体 ${carriers.map(rel).join(', ') || '无'}`}；` +
    `主审批区 ${hintBeforeError ? 'ok' : '顺序错'}，评估块 ${estHintBeforeError ? 'ok' : '顺序错'}；` +
    `${anchors.length ? `client.ts 注释缺: ${anchors.join(', ')}` : '注释锚点齐'}；` +
    `${noNav && noLiteral ? '详情页零 /403 引用' : '详情页仍在跳 /403'}`)
}

/* ---------------------------------------------------------------- 运行时：三角色各有可见需求 */

const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const PASSWORD = 'Passw0rd!'
const FIXTURE_HINT = '夹具缺失：先 make platform-demo-reset（需要 make platform-up + make platform-seed）'

async function login(username, password) {
  try {
    const res = await fetch(`${GATEWAY}/api/req/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantCode: 't1', username, password }),
    })
    if (!res.ok) return null
    const body = await res.json()
    return { token: body.token, tenantId: String(body.tenantId) }
  } catch {
    return null
  }
}

async function api(auth, path) {
  const res = await fetch(`${GATEWAY}${path}`, {
    headers: { Authorization: `Bearer ${auth.token}`, 'X-Tenant-Id': auth.tenantId },
  })
  let body = null
  try { body = await res.json() } catch { /* 非 JSON 响应保持 null */ }
  return { status: res.status, body }
}

const ROLES = [
  { role: 'dev1', user: process.env.A8_DEV_USER || 'dev1', listId: 'R1', detailId: 'R2' },
  { role: 'qa1', user: process.env.A8_QA_USER || 'qa1', listId: 'R3', detailId: 'R4' },
  { role: 'vendor1', user: process.env.A8_VENDOR_USER || 'vendor1', listId: 'R5', detailId: 'R6' },
]

async function runtimeChecks() {
  console.log('\n\x1b[1;36m[运行时] 三角色都能打开自己的需求详情（页面不被 403 替换的前提）\x1b[0m')

  const auths = {}
  for (const r of ROLES) {
    const auth = await login(r.user, PASSWORD)
    auths[r.role] = auth
    if (!auth) {
      check(`${r.listId} ${r.role} 登录后可见需求列表`, false, `${r.user} 登录失败（${GATEWAY} 未就绪或夹具缺失：${FIXTURE_HINT}）`)
      check(`${r.detailId} ${r.role} 打开需求详情返回 200`, false, '登录失败，详情未探测')
      continue
    }
    let list = { status: 0, body: null }
    try {
      list = await api(auth, '/api/req/requirements?page=1&size=50')
    } catch (err) {
      check(`${r.listId} ${r.role} 登录后可见需求列表`, false, String(err && err.message ? err.message : err))
      check(`${r.detailId} ${r.role} 打开需求详情返回 200`, false, '列表请求失败，详情未探测')
      continue
    }
    const items = list.body?.items ?? []
    check(`${r.listId} ${r.role} 登录后可见需求列表（≥1 条）`,
      list.status === 200 && items.length >= 1,
      list.status === 200 && items.length >= 1
        ? `${r.user} 可见 ${items.length} 条`
        : `HTTP ${list.status} items=${items.length} —— ${FIXTURE_HINT}`)
    if (list.status !== 200 || items.length === 0) {
      check(`${r.detailId} ${r.role} 打开需求详情返回 200`, false, '列表为空，无法挑选详情样本')
      continue
    }
    const target = items[0]
    try {
      const d = await api(auth, `/api/req/requirements/${target.id}`)
      const inst = d.body?.estimate?.instanceId ?? '-'
      // 详情响应的主数据在 view 里（RequirementDetailResponse.view），顶层是 estimate/allowedActions
      const bodyId = d.body?.view?.id ?? d.body?.id
      check(`${r.detailId} ${r.role} 打开需求详情返回 200（主数据不因审批 403 而失败）`,
        d.status === 200 && bodyId != null,
        d.status === 200 ? `需求 ${target.code ?? target.id} → 200，view.id=${bodyId}，estimate.instanceId=${inst}` : `HTTP ${d.status}`)
    } catch (err) {
      check(`${r.detailId} ${r.role} 打开需求详情返回 200（主数据不因审批 403 而失败）`, false,
        String(err && err.message ? err.message : err))
    }
  }
  return auths
}

/* ---------------------------------------------------------------- 负向：非参与实例仍 403 且不泄漏 */

/** 从管理员视角收集真实审批实例 id（最多 10 个），供三角色复用。 */
async function candidateInstanceIds(admin) {
  const ids = []
  if (!admin) return ids
  const list = await api(admin, '/api/req/requirements?page=1&size=100')
  const items = (list.body?.items ?? []).slice(0, 40)
  for (const it of items) {
    if (ids.length >= 10) break
    try {
      const d = await api(admin, `/api/req/requirements/${it.id}`)
      const id = d.body?.estimate?.instanceId
      if (id != null && Number(id) > 0 && !ids.includes(Number(id))) ids.push(Number(id))
    } catch { /* 单条探测失败不阻断候选收集 */ }
  }
  return ids
}

async function negativeFor(role, auth, ids) {
  if (!auth) return { ok: false, detail: `${role} 登录失败，无法验证非参与实例 403` }
  if (ids.length === 0) return { ok: false, detail: `没有候选审批实例 —— ${FIXTURE_HINT}` }
  for (const id of ids) {
    const res = await api(auth, `/api/req/approvals/instances/${id}`)
    if (res.status !== 403) continue
    const body = res.body ?? {}
    const raw = JSON.stringify(body)
    const leaked = ['instance', 'document', 'timeline'].filter((k) => raw.includes(`"${k}"`))
    const ok = body.code === 'FORBIDDEN' && body.message === '无权查看该审批实例' && leaked.length === 0
    return {
      ok,
      detail: `实例 ${id} → 403 code=${body.code} message=${body.message}` +
        (leaked.length ? `，泄漏字段: ${leaked.join(',')}` : '，不泄漏 instance/document/timeline'),
    }
  }
  return {
    ok: false,
    detail: `${role} 对 ${ids.length} 个候选实例都不是 403（没有找到非参与实例）—— ${FIXTURE_HINT}`,
  }
}

async function negativeChecks(auths) {
  console.log('\n\x1b[1;36m[负向] 非参与实例仍 403 FORBIDDEN（降级不得放宽权限模型）\x1b[0m')
  let ids = []
  try {
    const admin = await login('admin', 'Admin@12345')
    ids = await candidateInstanceIds(admin)
  } catch (err) {
    console.log(`  （候选实例收集失败：${err && err.message ? err.message : err}）`)
  }
  const pairs = [
    ['N1', 'dev1'],
    ['N2', 'qa1'],
    ['N3', 'vendor1'],
  ]
  for (const [id, role] of pairs) {
    try {
      const r = await negativeFor(role, auths[role], ids)
      check(`${id} ${role} 对非参与审批实例仍 403 FORBIDDEN 且不泄漏内容`, r.ok, r.detail)
    } catch (err) {
      check(`${id} ${role} 对非参与审批实例仍 403 FORBIDDEN 且不泄漏内容`, false,
        String(err && err.message ? err.message : err))
    }
  }
}

/* ---------------------------------------------------------------- main */

staticChecks()
let auths = {}
try {
  auths = (await runtimeChecks()) ?? {}
} catch (err) {
  console.log(`  （运行时断言异常：${err && err.message ? err.message : err}）`)
}
try {
  await negativeChecks(auths)
} catch (err) {
  console.log(`  （负向断言异常：${err && err.message ? err.message : err}）`)
}

const pass = results.filter((r) => r.ok).length
const total = results.length
console.log(`\n  a8 详情页审批探测：${pass}/${total}`)
if (process.env.ACCESS_COUNTS) {
  appendFileSync(process.env.ACCESS_COUNTS, `a8 ${pass} ${total}\n`)
}
if (total !== 13) {
  console.error(`\n\x1b[1;31ma8 断言数 ${total} != 13：断言被删或中途失效（13 = 静态 4 + 运行时 6 + 负向 3）\x1b[0m`)
  process.exit(1)
}
if (pass !== total) {
  console.error('\n\x1b[1;31ma8 未通过：审批探测必须静默、403 只能就地降级、非参与实例仍须 403\x1b[0m')
  process.exit(1)
}
