#!/usr/bin/env node
/**
 * a6-write-button-gate.mjs — 写按钮准入门禁（PRD AC-1/AC-2/AC-3/AC-4；TECHNICAL §6.1）
 *
 * 为什么单独一段：a2 证明「页面能进」（路由准入），a5 证明「可选探测 403 能降级」（能力准入），
 * 本段证明的是**写入口的可见形态与准入语义**——r25 的缺陷正是第三种：
 * 页面进得去、主端点也 200，但页头那颗「提报需求／新建缺陷／新建迭代」按钮是裸的：
 * 没有权限也照渲染、点了直接落到全局 403 页。没有任何现有门禁看得见「按钮长得对不对」，
 * 因为它在模板里，不在请求里。
 *
 * 三层证据，缺一层都会假绿：
 *   静态（14）—— 模板与 handler 的形态：disabled / title 原因 / 守卫先于 push / 字面量计数；
 *   降级（5）—— 探测类调用必须带 silentForbidden，client.ts 的 403 导航必须被它包住；
 *   运行时（4）—— vpm1 真登录后打 4 个写端点，**必须真 403**（前端禁用不是授权，服务端才是）；
 *   自检（2）—— 内存夹具证明这 14 条断言会红（抽掉 :disabled 后 W1 必红），防空扫假绿。
 *
 * 计数经 ACCESS_COUNTS 交给 scripts/access-verify.sh（格式 `a6 <pass> <total>`）。
 * 栈未起时 exit 1 且**不写计数行**（同 a5 口径）：聚合器按「该段无计数」判红，
 * 而不是把「0 条断言」当通过。
 *
 * 单跑：node scripts/tests/access/a6-write-button-gate.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
export const ROOT = path.resolve(HERE, '../../..')
const WEB = path.join(ROOT, 'web/src')
const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const TENANT = process.env.A6_TENANT || 't1'
const VPM = { user: process.env.A6_VPM_USER || 'vpm1', pass: process.env.A6_VPM_PASS || 'Passw0rd!' }

/** 被审计的源码（相对 ROOT）。路径写死：文件被删/改名时断言直接红，而不是静默跳过。 */
export const FILES = {
  pool: 'web/src/views/req/RequirementPoolView.vue',
  defect: 'web/src/views/deliver/DefectListView.vue',
  iterations: 'web/src/views/deliver/IterationsView.vue',
  releaseList: 'web/src/views/release/ReleaseListView.vue',
  releaseDetail: 'web/src/views/release/ReleaseDetailView.vue',
  caseLibrary: 'web/src/views/deliver/TestCaseLibraryView.vue',
  testPlans: 'web/src/views/deliver/TestPlansView.vue',
  reqApi: 'web/src/api/req.ts',
  dictionaries: 'web/src/api/dictionaries.ts',
  client: 'web/src/api/client.ts',
}

/** 运行时探针：四个写端点。空 JSON body 即可——鉴权先于参数校验，有权限才会走到 400。 */
const WRITE_ENDPOINTS = [
  { id: 'C1', path: '/api/req/requirements', label: 'POST /api/req/requirements（提报需求）' },
  { id: 'C2', path: '/api/req/releases', label: 'POST /api/req/releases（新建版本）' },
  { id: 'C3', path: '/api/deliver/testcases', label: 'POST /api/deliver/testcases（新建用例）' },
  { id: 'C4', path: '/api/deliver/defects', label: 'POST /api/deliver/defects（新建缺陷）' },
]

/* ================================================================ 源码工具（导出，供自检复用） */

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

/** `function name(...) { ... }` 的函数体（不含花括号）；找不到返回 '' */
function functionBody(src, name) {
  const m = new RegExp(`function\\s+${name}\\s*\\(`).exec(src)
  if (!m) return ''
  const paren = src.indexOf('(', m.index)
  const close = matchParen(src, paren)
  if (close < 0) return ''
  const brace = src.indexOf('{', close)
  if (brace < 0) return ''
  let depth = 0
  for (let i = brace; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(brace + 1, i)
  }
  return ''
}

