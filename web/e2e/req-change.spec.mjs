#!/usr/bin/env node
// web/e2e/req-change.spec.mjs — 需求变更单（r40）真实栈 UI 冒烟
//
// 为什么需要它：AC-13/17/18 里有几条只能看渲染结果才成立——
// 「第 10 个 Tab 与角标」「720 四步抽屉第③步只列白名单字段」「APPROVED 不等于已生效的措辞」
// 「APPLY 按钮在非 APPROVED 时根本不渲染」。接口断言证明不了这些。
//
// 依赖：Chrome 带 --remote-debugging-port=9222（headless 可）、web 5173、网关 8080、demo 夹具已重置。
// 任一项不可达 → 打印 SKIP 并 exit 0（离线可跑，不制造假红灯）；可达但断言失败 → exit 1。
// 跑法：node web/e2e/req-change.spec.mjs

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5173'
const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222'
const TENANT = process.env.REQ_CHANGE_TENANT || 't1'
const RUN_TAG = `e2e${Date.now()}`

const HERE = dirname(fileURLToPath(import.meta.url))

let total = 0
let pass = 0
const failures = []
function check(desc, ok, detail = '') {
  total++
  if (ok) {
    pass++
    console.log(`  PASS ${desc}`)
  } else {
    failures.push(`${desc}${detail ? ` — ${detail}` : ''}`)
    console.log(`  FAIL ${desc}${detail ? ` — ${detail}` : ''}`)
  }
}
function skip(reason) {
  console.log(`SKIP req-change-e2e: ${reason}`)
  process.exit(0)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function api(method, path, token, body) {
  const res = await fetch(GATEWAY + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': '1',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* keep null */
  }
  return { status: res.status, json, text }
}

async function login(username, password) {
  const { status, json } = await api('POST', '/api/req/auth/login', null, { tenantCode: TENANT, username, password })
  if (status !== 200 || !json?.token) throw new Error(`login ${username} -> ${status} ${json?.code ?? ''}`)
  return json
}

class Cdp {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl)
    this.seq = 0
    this.pending = new Map()
    this.ready = new Promise((ok, fail) => {
      this.ws.addEventListener('open', () => ok())
      this.ws.addEventListener('error', (e) => fail(new Error('cdp socket ' + e.message)))
    })
    this.ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve: done, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : done(msg.result)
      }
    })
  }
  send(method, params = {}, sessionId) {
    const id = ++this.seq
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    this.ws.send(JSON.stringify(payload))
    return new Promise((done, fail) => {
      this.pending.set(id, { resolve: done, reject: fail })
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          fail(new Error(`cdp timeout ${method}`))
        }
      }, 20000)
    })
  }
}

/** 每个场景独立 target：连续 navigate 复用 target 会偶发不返回。 */
async function newPage(browser) {
  const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true })
  const send = (m, p) => browser.send(m, p, sessionId)
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  const evalJs = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text ?? 'eval failed')
    return r.result?.value
  }
  return {
    evalJs,
    async navigate(url) {
      await send('Page.navigate', { url })
    },
    async close() {
      await browser.send('Target.closeTarget', { targetId })
    },
  }
}

async function open(browser, user, path, settle = 2400) {
  const session = await login(user.username, user.password)
  const permissions = (session.permissions ?? [])
    .filter((p) => (typeof p === 'string' ? true : p.access !== 'DENY'))
    .map((p) => (typeof p === 'string' ? p : p.code))
  const page = await newPage(browser)
  await page.navigate(`${WEB}/login`)
  await sleep(800)
  const state = {
    token: session.token,
    tenantId: String(session.user?.tenantId ?? '1'),
    tenantCode: TENANT,
    user: session.user,
    permissions,
    defaultRoute: '/requirements',
  }
  await page.evalJs(`sessionStorage.setItem('devops.session', ${JSON.stringify(JSON.stringify(state))}); true`)
  await page.navigate(`${WEB}${path}`)
  await sleep(settle)
  return page
}

const ADMIN = { username: 'admin', password: 'Admin@12345' }

