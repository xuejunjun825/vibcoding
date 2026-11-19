#!/usr/bin/env node
/**
 * qg-buckets.mjs — 质量门四态分桶的前端纯函数断言（r33 / AC-3 / AC-6 / AC-7）。
 *
 * 前端没有 vitest/DOM 测试，`qualityGateBuckets.ts` 又是「用户看到的数字与徽标同源」的唯一实现，
 * 所以这里用 `node --experimental-strip-types` 直接加载它并按命令行真断言文案与分桶：
 *
 *   node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text
 *   node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs legacy
 *
 * text   —— 新数据：全量规则数组 → 三态文案（跳过 N 条 · 未通过 M 条 / 未通过 M 条 / 空串）、
 *           质量门页 label（4/4 通过 · 跳过 1）、跳过与失败明细。
 * legacy —— 旧形状：r30/r32 冻结快照把 SKIPPED 混在 failedRules 里、没有 skippedRules 与计数，
 *           回放必须按 status 重新分桶（跳过 ≠ 未通过），且同 code 去重后不重复计数。
 *
 * 任一断言失败打印 FAIL 行并 exit 1；全通过打印 PASS 行数并 exit 0（退出码可被门禁 S8 判定）。
 */
import { readFileSync } from 'node:fs'
import {
  bucketGateRules,
  gateCountText,
  gateCountLabel,
  failedRuleLines,
  skippedRuleLines,
} from '../../../web/src/components/release/qualityGateBuckets.ts'

const mode = (process.argv[2] || '').trim()
if (mode !== 'text' && mode !== 'legacy') {
  console.error('用法: node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text|legacy')
  process.exit(2)
}

let cases = 0
let failures = 0

/** 断言值相等；数组/对象按 JSON 序列化比较（顺序也算，明细顺序是稳定的对外契约）。 */
function eq(label, actual, expected) {
  cases += 1
  const a = typeof actual === 'string' ? actual : JSON.stringify(actual)
  const e = typeof expected === 'string' ? expected : JSON.stringify(expected)
  if (a === e) {
    console.log(`PASS ${cases} ${label} → ${a}`)
    return
  }
  failures += 1
  console.log(`FAIL ${cases} ${label} → 实际 ${a} ≠ 期望 ${e}`)
}

function contains(label, haystack, needle) {
  cases += 1
  if (typeof haystack === 'string' && haystack.includes(needle)) {
    console.log(`PASS ${cases} ${label} → 含「${needle}」`)
    return
  }
  failures += 1
  console.log(`FAIL ${cases} ${label} → ${JSON.stringify(haystack)} 不含「${needle}」`)
}

/** 反向断言：组件源码里不得再出现自算/写死的计数文案（D-1/D-2 的回归闸门）。 */
function absent(label, haystack, needle) {
  cases += 1
  if (typeof haystack === 'string' && !haystack.includes(needle)) {
    console.log(`PASS ${cases} ${label} → 不含「${needle}」`)
    return
  }
  failures += 1
  console.log(`FAIL ${cases} ${label} → 仍含「${needle}」`)
}

/** 四条恒等式：四桶之和 = total；failed/skipped 列表长度与桶计数一致。 */
function identity(label, b) {
  cases += 1
  const sum = b.passed + b.waived + b.failed + b.skipped
  const ok = sum === b.total && b.failedRules.length === b.failed && b.skippedRules.length === b.skipped
  if (ok) {
    console.log(`PASS ${cases} ${label} → 恒等式 ${b.passed}+${b.waived}+${b.failed}+${b.skipped}=${b.total}`)
    return
  }
  failures += 1
  console.log(
    `FAIL ${cases} ${label} → 恒等式破裂 sum=${sum} total=${b.total} failedRules=${b.failedRules.length}/${b.failed} skippedRules=${b.skippedRules.length}/${b.skipped}`,
  )
}

// ---------------------------------------------------------------- 夹具

const rule = (code, status, extra = {}) => ({
  code,
  status,
  required: code === 'QG-05' ? false : true,
  actual: `${code} 实测`,
  threshold: `${code} 门槛`,
  ...extra,
})

/** 演示版本：Qg-01..03 PASS、QG-04 PASS（0 个未关闭 P0/P1）、QG-05 SKIPPED（外部源未接入）。 */
const demoRules = () => [
  rule('QG-01', 'PASS', { actual: '需求覆盖 100%（1/1） · 报告 v1 已会签', threshold: '覆盖 ≥ 100% · 已会签 RELEASE 报告' }),
  rule('QG-02', 'PASS', { actual: '报告有效（0 天）', threshold: '≤ 7 天' }),
  rule('QG-03', 'PASS', { actual: '通过率 100%（2/2）', threshold: '≥ 95%' }),
  rule('QG-04', 'PASS', { actual: '0 个未关闭 P0/P1', threshold: '≤ 0 个' }),
  rule('QG-05', 'SKIPPED', {
    actual: '未启用（本轮无外部质量数据源接入）',
    threshold: '外部数据源（SONAR/ARTIFACT/BUILD）',
  }),
]

