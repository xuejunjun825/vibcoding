#!/usr/bin/env node
/**
 * a3-nav-audit.mjs — 导航与字典的静态审计（PRD AC-7 / AC-8 / AC-4；TECHNICAL §4.2 §4.3）
 *
 * 为什么用 Node 读源码：前端没有测试运行器，而「每个入口都声明了功能码」「菜单码 ⊆
 * 路由码」「字典三个请求都 silentForbidden」这类契约是**声明**，不是运行时行为——
 * 静态审计能把它变成可执行证据，a4 再用真实权限推导「角色实际看到什么」。
 *
 * 9 条规则（R1..R9），全绿才算通过；任一失败打印具体条目与修复方向。
 * 计数通过环境变量 ACCESS_COUNTS 交给 scripts/access-verify.sh 汇总。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
export const ROOT = path.resolve(HERE, '../../..')
const WEB = path.join(ROOT, 'web/src')

// ---------------------------------------------------------------- 通用解析
/** 去掉整行注释（避免注释里的 `functionCode` 字样被当成声明） */
function stripCommentLines(src) {
  return src
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n')
}

/**
 * 扫描文本里所有成对的 `{...}`，返回每个对象的起止下标与原文（跳过字符串里的括号）。
 * 不用正则找 `{`：nav.ts 里有注释、模板字符串，括号配平交给扫描器更稳。
 */
export function extractObjects(text) {
  const out = []
  const stack = []
  let quote = null
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (quote) {
      if (ch === '\\') { i++; continue }
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue }
    if (ch === '{') stack.push(i)
    else if (ch === '}') {
      const start = stack.pop()
      if (start !== undefined) out.push({ start, end: i, text: text.slice(start, i + 1) })
    }
  }
  return out
}

/** 从对象文本里取 `functionCode`：支持 'x' / ['x','y'] / AUTHENTICATED 三种写法 */
export function parseFunctionCode(text, { sentinel = false } = {}) {
  const clean = stripCommentLines(text)
  if (sentinel && /functionCode:\s*AUTHENTICATED/.test(clean)) return ['AUTHENTICATED']
  const arr = clean.match(/functionCode:\s*\[([\s\S]*?)\]/)
  if (arr) return [...arr[1].matchAll(/'([^']+)'/g)].map((m) => m[1])
  const one = clean.match(/functionCode:\s*'([^']+)'/)
  if (one) return [one[1]]
  return null
}

function blockBetween(src, fromMarker, toMarker) {
  const start = src.indexOf(fromMarker)
  if (start < 0) return ''
  const end = toMarker ? src.indexOf(toMarker, start + fromMarker.length) : -1
  return src.slice(start, end < 0 ? src.length : end)
}

/** 解析 nav.ts：NAV_GROUPS 的 33 条侧栏项 + TOP_NAV 的 8 条顶栏项 */
export function parseNav() {
  const src = readFileSync(path.join(WEB, 'layouts/nav.ts'), 'utf8')
  const sidebar = []
  const navBlock = blockBetween(src, 'export const NAV_GROUPS', 'export interface TopNavItem')
  for (const obj of extractObjects(navBlock)) {
    // 分组对象也有 label/to（在它的 items 里）——只取叶子项，否则每组首项会被数两次
    if (/\bitems\s*:/.test(obj.text) || /\btitle\s*:/.test(obj.text)) continue
    if (!/\blabel\s*:/.test(obj.text) || !/\bto\s*:/.test(obj.text)) continue
    const label = (obj.text.match(/label:\s*'([^']*)'/) || [])[1] || ''
    const routeName = (obj.text.match(/routeName:\s*'([^']*)'/) || [])[1] || ''
    sidebar.push({
      label,
      routeName,
      codes: parseFunctionCode(obj.text, { sentinel: true }),
      internalOnly: /internalOnly:\s*true/.test(stripCommentLines(obj.text)),
    })
  }
  const top = []
  const topBlock = blockBetween(src, 'export const TOP_NAV', 'export const NAV_ICONS')
  for (const obj of extractObjects(topBlock)) {
    if (!/\blabel\s*:/.test(obj.text) || !/\bto\s*:/.test(obj.text)) continue
    const label = (obj.text.match(/label:\s*'([^']*)'/) || [])[1] || ''
    const routeName = (obj.text.match(/routeName:\s*'([^']*)'/) || [])[1] || ''
    top.push({
      label,
      routeName,
      codes: parseFunctionCode(obj.text, { sentinel: true }),
      internalOnly: /internalOnly:\s*true/.test(stripCommentLines(obj.text)),
    })
  }
  return { sidebar, top }
}