/** 措辞契约（AC-18）：README 级常量检查，比翻页面更直接——文案唯一来源是 dict.ts */
function wordingContract() {
  const dict = readFileSync(join(HERE, '../src/stores/dict.ts'), 'utf8')
  const changeStatusBlock = dict.slice(dict.indexOf('export const CHANGE_STATUS'), dict.indexOf('export const CHANGE_TYPE'))
  check('变更状态词表存在 APPROVED「已通过 · 待应用」', dict.includes('已通过 · 待应用'))
  // 只校验变更单状态词表：版本签核的 SIGNED 另有「已生效」，那不是本需求的措辞
  check('变更状态词表不含「已生效」（通过≠生效）', changeStatusBlock.length > 0 && !changeStatusBlock.includes('已生效'), changeStatusBlock.slice(0, 120))
  const changeUtil = readFileSync(join(HERE, '../src/views/req/change.ts'), 'utf8')
  check('缺数据文案统一「无法评估」（禁止 0 / —）', changeUtil.includes("IMPACT_MISSING = '无法评估'"))
  check('STALE 阈值严格 0.2（单一常量）', /STALE_THRESHOLD = 0\.2/.test(changeUtil))

  // 深链、审批中心标签、布局令牌：没有变更单数据时无法走页面，用源码契约兜住
  const router = readFileSync(join(HERE, '../src/router/routes/req.routes.ts'), 'utf8')
  const routeBlock = router.slice(router.indexOf("'/requirements/:id/changes/:changeId'"))
  check(
    '变更详情深链已注册（pageCode=req.pool，零新菜单码）',
    routeBlock.length > 0 && routeBlock.slice(0, 400).includes("'requirement-change-detail'") && routeBlock.slice(0, 400).includes("pageCode: 'req.pool'"),
    routeBlock.slice(0, 200),
  )
  const approval = readFileSync(join(HERE, '../src/views/approval/ApprovalCenterView.vue'), 'utf8')
  const changeBranch = approval.indexOf("if (raw.includes('CHANGE'))")
  const reqBranch = approval.indexOf("if (raw.includes('REQ'))")
  check(
    '审批中心 REQ_CHANGE 先于 REQ 分支匹配并显示「需求变更」',
    changeBranch > -1 && reqBranch > -1 && changeBranch < reqBranch && /REQ_CHANGE: '需求变更'/.test(approval),
    `change@${changeBranch} req@${reqBranch}`,
  )
  const tokens = readFileSync(join(HERE, '../src/styles/tokens.css'), 'utf8')
  check('720 抽屉 / 560 编辑抽屉令牌已定义', /--change-drawer-w:\s*720px/.test(tokens) && /--change-drawer-edit-w:\s*560px/.test(tokens))
  const sentence = readFileSync(join(HERE, '../src/components/AutomationRuleSentence.vue'), 'utf8')
  check(
    '自动化规则字段文案扩到 8 项（含期望交付日 / 父需求）',
    sentence.includes('expected_delivery_date') && sentence.includes('parent_id') && sentence.includes('期望交付日') && sentence.includes('父需求'),
  )
}

/** 状态机契约（AC-15）：APPLY 只在 canApply && APPROVED 时渲染 */
function applyRenderContract() {
  const view = readFileSync(join(HERE, '../src/views/req/RequirementChangeDetailView.vue'), 'utf8')
  check(
    'APPLY 按钮绑定 canApply（APPROVED 之外不渲染）',
    /const canApply = computed\(\(\) => status\.value === 'APPROVED' && detail\.value\?\.canApply === true\)/.test(view),
  )
  check('APPROVED 横幅写明「尚未应用」', view.includes('审批已通过，尚未应用'))
  const drawer = readFileSync(join(HERE, '../src/components/req/ChangeCreateDrawer.vue'), 'utf8')
  check('第④步走服务端 preview（不本地伪造影响）', drawer.includes('previewRequirementChange'))
  const detailView = readFileSync(join(HERE, '../src/views/req/RequirementDetailView.vue'), 'utf8')
  check('第 10 个 Tab 键为 change（插在 approval 之后）', /'approval',\s*'change',/.test(detailView))
}