if (mode === 'text') {
  // ① 演示版本：PASS 卡 + 「跳过 1 条 · 未通过 0 条」——r30 的自相矛盾（PASS 卡写 1 条未通过）消失
  const demo = bucketGateRules(demoRules())
  identity('演示链四桶恒等式', demo)
  eq('演示链 passed/waived/failed/skipped', [demo.passed, demo.waived, demo.failed, demo.skipped], [4, 0, 0, 1])
  eq('链路卡副标题（跳过 1 条 · 未通过 0 条）', gateCountText(demo), '跳过 1 条 · 未通过 0 条')
  eq('质量门页 label（跳过不进分子分母）', gateCountLabel(demo), '4/4 通过 · 跳过 1')
  eq('PASS 段没有未通过明细', failedRuleLines(demo), [])
  eq('跳过明细单列且带原因', skippedRuleLines(demo), ['跳过：QG-05 未启用（本轮无外部质量数据源接入）'])

  // ② 造 P0 后：QG-04 FAIL + QG-05 仍 SKIPPED → 文案同时报两个数字，但跳过不写成失败
  const failed = bucketGateRules([
    rule('QG-01', 'PASS'),
    rule('QG-02', 'PASS'),
    rule('QG-03', 'PASS'),
    rule('QG-04', 'FAIL', { actual: '1 个未关闭 P0/P1（#9）', threshold: '≤ 0 个' }),
    rule('QG-05', 'SKIPPED', { actual: '未启用（本轮无外部质量数据源接入）' }),
  ])
  identity('失败态四桶恒等式', failed)
  eq('失败态 buckets', [failed.passed, failed.failed, failed.skipped], [3, 1, 1])
  eq('失败态副标题', gateCountText(failed), '跳过 1 条 · 未通过 1 条')
  eq('失败态 label（分母含未通过、不含跳过）', gateCountLabel(failed), '3/4 通过 · 跳过 1')
  eq('失败明细只列 QG-04', failedRuleLines(failed), ['QG-04 1 个未关闭 P0/P1（#9）/≤ 0 个'])

  // ③ 纯失败（没有跳过）：只报「未通过 M 条」
  const failOnly = bucketGateRules([
    rule('QG-01', 'PASS'),
    rule('QG-04', 'FAIL'),
  ])
  eq('无跳过时副标题只报未通过', gateCountText(failOnly), '未通过 1 条')
  eq('无跳过时 label 无 · 跳过 后缀', gateCountLabel(failOnly), '1/2 通过')

  // ④ 全通过：空串（不挂无信息量的「未通过 0 条」）
  const allPass = bucketGateRules(demoRules().map((r) => ({ ...r, status: 'PASS' })))
  eq('全通过副标题为空串', gateCountText(allPass), '')
  eq('全通过 label', gateCountLabel(allPass), '5/5 通过')

  // ⑤ WAIVED 计入「通过」分子，分母仍不含跳过
  const waived = bucketGateRules([rule('QG-01', 'PASS'), rule('QG-03', 'WAIVED'), rule('QG-05', 'SKIPPED')])
  eq('WAIVED 计入通过分子', gateCountLabel(waived), '2/2 通过 · 跳过 1')

  // ⑥ 未知状态 fail-closed（与服务端 GateRuleBuckets 同一张真值表）
  const unknown = bucketGateRules([rule('QG-09', 'WHATEVER')])
  eq('未知状态归未通过', [unknown.failed, unknown.skipped], [1, 0])
  eq('未知状态副标题', gateCountText(unknown), '未通过 1 条')

  // ⑦ flow-chain 段形状（只有 failed/skipped 两个列表）与空输入
  const segment = bucketGateRules({
    failedRules: [{ code: 'QG-05', status: 'SKIPPED', actual: '未启用（本轮无外部质量数据源接入）' }],
    skippedRules: null,
  })
  eq('段形状：旧 failedRules 里的 SKIPPED 归跳过', [segment.failed, segment.skipped, segment.total], [0, 1, 1])
  eq('段形状副标题', gateCountText(segment), '跳过 1 条 · 未通过 0 条')
  eq('空输入不炸', gateCountText(null), '')
  eq('空输入 label 不炸', gateCountLabel(undefined), '')

  // ⑧ 详情页快照时间线（D-1）：时间线必须复用同一分桶，跳过既不算通过也不进分母。
  //    这里同时钉住「组件不再自算」：源码里没有写死的 5/5，且确实调用了 bucketGateRules。
  const detailView = readFileSync(
    new URL('../../../web/src/views/quality/QualityGateDetailView.vue', import.meta.url),
    'utf8',
  )
  const listView = readFileSync(
    new URL('../../../web/src/views/quality/QualityGatesView.vue', import.meta.url),
    'utf8',
  )
  contains('详情页时间线复用唯一分桶', detailView, 'bucketGateRules')
  absent('详情页不再写死 5/5 通过', detailView, '5/5 通过')
  absent('列表页兜底不再写死 5/5 通过', listView, '5/5 通过')
  absent('列表页兜底不再用 5 减失败数', listView, '5 - failed')
  // 时间线的输入是快照全量 rules（4 PASS + 1 SKIPPED），与面板同一函数 → 同一句话。
  eq('时间线 4 PASS + 1 SKIPPED 文案', gateCountLabel(bucketGateRules(demoRules())), '4/4 通过 · 跳过 1')
  eq('空输入明细为空', [failedRuleLines(null), skippedRuleLines(null)], [[], []])
}

