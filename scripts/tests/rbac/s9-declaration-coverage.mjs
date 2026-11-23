#!/usr/bin/env node
/**
 * s9-declaration-coverage.mjs — 动作声明覆盖率（TECHNICAL §6、AC-9）
 *
 * 本段回答一个问题：**有没有写端点从来不被检查**。
 * 这类缺陷不会让某个请求被误拒，而是让某个请求永远不被管——它比 403 少一次，比事故多一次。
 *
 * 判据（静态，不需要服务在跑）：
 *   1) 每个写端点（POST/PUT/PATCH/DELETE）要么带 @RequiresAction / @RequiresActionFromBody /
 *      @PageOnly，要么被 PermissionRouteTable 的规则命中；
 *   2) @PageOnly 必须带 reason（豁免可审计，而不是「忘了写」）；
 *   3) 路由表/注解里引用的码必须已注册（无悬空码）；请求体动作前缀必须能解析到已注册动作；
 *   4) /admin/permissions 只读：控制器里不得有写映射；
 *   5) fail-fast 默认 true：违规要在启动期暴露，而不是运行期 403。
 *
 * 豁免：拦截器的 PermissionInterceptor.isOpen(path) 是「不参与 RBAC」的唯一清单（登录、健康检查、
 * 内部回调、OpenAPI 摄取）。公开端点不能因为「本来就该随便调」而被算成漏声明——但这份清单
 * 从源码里解析而不是手抄，改动会立刻反映到这一段的计数上。
 */
import { readFileSync, readdirSync, statSync, appendFileSync } from 'node:fs'
import { dirname, join, resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '../../..')
const COUNTS = process.env.RBAC_COUNTS

let total = 0
let pass = 0
const failures = []

const ok = (label, detail = '') => {
  total += 1; pass += 1
  console.log(`  \u001b[1;32mPASS\u001b[0m ${label}${detail ? `（${detail}）` : ''}`)
}
const bad = (label, hint, detail = '') => {
  total += 1
  failures.push(label)
  console.log(`  \u001b[1;31mFAIL\u001b[0m ${label}${detail ? `（${detail}）` : ''}`)
  if (hint) console.log(`       修复: ${hint}`)
}

const read = (rel) => {
  try { return readFileSync(resolve(ROOT, rel), 'utf8') } catch { return '' }
}

function walk(dir, out = []) {
  let entries = []
  try { entries = readdirSync(resolve(ROOT, dir)) } catch { return out }
  for (const name of entries) {
    const rel = join(dir, name)
    let st
    try { st = statSync(resolve(ROOT, rel)) } catch { continue }
    if (st.isDirectory()) walk(rel, out)
    else if (name.endsWith('.java')) out.push(rel)
  }
  return out
}

