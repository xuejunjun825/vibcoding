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
const ROLES = [
  { user: 'admin', pass: 'Admin@12345' },
  { user: 'pm', pass: 'Passw0rd!' },
  { user: 'tl1', pass: 'Passw0rd!' },
  { user: 'po1', pass: 'Passw0rd!' },
  { user: 'dev1', pass: 'Passw0rd!' },
  { user: 'qa1', pass: 'Passw0rd!' },
  { user: 'vendor1', pass: 'Passw0rd!' },
]

/** 与 web/src/layouts/navAccess.ts 完全一致的四步判定（顺序本身是契约） */
function navVisible(item, ctx) {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped) return false
  // r18 双条件之一：页面码（MENU 层）。缺省/空串退化为「登录即可见」（通知中心）。
  const pageCode = (item.pageCode || '').trim()
  if (pageCode && ctx.hasMenu && !ctx.hasMenu(pageCode)) return false
  // 双条件之二：绑定的功能码（FUNCTION 层），AUTHENTICATED 是哨兵不是码
  const raw = item.functionCode ?? item.codes ?? []
  const codes = Array.isArray(raw) ? raw : [raw]
  if (codes.length === 0) return false
  if (codes.includes('AUTHENTICATED')) return true
  return ctx.canAny(codes)
}

/** 只按功能码判（r17 语义）：与双条件结果对照，用来证明「页面码没有额外挡人」 */
function navVisibleByFunction(item, ctx) {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped) return false
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
  // r18：页面码来自 /auth/me/permissions 的 menus 集合（与登录同源，都是 PermissionService）。
  // 端点未就绪（T4 之前）时返回 null —— ctx.hasMenu 缺省，退化为 r17 的单条件语义。
  let menus = null
  let epoch = null
  try {
    const p = await fetch(`${GATEWAY}/api/req/auth/me/permissions`, {
      headers: {
        Authorization: `Bearer ${body.token}`,
        ...(body.tenantId ? { 'X-Tenant-Id': String(body.tenantId) } : {}),
      },
    })
    if (p.ok) {
      const mine = await p.json()
      if (Array.isArray(mine.menus)) menus = new Set(mine.menus)
      if (typeof mine.epoch === 'number') epoch = mine.epoch
    }
  } catch {
    menus = null
  }
  return { codes, menus, epoch, tenantId: body.tenantId }
}

