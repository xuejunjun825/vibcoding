#!/usr/bin/env node
/**
 * r14 分析域中文化门禁 · 静态三方一致性（R1–R7）
 *
 * 「三方」= 分析域唯一注册表 `AnalyticsLabels.ROWS`（deliver-service）
 *        + 前端唯一词典 `web/src/stores/dict.ts`
 *        + `sys_dict` 种子（`RoleCatalog.DICTS` 与 `SeedService` 内联数组，G-3 双份）。
 *
 * 为什么是源码级比对：三份物理副本必须逐字符串相等，跑起来才能保证页面、
 * 导出与字典三处的中文完全同源；编译期拿不到 dict.ts，起栈比对又会漏掉
 * 「某个 code 从没被渲染过」的情况，所以这里直接解析字面表。
 *
 * 用法：
 *   node scripts/tests/analytics/i18n-labels-consistency.mjs
 *   ANALYTICS_I18N_ROOT=/tmp/fixture node ...   # 用夹具树自测解析器
 * 退出码：0 全绿 / 1 有差异（打印逐规则差异表）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(process.env.ANALYTICS_I18N_ROOT || path.join(HERE, '..', '..', '..'))
const UNKNOWN = '未知状态'

const FILES = {
  labels: 'deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsLabels.java',
  dict: 'web/src/stores/dict.ts',
  role: 'req-service/src/main/java/com/example/req/sys/RoleCatalog.java',
  seed: 'req-service/src/main/java/com/example/req/seed/SeedService.java',
  fixture: 'req-service/src/main/java/com/example/req/seed/DemoFixture.java',
}

const checks = []
const notes = []

/** 一条断言：rule 编号（R1..R7）供 QA 直接引用 */
function check(rule, title, ok, detail = '') {
  checks.push({ rule, title, ok, detail })
}

function fail(rule, title, detail) {
  check(rule, title, false, detail)
}

function read(rel) {
  const abs = path.join(ROOT, rel)
  if (!fs.existsSync(abs)) return null
  return fs.readFileSync(abs, 'utf8')
}

/** 从 `{` 起做括号配对（跳过字符串字面量），返回整段 `{...}`；失配返回 null */
function blockAt(src, openIdx) {
  if (openIdx < 0) return null
  let depth = 0
  for (let i = openIdx; i < src.length; i++) {
    const ch = src[i]
    if (ch === '"' || ch === "'" || ch === '`') {
      const quote = ch
      i++
      while (i < src.length) {
        if (src[i] === '\\') i += 2
        else if (src[i] === quote) break
        else i++
      }
      continue
    }
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return src.slice(openIdx, i + 1)
    }
  }
  return null
}

/** `const NAME: Record<...> = { ... }` → 块内文本（跳过类型标注里的 `{ ... }`） */
function dictBlock(src, name) {
  const at = src.indexOf(`const ${name}`)
  if (at < 0) return null
  const eq = src.indexOf('=', at)
  if (eq < 0) return null
  const open = src.indexOf('{', eq)
  return blockAt(src, open)
}

/** dict.ts 的一张表 → Map<code, label> */
function parseDictTable(block) {
  const out = new Map()
  if (!block) return out
  const entry = /([A-Z][A-Z0-9_]*)\s*:\s*\{([^{}]*)\}/g
  let m
  while ((m = entry.exec(block)) !== null) {
    const label = /\blabel\s*:\s*(?:'([^']*)'|"([^"]*)")/.exec(m[2])
    if (label) out.set(m[1], label[1] ?? label[2])
  }
  return out
}

