#!/usr/bin/env node
/**
 * s1-registry-three-way.mjs — 注册表三向比对（TECHNICAL §4.1/§4.2、AC-2）
 *
 * 三方：`sys_menu`/`sys_action`（库） = `MenuRegistry`/`ActionRegistry`（Java 常量）
 *       = `web/src/layouts/nav.ts` 的 pageCode（前端导航）。
 *
 * 期望值一律**从源码解析**，脚本里不抄任何清单：抄一份就等于制造第四份真相，
 * 而这一段的全部意义是「三份已经够多了」。
 *
 * 运行：node scripts/tests/rbac/s1-registry-three-way.mjs
 */
import { readFileSync, appendFileSync, readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '../../..')
const COUNTS = process.env.RBAC_COUNTS

let total = 0
let pass = 0
const failures = []

const ok = (label, detail = '') => {
  total += 1
  pass += 1
  console.log(`  \u001b[1;32mPASS\u001b[0m ${label}${detail ? `（${detail}）` : ''}`)
}
const bad = (label, hint, detail = '') => {
  total += 1
  failures.push(label)
  console.log(`  \u001b[1;31mFAIL\u001b[0m ${label}${detail ? `（${detail}）` : ''}`)
  if (hint) console.log(`       修复: ${hint}`)
}
const eq = (label, want, got, hint) =>
  want === got ? ok(label, String(got)) : bad(label, hint, `实际 ${JSON.stringify(got)}，期望 ${JSON.stringify(want)}`)

const setEq = (label, want, got, hint) => {
  const missing = [...want].filter((x) => !got.has(x)).sort()
  const extra = [...got].filter((x) => !want.has(x)).sort()
  if (!missing.length && !extra.length) ok(label, `${got.size} 项`)
  else bad(label, hint, `缺 ${JSON.stringify(missing)} / 多 ${JSON.stringify(extra)}`)
}

const read = (rel) => {
  try {
    return readFileSync(resolve(ROOT, rel), 'utf8')
  } catch {
    return ''
  }
}

/* ------------------------------------------------------------------ 解析 Java 常量 */

/** 从 `start` 处的 '(' 起，截出配平括号内的实参串（忽略字符串里的括号）。 */
function sliceArgs(src, open) {
  let depth = 0
  let inStr = false
  let esc = false
  for (let i = open; i < src.length; i += 1) {
    const ch = src[i]
    if (inStr) {
      if (esc) esc = false
      else if (ch === '\\') esc = true
      else if (ch === '"') inStr = false
      continue
    }
    if (ch === '"') inStr = true
    else if (ch === '(') depth += 1
    else if (ch === ')') {
      depth -= 1
      if (depth === 0) return src.slice(open + 1, i)
    }
  }
  return ''
}

/** 顶层逗号切分（尊重括号与字符串）。 */
function splitTop(s) {
  const parts = []
  let depth = 0
  let cur = ''
  let inStr = false
  let esc = false
  for (const ch of s) {
    if (inStr) {
      cur += ch
      if (esc) esc = false
      else if (ch === '\\') esc = true
      else if (ch === '"') inStr = false
      continue
    }
    if (ch === '"') {
      inStr = true
      cur += ch
      continue
    }
    if ('([{'.includes(ch)) depth += 1
    if (')]}'.includes(ch)) depth -= 1
    if (ch === ',' && depth === 0) {
      parts.push(cur.trim())
      cur = ''
      continue
    }
    cur += ch
  }
  if (cur.trim()) parts.push(cur.trim())
  return parts
}

/** 抓 `ctor("…` 形式的一次调用（不依赖 `new`、不依赖单行：多行实参由 sliceArgs 配平）。 */
function calls(src, ctor) {
  const out = []
  const re = new RegExp(`(?:^|[^A-Za-z0-9_.])${ctor}\\s*\\(\\s*"`, 'g')
  let m
  while ((m = re.exec(src)) !== null) {
    const open = src.indexOf('(', m.index)
    out.push(splitTop(sliceArgs(src, open)))
    re.lastIndex = open
  }
  return out
}

const firstStr = (arg = '') => (arg.match(/"([^"]*)"/) || [])[1] ?? ''
const strings = (arg = '') => [...arg.matchAll(/"([^"]*)"/g)].map((m) => m[1])
const truthy = (arg = '') => arg.trim() === 'true'

