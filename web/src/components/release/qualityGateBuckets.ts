/**
 * 质量门规则分桶（r33）：四态 PASS/WAIVED/SKIPPED/FAIL → 三桶「通过 / 跳过 / 未通过」的
 * 唯一前端实现。发版链路卡与质量门页共用，禁止在组件里另算一遍。
 *
 * 硬约束：
 * - 按 `status` 分桶，绝不看数组长度：r30/r32 冻结快照的 `failedRules` 里仍混着 SKIPPED 行，
 *   回放与新数据必须走同一条路径（AC-7）；
 * - 未知状态 fail-closed 归入「未通过」（与服务端 GateRuleBuckets 同一张真值表）；
 * - 零 import：`node --experimental-strip-types` 可直接加载断言，无需别名与依赖解析。
 */

export interface GateRuleLike {
  code?: string | null
  status?: string | null
  required?: boolean | null
  actual?: string | null
  threshold?: string | number | null
  fixHint?: string | null
}

export interface GateBuckets {
  passed: number
  waived: number
  failed: number
  skipped: number
  total: number
  failedRules: GateRuleLike[]
  skippedRules: GateRuleLike[]
}

/** 输入既可以是全量规则数组（质量门页），也可以是 flow-chain 段（只有失败/跳过两个列表） */
export type GateRuleInput =
  | GateRuleLike[]
  | { failedRules?: GateRuleLike[] | null; skippedRules?: GateRuleLike[] | null }
  | null
  | undefined

function statusOf(rule: GateRuleLike): string {
  return String(rule.status ?? '')
    .trim()
    .toUpperCase()
}

function codeOf(rule: GateRuleLike): string {
  return typeof rule.code === 'string' ? rule.code.trim() : ''
}

/**
 * 合并失败/跳过两个列表：以 code 去重，`skippedRules` 覆盖同 code 的旧行（旧快照把 SKIPPED
 * 混在 `failedRules` 里）；无 code 的行按原顺序占位，保证明细顺序稳定。
 */
function mergeRows(input: GateRuleInput): GateRuleLike[] {
  if (!input) return []
  if (Array.isArray(input)) return input.filter((rule): rule is GateRuleLike => !!rule)

  const rows: GateRuleLike[] = []
  const indexByCode = new Map<string, number>()
  const push = (rule: GateRuleLike | null | undefined): void => {
    if (!rule) return
    const code = codeOf(rule)
    if (code) indexByCode.set(code, rows.length)
    rows.push(rule)
  }

  for (const rule of input.failedRules ?? []) push(rule)
  for (const rule of input.skippedRules ?? []) {
    if (!rule) continue
    const code = codeOf(rule)
    const at = code ? indexByCode.get(code) : undefined
    if (at === undefined) push(rule)
    else rows[at] = rule
  }
  return rows
}

/** 四态切三桶：PASS→通过、WAIVED→豁免通过、SKIPPED→跳过、其余（含 FAIL/未知/null）→未通过 */
export function bucketGateRules(input: GateRuleInput): GateBuckets {
  const buckets: GateBuckets = {
    passed: 0,
    waived: 0,
    failed: 0,
    skipped: 0,
    total: 0,
    failedRules: [],
    skippedRules: [],
  }
  for (const rule of mergeRows(input)) {
    buckets.total += 1
    const status = statusOf(rule)
    if (status === 'PASS') {
      buckets.passed += 1
    } else if (status === 'WAIVED') {
      buckets.waived += 1
    } else if (status === 'SKIPPED') {
      buckets.skipped += 1
      buckets.skippedRules.push(rule)
    } else {
      buckets.failed += 1
      buckets.failedRules.push(rule)
    }
  }
  return buckets
}

/**
 * 链路卡副标题计数段（AC-3 三态）：
 * - 有跳过：`跳过 N 条 · 未通过 M 条`（M=0 也不省略，避免读者以为漏算）
 * - 无跳过有失败：`未通过 M 条`
 * - 都没有：空串（全通过不挂无信息量的「未通过 0 条」）
 */
export function gateCountText(b: GateBuckets): string {
  if (!b || b.total === 0) return ''
  if (b.skipped > 0) return `跳过 ${b.skipped} 条 · 未通过 ${b.failed} 条`
  if (b.failed > 0) return `未通过 ${b.failed} 条`
  return ''
}

/**
 * 质量门页计数标签（AC-6）：分母不含跳过，分子 = PASS + WAIVED；
 * 有跳过时追加 ` · 跳过 N`，即 `4/4 通过 · 跳过 1`。
 */
export function gateCountLabel(b: GateBuckets): string {
  if (!b || b.total === 0) return ''
  const judged = b.total - b.skipped
  const passedLike = b.passed + b.waived
  const base = `${passedLike}/${judged} 通过`
  return b.skipped > 0 ? `${base} · 跳过 ${b.skipped}` : base
}

function ruleLine(rule: GateRuleLike): string {
  const code = codeOf(rule) || '—'
  const actual = String(rule.actual ?? '').trim() || '—'
  const rawThreshold = rule.threshold
  const threshold =
    rawThreshold === null || rawThreshold === undefined || String(rawThreshold).trim() === ''
      ? '—'
      : String(rawThreshold).trim()
  return `${code} ${actual}/${threshold}`
}

/** 未通过明细行：`QG-04 1 个未关闭 P0/P1（#9）/≤ 0 个` */
export function failedRuleLines(b: GateBuckets, limit = 3): string[] {
  if (!b) return []
  const max = Number.isFinite(limit) ? Math.max(0, Math.trunc(limit)) : 3
  return b.failedRules.slice(0, max).map(ruleLine)
}

/** 跳过明细行：`跳过：QG-05 未启用（本轮无外部质量数据源接入）` —— 跳过永远不写成「未通过」 */
export function skippedRuleLines(b: GateBuckets, limit = 2): string[] {
  if (!b) return []
  const max = Number.isFinite(limit) ? Math.max(0, Math.trunc(limit)) : 2
  return b.skippedRules.slice(0, max).map((rule) => {
    const code = codeOf(rule) || '—'
    const why =
      String(rule.actual ?? '').trim() ||
      String(rule.fixHint ?? '').trim() ||
      '未启用'
    return `跳过：${code} ${why}`
  })
}
