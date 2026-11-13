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
 *   3) 路由表/注解里引用的码必须已注册（无悬空码），且注册的动作码都有引用（无死动作）；
 *   4) /admin/permissions 只读：控制器里不得有写映射；
 *   5) fail-fast 默认 true：违规要在启动期暴露，而不是运行期 403。
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
    const full = (classPrefix + methodPath).replace(/\{[^}]*\}/g, '\\d+').replace(/\/+$/, '') || '/'
    const line = text.slice(0, m.index).split('\n').length

    // 声明：注解紧跟在映射注解之后、方法签名之前
    const after = text.slice(m.index + m[0].length)
    const sig = after.search(/\(/)
    const gap = after.slice(0, sig < 0 ? 400 : sig)
    const declared = /@(RequiresAction|RequiresActionFromBody|PageOnly)\b/.test(gap)
    if (declared) { annotated += 1; continue }

    const covered = rules.some((r) => (!r.method || r.method === verb.toUpperCase()) && r.regex.test(full))
    if (!covered) violations.push(`${relative(ROOT, resolve(ROOT, file))}:${line} ${verb.toUpperCase()} ${full}`)
  }
}

if (writeEndpoints === 0) {
  bad('扫描到写端点', '扫描器没找到任何写端点：正则或目录结构变了，本段会假绿')
} else if (violations.length === 0) {
  ok('未声明动作的写端点 = 0', `${writeEndpoints} 个写端点，其中 ${annotated} 个用注解声明，其余由路由表覆盖`)
} else {
  bad(`未声明动作的写端点 = ${violations.length}`, '给端点补 @RequiresAction(page, action) 或在 PermissionRouteTable 加规则',
    violations.slice(0, 6).join(' | '))
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
const deadActions = [...actionCodes].filter((c) => !allText.includes(c))
if (!deadActions.length) {
  ok(`注册的 ${actionCodes.size} 个动作码都被路由表或注解引用（无死动作）`)
} else {
  bad(`存在死动作码 ${deadActions.length} 个`, '注册了却没人用的动作会出现在授权树里，管理员勾了也没任何效果',
    deadActions.slice(0, 8).join(', '))
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