/* ------------------------------------------------------------------ MenuRegistry */
const menuSrc = read('platform-contract/src/main/java/com/example/platform/contract/MenuRegistry.java')
if (!menuSrc) {
  bad('MenuRegistry.java 存在', 'T1 未落地：platform-contract/.../MenuRegistry.java')
}
const pages = calls(menuSrc, 'new\\s+MenuPage').map((a) => ({
  code: firstStr(a[0]),
  module: firstStr(a[1]),
  name: firstStr(a[2]),
  routeName: firstStr(a[3]),
  functionCodes: strings(a[4]),
  sort: Number.parseInt(a[5] || '0', 10),
})).filter((p) => p.code)
eq('解析 MenuRegistry 页面数 = 38', 38, pages.length, '注册表必须恰好 38 页（TECHNICAL §10.1）')

/* ------------------------------------------------------------------ ActionRegistry */
const actionSrc = read('platform-contract/src/main/java/com/example/platform/contract/ActionRegistry.java')
if (!actionSrc) bad('ActionRegistry.java 存在', 'T1 未落地：platform-contract/.../ActionRegistry.java')
const actions = []
for (const [ctor, high] of [['action', false], ['highRisk', true]]) {
  for (const a of calls(actionSrc, ctor)) {
    const code = firstStr(a[0])
    if (!code) continue
    actions.push({
      code,
      menuCode: firstStr(a[1]),
      name: firstStr(a[2]),
      functionCodes: a.slice(3).map((x) => firstStr(x)).filter(Boolean),
      highRisk: high,
    })
  }
}
eq('解析 ActionRegistry 动作数 = 81', 81, actions.length, '注册表必须恰好 81 个动作（TECHNICAL §10.1）')

const highRiskSrc = new Set(strings((actionSrc.match(/HIGH_RISK\s*=\s*Set\.of\(([\s\S]*?)\)/) || [])[1] || ''))
const highRiskDeclared = new Set(actions.filter((a) => a.highRisk).map((a) => a.code))
setEq('HIGH_RISK 常量与 highRisk(...) 声明一致（3 个高危）', highRiskDeclared, highRiskSrc,
  '高危动作只能有一份来源：`highRisk(...)` 与 `HIGH_RISK` 必须同集合')
eq('HIGH_RISK 数量 = 3', 3, highRiskSrc.size, 'AC-12：force_release / disable / grant')

/* ------------------------------------------------------------------ nav.ts */
const navSrc = read('web/src/layouts/nav.ts')
if (!navSrc) bad('web/src/layouts/nav.ts 存在', 'T5a 未落地：导航未加 pageCode')
const navCodes = new Set([...navSrc.matchAll(/pageCode:\s*['"]([^'"]*)['"]/g)]
  .map((m) => m[1]).filter(Boolean))