/** `const name = computed(() => ...)` 的实参文本；找不到返回 '' */
function computedBody(src, name) {
  const m = new RegExp(`const\\s+${name}\\s*=`).exec(src)
  if (!m) return ''
  const call = src.indexOf('computed(', m.index)
  if (call < 0) return ''
  const open = call + 'computed'.length
  const close = matchParen(src, open)
  return close < 0 ? '' : src.slice(open + 1, close)
}

/** 模板区（<template> 之后）：模板内联 push 只在这里才算「模板里」 */
function templateOf(src) {
  const at = src.indexOf('<template>')
  return at < 0 ? '' : src.slice(at)
}

/** 页头区块：断言必须落在页头那颗按钮上，而不是整页任一处 */
function headOf(tpl) {
  const start = tpl.indexOf('<header')
  if (start < 0) return ''
  const end = tpl.indexOf('</header>', start)
  return end < 0 ? tpl.slice(start) : tpl.slice(start, end)
}

/** 空态 #action 具名插槽区块 */
function slotOf(tpl) {
  const start = tpl.indexOf('<template #action>')
  if (start < 0) return ''
  const end = tpl.indexOf('</template>', start)
  return end < 0 ? tpl.slice(start) : tpl.slice(start, end)
}

/** 函数**首条语句**是否就是 `if (!canX.value) return`（handler 兜底不是冗余，见 TECHNICAL §5.2） */
function startsWithGuard(body, expr) {
  return new RegExp(`^\\s*if\\s*\\(\\s*!\\s*${expr}\\.value\\s*\\)\\s*return\\b`).test(body)
}

/** 调用点（不是声明/常量定义，不是 import 的具名列表） */
function callSites(src, callee) {
  const text = blankComments(src)
  const out = []
  let from = 0
  for (;;) {
    const start = text.indexOf(callee, from)
    if (start < 0) break
    from = start + callee.length
    const prev = start > 0 ? text[start - 1] : ''
    if (/[\w$]/.test(prev) || prev === '.') continue
    const before = text.slice(Math.max(0, start - 60), start)
    if (/\bfunction\s+$/.test(before) || /\b(const|let|var)\s+$/.test(before)) continue
    let j = from
    while (j < text.length && /\s/.test(text[j])) j++
    if (text[j] !== '(') continue
    out.push({ line: text.slice(0, start).split('\n').length })
  }
  return out
}

const srcOf = (src, key) => blankComments(src[key] ?? '')

/* ================================================================ A 组：静态收敛 14 条 */

/**
 * @param {Record<string,string>} src  key → 源码文本（FILES 的 key）
 * @returns {{id: string, ok: boolean, detail: string}[]} 14 条
 */
