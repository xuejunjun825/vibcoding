#!/usr/bin/env node
/**
 * a5-capability-probe.mjs — 可选能力探测门禁（PRD AC-2/AC-4/AC-5；TECHNICAL §8）
 *
 * 为什么单独一段：a2 证明的是「路由准入」（GET /code/policies 200），本段证明的是
 * 「能力准入」（页面内可选探测 403 时必须降级，不得整页跳 /403）。r8 的缺陷正卡在
 * 两者之间：主端点 200、探测端点 403，而 client.ts 在 throw **之前**就 navigate('/403')，
 * 页面里的 try/catch 结构再对也永远收不到那个错误。只断言主端点 200 的门禁看不见它。
 *
 * 22 条断言：
 *   a5-a 运行时 14 条：7 账号 × 2 端点精确状态码（与 a4 同一登录口径，Node 原生 fetch）
 *   a5-b 静态 5 条：调用点标记 / 非管理页逐点审计（scanned≥1 防空扫）/ client.ts 403 分支 /
 *                   页面里 /integration/configs 字面量归零 / 豁免台账每条带理由
 *   a5-c 自检 3 条：内存夹具证明「缺标记必红」「带标记必绿」「同文件别处出现关键字仍红」
 *
 * 计数经环境变量 ACCESS_COUNTS 交给 scripts/access-verify.sh（格式 `a5 <pass> <total>`）。
 * 栈未起时 exit 1 且**不写计数行**——「0 条断言也算过」正是本段要防的假绿。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
export const ROOT = path.resolve(HERE, '../../..')
const WEB = path.join(ROOT, 'web/src')
const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const TENANT = process.env.A5_TENANT || 't1'

/* ================================================================ 审计器（导出，供自检与复用） */

/** 把注释替换成等长空格、**保留换行**：注释里的字样不算声明，但行号必须还能对上源码 */
export function blankComments(src) {
  let out = ''
  let quote = null
  let i = 0
  while (i < src.length) {
    const ch = src[i]
    if (quote) {
      out += ch
      if (ch === '\\' && i + 1 < src.length) { out += src[i + 1]; i += 2; continue }
      if (ch === quote) quote = null
      i++
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; out += ch; i++; continue }
    if (ch === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') { out += ' '; i++ }
      continue
    }
    if (ch === '/' && src[i + 1] === '*') {
      out += '  '
      i += 2
      while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) {
        out += src[i] === '\n' ? '\n' : ' '
        i++
      }
      if (i < src.length) { out += '  '; i += 2 }
      continue
    }
    out += ch
    i++
  }
  return out
}

/** 从 `open` 处的 '(' 起配平括号，返回闭括号下标；不配平返回 -1 */
function matchParen(text, open) {
  let depth = 0
  for (let i = open; i < text.length; i++) {
    const c = text[i]
    if (c === '(') depth++
    else if (c === ')' && --depth === 0) return i
  }
  return -1
}

/**
 * 取 `callee(` 的平衡括号实参文本；注释先剔除。
 * 不用正则 `callee\s*\([^)]*\)`：`{ silentForbidden: true }` 正好在括号里，正则会截断。
 * 函数声明（`function listConfigs(`）/常量定义（`const listConfigs =`）不是调用点，跳过。
 */
export function callArgs(src, callee) {
  const text = blankComments(src)
  const out = []
  let from = 0
  for (;;) {
    const start = text.indexOf(callee, from)
    if (start < 0) break
    from = start + callee.length
    const prev = start > 0 ? text[start - 1] : ''
    if (/[\w$]/.test(prev)) continue
    if (prev === '.' && !callee.includes('.')) continue
    const before = text.slice(Math.max(0, start - 40), start)
    if (/\bfunction\s+$/.test(before) || /\b(const|let|var)\s+$/.test(before)) continue
    let j = from
    while (j < text.length && /\s/.test(text[j])) j++
    if (text[j] !== '(') continue
    const end = matchParen(text, j)
    if (end < 0) { out.push({ index: start, args: text.slice(j + 1) }); continue }
    out.push({ index: start, args: text.slice(j + 1, end) })
    from = end + 1
  }
  return out
}