const notificationsHasNoPage = /to:\s*'\/notifications'[\s\S]{0,240}?pageCode:\s*['"]{2}/.test(navSrc)
  || !/to:\s*'\/notifications'[\s\S]{0,240}?pageCode:\s*['"][a-z]/.test(navSrc)

/* ------------------------------------------------------------------ DB */
function dbQuery(sql) {
  const url = process.env.PLATFORM_DB_URL
    || 'jdbc:mysql://127.0.0.1:3306/devops_platform?useSSL=false&allowPublicKeyRetrieval=true'
  const m = url.replace(/^jdbc:mysql:\/\//, '').match(/^([^:/]+)(?::(\d+))?\/([^?]+)/)
  const [, host, port, db] = m || ['127.0.0.1', '3306', 'devops_platform']
  const r = spawnSync('mysql', [
    '--protocol=TCP', '-h', host, '-P', port || '3306',
    '-u', process.env.PLATFORM_DB_USER || 'root',
    '-N', '-B', '--default-character-set=utf8mb4', db, '-e', sql,
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.PLATFORM_DB_PASSWORD || '' } })
  if (r.status !== 0) {
    console.log(`  \u001b[1;31mDB 查询失败\u001b[0m ${r.stderr?.trim().split('\n')[0] || ''}`)
    return []
  }
  return r.stdout.split('\n').filter((l) => l.length > 0).map((l) => l.split('\t'))
}

const dbMenus = dbQuery('SELECT code, module, name, route_name, function_codes FROM sys_menu ORDER BY sort_no, code')
const dbActions = dbQuery('SELECT code, menu_code, high_risk FROM sys_action ORDER BY code')

eq('sys_menu 行数 = 38', 38, dbMenus.length, 'RbacSeeder.seedRegistries 未落库')
eq('sys_action 行数 = 81', 81, dbActions.length, 'RbacSeeder.seedRegistries 未落库')

const pageCodes = new Set(pages.map((p) => p.code))
const dbPageCodes = new Set(dbMenus.map((r) => r[0]))
setEq('库 sys_menu = MenuRegistry（页面码集合）', pageCodes, dbPageCodes,
  '两处必须逐码相同：新增页面要同时改 MenuRegistry 与重启种子')
setEq('nav.ts 的 pageCode = MenuRegistry（排除 notifications）', pageCodes, navCodes,
  '导航漏 pageCode 会让页面永远不可见；多出的码是幽灵菜单（AC-2）')
eq('导航 /notifications 不参与授权（pageCode 为空）', true, notificationsHasNoPage,
  '通知是登录可读页，注册进 sys_menu 会污染 1980 行计数')
eq('导航含新增「角色与权限」入口', true, navCodes.has('admin.roles'),
  'T5a：nav.ts 需新增 /admin/roles（pageCode: admin.roles）')

// 路由文件按目录扫描而不是手写清单：手写清单漏一个文件（flow/integration/base）就会
// 把好路由报成「未声明」，让这一条断言失去意义。
const routeDir = resolve(ROOT, 'web/src/router/routes')
const routeFiles = readdirSync(routeDir).filter((f) => f.endsWith('.ts')).sort()
  .map((f) => `web/src/router/routes/${f}`)
const routerSrc = [...routeFiles, 'web/src/router/index.ts'].map(read).join('\n')
const declaredRoutes = new Set([...routerSrc.matchAll(/name:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]))
const missingRoutes = pages.filter((p) => p.routeName && !declaredRoutes.has(p.routeName)).map((p) => p.routeName)
eq('每个注册页面的 routeName 在路由文件里已声明', 0, missingRoutes.length,
  `缺: ${JSON.stringify(missingRoutes)}（routeName 拼错会让导航自动隐藏）`)

const actionCodes = new Set(actions.map((a) => a.code))
setEq('库 sys_action = ActionRegistry（动作码集合）', actionCodes, new Set(dbActions.map((r) => r[0])),
  '动作码是种子、拦截器与前端按钮三处的判据，必须逐码相同')

const orphanActions = actions.filter((a) => !pageCodes.has(a.menuCode))
eq('每个动作都归属已注册页面（menuCode ∈ MenuRegistry）', 0, orphanActions.length,
  `越界: ${JSON.stringify(orphanActions.map((a) => `${a.code}→${a.menuCode}`))}`)

const dbOrphans = dbActions.filter((r) => !dbPageCodes.has(r[1]))
eq('库内动作的 menu_code 都存在于 sys_menu', 0, dbOrphans.length,
  `越界: ${JSON.stringify(dbOrphans.map((r) => `${r[0]}→${r[1]}`))}`)

const badNames = actions.filter((a) => !/^[a-z][a-z0-9]*\.action\.[a-z0-9_]+$/.test(a.code))
eq('动作码命名符合 <module>.action.<verb>', 0, badNames.length,
  `不合规: ${JSON.stringify(badNames.map((a) => a.code).slice(0, 5))}`)

const dbHigh = new Set(dbActions.filter((r) => String(r[2]) === '1').map((r) => r[0]))
setEq('库内 high_risk=1 的动作 = HIGH_RISK（3 个）', highRiskSrc, dbHigh,
  '高危标记只由 ActionRegistry 推导；库里多了会让普通授权被要求二次确认')

/* 每个页面的绑定功能码：库与常量必须一致（MENU 种子 = max(这些码)） */
const dbFn = new Map(dbMenus.map((r) => [r[0], (r[4] || '').split(',').map((s) => s.trim()).filter(Boolean)]))
const fnMismatch = pages.filter((p) => {
  const got = dbFn.get(p.code) || []
  return got.length !== p.functionCodes.length || got.some((c) => !p.functionCodes.includes(c))
})
eq('每页绑定功能码 库 = MenuRegistry（顺序无关）', 0, fnMismatch.length,
  `不一致: ${JSON.stringify(fnMismatch.map((p) => `${p.code}: 库=${JSON.stringify(dbFn.get(p.code))} 常量=${JSON.stringify(p.functionCodes)}`))}`)

/* ------------------------------------------------------------------ 汇总 */
const summary = `s1 ${pass} ${total}`
if (COUNTS) appendFileSync(COUNTS, `${summary}\n`)
if (total === 0) {
  console.error('s1: 一条断言都没执行：脚本实现有 bug')
  process.exit(1)
}
if (pass !== total) {
  console.error(`s1-registry-three-way: ${total - pass} 项失败 / ${total} 项`)
  process.exit(1)
}
console.log(`\u001b[1;32mOK\u001b[0m s1-registry-three-way: ${pass}/${total} 项通过（DB = MenuRegistry = nav.ts = ActionRegistry）`)