if (mode === 'legacy') {
  // 旧形状：r30/r32 冻结快照的 qualityGate 段——没有 skippedRules、没有五个计数，
  // 且 SKIPPED 混在 failedRules 里。回放必须按 status 重新分桶，不能看数组长度。
  const legacySkippedOnly = {
    status: 'PASS',
    result: 'PASS',
    snapshotId: 7,
    failedRules: [
      {
        code: 'QG-05',
        status: 'SKIPPED',
        required: false,
        actual: '未启用（本轮无外部质量数据源接入）',
        threshold: '外部数据源（SONAR/ARTIFACT/BUILD）',
        fixHint: '在外部数据接入后启用，或保持关闭',
      },
    ],
  }
  const b1 = bucketGateRules(legacySkippedOnly)
  identity('旧形状恒等式（1 行）', b1)
  eq('旧形状：SKIPPED 不再算未通过', [b1.skipped, b1.failed, b1.total], [1, 0, 1])
  eq('旧形状文案与新数据一致', gateCountText(b1), '跳过 1 条 · 未通过 0 条')
  eq('旧形状跳过明细', skippedRuleLines(b1), ['跳过：QG-05 未启用（本轮无外部质量数据源接入）'])

  // 旧形状混装：真的 FAIL + 混进来的 SKIPPED，各归各桶
  const legacyMixed = {
    failedRules: [
      { code: 'QG-04', status: 'FAIL', actual: '1 个未关闭 P0/P1（#9）', threshold: '≤ 0 个' },
      { code: 'QG-05', status: 'SKIPPED', actual: '未启用（本轮无外部质量数据源接入）' },
    ],
  }
  const b2 = bucketGateRules(legacyMixed)
  identity('旧形状恒等式（混装 2 行）', b2)
  eq('旧形状混装：失败 1 / 跳过 1', [b2.failed, b2.skipped], [1, 1])
  eq('旧形状混装文案', gateCountText(b2), '跳过 1 条 · 未通过 1 条')
  eq('旧形状混装失败明细只列 QG-04', failedRuleLines(b2).map((line) => line.split(' ')[0]), ['QG-04'])

  // 按 code 去重：旧行 + 新 skippedRules 里同一条，只计一次（不因两条列表而翻倍）
  const b3 = bucketGateRules({
    failedRules: [{ code: 'QG-05', status: 'SKIPPED', actual: '旧说法' }],
    skippedRules: [{ code: 'QG-05', status: 'SKIPPED', actual: '未启用（本轮无外部质量数据源接入）' }],
  })
  identity('同 code 去重恒等式', b3)
  eq('同 code 去重后只有一行', [b3.total, b3.skipped], [1, 1])
  contains('同 code 去重保留新行', skippedRuleLines(b3).join('|'), '未启用（本轮无外部质量数据源接入）')

  // 旧形状缺计数字段：不读计数也不炸（前端只按 status 分桶）
  const b4 = bucketGateRules({ failedRules: [], skippedRules: undefined })
  eq('空旧形状全零', [b4.total, b4.skipped, b4.failed], [0, 0, 0])
}

const label = failures === 0 ? 'OK' : 'FAILED'
console.log(`QG_BUCKETS_MODE=${mode} ${label}=${cases} FAIL=${failures}`)
process.exit(failures === 0 ? 0 : 1)
