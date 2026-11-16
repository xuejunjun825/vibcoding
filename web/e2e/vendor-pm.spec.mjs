#!/usr/bin/env node
// web/e2e/vendor-pm.spec.mjs — VENDOR_PM（厂商项目经理）前端收敛的 UI 级回归（r24）。
//
// 为什么放在 web/e2e：r24 的「用例库只读例外」「写口按钮禁用」「厂商必填」都是**渲染后**的行为，
// a3/a4 只能证明声明与推导，证明不了 DOM 上按钮是不是真的禁用了、焦点是不是真的落到厂商字段。
// 这份 spec 固化 QA 手工复核的那几条断言，后续轮次直接 `node web/e2e/vendor-pm.spec.mjs` 复跑。
//
// 依赖：本机 Chrome 已带 --remote-debugging-port=9222（headless 可），web dev 5173，网关 8080。
// 没有 CDP 或 web 未起 → 打印 SKIP 并以 0 退出（离线可跑，不制造假红灯）；起了但断言失败 → exit 1。
// 与 quality.spec.mjs 的约定一致：结论写 stdout（宿主截尾保留），失败逐条列在上方。

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5173'
const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222'
const TENANT = process.env.VENDOR_PM_TENANT || 't1'

let total = 0
let pass = 0
const failures = []
function check(desc, ok, detail = '') {
  total++
  if (ok) { pass++; console.log(`  PASS ${desc}`) } else {
    failures.push(`${desc}${detail ? ` — ${detail}` : ''}`)
    console.log(`  FAIL ${desc}${detail ? ` — ${detail}` : ''}`)
  }
}
function skip(reason) { console.log(`SKIP vendor-pm-e2e: ${reason}`); process.exit(0) }
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
  try { json = text ? JSON.parse(text) : null } catch { /* keep null */ }
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
        if (this.pending.has(id)) { this.pending.delete(id); fail(new Error(`cdp timeout ${method}`)) }
      }, 20000)
    })
  }
}

/** 每个场景独立 target：CDP 复用 target 连续 navigate 时第 5 次起会偶发不返回。 */
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
    async navigate(url) { await send('Page.navigate', { url }) },
    async close() { await browser.send('Target.closeTarget', { targetId }) },
  }
}