/** 解析 web/src/router/routes/*.ts 的 name → meta.functionCode（null = 路由无码） */
export function parseRouteCodes() {
  const dir = path.join(WEB, 'router/routes')
  const map = new Map()
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.ts')).sort()) {
    const src = readFileSync(path.join(dir, file), 'utf8')
    const matches = [...src.matchAll(/name:\s*'([^']+)'/g)]
    for (let i = 0; i < matches.length; i++) {
      const m = matches[i]
      const before = src.slice(Math.max(0, m.index - 30), m.index)
      if (/redirect\s*:\s*\{?\s*$/.test(before)) continue // `redirect: { name: ... }` 不是路由定义
      const end = i + 1 < matches.length ? matches[i + 1].index : src.length
      const win = src.slice(m.index, end)
      const metaAt = win.lastIndexOf('meta:')
      map.set(m[1], metaAt < 0 ? null : parseFunctionCode(win.slice(metaAt)))
    }
  }
  return map
}

/** 平台权限码全集（与后端 PermissionMatrix.FUNCTIONS 同一处定义） */
export function parseMatrixCodes() {
  const src = readFileSync(
    path.join(ROOT, 'platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java'),
    'utf8',
  )
  const at = src.indexOf('FUNCTIONS')
  const tail = src.slice(at)
  const end = tail.indexOf(');')
  const body = end < 0 ? tail : tail.slice(0, end)
  return new Set([...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]))
}

const walk = (dir) => {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (/\.(ts|vue)$/.test(entry)) out.push(full)
  }
  return out
}

