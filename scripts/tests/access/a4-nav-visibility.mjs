#!/usr/bin/env node
/**
 * a4-nav-visibility.mjs — 7 角色真实权限 → 菜单可见集（PRD AC-9；TECHNICAL §5）
 *
 * a3 证明「声明齐全」，a4 证明「角色实际看到什么」：用登录响应的 permissions
 * 代入 navVisible 的同一算法（含 AUTHENTICATED 哨兵、internalOnly、routeName 注册），
 * 再断言关键隐藏/可见。两者缺一：声明齐全但推导错，菜单照样是错的那一份。
 *
 * 厂商账号判定：登录响应不带 vendorId，所以由 access-verify.sh 用
 * sys_user.vendor_id IS NOT NULL 算出名单，经 A4_VENDOR_USERS 传入；
 * 单独运行时退化为 vendor1（本夹具的厂商账号）。
 */
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseNav, parseRouteCodes } from './a3-nav-audit.mjs'

const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const TENANT = process.env.A4_TENANT || 't1'
const VENDOR_USERS = new Set(
  (process.env.A4_VENDOR_USERS || 'vendor1').split(',').map((s) => s.trim()).filter(Boolean),
)
const DUMP = process.env.A4_DUMP === '1'
const ADMIN_GROUP_LABELS = [
  '组织与租户', '用户与角色', '厂商与域', '权限矩阵', '流程定义',
  'API 客户端', '集成配置', '指标字典', '审计日志',
]

const ROLES = [
  { user: 'admin', pass: 'Admin@12345' },
  { user: 'pm', pass: 'Passw0rd!' },
  { user: 'tl1', pass: 'Passw0rd!' },
  { user: 'po1', pass: 'Passw0rd!' },
  { user: 'dev1', pass: 'Passw0rd!' },
  { user: 'qa1', pass: 'Passw0rd!' },
  { user: 'vendor1', pass: 'Passw0rd!' },
]

/** 与 web/src/layouts/navAccess.ts 完全一致的三步判定（顺序本身是契约） */
function navVisible(item, ctx) {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped) return false
  // parseNav 把 nav.ts 的 functionCode 解析成 codes 数组（a3 的产物形状）；
  // 这里两者都接受，判定语义与 navAccess.ts 逐行一致。
  const raw = item.functionCode ?? item.codes ?? []
  const codes = Array.isArray(raw) ? raw : [raw]
  if (codes.length === 0) return false
  if (codes.includes('AUTHENTICATED')) return true
  return ctx.canAny(codes)
}