/** AnalyticsLabels.ROWS 的字面四元组 → [{dataset, field, code, label}] */
function parseRegistry(src) {
  // 必须定位到「声明」`ROWS = {`——javadoc 里也会出现 {@code ROWS}，按裸 indexOf('ROWS') 会截错块。
  const at = src.search(/ROWS\s*=\s*\{/)
  if (at < 0) return []
  const open = src.indexOf('{', at)
  const block = blockAt(src, open)
  if (!block) return []
  const rows = []
  const quad = /\{\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\}/g
  let m
  while ((m = quad.exec(block)) !== null) {
    rows.push({ dataset: m[1], field: m[2], code: m[3], label: m[4] })
  }
  return rows
}

/** RoleCatalog：new DictDef("type", "code", "label", order) */
function parseRoleCatalog(src) {
  const out = []
  const re = /new DictDef\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*[^)]*\)/g
  let m
  while ((m = re.exec(src)) !== null) out.push({ type: m[1], code: m[2], label: m[3] })
  return out
}

/** SeedService：String[][] dicts = { {"type","code","label","sort"}, ... } */
function parseSeedService(src) {
  const at = src.indexOf('String[][] dicts')
  if (at < 0) return []
  const open = src.indexOf('{', at)
  const block = blockAt(src, open)
  if (!block) return []
  const out = []
  const quad = /\{\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"[^"]*"\s*\}/g
  let m
  while ((m = quad.exec(block)) !== null) out.push({ type: m[1], code: m[2], label: m[3] })
  return out
}

/** DemoFixture.DEFECTS：{"code","req","title","severity","priority","status","domain","handler"} */
function parseDemoFixtureSeverity(src) {
  const at = src.indexOf('String[][] DEFECTS')
  if (at < 0) return []
  const open = src.indexOf('{', at)
  const block = blockAt(src, open)
  if (!block) return []
  const out = []
  const row = /\{\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*"([^"]*)"\s*,/g
  let m
  while ((m = row.exec(block)) !== null) out.push(m[1])
  return out
}

function diffSet(rule, title, expected, actual) {
  const missing = [...expected.keys()].filter((code) => !actual.has(code)).sort()
  const extra = [...actual.keys()].filter((code) => !expected.has(code)).sort()
  const labelDiff = [...expected.entries()]
    .filter(([code, label]) => actual.has(code) && actual.get(code) !== label)
    .map(([code, label]) => `${code}: 注册表="${label}" / 对照="${actual.get(code)}"`)
    .sort()
  const ok = missing.length === 0 && extra.length === 0 && labelDiff.length === 0
  check(
    rule,
    title,
    ok,
    ok
      ? ''
      : [
          missing.length ? `对照缺 ${missing.length} 个 code: ${missing.join(', ')}` : '',
          extra.length ? `对照多 ${extra.length} 个 code: ${extra.join(', ')}` : '',
          labelDiff.length ? `label 不一致 ${labelDiff.length} 处:\n    - ${labelDiff.join('\n    - ')}` : '',
        ]
          .filter(Boolean)
          .join('\n  '),
  )
}

/** 注册表按 dataset.field 分组 → Map<code, label> */
function registryField(rows, dataset, field) {
  const out = new Map()
  for (const row of rows) {
    if (row.dataset.toUpperCase() !== dataset.toUpperCase()) continue
    if (row.field.toLowerCase() !== field.toLowerCase()) continue
    out.set(row.code, row.label)
  }
  return out
}