// ---------------------------------------------------------------- 主流程
export function runAudit({ quiet = false } = {}) {
  let pass = 0
  let total = 0
  const failures = []
  const ok = (rule, msg) => {
    total++
    pass++
    if (!quiet) console.log(`  \u001b[1;32mPASS\u001b[0m ${rule} ${msg}`)
  }
  const bad = (rule, msg, fix) => {
    total++
    failures.push(`[${rule}] ${msg}${fix ? `；修复: ${fix}` : ''}`)
    if (!quiet) {
      console.log(`  \u001b[1;31mFAIL\u001b[0m ${rule} ${msg}`)
      if (fix) console.log(`       修复: ${fix}`)
    }
  }

  const { sidebar, top } = parseNav()
  const routeCodes = parseRouteCodes()
  const matrix = parseMatrixCodes()
  const all = [...sidebar, ...top]

  // R1：33 + 8 每条都显式声明 functionCode（必填字段，漏写即红）
  const missing = all.filter((it) => !it.codes || it.codes.length === 0).map((it) => it.label)
  if (sidebar.length === 33 && top.length === 8 && missing.length === 0) {
    ok('R1', `33 条侧栏 + 8 条顶栏均声明 functionCode（实际 ${sidebar.length}+${top.length}）`)
  } else if (sidebar.length !== 33 || top.length !== 8) {
    bad('R1', `导航条目数漂移：侧栏 ${sidebar.length}（期望 33）/ 顶栏 ${top.length}（期望 8）`,
      '新增/删除入口时同步更新 a3 的 33+8 基线，并确认新入口有 functionCode')
  } else {
    bad('R1', `未声明 functionCode 的入口：${missing.join('、')}`,
      'NavItem.functionCode 是必填字段；登录可读页写 AUTHENTICATED（AC-7）')
  }

  // R2：功能码值域 ⊆ 56 个矩阵码 ∪ {AUTHENTICATED}
  const unknown = []
  for (const it of all) {
    for (const code of it.codes || []) {
      if (code !== 'AUTHENTICATED' && !matrix.has(code)) unknown.push(`${it.label}:${code}`)
    }
  }
  if (unknown.length === 0) {
    ok('R2', `功能码全部在矩阵值域内（${matrix.size} 个码 + AUTHENTICATED 哨兵）`)
  } else {
    bad('R2', `以下功能码不在 PermissionMatrix.FUNCTIONS：${unknown.join('、')}`,
      '拼错的码永远不会命中 canAny：后端矩阵加码，或改成 AUTHENTICATED')
  }

  // R3：菜单码 ⊆ 落地路由 meta 码；AUTHENTICATED 只在路由无码时成立
  const routeIssues = []
  for (const it of all) {
    if (!it.routeName) { routeIssues.push(`${it.label} 缺 routeName`); continue }
    if (!routeCodes.has(it.routeName)) { routeIssues.push(`${it.label}(${it.routeName}) 路由未注册`); continue }
    const meta = routeCodes.get(it.routeName)
    if ((it.codes || []).includes('AUTHENTICATED')) {
      if (meta) routeIssues.push(`${it.label}(${it.routeName}) 菜单=登录可读，路由却要求 ${meta.join('/')}`)
      continue
    }
    if (!meta) continue // 路由无码（登录可读）：菜单更窄是允许的
    const outside = (it.codes || []).filter((c) => !meta.includes(c))
    if (outside.length) routeIssues.push(`${it.label}(${it.routeName}) 菜单码 ${outside.join('/')} 超出路由码 ${meta.join('/')}`)
  }
  if (routeIssues.length === 0) {
    ok('R3', `菜单码 ⊆ 落地路由 meta 码（${all.length} 条入口，${routeCodes.size} 条命名路由）`)
  } else {
    bad('R3', `菜单与路由不同源：${routeIssues.join('；')}`,
      '菜单码必须是路由码的子集：加了入口守卫就同步 meta.functionCode（AC-8）')
  }

  // R4：DICTS 与 ADMIN 同网关前缀（改前缀只改一处）
  const dictSrc = readFileSync(path.join(WEB, 'api/dictionaries.ts'), 'utf8')
  const adminSrc = readFileSync(path.join(WEB, 'api/admin.ts'), 'utf8')
  const dictsBase = (dictSrc.match(/const DICTS = '([^']+)'/) || [])[1]
  const adminBase = (adminSrc.match(/const ADMIN = '([^']+)'/) || [])[1]
  if (dictsBase && adminBase && dictsBase.startsWith(adminBase.replace(/\/admin$/, ''))) {
    ok('R4', `字典前缀 ${dictsBase} 与管理面前缀 ${adminBase} 同源`)
  } else {
    bad('R4', `字典前缀 ${dictsBase || '<缺失>'} 与管理面前缀 ${adminBase || '<缺失>'} 不同源`,
      '网关前缀改动只应改一处：api/admin.ts 与 api/dictionaries.ts 必须同前缀')
  }

  // R5：三个 http.get 全部带 silentForbidden: true（降级不跳 /403）
  const dictCalls = dictSrc.split('http.get').slice(1)
  const unguarded = dictCalls.filter((call) => !/silentForbidden:\s*true/.test(call.slice(0, 400)))
  if (dictCalls.length === 3 && unguarded.length === 0) {
    ok('R5', '字典三个请求均带 silentForbidden: true（403 只降级为空表）')
  } else {
    bad('R5', `字典请求数 ${dictCalls.length}（期望 3），未声明 silentForbidden 的 ${unguarded.length} 处`,
      '每个字典请求都要 silentForbidden: true，否则一次 403 就把用户甩到 /403（AC-4）')
  }

  // R6：client.ts 的 403 全局跳转仍受 !options.silentForbidden 守卫
  const clientSrc = readFileSync(path.join(WEB, 'api/client.ts'), 'utf8')
  const forbiddenAt = clientSrc.indexOf('response.status === 403')
  const guardOk = forbiddenAt >= 0 && /if\s*\(!options\.silentForbidden\)/.test(clientSrc.slice(forbiddenAt, forbiddenAt + 300))
  if (guardOk) {
    ok('R6', 'client.ts 403 分支仍受 !options.silentForbidden 守卫（AC-4 唯一开关）')
  } else {
    bad('R6', 'client.ts 的 403 分支不再检查 options.silentForbidden',
      '静默降级靠这一个开关：把 `if (!options.silentForbidden)` 恢复回来')
  }

  // R7：管理面之外不得 import api/admin 的三个字典函数（绕开入口就会重现 org:manage 403）
  const offenders = []
  const scanDirs = ['views', 'components', 'api'].map((d) => path.join(WEB, d))
  for (const dir of scanDirs) {
    for (const file of walk(dir)) {
      const rel = path.relative(WEB, file)
      if (rel === 'api/admin.ts' || rel.startsWith('views/admin/')) continue
      const src = readFileSync(file, 'utf8')
      for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'@\/api\/admin'/g)) {
        const names = m[1].split(',').map((s) => s.trim()).filter(Boolean)
        const hits = names.filter((n) => /^(listDomains|listVendors|listUsers)$/.test(n))
        if (hits.length) offenders.push(`web/src/${rel}:${hits.join('/')}`)
      }
    }
  }
  if (offenders.length === 0) {
    ok('R7', '管理面之外没有直连 api/admin 字典函数的调用点（16 处已收敛到字典入口）')
  } else {
    bad('R7', `仍有直连管理面字典的调用点：${offenders.join('、')}`,
      '改走 web/src/api/dictionaries.ts；否则非管理员又会 org:manage 403（AC-2）')
  }

  // R8：SideNav/TopNav 共用 navVisible；TopNav 不再有 admin 内联特判
  const sideSrc = readFileSync(path.join(WEB, 'layouts/SideNav.vue'), 'utf8')
  const topSrc = readFileSync(path.join(WEB, 'layouts/TopNav.vue'), 'utf8')
  const accessSrc = readFileSync(path.join(WEB, 'layouts/navAccess.ts'), 'utf8')
  const inlineAdmin = /workflow:manage/.test(stripCommentLines(topSrc))
  const issues = []
  if (!/export function navVisible/.test(accessSrc) || !/export const AUTHENTICATED/.test(accessSrc)) {
    issues.push('navAccess.ts 未导出 navVisible/AUTHENTICATED')
  }
  if (!/navVisible\(/.test(sideSrc)) issues.push('SideNav.vue 未调用 navVisible')
  if (!/navVisible\(/.test(topSrc)) issues.push('TopNav.vue 未调用 navVisible')
  if (inlineAdmin) issues.push('TopNav.vue 仍有 admin 内联码特判（workflow:manage）')
  if (issues.length === 0) {
    ok('R8', '侧栏与顶栏共用 navAccess.navVisible，TopNav 无内联 admin 特判')
  } else {
    bad('R8', issues.join('；'),
      '准入判定只允许一处：navVisible；顶栏的六码特判会让 PMO 看见「设置」却点开 403')
  }

  // R9：非管理页 import 管理面接口时，每个调用点必须声明 silentForbidden（D-01 的类）
  // 判定逐调用点做（不是「文件里出现过 silentForbidden」）：借读管理面数据的页面拿到 403
  // 只能降级为空表，否则 client.ts 的默认策略会把人甩到 /403 —— 与 r7 修的故障同构。
  const adminLeaks = []
  for (const dir of ['views', 'components'].map((d) => path.join(WEB, d))) {
    for (const file of walk(dir)) {
      const rel = path.relative(WEB, file)
      if (rel.startsWith('views/admin/')) continue
      const src = readFileSync(file, 'utf8')
      for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'@\/api\/admin'/g)) {
        const names = m[1]
          .split(',')
          .map((s) => s.trim().split(/\s+as\s+/).pop())
          .filter(Boolean)
        for (const name of names) {
          for (const call of src.matchAll(new RegExp(`\\b${name}\\s*\\(`, 'g'))) {
            const open = call.index + call[0].length - 1
            let depth = 0
            let end = -1
            for (let i = open; i < src.length; i++) {
              if (src[i] === '(') depth++
              else if (src[i] === ')' && --depth === 0) { end = i; break }
            }
            const args = end > open ? src.slice(open, end + 1) : ''
            if (!/silentForbidden/.test(args)) {
              adminLeaks.push(`web/src/${rel}:${name}()`)
            }
          }
        }
      }
    }
  }
  if (adminLeaks.length === 0) {
    ok('R9', '非管理页调用管理面接口的每个调用点都声明了 silentForbidden（403 只降级）')
  } else {
    bad('R9', `未声明 silentForbidden 的管理面调用：${adminLeaks.join('、')}`,
      '给该调用传 { silentForbidden: true }（见 api/admin.ts 的 listApiClients 第二参）；否则 403 会全局跳 /403')
  }

  return { pass, total, failures }
}

// 作为脚本直接运行时执行并给出退出码（被 a4 import 时只取解析函数）
const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  console.log('\n---- a3 导航与字典静态审计（9 条规则）----')
  const result = runAudit()
  const countsFile = process.env.ACCESS_COUNTS
  if (countsFile) {
    const { appendFileSync } = await import('node:fs')
    appendFileSync(countsFile, `a3 ${result.pass} ${result.total}\n`)
  }
  if (result.total === 0) {
    console.error('a3: 一条规则都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (result.failures.length) {
    console.error(`\na3-nav-audit: ${result.total - result.pass} 条规则失败 / ${result.total} 条`)
    for (const f of result.failures) console.error(`  - ${f}`)
    process.exit(1)
  }
  console.log(`\n  ok a3-nav-audit: ${result.pass}/${result.total} 条规则通过（静态契约可执行）`)
}