async function login(user, pass) {
  const res = await fetch(`${GATEWAY}/api/req/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: TENANT, username: user, password: pass }),
  })
  if (!res.ok) {
    throw new Error(`登录失败 ${user}: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`)
  }
  const body = await res.json()
  // auth store 的 normalizePermissions：access !== 'DENY' 即视为拥有（RESTRICTED 也算）
  const codes = new Set((body.permissions || []).filter((p) => p.access !== 'DENY').map((p) => p.code))
  return { codes, tenantId: body.tenantId }
}

async function main() {
  const { sidebar, top } = parseNav()
  const routeCodes = parseRouteCodes()
  const byLabel = new Map(sidebar.map((it) => [it.label, it]))

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

  // 逐角色推导可见集（perms 与 ctx 各算一次，供侧栏/顶栏复用）
  const visibleByRole = new Map()
  const hiddenByRole = new Map()
  const ctxByRole = new Map()
  for (const role of ROLES) {
    const { codes } = await login(role.user, role.pass)
    const ctx = {
      hasRoute: (name) => routeCodes.has(name),
      canAny: (list) => list.some((c) => codes.has(c)),
      vendorScoped: VENDOR_USERS.has(role.user),
    }
    ctxByRole.set(role.user, ctx)
    const visible = sidebar.filter((it) => navVisible(it, ctx)).map((it) => it.label)
    visibleByRole.set(role.user, visible)
    hiddenByRole.set(role.user, new Set(sidebar.filter((it) => !visible.includes(it.label)).map((it) => it.label)))
    if (DUMP) {
      console.error(`[a4] ${role.user} 可见(${visible.length}): ${visible.join('、')}`)
      console.error(`[a4] ${role.user} 隐藏: ${[...hiddenByRole.get(role.user)].join('、')}`)
    }
  }

  const visible = (role, label) => (visibleByRole.get(role) || []).includes(label)
  const hidden = (role, label) => (hiddenByRole.get(role) || new Set()).has(label)
  const topVisible = (role, label) => {
    const item = top.find((t) => t.label === label)
    return item ? navVisible(item, ctxByRole.get(role)) : false
  }

  // A1：admin 侧栏 33 条全可见
  const adminVisible = visibleByRole.get('admin') || []
  if (adminVisible.length === sidebar.length) ok(`admin 侧栏 ${sidebar.length} 条全部可见（无隐藏入口）`)
  else bad(`admin 侧栏只有 ${adminVisible.length}/${sidebar.length} 条可见，隐藏: ${[...(hiddenByRole.get('admin') || [])].join('、')}`,
    'admin 应拥有全部功能码；隐藏说明某个入口的 functionCode 拼错或矩阵缺码')

  // A2：admin 8 条顶栏全可见
  const topAll = top.filter((t) => topVisible('admin', t.label))
  if (topAll.length === top.length) ok(`admin 顶栏 ${top.length} 条全部可见`)
  else bad(`admin 顶栏只有 ${topAll.length}/${top.length} 条可见`, '顶栏 8 项应全部对 admin 可见')

  // A3：admin 五页入口（需求池/版本计划/审批中心/测试计划/分支策略）全可见
  const adminFive = ['需求池', '版本计划', '我的待办', '测试计划', '分支策略'].filter((l) => visible('admin', l))
  if (adminFive.length === 5) ok('admin 五页入口全可见（需求池/版本计划/审批/测试计划/分支策略）')
  else bad(`admin 五页入口缺: ${['需求池', '版本计划', '我的待办', '测试计划', '分支策略'].filter((l) => !visible('admin', l)).join('、')}`,
    '五页是本需求的核心修复目标，admin 必须全部看得到')

  // A4：po1 隐藏 6 类（用例库/测试计划/测试报告/分支策略/价值流 + 管理后台）
  const po1MustHide = ['用例库', '测试计划', '测试报告', '分支策略', '价值流', ...ADMIN_GROUP_LABELS]
  const po1Leaked = po1MustHide.filter((l) => !hidden('po1', l))
  if (po1Leaked.length === 0) ok('po1 隐藏 6 类入口（用例库/测试计划/测试报告/分支策略/价值流/管理后台 9 项）')
  else bad(`po1 仍可见本应隐藏的入口: ${po1Leaked.join('、')}`,
    'po1（SUB_BUSINESS_LEADER）没有 case:manage/plan:manage/report:*/CCODE_POLICY_VIEW/FLOW_VIEW/org:manage')

  // A5：po1 可见 需求池/版本计划/我的待办
  const po1MustSee = ['需求池', '版本计划', '我的待办'].filter((l) => visible('po1', l))
  if (po1MustSee.length === 3) ok('po1 可见 需求池/版本计划/我的待办（AC-5 的 3 个 ✅）')
  else bad(`po1 看不到: ${['需求池', '版本计划', '我的待办'].filter((l) => !visible('po1', l)).join('、')}`,
    '版本计划对 SUB_BUSINESS_LEADER 是 RESTRICTED（非 DENY）→ 入口必须保留')

  // A6：dev1 隐藏 测试计划/测试报告
  const dev1Leaked = ['测试计划', '测试报告'].filter((l) => !hidden('dev1', l))
  if (dev1Leaked.length === 0) ok('dev1 隐藏 测试计划/测试报告')
  else bad(`dev1 仍可见: ${dev1Leaked.join('、')}`, 'DEV 对 plan:manage 是 DENY，入口不该渲染')

  // A7：dev1 可见 用例库（矩阵 DEV: case:manage = RESTRICTED，TECHNICAL §5 唯一偏差）
  if (visible('dev1', '用例库')) ok('dev1 可见 用例库（矩阵 RESTRICTED：入口保留、范围收窄）')
  else bad('dev1 看不到用例库', '矩阵里 DEV 的 case:manage 是 RESTRICTED，隐藏即"有权限但菜单不显示"（TECHNICAL §5 偏差记录）')

  // A8：qa1 隐藏 甘特/分支策略/价值流 + 管理后台
  const qa1MustHide = ['甘特', '分支策略', '价值流', ...ADMIN_GROUP_LABELS]
  const qa1Leaked = qa1MustHide.filter((l) => !hidden('qa1', l))
  if (qa1Leaked.length === 0) ok('qa1 隐藏 4 类入口（甘特/分支策略/价值流/管理后台）')
  else bad(`qa1 仍可见: ${qa1Leaked.join('、')}`, 'QA 没有 GANTT_VIEW/CCODE_POLICY_VIEW/FLOW_VIEW/org:manage')

  // A9：qa1 可见 测试计划（AC-5 的 ✅）
  if (visible('qa1', '测试计划')) ok('qa1 可见 测试计划')
  else bad('qa1 看不到测试计划', 'QA 对 plan:manage 是 ALLOW，入口必须保留')

  // A10：pm 隐藏 分支策略 + 8 个后台项（PMO 只有 workflow:manage，没有 org/tenant/apiclient/
  // integration/metric/audit 这些码；「流程定义」对 PMO 是正常能力，不算越权）
  const pmDenied = ['分支策略', ...ADMIN_GROUP_LABELS.filter((l) => l !== '流程定义')]
  const pmLeaked = pmDenied.filter((l) => !hidden('pm', l))
  if (pmLeaked.length === 0) ok('pm 隐藏 分支策略 + 8 个后台入口（仅保留流程定义）')
  else bad(`pm 仍可见: ${pmLeaked.join('、')}`, 'PMO 没有 CCODE_POLICY_VIEW/org:manage/tenant:manage/apiclient:manage/integration:manage/metric:manage/audit:view')

  // A11：pm 可见 版本计划/我的待办/流程定义（workflow:manage 在 PMO 的权限清单里）
  const pmMustSee = ['版本计划', '我的待办', '流程定义'].filter((l) => visible('pm', l))
  if (pmMustSee.length === 3) ok('pm 可见 版本计划/我的待办/流程定义')
  else bad(`pm 看不到: ${['版本计划', '我的待办', '流程定义'].filter((l) => !visible('pm', l)).join('、')}`,
    '版本计划对 PMO 是 ALLOW；我的待办是 AUTHENTICATED；流程定义来自 workflow:manage')

  // A12：tl1 隐藏 6 个「组织级」后台项、可见 需求池 + 指标字典（metric:manage）
  const orgOnly = ['组织与租户', '用户与角色', '厂商与域', '权限矩阵', 'API 客户端', '集成配置']
  const tl1Leaked = orgOnly.filter((l) => !hidden('tl1', l))
  if (tl1Leaked.length === 0 && visible('tl1', '需求池') && visible('tl1', '指标字典')) {
    ok('tl1 隐藏 6 个组织级后台入口、可见 需求池/指标字典')
  } else {
    bad(`tl1 仍可见: ${tl1Leaked.join('、')}；缺可见: ${['需求池', '指标字典'].filter((l) => !visible('tl1', l)).join('、')}`,
      'TEAM_LEADER 无 org:manage/apiclient:manage/integration:manage；需求池 AUTHENTICATED、指标字典 metric:manage')
  }

  // A13：vendor1 隐藏 用例库（internalOnly）+ 6 个组织级后台项
  const vendorLeaked = ['用例库', ...orgOnly].filter((l) => !hidden('vendor1', l))
  if (vendorLeaked.length === 0) ok('vendor1 隐藏 用例库（internalOnly）+ 6 个组织级后台入口')
  else bad(`vendor1 仍可见: ${vendorLeaked.join('、')}`,
    '厂商账号不作用例：用例库 nav 项 internalOnly + 路由 meta.internalOnly 双重拦截')

  // A14：vendor1 可见 需求池/缺陷；且每个角色可见数在 [2, 41]
  const vendorSees = ['需求池', '缺陷'].filter((l) => visible('vendor1', l))
  const rangeBad = ROLES.map((r) => r.user).filter((u) => {
    const n = (visibleByRole.get(u) || []).length
    return n < 2 || n > sidebar.length
  })
  if (vendorSees.length === 2 && rangeBad.length === 0) {
    ok('vendor1 可见 需求池/缺陷；7 角色可见数均在 [2, 41] 合理区间')
  } else {
    bad(`vendor1 看不到: ${['需求池', '缺陷'].filter((l) => !visible('vendor1', l)).join('、')}；可见数越界角色: ${rangeBad.join('、')}`,
      '厂商账号仍应看到需求池/缺陷；可见数越界通常意味着 AUTHENTICATED 哨兵判定顺序被打乱')
  }

  const countsFile = process.env.ACCESS_COUNTS
  if (countsFile) {
    const { appendFileSync } = await import('node:fs')
    appendFileSync(countsFile, `a4 ${pass} ${total}\n`)
  }
  if (total === 0) {
    console.error('a4: 一条断言都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (failures.length) {
    console.error(`\na4-nav-visibility: ${total - pass} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f}`)
    process.exit(1)
  }
  console.log(`\n  ok a4-nav-visibility: ${pass}/${total} 项通过（7 角色菜单可见集与矩阵一致）`)
}

main().catch((e) => {
  console.error(`a4-nav-visibility 无法执行: ${e.message}`)
  console.error('  修复: 先 make platform-up && make platform-seed（需要网关与 12 个种子账号）')
  process.exit(1)
})