/** 实参里是否**显式**声明了 silentForbidden（`{ silentForbidden: false }` 不算） */
function declaresSilent(args) {
  for (const m of args.matchAll(/\bsilentForbidden\b\s*(?::\s*([A-Za-z0-9_$.!]+))?/g)) {
    const value = m[1]
    if (value === undefined || value === 'true' || value === '!0') return true
  }
  return false
}

/** 页面绕过 api 层直连时也按调用点判：`http.get('/integration/configs', ...)` */
function rawHttpConfigCalls(src) {
  const text = blankComments(src)
  const out = []
  for (const m of text.matchAll(/\bhttp\s*\.\s*(?:get|post|put|patch|delete)\s*\(/g)) {
    const open = m.index + m[0].length - 1
    const end = matchParen(text, open)
    if (end < 0) continue
    const args = text.slice(open + 1, end)
    if (!/\/integration\/configs/.test(args)) continue
    out.push({ index: m.index, args })
  }
  return out
}

function lineOf(src, index) {
  let line = 1
  for (let i = 0; i < index && i < src.length; i++) if (src[i] === '\n') line++
  return line
}

function snippetOf(src, index) {
  return src.slice(index, index + 160).replace(/\s+/g, ' ').trim()
}

/**
 * 判定「集成配置读调用点是否显式声明了 silentForbidden」。
 * @param {{ file: string, src: string }[]} sources file 为相对仓库根路径（报错用）
 * @returns {{ scanned: number, offenders: { file: string, line: number, snippet: string }[] }}
 */
export function auditCapabilityCalls(sources) {
  let scanned = 0
  const offenders = []
  for (const { file, src } of sources) {
    const sites = [
      ...callArgs(src, 'listConfigs').map((s) => ({ ...s, kind: 'listConfigs' })),
      ...rawHttpConfigCalls(src).map((s) => ({ ...s, kind: 'http' })),
    ].sort((a, b) => a.index - b.index)
    for (const site of sites) {
      scanned++
      if (declaresSilent(site.args)) continue
      offenders.push({ file, line: lineOf(src, site.index), snippet: snippetOf(src, site.index) })
    }
  }
  return { scanned, offenders }
}

/* ================================================================ 静态夹具事实 */

/**
 * 豁免台账：**未声明且未豁免 = 0** 才算过；每条必须带 file/line/reason，file 必须存在。
 * 管理页（`web/src/views/admin/**`）按路径豁免「读自身守卫码对应端点」，不进本台账；
 * 台账只收「管理页之外但确实不需要降级」的例外，且必须写清理由（TECHNICAL §4.3）。
 */
const EXEMPT_CALL_SITES = []

const CODE_POLICY_VIEW = 'web/src/views/code/CodePolicyView.vue'
const CLIENT_TS = 'web/src/api/client.ts'

/* ================================================================ 运行时矩阵（TECHNICAL §8.1） */

const ROLES = [
  { user: 'admin', pass: 'Admin@12345', policies: 200, configs: 200, why: '唯一持 integration:manage 的账号，探测真的能读到' },
  { user: 'dev1', pass: 'Passw0rd!', policies: 200, configs: 403, why: '能进页 + 探测必 403 → 前端必须降级（本缺陷的同构条件）' },
  { user: 'tl1', pass: 'Passw0rd!', policies: 200, configs: 403, why: '能进页 + 探测必 403 → 前端必须降级' },
  { user: 'vendor1', pass: 'Passw0rd!', policies: 200, configs: 403, why: '能进页 + 探测必 403（且不因域裁剪变成别的码）' },
  { user: 'pm', pass: 'Passw0rd!', policies: 403, configs: 403, why: '无 CCODE_POLICY_VIEW → 路由层就挡住' },
  { user: 'po1', pass: 'Passw0rd!', policies: 403, configs: 403, why: '无 CCODE_POLICY_VIEW → 路由层就挡住' },
  { user: 'qa1', pass: 'Passw0rd!', policies: 403, configs: 403, why: '无 CCODE_POLICY_VIEW → 路由层就挡住' },
]

const ENDPOINTS = [
  { key: 'policies', label: 'GET /api/req/code/policies', path: '/api/req/code/policies' },
  { key: 'configs', label: 'GET /api/req/integration/configs?system=GITLAB', path: '/api/req/integration/configs?system=GITLAB' },
]

function stackDown(message) {
  console.error('a5: 平台栈未就绪（先 make platform-up && make platform-seed）')
  console.error(`  ${message}`)
  process.exit(1) // 故意不写计数行：让聚合器报「该段无计数」，而不是 0 条也算过
}

async function login(role) {
  let res
  try {
    res = await fetch(`${GATEWAY}/api/req/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantCode: TENANT, username: role.user, password: role.pass }),
    })
  } catch (e) {
    stackDown(`${GATEWAY}/api/req/auth/login 不可达：${e.message}`)
  }
  const text = await res.text()
  if (res.status !== 200) stackDown(`账号 ${role.user} 登录失败 HTTP ${res.status}：${text.slice(0, 200)}`)
  let body
  try {
    body = JSON.parse(text)
  } catch {
    stackDown(`账号 ${role.user} 登录响应不是 JSON：${text.slice(0, 200)}`)
  }
  if (!body.token) stackDown(`账号 ${role.user} 登录响应缺 token（种子未就绪？）`)
  return { token: body.token, tenantId: body.tenantId ?? TENANT }
}

async function probe(auth, pathname) {
  const res = await fetch(`${GATEWAY}${pathname}`, {
    headers: {
      Authorization: `Bearer ${auth.token}`,
      'X-Tenant-Id': String(auth.tenantId),
    },
  })
  await res.text().catch(() => '')
  return res.status
}

/* ================================================================ 源码扫描 */

const SRC_EXT = /\.(vue|ts|tsx|js|mjs|cjs)$/

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const abs = path.join(dir, name)
    const st = statSync(abs)
    if (st.isDirectory()) { walk(abs, out); continue }
    if (SRC_EXT.test(name)) out.push(abs)
  }
  return out
}

function rel(abs) {
  return path.relative(ROOT, abs).split(path.sep).join('/')
}

function sources(files) {
  return files.map((abs) => ({ file: rel(abs), src: readFileSync(abs, 'utf8') }))
}

function fileExists(relPath) {
  try {
    return statSync(path.join(ROOT, relPath)).isFile()
  } catch {
    return false
  }
}

function isExempt(offender) {
  return EXEMPT_CALL_SITES.some((e) => e.file === offender.file && e.line === offender.line)
}

/* ================================================================ 断言 */
async function main() {
  let pass = 0
  let total = 0
  const failures = []
  const ok = (msg) => {
    total++
    pass++
    console.log(`  \u001b[1;32mPASS\u001b[0m ${msg}`)
  }
  const bad = (msg, fix) => {
    total++
    failures.push(`${msg}${fix ? `；修复: ${fix}` : ''}`)
    console.log(`  \u001b[1;31mFAIL\u001b[0m ${msg}`)
    if (fix) console.log(`       修复: ${fix}`)
  }

  /* ---------- a5-a 运行时 14 条：路由准入与能力准入分开判 ---------- */
  console.log('\n---- a5-a 运行时：7 账号 × 2 端点精确状态码 ----')
  for (const role of ROLES) {
    const auth = await login(role)
    for (const ep of ENDPOINTS) {
      const want = role[ep.key]
      const got = await probe(auth, ep.path)
      const desc = `${role.user} → ${ep.label} 期望 ${want}`
      if (got === want) ok(`${desc}（实际 ${got}）`)
      else bad(`${desc}，实际 ${got}`, `${role.why}；状态码矩阵见 TECHNICAL §8.1`)
    }
  }

  /* ---------- a5-b 静态 5 条 ---------- */
  console.log('\n---- a5-b 静态：调用点标记 / 403 分支 / 字面量 / 豁免台账 ----')

  // S1：分支策略页的那一次探测恰好 1 处，且实参同时带 'GITLAB' 与 silentForbidden
  const policySrc = readFileSync(path.join(ROOT, CODE_POLICY_VIEW), 'utf8')
  const policyCalls = callArgs(policySrc, 'listConfigs')
  const s1Calls = policyCalls.filter((c) => /['"]GITLAB['"]/.test(c.args))
  if (policyCalls.length === 1 && s1Calls.length === 1 && declaresSilent(s1Calls[0].args)) {
    ok(`S1 ${CODE_POLICY_VIEW} 的可选探测调用点恰好 1 处且实参含 'GITLAB' + silentForbidden`)
  } else {
    bad(
      `S1 ${CODE_POLICY_VIEW} 的 listConfigs 调用点 ${policyCalls.length} 处（带 GITLAB ${s1Calls.length} 处），` +
        `标记${s1Calls.length && declaresSilent(s1Calls[0].args) ? '已' : '未'}声明`,
      '写成 listConfigs(\'GITLAB\', { silentForbidden: true })：标记必须落在**这次调用的实参**上',
    )
  }

  // S2：views/**（排除 views/admin/**）逐调用点都必须带标记；scanned≥1 防空扫绿
  const viewSources = sources(walk(path.join(WEB, 'views'))).filter(
    (s) => !s.file.startsWith('web/src/views/admin/'),
  )
  const viewAudit = auditCapabilityCalls(viewSources)
  const viewViolations = viewAudit.offenders.filter((o) => !isExempt(o))
  if (viewAudit.scanned >= 1 && viewViolations.length === 0) {
    ok(`S2 非管理页 ${viewAudit.scanned} 个集成配置读调用点逐点带 silentForbidden（未声明且未豁免 = 0）`)
  } else if (viewAudit.scanned < 1) {
    bad('S2 非管理页一个集成配置读调用点都没扫到（防空扫绿）', '审计器口径见 TECHNICAL §8.2：listConfigs( 与 http.*(\'/integration/configs\' 都算调用点')
  } else {
    bad(
      `S2 未声明 silentForbidden 的调用点：${viewViolations.map((o) => `${o.file}:${o.line}`).join('、')}`,
      '给该调用传 { silentForbidden: true }；否则 403 会在 client.ts 里先跳 /403 再抛错，页面 catch 收不到',
    )
  }

  // S3：client.ts 的 403 分支仍「先判标记再跳转」——本轮零改动，改掉就是安全语义倒退
  const clientSrc = readFileSync(path.join(ROOT, CLIENT_TS), 'utf8')
  const branch = clientSrc.indexOf('response.status === 403')
  const window = branch < 0 ? '' : clientSrc.slice(branch, branch + 300)
  const hasGuard = /if\s*\(\s*!options\.silentForbidden\s*\)/.test(window)
  const hasNav = /navigate\(\s*`\/403/.test(window)
  if (hasGuard && hasNav) {
    ok(`S3 ${CLIENT_TS}:${lineOf(clientSrc, branch)} 403 分支仍为「!silentForbidden → navigate('/403')」`)
  } else {
    bad(
      `S3 ${CLIENT_TS} 的 403 分支被改动（silentForbidden 判定 ${hasGuard ? '在' : '缺失'}，navigate('/403') ${hasNav ? '在' : '缺失'}）`,
      'K12：client.ts 本轮零改动；默认跳转是安全语义，删掉它 pm/po1/qa1 的深链就不再落 403',
    )
  }

  // S4：页面不得绕过 api/integration.ts 直连端点
  const pageSources = sources([
    ...walk(path.join(WEB, 'views')),
    ...walk(path.join(WEB, 'components')),
  ])
  const literalHits = pageSources
    .filter((s) => s.src.includes('/integration/configs'))
    .map((s) => s.file)
  if (literalHits.length === 0 && pageSources.length > 0) {
    ok(`S4 views/components ${pageSources.length} 个源文件里 /integration/configs 字面量 0 次（页面只走 api 层）`)
  } else {
    bad(
      `S4 views/components 出现 /integration/configs 字面量：${literalHits.join('、') || '（源文件数为 0）'}`,
      '页面只能调 api/integration.ts 的封装；直连会让 silentForbidden 无处声明，也让本段审计看不见它',
    )
  }

  // S5：豁免台账每条带理由 + 全仓（除 api/ 与管理页）未声明且未豁免 = 0
  const ledgerProblems = EXEMPT_CALL_SITES.flatMap((e, i) => {
    const tag = `#${i + 1}(${e.file ?? '?'}:${e.line ?? '?'})`
    const problems = []
    if (typeof e.file !== 'string' || !e.file) problems.push(`${tag} 缺 file`)
    if (!Number.isInteger(e.line) || e.line <= 0) problems.push(`${tag} 缺 line`)
    if (typeof e.reason !== 'string' || e.reason.trim().length < 5) problems.push(`${tag} 缺 reason`)
    if (typeof e.file === 'string' && e.file && !fileExists(e.file)) problems.push(`${tag} 文件不存在`)
    return problems
  })
  const broadSources = sources(walk(WEB)).filter(
    (s) => !s.file.startsWith('web/src/api/') && !s.file.startsWith('web/src/views/admin/'),
  )
  const broadAudit = auditCapabilityCalls(broadSources)
  const broadViolations = broadAudit.offenders.filter((o) => !isExempt(o))
  if (ledgerProblems.length === 0 && broadViolations.length === 0) {
    ok(`S5 豁免台账 ${EXEMPT_CALL_SITES.length} 条均带 file/line/reason 且文件存在；全仓未声明且未豁免 = 0`)
  } else {
    bad(
      `S5 ${[...ledgerProblems, ...broadViolations.map((o) => `${o.file}:${o.line} 未声明且未豁免`)].join('；')}`,
      '豁免必须写清「为什么这一次 403 不需要降级」，否则删标记也能靠加豁免糊过去',
    )
  }

  /* ---------- a5-c 审计器自检 3 条：夹具在内存里，不落盘 ---------- */
  console.log('\n---- a5-c 自检：门禁不是「匹配到关键字就过」 ----')

  const fixtureBad = "// 夹具 C1：故意缺标记\nconst c = await listConfigs('GITLAB')\n"
  const c1 = auditCapabilityCalls([{ file: CODE_POLICY_VIEW, src: fixtureBad }])
  if (c1.offenders.length === 1 && c1.offenders[0].line === 2) {
    ok(`C1 缺标记的调用点被判红，且行号准确（${c1.offenders[0].file}:${c1.offenders[0].line}）`)
  } else {
    bad(`C1 缺标记夹具未按预期判红：offenders=${JSON.stringify(c1.offenders)}`, '审计器必须按调用点判定并报出文件:行')
  }

  const fixtureGood = "// 夹具 C2：带标记\nconst c = await listConfigs('GITLAB', { silentForbidden: true })\n"
  const c2 = auditCapabilityCalls([{ file: CODE_POLICY_VIEW, src: fixtureGood }])
  if (c2.offenders.length === 0 && c2.scanned === 1) {
    ok('C2 带 { silentForbidden: true } 的同一调用点判绿（scanned = 1，不是空扫）')
  } else {
    bad(`C2 带标记夹具未判绿：scanned=${c2.scanned} offenders=${JSON.stringify(c2.offenders)}`, '实参对象里的标记必须被识别')
  }

  const fixtureNearby = [
    '// 夹具 C3：坏调用 + 同文件另一处带标记 + 注释里出现关键字',
    "const a = await listConfigs('GITLAB')",
    '// silentForbidden 只写在注释里，不算这次调用的声明',
    "const b = await listConfigs('GITLAB', { silentForbidden: true })",
  ].join('\n')
  const c3 = auditCapabilityCalls([{ file: CODE_POLICY_VIEW, src: fixtureNearby }])
  if (c3.offenders.length === 1 && c3.offenders[0].line === 2 && c3.scanned === 2) {
    ok('C3 同文件别处出现 silentForbidden （注释/另一调用）时，坏调用仍判红且只报它')
  } else {
    bad(
      `C3 关键字假绿：offenders=${JSON.stringify(c3.offenders)} scanned=${c3.scanned}`,
      '必须按调用点判定：文件里出现关键字就过，等于删了实参门禁还绿（PRD §12 反目标）',
    )
  }

  /* ---------- 计数与退出 ---------- */
  const countsFile = process.env.ACCESS_COUNTS
  if (countsFile) {
    const { appendFileSync } = await import('node:fs')
    appendFileSync(countsFile, `a5 ${pass} ${total}\n`)
  }
  if (total === 0) {
    console.error('a5: 一条断言都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (failures.length) {
    console.error(`\na5-capability-probe: ${total - pass} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f}`)
    process.exit(1)
  }
  console.log(`\n  ok a5-capability-probe: ${pass}/${total} 项通过（能力准入：探测可读性不影响路由准入）`)
}

// 作为脚本直接运行时执行并给出退出码；被 import 时只取审计器（a3 的同款口径），
// 否则任何 import 都会顺手打一遍 HTTP 断言并抢走 process.exit。
const invokedDirectly =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  main().catch((e) => {
    console.error(`a5-capability-probe 无法执行: ${e.message}`)
    console.error('  修复: 先 make platform-up && make platform-seed（需要网关与 7 个种子账号）')
    process.exit(1)
  })
}