/* ------------------------------------------------------------------ 注册表 */
const menuSrc = read('platform-contract/src/main/java/com/example/platform/contract/MenuRegistry.java')
const actionSrc = read('platform-contract/src/main/java/com/example/platform/contract/ActionRegistry.java')
const pageCodes = new Set([...menuSrc.matchAll(/new\s+MenuPage\(\s*"([^"]+)"/g)].map((m) => m[1]))
const actionCodes = new Set([...actionSrc.matchAll(/(?:^|[^A-Za-z0-9_.])(?:action|highRisk)\(\s*"([^"]+)"/gm)].map((m) => m[1]))

/* ------------------------------------------------------------------ 路由表 */
const tableSrc = read('platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java')
const rules = []
for (const chunk of tableSrc.split(/rules\.add\(rule\(/).slice(1)) {
  const method = (chunk.match(/^\s*(null|"([A-Z]+)")/) || [])[2] ?? null
  const path = (chunk.match(/^\s*(?:null|"[A-Z]+")\s*,\s*"((?:[^"\\]|\\.)*)"/) || [])[1]
  const kind = (chunk.match(/,\s*(page|action|bodyAction)\(/) || [])[1]
  const args = (chunk.match(/,\s*(?:page|action|bodyAction)\(([^)]*)\)/) || [])[1] || ''
  const codes = [...args.matchAll(/"([^"]*)"/g)].map((m) => m[1])
  if (!path) continue
  rules.push({ method, regex: new RegExp(path.replace(/\\\\/g, '\\')), kind, codes, raw: path })
}
const rulePages = new Set(rules.flatMap((r) => (r.kind === 'bodyAction' ? [r.codes[0]] : r.kind === 'action' ? [r.codes[0]] : r.codes)))
const ruleActions = new Set(rules.flatMap((r) => (r.kind === 'action' ? [r.codes[1]] : [])))

/* ------------------------------------------------------------------ 公开路径（拦截器的唯一豁免清单） */
const interceptorSrc = read('platform-common/src/main/java/com/example/platform/common/security/PermissionInterceptor.java')
const validatorSrc0 = read('platform-common/src/main/java/com/example/platform/common/security/PermissionDeclarationValidator.java')
const openBody = (() => {
  // 定位方法签名而不是调用点：isOpen(path) 在拦截器里先被调用、后定义
  const at = interceptorSrc.search(/isOpen\s*\(\s*String\s+path\s*\)/)
  if (at < 0) return ''
  const start = interceptorSrc.indexOf('{', at)
  let depth = 0
  for (let i = start; i < interceptorSrc.length; i++) {
    if (interceptorSrc[i] === '{') depth++
    else if (interceptorSrc[i] === '}' && --depth === 0) return interceptorSrc.slice(start, i)
  }
  return ''
})()
const openExact = [...openBody.matchAll(/path\.equals\("([^"]+)"\)/g)].map((m) => m[1])
const openPrefix = [...openBody.matchAll(/path\.startsWith\("([^"]+)"\)/g)].map((m) => m[1])
const isOpen = (p) => openExact.includes(p) || openPrefix.some((pre) => p.startsWith(pre))

/* ------------------------------------------------------------------ 控制器扫描 */
const WRITE = /^@(Post|Put|Patch|Delete)Mapping/
const controllers = [...walk('req-service/src/main/java'), ...walk('deliver-service/src/main/java')]
  .filter((f) => /@RestController|@Controller/.test(read(f)))

const violations = []
let writeEndpoints = 0
let annotated = 0

for (const file of controllers) {
  const text = read(file)
  const classPrefix = (text.match(/^@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/m) || [])[1] || ''
  const annRe = /@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?/g
  let m
  while ((m = annRe.exec(text)) !== null) {
    const [, verb, args = ''] = m
    if (verb === 'Get') continue
    writeEndpoints += 1
    const methodPath = (args.match(/"([^"]*)"/) || [])[1] || ''
    // 路径变量换成具体样例值：PermissionRouteTable 的规则是给真实请求路径用的正则
    // （如 ^/changes/\d+/apply$）。若这里也塞 \d+ 字面量，r.regex.test(full) 永远匹配不上，
    // 路由表已覆盖的端点会被误报成「未声明动作」。
    const full = (classPrefix + methodPath).replace(/\{[^}]*\}/g, '1').replace(/\/{2,}/g, '/').replace(/\/+$/, '') || '/'
    const line = text.slice(0, m.index).split('\n').length

    // 声明：注解紧跟在映射注解之后、方法签名之前
    const after = text.slice(m.index + m[0].length)
    const sig = after.search(/\(/)
    const gap = after.slice(0, sig < 0 ? 400 : sig)
    const declared = /@(RequiresAction|RequiresActionFromBody|PageOnly)\b/.test(gap)
    if (declared) { annotated += 1; continue }

    const covered = rules.some((r) => (!r.method || r.method === verb.toUpperCase()) && r.regex.test(full))
    if (covered || isOpen(full)) continue
    violations.push(`${relative(ROOT, resolve(ROOT, file))}:${line} ${verb.toUpperCase()} ${full}`)
  }
}

if (writeEndpoints === 0) {
  bad('扫描到写端点', '扫描器没找到任何写端点：正则或目录结构变了，本段会假绿')
} else if (violations.length === 0) {
  ok('未声明动作的写端点 = 0',
    `${writeEndpoints} 个写端点，其中 ${annotated} 个用注解声明，其余由路由表覆盖，公开路径 ${openExact.length + openPrefix.length} 条豁免`)
} else {
  bad(`未声明动作的写端点 = ${violations.length}`, '给端点补 @RequiresAction(page, action) 或在 PermissionRouteTable 加规则',
    violations.slice(0, 6).join(' | '))
}

/* ------------------------------------------------------------------ 公开清单与启动校验同源 */
if (openExact.length === 0 && openPrefix.length === 0) {
  bad('解析不到 PermissionInterceptor.isOpen 的豁免清单',
    'isOpen 被改名/改结构后本段会失去豁免依据：请把清单保持为 path.equals/path.startsWith 字面量')
} else if (/isOpen\(/.test(validatorSrc0) || /PermissionInterceptor/.test(validatorSrc0)) {
  ok(`启动校验复用拦截器的豁免清单（${openExact.length} 条精确 + ${openPrefix.length} 条前缀）`)
} else {
  bad('PermissionDeclarationValidator 未复用 PermissionInterceptor.isOpen 的豁免清单',
    '启动校验会把 /auth/login、/internal/** 这类公开写端点判成「未声明」并 fail-fast 拒绝启动（二者必须同源）',
    `拦截器豁免 ${openExact.length}+${openPrefix.length} 条，校验器 0 条`)
}

/* ------------------------------------------------------------------ @PageOnly 豁免可审计 */
const pageOnlyUses = []
for (const file of controllers) {
  const text = read(file)
  const re = /@PageOnly\(([^)]*)\)/g
  let m
  while ((m = re.exec(text)) !== null) pageOnlyUses.push({ file, args: m[1] })
}
const noReason = pageOnlyUses.filter((u) => !/reason\s*=\s*"[^"]+"/.test(u.args))
if (pageOnlyUses.length === 0) {
  ok('@PageOnly 使用 = 0（全部写端点都有动作码，无需豁免）')
} else if (noReason.length === 0) {
  ok(`@PageOnly 使用处都带 reason（${pageOnlyUses.length} 处）`)
} else {
  bad('@PageOnly 缺少 reason', '豁免必须说明理由，否则「忘了声明动作码」和「故意豁免」无法区分',
    noReason.map((u) => u.file).join(' | '))
}

/* ------------------------------------------------------------------ /admin/permissions 只读 */
const permController = controllers.find((f) => /AdminPermissionController\.java$/.test(f))
if (!permController) {
  bad('AdminPermissionController 存在', 'T4 未落地：req-service/.../sys/AdminPermissionController.java')
} else {
  const text = read(permController)
  const writes = [...text.matchAll(/@(Post|Put|Patch|Delete)Mapping/g)].map((m) => m[1])
  if (writes.length === 0) ok('/admin/permissions 只有读映射（无写控件，AC-17）')
  else bad(`/admin/permissions 仍有写映射：${writes.join(',')}`, '总览页必须只读：写语义只能走 /admin/roles/{id}/permissions')
}

/* ------------------------------------------------------------------ 码的引用完整性 */
const danglingPages = [...rulePages].filter((c) => !pageCodes.has(c))
const danglingActions = [...ruleActions].filter((c) => !actionCodes.has(c))
if (!danglingPages.length && !danglingActions.length) {
  ok(`路由表引用的码全部已注册（页面 ${rulePages.size} / 动作 ${ruleActions.size}）`)
} else {
  bad('路由表引用了未注册的码', '码写错会让请求恒定 403 且日志指向「权限不足」而不是「码不存在」',
    `页面 ${JSON.stringify(danglingPages)} 动作 ${JSON.stringify(danglingActions)}`)
}

const allText = tableSrc + controllers.map((f) => read(f)).join('\n')
const bodyRules = rules.filter((r) => r.kind === 'bodyAction')
const bodyPrefixes = [...new Set(bodyRules.map((r) => r.codes[1]).filter(Boolean))]
const unresolved = bodyPrefixes.filter((pre) => ![...actionCodes].some((code) => code.startsWith(pre)))
const coveredByPrefix = [...actionCodes].filter((code) => bodyPrefixes.some((pre) => code.startsWith(pre)))
if (bodyPrefixes.length === 0) {
  bad('路由表里没有 bodyAction 规则', '需求/缺陷的 /actions 端点共用 URL，只能从请求体取动作码（TECHNICAL §6.3）')
} else if (unresolved.length === 0) {
  ok(`请求体动作前缀全部可解析（${bodyPrefixes.length} 个前缀覆盖 ${coveredByPrefix.length} 个已注册动作）`,
    bodyPrefixes.join(','))
} else {
  bad(`请求体动作前缀无对应动作码：${unresolved.join(',')}`,
    '前缀写错会让所有 body 驱动的动作恒定 403（且日志只会说「无权」）')
}

const grantRule = rules.some((r) => r.method === 'PUT' && /admin\/roles/.test(r.raw) && r.codes.includes('role.action.grant'))
if (grantRule) ok('PUT /admin/roles/{id}/permissions ⇒ role.action.grant（最高危写动作已声明）')
else bad('授权保存端点未声明 role.action.grant', '这是本需求最危险的一次写：必须要求高危动作码并触发二次确认')

/* ------------------------------------------------------------------ fail-fast 默认 */
const validator = read('platform-common/src/main/java/com/example/platform/common/security/PermissionDeclarationValidator.java')
if (/platform\.rbac\.fail-fast:true/.test(validator) && !/fail-fast:false/.test(validator)) {
  ok('启动期声明校验默认 fail-fast=true（违规即启动失败）')
} else {
  bad('PermissionDeclarationValidator 的 fail-fast 默认不是 true', '默认 false 会让漏声明的端点在运行期裸奔，且没人发现')
}

/* ------------------------------------------------------------------ 汇总 */
if (COUNTS) appendFileSync(COUNTS, `s9 ${pass} ${total}\n`)
if (total === 0) {
  console.error('s9: 一条断言都没执行：脚本实现有 bug')
  process.exit(1)
}
if (pass !== total) {
  console.error(`s9-declaration-coverage: ${total - pass} 项失败 / ${total} 项`)
  process.exit(1)
}
console.log(`\u001b[1;32mOK\u001b[0m s9-declaration-coverage: ${pass}/${total} 项通过（写端点无裸奔）`)