/** 注入会话（sessionStorage 的 devops.session）后导航到目标路由，等 SPA 渲染。 */
async function open(browser, user, path, settle = 2200) {
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

const VPM = { username: 'vpm1', password: 'Passw0rd!' }
const VENDOR1 = { username: 'vendor1', password: 'Passw0rd!' }
const ADMIN = { username: 'admin', password: 'Admin@12345' }

async function main() {
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
  try {
    await login(VPM.username, VPM.password)
  } catch (e) {
    skip(`vpm1 登录失败（演示夹具未重置？）: ${e.message} —— 先 make platform-demo-reset`)
  }

  const browser = new Cdp(version.webSocketDebuggerUrl)
  await browser.ready

  // 1) vpm1 侧栏：用例库可进（internalOnly 唯一窄例外），后台入口不出现
  {
    const page = await open(browser, VPM, '/requirements', 2600)
    const navText = await page.evalJs(`document.querySelector('aside')?.innerText || document.body.innerText`)
    check('vpm1 侧栏出现「用例库」', /用例库/.test(navText))
    check('vpm1 侧栏无「用户与角色」（admin.* DENY）', !/用户与角色/.test(navText))
    await page.close()
  }

  // 2) vpm1 深链 /testcases：例外放行 + 写口全关（D-3：主操作不再显示 primary 蓝）
  {
    const page = await open(browser, VPM, '/testcases', 2600)
    const path = await page.evalJs('location.pathname')
    check('vpm1 深链 /testcases 未被 403（例外生效）', path === '/testcases', path)
    const btns = await page.evalJs(`(() => {
      const find = (t) => [...document.querySelectorAll('button')].find((b) => (b.innerText || '').trim() === t)
      const pack = (b) => b ? { disabled: b.disabled, title: b.title, cls: b.className } : null
      return { create: pack(find('新建用例')), imp: pack(find('导入')), exp: pack(find('导出 CSV')) }
    })()`)
    check('「新建用例」禁用 + 提示 test.action.case_edit',
      btns.create?.disabled === true && /test\.action\.case_edit/.test(btns.create?.title ?? ''), JSON.stringify(btns.create))
    check('「新建用例」不再使用 primary 主色（权限不足即降级为默认按钮）',
      btns.create != null && !/el-button--primary/.test(btns.create.cls), btns.create?.cls)
    check('「导入」禁用（写口）', btns.imp?.disabled === true, JSON.stringify(btns.imp))
    check('「导出 CSV」仍可用（只读保留导出）', btns.exp?.disabled === false, JSON.stringify(btns.exp))
    await page.close()
  }

  // 3) vpm1 版本列表：能看但不能建（D-3 同类：禁用态不得是 primary）
  {
    const page = await open(browser, VPM, '/releases', 2400)
    const btn = await page.evalJs(`(() => {
      const b = [...document.querySelectorAll('button')].find((x) => (x.innerText || '').trim().includes('新建版本'))
      return b ? { disabled: b.disabled, title: b.title, cls: b.className } : null
    })()`)
    check('「新建版本」存在且禁用', btn?.disabled === true, JSON.stringify(btn))
    check('禁用提示写明 release.action.create', /release\.action\.create/.test(btn?.title ?? ''), btn?.title ?? '')
    check('「新建版本」不再是 primary 主色',
      btn != null && !/el-button--primary/.test(btn.cls), btn?.cls)
    await page.close()
  }

  // 4) 深链 403：vendor1 仍被 internalOnly 拦（例外只认 VENDOR_PM，不认 vendorId）
  {
    const page = await open(browser, VENDOR1, '/testcases', 2400)
    const url = await page.evalJs('location.pathname + location.search')
    check('vendor1 深链 /testcases 落 /403', url.startsWith('/403'), url)
    check('错误码是 INTERNAL_ONLY', /INTERNAL_ONLY/.test(decodeURIComponent(url)), url)
    await page.close()
  }

  // 5) vpm1 后台深链仍 403
  {
    const page = await open(browser, VPM, '/admin/users', 2400)
    const path = await page.evalJs('location.pathname')
    check('vpm1 深链 /admin/users 落 /403', path.startsWith('/403'), path)
    await page.close()
  }

  // 6) admin 建 VENDOR_PM 用户：厂商必填有内联错误 + 焦点落到厂商字段（D-4）
  {
    const page = await open(browser, ADMIN, '/admin/users', 2600)
    const opened = await page.evalJs(`(() => {
      const b = [...document.querySelectorAll('button')].find((x) => (x.innerText || '').trim() === '新建用户')
      if (!b) return false
      b.click()
      return true
    })()`)
    await sleep(700)
    const filled = await page.evalJs(`(() => {
      const inputs = [...document.querySelectorAll('.el-dialog input')]
      const set = (el, v) => {
        el.value = v
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
      }
      if (inputs[0]) set(inputs[0], 'vpm-e2e-${Date.now() % 100000}')
      if (inputs[1]) set(inputs[1], 'e2e-厂商必填')
      return inputs.length
    })()`)
    // 选 VENDOR_PM：点开角色下拉，点选项
    const chosen = await page.evalJs(`(async () => {
      const item = [...document.querySelectorAll('.el-form-item')].find((el) => /角色/.test(el.innerText || ''))
      const trigger = item?.querySelector('.el-select__wrapper, .el-input__inner, input')
      if (!trigger) return 'no-trigger'
      trigger.click()
      await new Promise((r) => setTimeout(r, 400))
      const opt = [...document.querySelectorAll('.el-select-dropdown__item')].find((o) => /VENDOR_PM/.test(o.innerText || ''))
      if (!opt) return 'no-option'
      opt.click()
      await new Promise((r) => setTimeout(r, 300))
      return 'picked'
    })()`)
    const submit = await page.evalJs(`(() => {
      const b = [...document.querySelectorAll('.el-dialog__footer button')].find((x) => (x.innerText || '').trim() === '保存')
      if (!b) return false
      b.click()
      return true
    })()`)
    await sleep(700)
    const after = await page.evalJs(`(() => {
      const items = [...document.querySelectorAll('.el-form-item')]
      const item = items.find((el) => /所属厂商/.test(el.innerText || ''))
      const inline = item?.querySelector('.el-form-item__error')?.textContent || ''
      const active = document.activeElement
      return {
        inline,
        focused: !!(item && active && item.contains(active)),
        dialogOpen: !!document.querySelector('.el-overlay'),
        toast: (document.querySelector('.el-message')?.innerText || ''),
      }
    })()`)
    check('用户名/姓名已填（越过 el-form 必填规则）', Number(filled) >= 2, String(filled))
    check('角色下拉可选 VENDOR_PM', chosen === 'picked', String(chosen))
    check('保存按钮可点', submit === true, String(submit))
    check('厂商字段出现内联错误（不是只有顶部 toast）',
      /必须绑定厂商|必须选择所属厂商/.test(after.inline), JSON.stringify(after))
    check('焦点落到厂商字段（D-4：改哪里）', after.focused === true, JSON.stringify(after))
    check('弹窗保持打开（未误关）', after.dialogOpen === true, JSON.stringify(after))
    await page.close()
  }

  if (total === 0) {
    console.error('vendor-pm-e2e: 一条断言都没执行：脚本实现有 bug')
    process.exit(1)
  }
  if (failures.length) {
    console.error(`\nvendor-pm-e2e: ${total - pass} 项失败 / ${total} 项`)
    for (const f of failures) console.error(`  - ${f}`)
    process.exit(1)
  }
  console.log(`\n  ok vendor-pm-e2e: ${pass}/${total} 项通过（VENDOR_PM 只读例外 / 写口禁用 / 深链 403 / 厂商必填）`)
  process.exit(0)
}

main().catch((e) => {
  console.error(`vendor-pm-e2e 无法执行: ${e.message}`)
  process.exit(1)
})
