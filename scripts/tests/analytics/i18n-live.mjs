#!/usr/bin/env node
/**
 * r14 分析域中文化门禁 · 现场渲染与导出（A1–A9）
 *
 * 起栈后跑真实浏览器：/dashboard、/portal、/reports、/reports/:id 四页的
 * 用户可见维度取值、缓存徽标、portal 口径、范围 chips 必须全中文；页面表头
 * 必须与同一报表的 CSV/XLSX 导出**逐字符串相等**（导出复用同一个 CrossTab，
 * 所以这条同时守住页面与文件的同源）。
 *
 * 为什么必须现场跑：静态门禁只能比三份字面表，表头到底拼成了什么、导出里
 * 是不是也换了，只有真实请求能回答。ECharts 用 canvas 画图例/坐标轴（DOM
 * 读不到），所以在页面脚本之前把 2D 上下文的 fillText/strokeText 录进
 * window.__drawnTexts，再对录制结果做同样的正则断言。
 *
 * Playwright 走全局安装（不新增依赖）：从 PLAYWRIGHT_ROOT / `npm root -g`
 * 解析；取不到即红灯，绝不静默跳过。
 *
 * 用法：node scripts/tests/analytics/i18n-live.mjs
 * 退出码：0 全绿 / 1 有断言失败（打印首个失败下标与命中文本）。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const WEB = (process.env.WEB_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const GATEWAY = (process.env.GATEWAY_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const TENANT = process.env.I18N_TENANT || 't1'
const USER = process.env.I18N_USER || 'admin'
const PASSWORD = process.env.I18N_PASS || 'Admin@12345'
const REPORT_IDS = (process.env.I18N_REPORT_IDS || '250,261,228')
  .split(',')
  .map((id) => Number(id.trim()))
  .filter((id) => Number.isFinite(id))
const SESSION_KEY = 'devops.session'
/** AC-5 判据：三个字母以上、可带 _ 分段的英文枚举码 */
const ENUM_TOKEN = /\b[A-Z]{3,}(?:_[A-Z]+)*\b/
const ENUM_TOKEN_ALL = /\b[A-Z]{3,}(?:_[A-Z]+)*\b/g

const checks = []
const notes = []

function check(rule, title, ok, detail = '') {
  checks.push({ rule, title, ok, detail })
}