async function main() {
  const { sidebar, top } = parseNav()
  const routeCodes = parseRouteCodes()
  const byLabel = new Map(sidebar.map((it) => [it.label, it]))

  // 后台子导航标签从 nav.ts 的解析结果派生，不再写死第二份标签真相：AC-11 把「权限矩阵」
  // 改名「权限总览」并新增「角色与权限」后，写死的列表要么恒不命中（误报 leaked），要么漏项
  // （新入口没人断言它该隐藏）。按 routeName 前缀取，只读一处。
  const ADMIN_GROUP_LABELS = sidebar
    .filter((it) => (it.routeName || '').startsWith('admin-'))
    .map((it) => it.label)
  // 「组织级」后台项 = 由 tenant/org/apiclient/integration 这几个管理码把守的入口（tl1/vendor1
  // 一个都没有）。按码派生而不按标签，既避免改名后漏项，也保住 API 客户端/集成配置 两项不被
  // 悄悄从断言里丢掉。
  const ORG_ONLY_CODES = ['org:manage', 'apiclient:manage', 'integration:manage']
  const ORG_ONLY_LABELS = sidebar
    .filter((it) => (it.routeName || '').startsWith('admin-')
      && (it.codes || []).some((c) => ORG_ONLY_CODES.includes(c)))
    .map((it) => it.label)

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
  const menusByRole = new Map()
  const epochByRole = new Map()
  for (const role of ROLES) {
    const { codes, menus, epoch } = await login(role.user, role.pass)
    menusByRole.set(role.user, menus)
    epochByRole.set(role.user, epoch)
    const ctx = {
      hasRoute: (name) => routeCodes.has(name),
      canAny: (list) => list.some((c) => codes.has(c)),
      // 页面码（MENU 层）——和 SideNav/TopNav 传进 NavContext 的是同一份数据
      hasMenu: menus ? (code) => menus.has(code) : undefined,
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
  if (po1Leaked.length === 0) ok(`po1 隐藏 6 类入口（用例库/测试计划/测试报告/分支策略/价值流/管理后台 ${ADMIN_GROUP_LABELS.length} 项）`)
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
  if (pmLeaked.length === 0) ok(`pm 隐藏 分支策略 + ${ADMIN_GROUP_LABELS.length - 1} 个后台入口（仅保留流程定义）`)
  else bad(`pm 仍可见: ${pmLeaked.join('、')}`, 'PMO 没有 CCODE_POLICY_VIEW/org:manage/tenant:manage/apiclient:manage/integration:manage/metric:manage/audit:view')

  // A11：pm 可见 版本计划/我的待办/流程定义（workflow:manage 在 PMO 的权限清单里）
  const pmMustSee = ['版本计划', '我的待办', '流程定义'].filter((l) => visible('pm', l))
  if (pmMustSee.length === 3) ok('pm 可见 版本计划/我的待办/流程定义')
  else bad(`pm 看不到: ${['版本计划', '我的待办', '流程定义'].filter((l) => !visible('pm', l)).join('、')}`,
    '版本计划对 PMO 是 ALLOW；我的待办是 AUTHENTICATED；流程定义来自 workflow:manage')

  // A12：tl1 隐藏 6 个「组织级」后台项、可见 需求池 + 指标字典（metric:manage）
  const orgOnly = ORG_ONLY_LABELS
  const tl1Leaked = orgOnly.filter((l) => !hidden('tl1', l))
  if (tl1Leaked.length === 0 && visible('tl1', '需求池') && visible('tl1', '指标字典')) {
    ok(`tl1 隐藏 ${orgOnly.length} 个组织级后台入口、可见 需求池/指标字典`)
  } else {
    bad(`tl1 仍可见: ${tl1Leaked.join('、')}；缺可见: ${['需求池', '指标字典'].filter((l) => !visible('tl1', l)).join('、')}`,
      'TEAM_LEADER 无 org:manage/apiclient:manage/integration:manage；需求池 AUTHENTICATED、指标字典 metric:manage')
  }

  // A13：vendor1 隐藏 用例库（internalOnly）+ orgOnly 个组织级后台项
  const vendorLeaked = ['用例库', ...orgOnly].filter((l) => !hidden('vendor1', l))
  if (vendorLeaked.length === 0) ok(`vendor1 隐藏 用例库（internalOnly）+ ${orgOnly.length} 个组织级后台入口`)
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

  // A15：/auth/me/permissions 的 menus 集合 = 36 个页面码（r18 的 MENU 层已生效）
  const adminMenus = menusByRole.get('admin')
  const adminEpoch = epochByRole.get('admin')
  if (adminMenus && adminMenus.size === 36) {
    ok(`admin 的 menus 集合 = 36 个页面码（epoch=${adminEpoch}，MENU 层与 FUNCTION 同源）`)
  } else if (!adminMenus) {
    bad('admin 拿不到 /auth/me/permissions 的 menus 集合',
      'T4 落地后 login 响应须带 epoch + menus（TECHNICAL §7）：否则前端只能按功能码推页面，三层授权形同两层')
  } else {
    bad(`admin 的 menus 集合只有 ${adminMenus.size} 个页面码（应 36）`,
      'RbacSeeder 的 MENU 行必须覆盖 10 角色 × 36 页；缺行 = 该页面永远不可见（s0/s2 会同时红）')
  }

  // A16：页面码不额外挡人 —— 双条件可见集与「只按功能码」逐角色完全相同。
  // 前提是 MENU 档位 = max(绑定功能码)（TECHNICAL §3.3）：若这里出现差异，说明 nav 的
  // functionCode 与 MenuRegistry 的 functionCodes 不是同一组码，先修注册表再谈 UI。
  {
    const diffs = []
    for (const role of ROLES) {
      const ctx = ctxByRole.get(role.user)
      const double = new Set(sidebar.filter((it) => navVisible(it, ctx)).map((it) => it.label))
      const single = new Set(sidebar.filter((it) => navVisibleByFunction(it, ctx)).map((it) => it.label))
      const onlyDouble = [...single].filter((l) => !double.has(l))
      const onlySingle = [...double].filter((l) => !single.has(l))
      if (onlyDouble.length || onlySingle.length) {
        diffs.push(`${role.user}(页面码多挡=[${onlyDouble.join(',')}] 页面码放行=[${onlySingle.join(',')}])`)
      }
    }
    if (diffs.length === 0) {
      ok('7 角色的双条件可见集与单功能码可见集一致（MENU = max(绑定功能码)，页面码不额外挡人）')
    } else {
      bad(`页面码与功能码判定不一致：${diffs.join('；')}`,
        'nav 的 functionCode 必须取自该页面在 MenuRegistry 的 functionCodes；不一致时 MENU 档位与 nav 判据会永久打架')
    }
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