export function auditWriteButtons(src) {
  const out = []
  const push = (id, ok, detail) => out.push({ id, ok: Boolean(ok), detail })

  const pool = srcOf(src, 'pool')
  const poolTpl = templateOf(pool)
  const poolHead = headOf(poolTpl)
  const poolSlot = slotOf(poolTpl)
  const denyTip = computedBody(pool, 'reportDenyTip')

  push(
    'W1',
    poolHead.includes(':disabled="!canReport"'),
    '需求池页头「提报需求」绑定 :disabled="!canReport"（无权限时仍渲染、但不让点）',
  )
  push(
    'W2',
    poolHead.includes(':title="reportDenyTip"') &&
      /需要权限：/.test(denyTip) &&
      pool.includes('req:report:bus') &&
      pool.includes('req:report:dev'),
    '页头 :title="reportDenyTip"，且原因含「需要权限：」与 req:report:bus / req:report:dev',
  )
  push('W3', poolHead.includes('@click="goCreate"'), '页头按钮 @click="goCreate"（不是内联 push）')

  const goCreate = functionBody(pool, 'goCreate')
  const guardAt = goCreate.indexOf('if (!canReport.value) return')
  const pushAt = goCreate.indexOf('router.push')
  push(
    'W4',
    guardAt >= 0 && pushAt > guardAt,
    'goCreate 内 `if (!canReport.value) return` 先于 router.push（键盘/程序化点击也挡住）',
  )

  push(
    'W5',
    poolSlot !== '' &&
      poolSlot.includes(':disabled="!canReport"') &&
      poolSlot.includes(':title="reportDenyTip"') &&
      poolSlot.includes('@click="goCreateFromEmpty"'),
    '空态走 EmptyState #action 插槽，按钮同样 disabled + title + 具名 handler',
  )

  const literals = (pool.match(/\/requirements\/new/g) || []).length
  push(
    'W6',
    literals === 2,
    `router.push('/requirements/new') 字面量计数 = ${literals}（期望 2 = 页头 + 空态两条路径，AC-1 口径）`,
  )

  const fromEmpty = functionBody(pool, 'goCreateFromEmpty')
  const emptyGuard = fromEmpty.indexOf('if (!canReport.value) return')
  const emptyPush = fromEmpty.indexOf('router.push')
  const filterAt = fromEmpty.indexOf('isFiltered.value')
  push(
    'W7',
    emptyGuard >= 0 &&
      emptyPush > emptyGuard &&
      (filterAt < 0 || filterAt < emptyGuard),
    'goCreateFromEmpty：清空筛选分支可在前（不受写权限限制），但权限守卫必须紧邻 push 之前',
  )

  push(
    'W8',
    !/router\.push\(\s*['"`]\/requirements\/new['"`]\s*\)/.test(poolTpl),
    '模板区无内联 router.push(需求新建)（全部走具名 handler，才有兜底）',
  )

  // 已合规的 4 个入口：只断言，不修改（TECHNICAL §3.4）
  const releaseList = srcOf(src, 'releaseList')
  push(
    'W9',
    /canAction\(\s*'release\.action\.create'\s*\)/.test(releaseList) &&
      headOf(templateOf(releaseList)).includes(':disabled="!canCreateRelease"') &&
      headOf(templateOf(releaseList)).includes('需要权限：release.action.create'),
    '新建版本：canAction(release.action.create) + 页头 disabled + title 带原因',
  )

  const releaseDetail = srcOf(src, 'releaseDetail')
  push(
    'W10',
    /canAction\(\s*'release\.action\.approve'\s*\)/.test(releaseDetail) &&
      /:disabled="!canApprove\b/.test(releaseDetail) &&
      releaseDetail.includes('需要权限：release.action.approve'),
    '提交发布审批：canAction(release.action.approve) + disabled + title 带原因',
  )

  const caseLibrary = srcOf(src, 'caseLibrary')
  push(
    'W11',
    /canAction\(\s*'test\.action\.case_edit'\s*\)/.test(caseLibrary) &&
      headOf(templateOf(caseLibrary)).includes(':disabled="!canEdit"') &&
      headOf(templateOf(caseLibrary)).includes('需要权限：test.action.case_edit') &&
      headOf(templateOf(caseLibrary)).includes('@click="openCreate"'),
    '新建用例：canAction(test.action.case_edit) + 页头 disabled + title 带原因',
  )

  const testPlans = srcOf(src, 'testPlans')
  push(
    'W12',
    /canAction\(\s*'test\.action\.plan_create'\s*\)/.test(testPlans) &&
      headOf(templateOf(testPlans)).includes(':disabled="!canCreate"') &&
      headOf(templateOf(testPlans)).includes('需要权限：test.action.plan_create'),
    '新建计划：canAction(test.action.plan_create) + 页头 disabled + title 带原因',
  )

  const defect = srcOf(src, 'defect')
  const defectHead = headOf(templateOf(defect))
  push(
    'W13',
    /canAction\(\s*'defect\.action\.create'\s*\)/.test(defect) &&
      defectHead.includes(':disabled="!canCreateDefect"') &&
      defectHead.includes(':title="createDenyTip"') &&
      startsWithGuard(functionBody(defect, 'openCreate'), 'canCreateDefect'),
    '新建缺陷：disabled/title 绑 canCreateDefect，openCreate 首行 `if (!canCreateDefect.value) return`',
  )

  const iters = srcOf(src, 'iterations')
  const iterHead = headOf(templateOf(iters))
  const iterTip = computedBody(iters, 'createDenyTip')
  const precondition = iterTip.indexOf('请先选择版本')
  const permReason = iterTip.indexOf('需要权限：sprint.action.create')
  const iterGate = computedBody(iters, 'canCreateIteration')
  push(
    'W14',
    /canAction\(\s*'sprint\.action\.create'\s*\)/.test(iters) &&
      /canCreateSprint/.test(iterGate) &&
      /releaseId/.test(iterGate) &&
      iterHead.includes(':disabled="!canCreateIteration"') &&
      iterHead.includes(':title="createDenyTip"') &&
      precondition >= 0 &&
      permReason > precondition &&
      startsWithGuard(functionBody(iters, 'openCreate'), 'canCreateIteration'),
    '新建迭代：两条件合成 canCreateIteration（disabled 与 handler 同源），title 前置条件「请先选择版本」优先于权限原因，openCreate 首行守卫',
  )

  return out
}

/* ================================================================ B 组：403 降级 5 条 */

/**
 * @param {Record<string,string>} src
 * @returns {{id: string, ok: boolean, detail: string}[]} 5 条
 */
export function auditForbiddenDegrade(src) {
  const out = []
  const push = (id, ok, detail) => out.push({ id, ok: Boolean(ok), detail })

  const dict = srcOf(src, 'dictionaries')
  const dictFns = ['listDictionaryDomains', 'listDictionaryVendors', 'listDictionaryUsers']
  const dictMissing = dictFns.filter((fn) => !/silentForbidden/.test(functionBody(dict, fn)))
  push(
    'D1',
    dictMissing.length === 0,
    `api/dictionaries.ts 三个字典函数均带 silentForbidden（缺: ${dictMissing.join(',') || '无'}）—— 字典拿不到只能降级为空表`,
  )

  const reqApi = srcOf(src, 'reqApi')
  push(
    'D2',
    /silentForbidden/.test(functionBody(reqApi, 'searchRequirements')),
    'req.ts searchRequirements 实参带 silentForbidden（缺陷弹窗的需求搜索 403 就地降级为空选项）',
  )

  const client = srcOf(src, 'client')
  const at = client.indexOf('response.status === 403')
  const branch = at < 0 ? '' : client.slice(at, at + 400)
  push(
    'D3',
    at >= 0 &&
      /!\s*options\.silentForbidden/.test(branch) &&
      /navigate\(\s*[`'"]\/403/.test(branch) &&
      branch.indexOf('silentForbidden') < branch.indexOf('navigate('),
    'client.ts：/403 导航被 `if (!options.silentForbidden)` 包住（否则页面 try/catch 永远收不到 403）',
  )

  const views = ['pool', 'defect', 'iterations'].filter((k) =>
    /(?:navigate|router\.push)\(\s*['"`]\/403/.test(srcOf(src, k)),
  )
  push(
    'D4',
    views.length === 0,
    `三个视图内无主动跳 /403（命中: ${views.join(',') || '无'}）—— 探测失败只能就地降级`,
  )

  const sites = []
  for (const abs of walk(WEB)) {
    const relPath = toRel(abs)
    for (const site of callSites(readFileSync(abs, 'utf8'), 'searchRequirements')) {
      sites.push(`${relPath}:${site.line}`)
    }
  }
  push(
    'D5',
    sites.length === 1 && sites[0].startsWith(FILES.defect),
    `searchRequirements 全仓调用点 = ${sites.length}（${sites.join(', ') || '无'}）：改动面只有缺陷弹窗一处`,
  )

  return out
}

/* ================================================================ C 组：运行时 4 条（vpm1 必须真 403） */

function stackDown(message) {
  console.error('a6: 平台栈未就绪（先 make platform-up && make platform-seed）')
  console.error(`  ${message}`)
  process.exit(1) // 故意不写计数行：让聚合器报「该段无计数」，而不是 0 条也算过
}

async function loginVpm1(baseUrl) {
  let res
  try {
    res = await fetch(`${baseUrl}/api/req/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantCode: TENANT, username: VPM.user, password: VPM.pass }),
    })
  } catch (e) {
    stackDown(`${baseUrl}/api/req/auth/login 不可达：${e.message}`)
  }
  const text = await res.text()
  if (res.status !== 200) stackDown(`账号 ${VPM.user} 登录失败 HTTP ${res.status}：${text.slice(0, 200)}`)
  let body
  try {
    body = JSON.parse(text)
  } catch {
    stackDown(`账号 ${VPM.user} 登录响应不是 JSON：${text.slice(0, 200)}`)
  }
  if (!body.token) stackDown(`账号 ${VPM.user} 登录响应缺 token（种子未就绪？）`)
  return { token: body.token, tenantId: body.tenantId ?? TENANT }
}

/**
 * vpm1 对四个写端点发 POST（空 body），必须**真的** 403。
 * 前端的 disabled 只是 UX：它挡不住深链、挡不住 curl、更挡不住「按钮 disabled 被误删」。
 * @param {string} baseUrl 网关根（如 http://127.0.0.1:8080）
 * @param {string} token   vpm1 的 Bearer token
 * @param {string|number} [tenantId]
 * @returns {Promise<{id: string, ok: boolean, detail: string}[]>} 4 条
 */
export async function probeVpm1Write(baseUrl, token, tenantId = TENANT) {
  const out = []
  for (const ep of WRITE_ENDPOINTS) {
    let got = 'transport-error'
    let note = ''
    try {
      const res = await fetch(`${baseUrl}${ep.path}`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'X-Tenant-Id': String(tenantId),
          'Content-Type': 'application/json',
        },
        body: '{}',
      })
      got = res.status
      note = (await res.text().catch(() => '')).slice(0, 160)
    } catch (e) {
      note = e.message
    }
    out.push({
      id: ep.id,
      ok: got === 403,
      detail:
        got === 403
          ? `${VPM.user} → ${ep.label} = 403（服务端是授权唯一真相）`
          : `${VPM.user} → ${ep.label} 期望 403，实际 ${got}${note ? `：${note}` : ''}`,
    })
  }
  return out
}

/* ================================================================ D 组：内存自检 2 条 */

/** 合规夹具：14 条静态断言必须全绿（正控）。刻意写成最小可判形态，不复制真实文件。 */
const FIXTURES = {
  pool: `
<script setup lang="ts">
const reportCodes = ['req:report:bus', 'req:report:dev'] as const
const canReport = computed(() => can('req:report:bus') || can('req:report:dev'))
const reportDenyTip = computed(() => (canReport.value ? '' : '需要权限：' + reportCodes.join(' / ')))
function goCreate(): void {
  if (!canReport.value) return
  router.push('/requirements/new')
}
function goCreateFromEmpty(): void {
  if (isFiltered.value) {
    reset()
    return
  }
  if (!canReport.value) return
  router.push('/requirements/new')
}
</script>
<template>
  <header>
    <el-button :type="canReport ? 'primary' : 'default'" :disabled="!canReport" :title="reportDenyTip" @click="goCreate">提报需求</el-button>
  </header>
  <EmptyState>
    <template #action>
      <el-button :type="canReport ? 'primary' : 'default'" :disabled="!canReport" :title="reportDenyTip" @click="goCreateFromEmpty">提报需求</el-button>
    </template>
  </EmptyState>
</template>
`,
  defect: `
<script setup lang="ts">
const canCreateDefect = computed(() => perm.canAction('defect.action.create'))
const createDenyTip = computed(() => (canCreateDefect.value ? '' : '需要权限：defect.action.create'))
function openCreate(): void {
  if (!canCreateDefect.value) return
  createVisible.value = true
}
</script>
<template>
  <header>
    <el-button :disabled="!canCreateDefect" :title="createDenyTip" @click="openCreate">新建缺陷</el-button>
  </header>
</template>
`,
  iterations: `
<script setup lang="ts">
const canCreateSprint = computed(() => perm.canAction('sprint.action.create'))
const canCreateIteration = computed(() => canCreateSprint.value && !!releaseId.value)
const createDenyTip = computed(() => (!releaseId.value ? '请先选择版本' : canCreateSprint.value ? '' : '需要权限：sprint.action.create'))
function openCreate(): void {
  if (!canCreateIteration.value) return
  createVisible.value = true
}
</script>
<template>
  <header>
    <el-button :disabled="!canCreateIteration" :title="createDenyTip" @click="openCreate">新建迭代</el-button>
  </header>
</template>
`,
  releaseList: `
<script setup lang="ts">
const canCreateRelease = computed(() => canAction('release.action.create'))
</script>
<template>
  <header>
    <el-button :disabled="!canCreateRelease" :title="canCreateRelease ? '' : '需要权限：release.action.create'" @click="openCreate">新建版本</el-button>
  </header>
</template>
`,
  releaseDetail: `
<script setup lang="ts">
const canApprove = computed(() => canAction('release.action.approve'))
</script>
<template>
  <header>
    <el-button :disabled="!canApprove || release?.status !== 'PLAN'" :title="canApprove ? '' : '需要权限：release.action.approve'" @click="approvalOpen = true">提交发布审批</el-button>
  </header>
</template>
`,
  caseLibrary: `
<script setup lang="ts">
const canEdit = computed(() => canAction('test.action.case_edit'))
</script>
<template>
  <header>
    <el-button :disabled="!canEdit" :title="canEdit ? '' : '需要权限：test.action.case_edit'" @click="openCreate">新建用例</el-button>
  </header>
</template>
`,
  testPlans: `
<script setup lang="ts">
const canCreate = computed(() => canAction('test.action.plan_create'))
</script>
<template>
  <header>
    <el-button :disabled="!canCreate" :title="canCreate ? '' : '需要权限：test.action.plan_create'" @click="openCreate">新建计划</el-button>
  </header>
</template>
`,
}

/**
 * 2 条自检：正控（合规夹具 14 条全绿）+ 负控（抽掉页头 :disabled 后 W1 必红）。
 * 少了负控，「断言恒真」与「断言真的在判」在输出上长得一模一样。
 */
export function selfCheck() {
  const out = []
  const push = (id, ok, detail) => out.push({ id, ok: Boolean(ok), detail })

  const good = auditWriteButtons(FIXTURES)
  const red = good.filter((a) => !a.ok)
  push(
    'S1',
    good.length === 14 && red.length === 0,
    `合规夹具 14 条全绿（实跑 ${good.length - red.length}/${good.length}${red.length ? `，红: ${red.map((a) => a.id).join(',')}` : ''}）`,
  )

  const mutated = {
    ...FIXTURES,
    pool: FIXTURES.pool.replace(
      ' :disabled="!canReport" :title="reportDenyTip" @click="goCreate"',
      ' :title="reportDenyTip" @click="goCreate"',
    ),
  }
  const mutatedAudit = auditWriteButtons(mutated)
  const w1 = mutatedAudit.find((a) => a.id === 'W1')
  push(
    'S2',
    Boolean(w1 && !w1.ok) && mutatedAudit.filter((a) => !a.ok).length === 1,
    '抽掉页头 :disabled="!canReport" 后 W1 变红（且只红它）—— 断言可证伪，不是永真',
  )
  return out
}

/* ================================================================ 源码遍历 */

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

function toRel(abs) {
  return path.relative(ROOT, abs).split(path.sep).join('/')
}

function loadSources() {
  const src = {}
  const missing = []
  for (const [key, rel] of Object.entries(FILES)) {
    try {
      src[key] = readFileSync(path.join(ROOT, rel), 'utf8')
    } catch {
      src[key] = ''
      missing.push(rel)
    }
  }
  if (missing.length) {
    console.error(`a6: 缺少被审计文件（断言将逐条判红）: ${missing.join(', ')}`)
  }
  return src
}

/* ================================================================ 主流程 */

async function main() {
  const src = loadSources()

  console.log('---- a6-a 静态收敛 14 条：写入口的形态 ----')
  const staticAsserts = auditWriteButtons(src)
  staticAsserts.forEach((a) => report(a))

  console.log('\n---- a6-b 403 降级 5 条：探测类调用就地降级 ----')
  const degradeAsserts = auditForbiddenDegrade(src)
  degradeAsserts.forEach((a) => report(a))

  console.log('\n---- a6-c 运行时 4 条：vpm1 真登录 → 4 个写端点必须 403 ----')
  const auth = await loginVpm1(GATEWAY)
  const runtimeAsserts = await probeVpm1Write(GATEWAY, auth.token, auth.tenantId)
  runtimeAsserts.forEach((a) => report(a))

  console.log('\n---- a6-d 自检 2 条：夹具正控 + 反控 ----')
  const selfAsserts = selfCheck()
  selfAsserts.forEach((a) => report(a))

  const all = [...staticAsserts, ...degradeAsserts, ...runtimeAsserts, ...selfAsserts]
  const pass = all.filter((a) => a.ok).length
  const total = all.length
  const failures = all.filter((a) => !a.ok)

  console.log(
    `\n  分组：静态 ${staticAsserts.filter((a) => a.ok).length}/${staticAsserts.length}` +
      ` · 降级 ${degradeAsserts.filter((a) => a.ok).length}/${degradeAsserts.length}` +
      ` · 运行时 ${runtimeAsserts.filter((a) => a.ok).length}/${runtimeAsserts.length}` +
      ` · 自检 ${selfAsserts.filter((a) => a.ok).length}/${selfAsserts.length}`,
  )
  if (total !== 25) {
    console.log(`  注意：断言总数 ${total}（TECHNICAL §6.1 的计划值是 25）；聚合器下限为 24，增删需同步文档`)
  }

  const countsFile = process.env.ACCESS_COUNTS
  if (countsFile) {
    const { appendFileSync } = await import('node:fs')
    appendFileSync(countsFile, `a6 ${pass} ${total}\n`)
  }

  if (total === 0) {
    console.error('a6: 一条断言都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (failures.length) {
    console.error(`\na6-write-button-gate: ${total - pass} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f.id} ${f.detail}`)
    process.exit(1)
  }
  console.log(`\n  ok a6-write-button-gate: ${pass}/${total} 项通过（写入口形态 / 403 降级 / 服务端仍 403）`)
}

function report(a) {
  const tag = a.ok ? '\u001b[1;32mPASS\u001b[0m' : '\u001b[1;31mFAIL\u001b[0m'
  console.log(`  ${tag} ${a.id} ${a.detail}`)
}

// 作为脚本直接运行时执行并给出退出码；被 import 时只取审计器（同 a5 口径），
// 否则任何 import 都会顺手打一遍 HTTP 断言并抢走 process.exit。
const invokedDirectly =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  main().catch((e) => {
    console.error(`a6-write-button-gate 无法执行: ${e.message}`)
    console.error('  修复: 先 make platform-up && make platform-seed（需要网关与 vpm1 种子账号）')
    process.exit(1)
  })
}