function resolvePlaywright() {
  const roots = []
  if (process.env.PLAYWRIGHT_ROOT) roots.push(process.env.PLAYWRIGHT_ROOT)
  try {
    roots.push(
      execFileSync('npm', ['root', '-g'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(),
    )
  } catch {
    /* npm 不可用：下面的候选目录仍会尝试 */
  }
  roots.push(path.join(HERE, '..', '..', '..', 'web', 'node_modules'))
  for (const root of roots) {
    if (!root) continue
    const entry = path.join(root, 'playwright', 'index.js')
    if (fs.existsSync(entry)) return entry
  }
  return null
}

async function api(token, tenantId, pathname) {
  const res = await fetch(`${GATEWAY}${pathname}`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Tenant-Id': String(tenantId) },
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`GET ${pathname} → HTTP ${res.status}: ${text.slice(0, 200)}`)
  return JSON.parse(text)
}

/** 带方法与请求体的调用：现场探针要临时建/删一张质量域报表（A10）。 */
async function apiSend(token, tenantId, method, pathname, body) {
  const res = await fetch(`${GATEWAY}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Tenant-Id': String(tenantId),
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${pathname} → HTTP ${res.status}: ${text.slice(0, 200)}`)
  return text ? JSON.parse(text) : null
}

async function login() {
  const res = await fetch(`${GATEWAY}/api/req/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ tenantCode: TENANT, username: USER, password: PASSWORD }),
  })
  const body = await res.json().catch(() => ({}))
  if (!res.ok || !body.token) {
    throw new Error(`登录失败 HTTP ${res.status}: ${JSON.stringify(body).slice(0, 200)}`)
  }
  const permissions = (body.permissions ?? [])
    .map((item) => (typeof item === 'string' ? item : item && item.access !== 'DENY' ? item.code : null))
    .filter(Boolean)
  return {
    session: {
      token: body.token,
      tenantId: String(body.tenantId ?? ''),
      tenantCode: body.tenantCode ?? TENANT,
      user: body.user ?? null,
      permissions,
      defaultRoute: body.defaultRoute || '/dashboard',
    },
    token: body.token,
    tenantId: String(body.tenantId ?? ''),
  }
}

/** 页面脚本之前注入：会话 + canvas 文字录制（ECharts 的图例/轴只有 canvas 里有） */
function initScript(session) {
  try {
    sessionStorage.setItem('devops.session', JSON.stringify(session))
  } catch {
    /* 隐私模式忽略：下面的断言会因未登录而失败，不会假绿 */
  }
  window.__drawnTexts = []
  const proto = HTMLCanvasElement.prototype
  const original = proto.getContext
  proto.getContext = function patched(type, ...rest) {
    const ctx = original.call(this, type, ...rest)
    if (ctx && type === '2d' && !ctx.__i18nPatched) {
      ctx.__i18nPatched = true
      for (const name of ['fillText', 'strokeText']) {
        const draw = ctx[name]
        if (typeof draw !== 'function') continue
        ctx[name] = function record(text, ...args) {
          try {
            window.__drawnTexts.push(String(text))
          } catch {
            /* 录制失败不影响绘制 */
          }
          return draw.call(this, text, ...args)
        }
      }
    }
    return ctx
  }
}

function scanText(text) {
  ENUM_TOKEN_ALL.lastIndex = 0
  const hits = []
  let m
  while ((m = ENUM_TOKEN_ALL.exec(text)) !== null) hits.push(m[0])
  return hits
}

/** 把若干容器的可见文本收成 [{where, text}]（去重，空串丢弃） */
async function regionTexts(page, selectors) {
  const rows = await page.evaluate((sels) => {
    const out = []
    const seen = new Set()
    for (const sel of sels) {
      for (const el of document.querySelectorAll(sel)) {
        const text = (el.textContent || '').replace(/\s+/g, ' ').trim()
        if (!text || seen.has(text)) continue
        seen.add(text)
        out.push({ where: sel, text })
      }
    }
    return out
  }, selectors)
  return rows
}

async function cellTexts(page, selector) {
  return page.$$eval(selector, (els) => els.map((el) => (el.textContent || '').replace(/\s+/g, ' ').trim()))
}

/** 对一组文本做「0 命中英文枚举码」断言；detail 打印第一个命中与位置 */
function assertNoEnum(rule, title, items) {
  const hit = []
  for (const item of items) {
    const where = typeof item === 'string' ? '' : item.where
    const text = typeof item === 'string' ? item : item.text
    const found = scanText(text)
    if (found.length) hit.push(`${where ? `${where} ` : ''}命中 ${found.join(', ')} ← "${text.slice(0, 120)}"`)
  }
  check(rule, title, hit.length === 0, hit.slice(0, 5).join('\n  '))
}

function parseCsvLine(line) {
  const cells = []
  let cur = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quoted) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else quoted = false
      } else cur += ch
    } else if (ch === '"') quoted = true
    else if (ch === ',') {
      cells.push(cur)
      cur = ''
    } else cur += ch
  }
  cells.push(cur)
  return cells
}

function csvHeaderLines(buffer) {
  let text = buffer.toString('utf8')
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
  const lines = text.split(/\r?\n/).filter((line) => line.length > 0 && !line.startsWith('#'))
  return lines
}

async function exportBuffer(token, tenantId, id, format) {
  const res = await fetch(`${GATEWAY}/api/deliver/reports/${id}/export?format=${format}&preset=30d`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Tenant-Id': String(tenantId) },
  })
  const buf = Buffer.from(await res.arrayBuffer())
  return { status: res.status, buf }
}

/** A6/A7/A8：页面表头 vs 导出表头，逐列相等；不等时打印首个失败下标 */
function compareHeaders(rule, title, pageHeaders, exportHeaders) {
  const limit = Math.max(pageHeaders.length, exportHeaders.length)
  const diff = []
  for (let i = 0; i < limit; i++) {
    if (pageHeaders[i] !== exportHeaders[i]) {
      diff.push(`[${i}] page=${JSON.stringify(pageHeaders[i] ?? null)} export=${JSON.stringify(exportHeaders[i] ?? null)}`)
      break
    }
  }
  if (pageHeaders.length !== exportHeaders.length) {
    diff.push(`列数 page=${pageHeaders.length} export=${exportHeaders.length}`)
  }
  check(rule, title, diff.length === 0, diff.join('\n  '))
}

/** A6：页面行头 vs 导出第 1 列（含小计/总计），逐行相等。 */
function compareRows(rule, title, pageRows, exportRows) {
  const limit = Math.max(pageRows.length, exportRows.length)
  const diff = []
  for (let i = 0; i < limit; i++) {
    if (pageRows[i] !== exportRows[i]) {
      diff.push(`[${i}] page=${JSON.stringify(pageRows[i] ?? null)} export=${JSON.stringify(exportRows[i] ?? null)}`)
      break
    }
  }
  if (pageRows.length !== exportRows.length) {
    diff.push(`行数 page=${pageRows.length} export=${exportRows.length}`)
  }
  check(rule, title, diff.length === 0, diff.join('\n  '))
}

/** 读 XLSX 的共享串（unzip 取 xl/sharedStrings.xml）；取不到返回 null，不静默通过。 */
function xlsxSharedStrings(buffer) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-xlsx-'))
  const file = path.join(dir, 'export.xlsx')
  try {
    fs.writeFileSync(file, buffer)
    const xml = execFileSync('unzip', ['-p', file, 'xl/sharedStrings.xml'], {
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
    })
    const out = []
    const re = /<t[^>]*>([\s\S]*?)<\/t>/g
    let m
    while ((m = re.exec(xml)) !== null) {
      out.push(
        m[1]
          .replace(/&lt;/g, '<')
          .replace(/&gt;/g, '>')
          .replace(/&quot;/g, '"')
          .replace(/&apos;/g, "'")
          .replace(/&amp;/g, '&'),
      )
    }
    return out
  } catch {
    return null
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

async function main() {
  const entry = resolvePlaywright()
  if (!entry) {
    console.error('[live] 找不到 Playwright：全局装（npm i -g playwright）或用 PLAYWRIGHT_ROOT 指到 node_modules。')
    console.error('[live] 本门禁不允许跳过：依赖取不到即红灯（禁新增依赖，所以用全局安装）。')
    console.log('COUNT live 0 1')
    return 1
  }
  notes.push(`Playwright: ${entry}`)

  let auth
  try {
    auth = await login()
  } catch (err) {
    console.error(`[live] 登录失败：${err.message}`)
    console.error('[live] 修复：make platform-up && make platform-seed，再跑 make analytics-i18n-verify。')
    console.log('COUNT live 0 1')
    return 1
  }

  let reports = []
  try {
    const listed = await api(auth.token, auth.tenantId, '/api/deliver/reports?page=1&size=100')
    reports = Array.isArray(listed.items) ? listed.items : []
  } catch (err) {
    console.error(`[live] 报表列表读取失败：${err.message}`)
    console.log('COUNT live 0 1')
    return 1
  }
  const known = new Map(reports.map((item) => [Number(item.id), item]))
  let reportIds = REPORT_IDS.filter((id) => known.has(id))
  if (reportIds.length < 3) {
    const fallback = reports
      .filter((item) => !reportIds.includes(Number(item.id)) && String(item.chartType || '').toUpperCase() === 'TABLE')
      .slice(0, 3 - reportIds.length)
      .map((item) => Number(item.id))
    notes.push(`预置报表 ${REPORT_IDS.join('/')} 命中 ${reportIds.length} 个，补入 ${fallback.join('/') || '（无）'}`)
    reportIds = [...reportIds, ...fallback]
  }
  // r15：platform-demo-reset 把 report_definition 清成 0（演示态就该是空报表中心），
  // 所以门禁不能依赖「上一轮探针残留的报表」——那正是 r15 要治理的污染。少于 3 张时
  // 自带夹具（requirement/status 表，与 r14 预置报表同形），跑完在 finally 里删掉，
  // 重置后的门禁因此可独立复跑，且不给下一次重置留垃圾。
  const fixtureReports = []
  if (reportIds.length < 3) {
    for (let i = reportIds.length; i < 3; i += 1) {
      try {
        const created = await apiSend(auth.token, auth.tenantId, 'POST', '/api/deliver/reports', {
          name: `门禁临时-需求状态-${process.pid}-${i}`,
          datasetId: 'requirement',
          chartType: 'TABLE',
          definition: {
            datasetId: 'requirement',
            rows: ['status'],
            columns: [],
            measures: ['req.total'],
            filters: [],
          },
        })
        const id = Number(created?.id)
        if (Number.isFinite(id)) {
          fixtureReports.push(id)
          reportIds.push(id)
        }
      } catch (err) {
        console.error(`[live] 夹具报表创建失败：${err.message}`)
      }
    }
    notes.push(`报表中心缺 3 张：门禁自建夹具 ${fixtureReports.join('/') || '（失败）'}，跑完即删（DELETE 为软删 status=DELETED，列表不再可见）`)
  }
  if (reportIds.length === 0) {
    console.error('[live] 一条报表都取不到，夹具也没建成：make platform-up 后看 deliver-service 日志。')
    console.log('COUNT live 0 1')
    return 1
  }

  // playwright 是 CJS：全局安装下 `import()` 取到的具名导出可能只在 default 上
  const loaded = await import(pathToFileURL(entry).href)
  const chromium = loaded.chromium ?? loaded.default?.chromium
  if (!chromium) {
    console.error(`[live] ${entry} 里没有 chromium 导出：Playwright 安装不完整（重装 npm i -g playwright）。`)
    console.log('COUNT live 0 1')
    return 1
  }
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.addInitScript(initScript, auth.session)
  const page = await context.newPage()
  const logs = []
  page.on('console', (msg) => {
    const text = msg.text()
    if (msg.type() === 'error') logs.push(`console.error: ${text}`)
    else if (msg.type() === 'warning' && text.includes('[Vue warn]')) logs.push(`vue warn: ${text}`)
  })
  page.on('pageerror', (err) => logs.push(`pageerror: ${err.message}`))

  async function open(route) {
    logs.length = 0
    const res = await page.goto(`${WEB}${route}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    return res
  }

  try {
    // A1 /dashboard：交叉表 th + 图表 canvas 文字
    await open('/dashboard')
    await page.waitForSelector('[data-page="dashboard"] .an-table__table', { timeout: 30000 })
    await page.waitForTimeout(800)
    const dashItems = await regionTexts(page, ['[data-page="dashboard"] .an-head', '[data-page="dashboard"] .dash'])
    const dashCells = await cellTexts(
      page,
      '[data-page="dashboard"] .an-table__table thead th, [data-page="dashboard"] .an-table__table tbody th',
    )
    assertNoEnum('A1', `/dashboard 头部/交叉表/图表文字 0 命中英文枚举码（检查了 ${dashCells.length} 个 th）`, dashItems)
    assertNoEnum('A1', '/dashboard 交叉表 th 单元格 0 命中英文枚举码', dashCells.map((text, i) => ({ where: `th[${i}]`, text })))
    check('A1', '/dashboard 交叉表已渲染（th ≥ 2）', dashCells.length >= 2, `th=${dashCells.length}`)

    const drawn = await page.evaluate(() => (window.__drawnTexts || []).slice(0, 400))
    const hasCanvas = await page.$('.chart__canvas')
    if (hasCanvas) {
      check('A1', 'canvas 文字录制生效（ECharts 图例/轴确实被抓到）', drawn.length > 0, `录制条数=${drawn.length}`)
      assertNoEnum('A1', '/dashboard 图表图例/坐标轴 0 命中英文枚举码', drawn.map((text) => ({ where: 'canvas', text })))
    } else {
      notes.push('/dashboard 本次没有图表 canvas（不出图），跳过 canvas 文字断言')
    }

    // A2 缓存徽标
    const stamp = (await page.textContent('[data-testid="data-stamp-cache"]').catch(() => '')) || ''
    const stampText = stamp.replace(/\s+/g, ' ').trim()
    const leakHits = scanText(stampText)
    check(
      'A2',
      `缓存徽标只用中文（实际 "${stampText}"）`,
      leakHits.length === 0 && (stampText === '' || /缓存命中|缓存未命中/.test(stampText)),
      leakHits.length ? `命中 ${leakHits.join(', ')}` : `徽标文案不含「缓存命中/缓存未命中」`,
    )

    // A9 dashboard 的 console 干净
    check('A9', '/dashboard 无 console.error / Vue warn', logs.length === 0, logs.slice(0, 3).join('\n  '))

    // A3 /portal：五区卡片标题 + 口径
    await open('/portal')
    await page.waitForSelector('[data-page="portal"] .an-sections', { timeout: 30000 })
    await page.waitForTimeout(500)
    const portalSections = await page.$$('[data-page="portal"] section.ps')
    const portalItems = await regionTexts(page, ['[data-page="portal"] .an-head', '[data-page="portal"] .an-sections'])
    assertNoEnum('A3', '/portal 区标题/卡片/口径 0 命中英文枚举码', portalItems)
    check('A3', `/portal 区数 ≥ 4（实际 ${portalSections.length}）`, portalSections.length >= 4, `section.ps=${portalSections.length}`)
    check('A9', '/portal 无 console.error / Vue warn', logs.length === 0, logs.slice(0, 3).join('\n  '))

    // A4 /reports 列表
    await open('/reports')
    await page.waitForSelector('[data-page="report-list"]', { timeout: 30000 })
    await page.waitForTimeout(600)
    const listItems = await regionTexts(page, [
      '[data-page="report-list"] .an-head',
      'table.an-table--reports',
      '.an-table--reports thead',
      '.an-table--reports tbody',
      '.an-table__pager',
      '.an-table__empty',
    ])
    assertNoEnum('A4', '/reports 列表列名/范围/空态/分页器 0 命中英文枚举码', listItems)
    check('A9', '/reports 无 console.error / Vue warn', logs.length === 0, logs.slice(0, 3).join('\n  '))

    // A5–A8 编辑器 + 导出（三张报表各跑一遍）
    let compared = 0
    for (const id of reportIds) {
      await open(`/reports/${id}?reqType=RD`)
      await page.waitForSelector('.ed__preview', { timeout: 30000 })

      // 报表可能存成图表视图：切到「表格」再读表头
      if (!(await page.$('.ed__preview table.an-table__table'))) {
        const tableTab = page.locator('.an-filters .el-radio-button', { hasText: '表格' }).first()
        if (await tableTab.count()) await tableTab.click()
      }
      await page.waitForSelector('.ed__preview table.an-table__table thead th', { timeout: 30000 })
      await page.waitForTimeout(400)

      const chips = await cellTexts(page, '.ed__scope .el-tag')
      const chipText = chips.join(' | ')
      check(
        'A5',
        `报表 ${id} 范围 chips 显示中文需求类型（实际 "${chipText}"）`,
        chips.length > 0 &&
          /需求类型\s*(研发需求|业务需求|产品需求)/.test(chipText) &&
          !/\bRD\b|\bPDR\b|\bBUS\b/.test(chipText),
        chipText || '未渲染任何范围 chip（?reqType=RD 是否被路由吞掉了？）',
      )

      const slotText = await regionTexts(page, ['.ed__slots', '.ed__preview-head', '.ed__scope'])
      assertNoEnum('A5', `报表 ${id} 字段池/范围 0 命中英文枚举码`, slotText)

      const headers = await cellTexts(
        page,
        '.ed__preview table.an-table__table thead tr th[scope="col"]',
      )
      const pageHeaders = headers.slice(1).map((text) => text.trim())
      const pageRows = (await cellTexts(
        page,
        '.ed__preview table.an-table__table tbody tr th[scope="row"], ' +
          '.ed__preview table.an-table__table tfoot tr th[scope="row"]',
      )).map((text) => text.trim())
      assertNoEnum(
        'A5',
        `报表 ${id} 预览表头 0 命中英文枚举码（${pageHeaders.length} 列）`,
        pageHeaders.map((text, i) => ({ where: `col[${i}]`, text })),
      )

      // A6/A8 页面 vs 导出（CSV 列头逐列、行头逐行；XLSX 共享串再扫一遍）
      const csv = await exportBuffer(auth.token, auth.tenantId, id, 'csv')
      if (csv.status !== 200) {
        check('A6', `报表 ${id} CSV 导出 HTTP 200`, false, `HTTP ${csv.status}`)
      } else {
        const lines = csvHeaderLines(csv.buf)
        const exportHeaders = lines.length > 0 ? parseCsvLine(lines[0]).slice(1) : []
        const exportRows = lines.slice(1).map((line) => parseCsvLine(line)[0])
        compareHeaders('A6', `报表 ${id} 页面表头 == CSV 导出表头（${pageHeaders.length} 列）`, pageHeaders, exportHeaders)
        compareRows('A6', `报表 ${id} 页面行头 == CSV 导出第 1 列（${pageRows.length} 行）`, pageRows, exportRows)
        compared++
      }
      const xlsx = await exportBuffer(auth.token, auth.tenantId, id, 'xlsx')
      const zipOk = xlsx.status === 200 && xlsx.buf.length > 4 && xlsx.buf[0] === 0x50 && xlsx.buf[1] === 0x4b
      check('A6', `报表 ${id} XLSX 导出 200 且是 xlsx（PK 头）`, zipOk, `HTTP ${xlsx.status}, bytes=${xlsx.buf.length}`)
      if (zipOk) {
        const strings = xlsxSharedStrings(xlsx.buf)
        if (strings === null) {
          check('A6', `报表 ${id} XLSX 共享串可解析`, false, 'unzip 读取 xl/sharedStrings.xml 失败')
        } else {
          const leaked = strings
            .map((text) => text.trim())
            .filter((text) => /^[A-Z]{3,}(?:_[A-Z]+)*$/.test(text))
          check(
            'A6',
            `报表 ${id} XLSX 共享串 0 个英文枚举码（检查 ${strings.length} 串）`,
            leaked.length === 0,
            leaked.slice(0, 5).join(', '),
          )
        }
      }

      check('A9', `报表 ${id} 页无 console.error / Vue warn`, logs.length === 0, logs.slice(0, 3).join('\n  '))
    }
    check('A8', `三张报表都跑了页面/导出对比（实际 ${compared}/${reportIds.length}）`, compared === reportIds.length, `对比 ${compared} 张`)

    // A10 质量域 severity：实际取值必须都在注册表 P0..P3，行头不得落「未知状态」（D4）
    let qualityId = null
    try {
      const created = await apiSend(auth.token, auth.tenantId, 'POST', '/api/deliver/reports', {
        name: `门禁临时-缺陷等级-${Date.now()}`,
        datasetId: 'quality',
        chartType: 'TABLE',
        definition: {
          datasetId: 'quality',
          rows: ['severity'],
          columns: [],
          measures: ['quality.defects'],
          filters: [],
        },
      })
      qualityId = Number(created?.id)
      check('A10', '质量域临时报表已创建', Number.isFinite(qualityId), JSON.stringify(created).slice(0, 200))
      const tab = await apiSend(auth.token, auth.tenantId, 'POST', `/api/deliver/reports/${qualityId}/run`, {})
      const codes = Array.isArray(tab?.rowCodes) ? tab.rowCodes : []
      const rowHeaders = Array.isArray(tab?.rowHeaders) ? tab.rowHeaders : []
      check(
        'A10',
        '质量域 severity 实际取值 ⊆ P0..P3 ∪ {无缺陷}',
        codes.length > 0 && codes.every((code) => /^P[0-3]$/.test(code) || code === '无缺陷'),
        `rowCodes=${JSON.stringify(codes)}`,
      )
      check(
        'A10',
        '质量域 severity 行头无「未知状态」',
        rowHeaders.length > 0 && !rowHeaders.includes('未知状态'),
        `rowHeaders=${JSON.stringify(rowHeaders)}`,
      )
    } catch (err) {
      check('A10', '质量域 severity 现场探针跑完', false, err.message)
    } finally {
      if (Number.isFinite(qualityId)) {
        await apiSend(auth.token, auth.tenantId, 'DELETE', `/api/deliver/reports/${qualityId}`).catch(() => {})
      }
    }

    // A0 自检：扫描器不是空转（把一条英文码塞进真实 DOM，必须被抓到）
    await open('/login')
    await page.setContent('<div class="an-head">状态 PENDING_TEST 与 SUBMITTED</div>')
    const probe = await regionTexts(page, ['.an-head'])
    const probeHit = probe.flatMap((item) => scanText(item.text))
    check(
      'A0',
      '英文枚举扫描器自检：注入的 PENDING_TEST/SUBMITTED 必须命中',
      probeHit.includes('PENDING_TEST') && probeHit.includes('SUBMITTED'),
      `命中 ${probeHit.join(', ') || '（无）'}`,
    )
  } catch (err) {
    check('A0', '现场流程跑完（无异常中断）', false, `${err.message}`)
  } finally {
    // 夹具是门禁自己造的，跑完必须删干净：否则下一次 platform-demo-reset 的「报表 0 行」
    // 断言会被门禁自己的垃圾顶掉，等于把 r15 治理掉的污染又种回来。
    for (const id of fixtureReports) {
      await apiSend(auth.token, auth.tenantId, 'DELETE', `/api/deliver/reports/${id}`).catch(() => {})
    }
    await context.close().catch(() => {})
    await browser.close().catch(() => {})
  }

  const pass = checks.filter((item) => item.ok).length
  for (const item of checks) {
    if (item.ok) console.log(`[live] ${item.rule} PASS  ${item.title}`)
    else {
      console.log(`[live] ${item.rule} FAIL  ${item.title}`)
      if (item.detail) console.log(`  ${item.detail}`)
    }
  }
  for (const note of notes) console.log(`[live] NOTE  ${note}`)
  console.log(
    `[live] 终态：${reportIds.length} 张报表（${reportIds.join('/')}）页面与 CSV 表头逐列比对，${pass}/${checks.length} 断言通过`,
  )
  console.log(`COUNT live ${pass} ${checks.length}`)
  return pass === checks.length ? 0 : 1
}

process.exit(await main())