function main() {
  const sources = {}
  const missing = []
  for (const [key, rel] of Object.entries(FILES)) {
    const text = read(rel)
    if (text === null) missing.push(rel)
    else sources[key] = text
  }
  if (missing.length > 0) {
    console.error('[static] 缺少源文件（并行任务可能还没落盘）:')
    for (const rel of missing) console.error(`  - ${rel}`)
    console.error('[static] 修复：先完成 AnalyticsLabels / dict.ts / sys_dict 种子（本门禁比的是这三份）。')
    console.log(`COUNT static 0 1`)
    process.exit(1)
  }

  const rows = parseRegistry(sources.labels)
  if (rows.length === 0) {
    fail(
      'R0',
      'AnalyticsLabels.ROWS 可解析',
      '未解析到 {dataset, field, code, 中文} 四元组——注册表必须写成 String[][] 字面表（门禁直接解析源码）',
    )
  } else {
    check('R0', `AnalyticsLabels.ROWS 可解析（${rows.length} 行）`, true)
  }

  const unknownConst = /UNKNOWN\s*=\s*"([^"]*)"/.exec(sources.labels)
  check(
    'R7',
    `AnalyticsLabels.UNKNOWN == "${UNKNOWN}"`,
    Boolean(unknownConst) && unknownConst[1] === UNKNOWN,
    unknownConst ? `实际 "${unknownConst[1]}"` : '未找到 UNKNOWN 常量',
  )
  const unknownAsLabel = rows.filter((row) => row.label === UNKNOWN)
  check(
    'R7',
    '注册表不得把「未知状态」登记成某 code 的译名（兜底值不能成为真实译名）',
    unknownAsLabel.length === 0,
    unknownAsLabel.map((row) => `${row.dataset}.${row.field}.${row.code}`).join(', '),
  )

  const dict = {}
  for (const name of ['REQUIREMENT', 'APPROVAL', 'REQ_TYPE', 'PRIORITY', 'SEVERITY']) {
    dict[name] = parseDictTable(dictBlock(sources.dict, name))
  }
  check(
    'R0',
    'dict.ts 五张表可解析（REQUIREMENT/APPROVAL/REQ_TYPE/PRIORITY/SEVERITY）',
    Object.values(dict).every((table) => table.size > 0),
    Object.entries(dict)
      .filter(([, table]) => table.size === 0)
      .map(([name]) => name)
      .join(', ') + ' 解析为空',
  )

  // R1–R5：注册表 ↔ dict.ts 逐字符串相等（code 集合 + label）
  diffSet('R1', 'REQUIREMENT.status ↔ dict.ts REQUIREMENT', registryField(rows, 'REQUIREMENT', 'status'), dict.REQUIREMENT)
  diffSet('R2', 'APPROVAL.result ↔ dict.ts APPROVAL', registryField(rows, 'APPROVAL', 'result'), dict.APPROVAL)
  diffSet('R3', 'REQUIREMENT.type ↔ dict.ts REQ_TYPE', registryField(rows, 'REQUIREMENT', 'type'), dict.REQ_TYPE)
  diffSet('R4', 'REQUIREMENT.priority ↔ dict.ts PRIORITY', registryField(rows, 'REQUIREMENT', 'priority'), dict.PRIORITY)
  diffSet('R5', 'QUALITY.severity ↔ dict.ts SEVERITY', registryField(rows, 'QUALITY', 'severity'), dict.SEVERITY)

  // R6：demo 夹具的缺陷等级也必须是注册表 code（D4：DemoFixture 曾写 S2/S3，
  // 而 sys_dict/注册表已统一 P0..P3，运行后质量域行头会落到「未知状态」）。
  const fixtureSeverity = parseDemoFixtureSeverity(sources.fixture)
  check(
    'R6',
    `DemoFixture.DEFECTS.severity 可解析（${fixtureSeverity.length} 行）`,
    fixtureSeverity.length > 0,
    '未解析到 String[][] DEFECTS 的第四列（severity）',
  )
  const severityReg = registryField(rows, 'QUALITY', 'severity')
  const fixtureOrphans = [...new Set(fixtureSeverity)].filter((code) => !severityReg.has(code)).sort()
  check(
    'R6',
    'DemoFixture 的缺陷等级都在注册表 P0..P3 内',
    fixtureOrphans.length === 0,
    fixtureOrphans.length
      ? `注册表外取值: ${fixtureOrphans.join(', ')}（注册表: ${[...severityReg.keys()].sort().join(', ')}）`
      : '',
  )

  // R6：sys_dict 种子（两处种同一种表，G-3）
  const roleRows = parseRoleCatalog(sources.role)
  const seedRows = parseSeedService(sources.seed)
  check('R6', `RoleCatalog.DICTS 可解析（${roleRows.length} 行）`, roleRows.length > 0, '未解析到 new DictDef("type","code","label",order)')
  check('R6', `SeedService 内联 sys_dict 可解析（${seedRows.length} 行）`, seedRows.length > 0, '未解析到 String[][] dicts 四元组')

  const MAPPED = {
    'REQUIREMENT.status': 'req_status',
    'REQUIREMENT.type': 'req_type',
    'REQUIREMENT.priority': 'priority',
    'QUALITY.severity': 'severity',
    'APPROVAL.result': 'approval_result',
    'APPROVAL.bizType': 'approval_biz_type',
  }

  const byType = (list, type) => list.filter((row) => row.type === type)

  for (const [regKey, dictType] of Object.entries(MAPPED)) {
    const [dataset, field] = regKey.split('.')
    const reg = registryField(rows, dataset, field)
    const fromRole = byType(roleRows, dictType)
    const fromSeed = byType(seedRows, dictType)
    if (fromRole.length === 0 && fromSeed.length === 0) {
      notes.push(`sys_dict 未种 ${dictType}（注册表已登记 ${reg.size} 个 code，属有意子集）——不判错`)
      continue
    }

    // G-3：同一 (type, code) 在 RoleCatalog 与 SeedService 里不得出现两份文案
    const clash = []
    for (const a of fromRole) {
      for (const b of fromSeed) {
        if (a.code === b.code && a.label !== b.label) {
          clash.push(`${dictType}.${a.code}: RoleCatalog="${a.label}" / SeedService="${b.label}"`)
        }
      }
    }
    check(
      'R6',
      `sys_dict.${dictType} 两处种子文案一致（RoleCatalog ↔ SeedService）`,
      clash.length === 0,
      clash.join('\n  '),
    )

    // 种子里的 code 必须是注册表 code（抓 severity S1..S4 → P0..P3 这类整体漂移）
    for (const list of [fromRole, fromSeed]) {
      const orphans = [...new Set(list.map((row) => row.code))].filter((code) => !reg.has(code)).sort()
      check(
        'R6',
        `sys_dict.${dictType} 的 code 都在注册表内`,
        orphans.length === 0,
        orphans.length ? `孤儿 code: ${orphans.join(', ')}（注册表只有 ${[...reg.keys()].sort().join(', ')}）` : '',
      )
      const labelDiff = list
        .filter((row) => reg.has(row.code) && reg.get(row.code) !== row.label)
        .map((row) => `${dictType}.${row.code}: 注册表="${reg.get(row.code)}" / 种子="${row.label}"`)
      check(
        'R6',
        `sys_dict.${dictType} 的 label 与注册表相等`,
        labelDiff.length === 0,
        labelDiff.join('\n  '),
      )
    }

    const absent = [...reg.keys()].filter((code) => !fromRole.some((r) => r.code === code) && !fromSeed.some((r) => r.code === code))
    if (absent.length > 0) notes.push(`sys_dict.${dictType} 只种了部分 code，缺 ${absent.length} 个（有意子集，不判错）: ${absent.join(', ')}`)
  }

  const mappedTypes = new Set(Object.values(MAPPED))
  const seedTypes = [...new Set([...roleRows, ...seedRows].map((row) => row.type))].filter((type) => !mappedTypes.has(type)).sort()
  if (seedTypes.length > 0) notes.push(`sys_dict 另有未映射族（只列出、不判错）: ${seedTypes.join(', ')}`)

  let pass = 0
  for (const item of checks) {
    if (item.ok) {
      pass++
      console.log(`[static] ${item.rule} PASS  ${item.title}`)
    } else {
      console.log(`[static] ${item.rule} FAIL  ${item.title}`)
      if (item.detail) console.log(`  ${item.detail}`)
    }
  }
  for (const note of notes) console.log(`[static] NOTE  ${note}`)
  console.log(`COUNT static ${pass} ${checks.length}`)
  return pass === checks.length ? 0 : 1
}

process.exit(main())