async function main() {
  wordingContract()
  applyRenderContract()
  // 静态契约失败必须在 SKIP 之前就红：否则「栈没起」会把措辞回归吞成 0 退出
  if (failures.length) {
    console.error(`\nreq-change-e2e: 静态契约 ${failures.length} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f}`)
    process.exit(1)
  }

  if (typeof WebSocket === 'undefined') skip('当前 Node 没有全局 WebSocket（需要 Node 22+）')
  let version
  try {
    version = await (await fetch(`${CDP}/json/version`, { signal: AbortSignal.timeout(2500) })).json()
  } catch {
    skip(`Chrome CDP 不可达（${CDP}）—— 用 --remote-debugging-port=9222 起一个 Chrome 再跑`)
  }
  try {
    await fetch(WEB, { signal: AbortSignal.timeout(2500) })
  } catch {
    skip(`web 未启动（${WEB}）—— make platform-up 后再跑`)
  }
  let session
  try {
    session = await login(ADMIN.username, ADMIN.password)
  } catch (e) {
    skip(`admin 登录失败（演示夹具未重置？）: ${e.message} —— 先 make platform-demo-reset`)
  }
  const token = session.token

  // —— S1 接口契约：创建 → 列表 → 详情 → 未通过不可应用 → PUT 收口 ——
  const pool = await api('GET', '/api/req/requirements?page=1&size=100', token)
  // 夹具必须是有已批评估的叶子 RD：BUS/PDR 的人天由子需求汇总（PARENT_DAYS_READONLY），
  // 不能直接发 DAYS 变更；IN_DEV 非早期态，PUT 直改仍会 409 CHANGE_REQUIRED。
  const candidates = (pool.json?.items ?? []).filter((row) => row.type === 'RD' && row.status === 'IN_DEV')
  if (!candidates.length) skip('没有可用的 RD/IN_DEV 需求做变更单夹具（叶子需求才可变更人天）')
  const target = candidates[0]
  const beforeDays = Number(target.estimateDays ?? 0)
  const afterDays = beforeDays > 0.5 ? beforeDays - 0.5 : beforeDays + 0.5
  const reason = `e2e ${RUN_TAG} 人天微调，验证变更单闭环`

  const preview = await api('POST', `/api/req/requirements/${target.id}/changes/preview`, token, {
    changeType: 'DAYS',
    reason,
    patch: { estimateDays: afterDays },
  })
  // 404 = 变更单接口还没上线（T2/T3 未合并）：只跳过依赖它的断言，不把 T4 判红
  const backendReady = preview.status !== 404
  if (!backendReady) {
    console.log('  SKIP 变更单接口返回 404 —— 后端 T2/T3 未就绪，跳过接口与依赖数据的 UI 断言')
  }
  if (backendReady) {
    check('preview 返回 200 且带 8 键影响快照', preview.status === 200 && ['fields', 'children', 'release', 'targetRelease', 'days', 'admission', 'postLock', 'frozenAt'].every((k) => k in (preview.json?.impact ?? {})), `${preview.status} ${preview.text.slice(0, 160)}`)
  }

  const created = await api('POST', `/api/req/requirements/${target.id}/changes`, token, {
    changeType: 'DAYS',
    reason,
    patch: { estimateDays: afterDays },
  })
  const changeId = created.json?.id
  if (backendReady) {
    check('创建变更单返回 201 + PENDING + instanceId', created.status === 201 && created.json?.status === 'PENDING' && Number(created.json?.instanceId) > 0, `${created.status} ${created.text.slice(0, 160)}`)
  }

  if (backendReady && changeId) {
    const detail = await api('GET', `/api/req/changes/${changeId}`, token)
    check('详情 canApply=false（PENDING 不可应用）', detail.status === 200 && detail.json?.canApply === false, `${detail.status}`)
    check('详情 before/after 只含 7 字段快照', Object.keys(detail.json?.before ?? {}).length === 7 && Object.keys(detail.json?.after ?? {}).length === 7, JSON.stringify(Object.keys(detail.json?.before ?? {})))
    check('items 在 apply 前为空（未落库）', Array.isArray(detail.json?.items) && detail.json.items.length === 0)

    const list = await api('GET', `/api/req/requirements/${target.id}/changes?status=PENDING&page=1&size=20`, token)
    check('列表按状态筛选能查到本单', list.status === 200 && (list.json?.items ?? []).some((row) => row.id === changeId), `${list.status}`)

    const applyEarly = await api('POST', `/api/req/changes/${changeId}/apply`, token)
    check('PENDING 直接 apply 被拦（409 CHANGE_NOT_APPROVED）', applyEarly.status === 409 && applyEarly.json?.code === 'CHANGE_NOT_APPROVED', `${applyEarly.status} ${applyEarly.json?.code}`)

    const direct = await api('PUT', `/api/req/requirements/${target.id}`, token, { estimateDays: afterDays })
    const directFields = direct.json?.detail?.fields ?? []
    check('PUT 直改人天被 409 CHANGE_REQUIRED 收口', direct.status === 409 && direct.json?.code === 'CHANGE_REQUIRED', `${direct.status} ${direct.json?.code}`)
    check('CHANGE_REQUIRED 带 detail.fields（banner 预填依据）', Array.isArray(directFields) && directFields.includes('estimateDays'), JSON.stringify(direct.json?.detail ?? null))
  }

  // —— S2 渲染：Tab / 角标 / 抽屉四步 / 深链横幅 / APPLY 不渲染 ——
  const browser = new Cdp(version.webSocketDebuggerUrl)
  await browser.ready

  const poolPage = await open(browser, ADMIN, `/requirements/${target.id}?tab=change`)
  const tabInfo = await poolPage.evalJs(`(() => {
    const tabs = [...document.querySelectorAll('.detail__tab')].map((el) => (el.textContent || '').trim())
    return {
      count: tabs.length,
      hasChange: tabs.some((t) => t.startsWith('变更')),
      badge: (document.querySelector('[data-test="change-tab-badge"]')?.textContent || '').trim(),
      rows: document.querySelectorAll('[data-test="change-table"] tbody tr').length,
    }
  })()`)
  check('详情共 10 个 Tab 且含「变更」', tabInfo.count === 10 && tabInfo.hasChange === true, JSON.stringify(tabInfo))
  if (backendReady) {
    check('PENDING 角标渲染数字', /^\d+$/.test(tabInfo.badge) && Number(tabInfo.badge) >= 1, JSON.stringify(tabInfo))
    check('变更列表渲染出本单行', tabInfo.rows >= 1, String(tabInfo.rows))
  }

  const drawer = await poolPage.evalJs(`(() => {
    const btn = document.querySelector('[data-test="change-request"]')
    if (!btn) return { opened: false }
    btn.click()
    return { opened: true }
  })()`)
  await sleep(900)
  const drawerInfo = await poolPage.evalJs(`(() => {
    const steps = [...document.querySelectorAll('.change-steps__label')].map((el) => (el.textContent || '').trim())
    const primary = [...document.querySelectorAll('.el-drawer__footer button')].find((b) => /下一步|提交审批/.test(b.textContent || ''))
    return { steps, nextDisabled: primary ? primary.disabled === true : null, cards: document.querySelectorAll('.type-card').length }
  })()`)
  check('抽屉打开并显示四步（类型/原因/字段/影响）', drawer.opened && JSON.stringify(drawerInfo.steps) === JSON.stringify(['类型', '原因', '字段', '影响']), JSON.stringify(drawerInfo))
  check('未选类型时「下一步」禁用', drawerInfo.nextDisabled === true, JSON.stringify(drawerInfo))
  check('四类变更类型卡齐备', drawerInfo.cards === 4, String(drawerInfo.cards))

  // 编辑抽屉（AC-13 载体）：服务端没下发 editableFields 时必须全锁 + 组内给「需发起变更」，且不渲染保存
  await poolPage.evalJs(`(() => {
    const close = document.querySelector('.el-drawer__close-btn')
    close?.click()
    return true
  })()`)
  await sleep(600)
  await poolPage.evalJs(`(() => {
    const btn = document.querySelector('[data-test="requirement-edit"]')
    btn?.click()
    return true
  })()`)
  await sleep(700)
  const editInfo = await poolPage.evalJs(`(() => {
    const groups = [...document.querySelectorAll('.edit-group')]
    return {
      groups: groups.length,
      locked: groups.filter((g) => (g.textContent || '').includes('需发起变更')).length,
      saveRendered: [...document.querySelectorAll('.edit-footer button')].some((b) => /^保存/.test((b.textContent || '').trim())),
    }
  })()`)
  check('编辑抽屉四组齐备且都锁定并提示「需发起变更」', editInfo.groups === 4 && editInfo.locked === 4, JSON.stringify(editInfo))
  await poolPage.close()

  if (backendReady && changeId) {
    const deepPage = await open(browser, ADMIN, `/requirements/${target.id}/changes/${changeId}`)
    const deep = await deepPage.evalJs(`(() => ({
      banner: (document.querySelector('[data-test="change-banner"]')?.innerText || '').trim(),
      apply: !!document.querySelector('[data-test="change-apply"]'),
      texto: document.body.innerText,
      diffRows: document.querySelectorAll('[data-test="change-diff"] [class*="diff__row"]').length,
    }))()`)
    check('深链页渲染「等待审批」横幅', /等待审批/.test(deep.banner), JSON.stringify(deep.banner))
    check('非 APPROVED 时 APPLY 按钮不渲染（不是置灰）', deep.apply === false)
    check('页面上不出现「已生效」', !/已生效/.test(deep.texto))
    check('字段对照表渲染前后值', deep.diffRows >= 1, String(deep.diffRows))
    await deepPage.close()

    // 清场：撤回审批实例 → 变更单 WITHDRAWN，不污染后续 demo 重置
    const inst = created.json?.instanceId
    if (inst) {
      const withdrawn = await api('POST', `/api/req/approvals/instances/${inst}/withdraw`, token, { reason: `${RUN_TAG} cleanup` })
      const after = await api('GET', `/api/req/changes/${changeId}`, token)
      check('撤回后变更单为 WITHDRAWN（未写需求字段）', after.json?.status === 'WITHDRAWN', `${withdrawn.status} -> ${after.json?.status}`)
    }
  }

  if (total === 0) {
    console.error('req-change-e2e: 一条断言都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (failures.length) {
    console.error(`\nreq-change-e2e: ${total - pass} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f}`)
    process.exit(1)
  }
  console.log(`\n  ok req-change-e2e: ${pass}/${total} 项通过（Tab/角标/四步抽屉/深链/APPLY 渲染/措辞）`)
  process.exit(0)
}

main().catch((e) => {
  console.error(`req-change-e2e 无法执行: ${e.message}`)
  process.exit(1)
})
