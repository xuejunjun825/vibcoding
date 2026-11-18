<script setup lang="ts">
/**
 * 四段发版链路（r30 / DESIGN §4.1）：纯展示，零判定、零请求。
 *
 * 硬约束（AC-9 静态检索）：
 * - 段状态只映射服务端给的 status，本组件不存在「按签署人数 / 规则条数自行推通过」的分支；
 * - 不内置任何门规则编号或准入项顺序，失败规则与准入项一律原样展示服务端事实；
 * - 缺段不美化：MISSING 用虚线 + 「暂无」+ 原因，绝不画成绿色通过。
 */
import { computed } from 'vue'
import type { FlowChain } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  chain: FlowChain | null
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{ (e: 'retry'): void }>()

type Tone = 'pass' | 'pending' | 'fail' | 'missing'

interface SegmentView {
  key: 'testPlan' | 'signoff' | 'qualityGate' | 'deployAdmission'
  no: string
  title: string
  tone: Tone
  stamp: string
  value: string
  sub: string
  reason: string
  details: string[]
}

/** §4.2：PASS/OK→已通过；IN_PROGRESS→进行中；PENDING→待办；FAIL→未通过；其余（含 MISSING）→暂无 */
const TONES: Record<string, Tone> = {
  PASS: 'pass',
  OK: 'pass',
  IN_PROGRESS: 'pending',
  PENDING: 'pending',
  FAIL: 'fail',
  MISSING: 'missing',
}

function toneOf(status?: string | null): Tone {
  return status ? TONES[status] ?? 'missing' : 'missing'
}

function stampOf(status: string | null | undefined, tone: Tone): string {
  if (tone === 'pass') return '已通过'
  if (tone === 'fail') return '未通过'
  if (status === 'PENDING') return '待办'
  if (status === 'IN_PROGRESS') return '进行中'
  return '暂无'
}

const signoffLevelLabel: Record<string, string> = {
  APPROVED: '已签',
  PENDING: '待签',
  WAITING: '未开始',
  REJECTED: '已驳回',
  SKIPPED: '已跳过',
}

const levelLabel = computed(() => {
  const raw = props.chain?.releaseLevel
  if (raw === 'NORMAL') return '常规'
  if (raw === 'EMERGENCY') return '紧急'
  if (raw === 'ROLLBACK') return '回滚'
  return ''
})

const segments = computed<SegmentView[]>(() => {
  const chain = props.chain
  const plan = chain?.testPlan
  const signoff = chain?.signoff
  const gate = chain?.qualityGate
  const admission = chain?.deployAdmission

  const planTone = toneOf(plan?.status)
  const signTone = toneOf(signoff?.status)
  const gateTone = toneOf(gate?.status)
  const admissionTone = toneOf(admission?.status)

  const admissionTotal = (admission?.passedCount ?? 0) + (admission?.failedCount ?? 0)
  const failedRules = gate?.failedRules ?? []
  const failedItems = (admission?.items ?? []).filter((item) => item.status === 'FAIL')

  return [
    {
      key: 'testPlan',
      no: '①',
      title: '测试计划',
      tone: planTone,
      stamp: stampOf(plan?.status, planTone),
      value: plan ? `${plan.approved ?? 0}/${plan.total ?? 0}` : '—',
      sub: plan
        ? `计划 ${plan.total ?? 0} 条 · 待批 ${plan.pending ?? 0}`
        : '计划结论由 deliver 汇总',
      reason: plan?.reason ?? '',
      details: (plan?.plans ?? [])
        .slice(0, 2)
        .map((line) => `${line.code} ${line.conclusion || line.status || ''}`.trim()),
    },
    {
      key: 'signoff',
      no: '②',
      title: '报告三签',
      tone: signTone,
      stamp: stampOf(signoff?.status, signTone),
      value: signoff ? `${signoff.approved ?? 0}/${signoff.total ?? 0}` : '—',
      sub: signoff
        ? [
            signoff.versionNo ? `报告 v${signoff.versionNo}` : '最新 RELEASE 报告',
            signoff.conclusion || (signoff.pendingRole ? `待 ${signoff.pendingRole} 签署` : ''),
          ]
            .filter(Boolean)
            .join(' · ')
        : '签署结论由 deliver 汇总',
      reason: signoff?.reason ?? '',
      details: (signoff?.levels ?? [])
        .slice(0, 2)
        .map(
          (level) =>
            `${level.roleName || level.signRole || '签署'} ${signoffLevelLabel[level.status ?? ''] ?? ''}`.trim(),
        ),
    },
    {
      key: 'qualityGate',
      no: '③',
      title: '质量门',
      tone: gateTone,
      stamp: stampOf(gate?.status, gateTone),
      value: gate?.result || (gate ? '—' : ''),
      sub: gate
        ? [
            gate.computedAt ? formatDateTime(gate.computedAt) : '',
            failedRules.length > 0 ? `${failedRules.length} 条未通过` : '',
            gate.waivers && gate.waivers.length > 0 ? `${gate.waivers.length} 条豁免` : '',
          ]
            .filter(Boolean)
            .join(' · ') || '门结果由 deliver 计算'
        : '门结果由 deliver 计算',
      reason: gate?.reason ?? '',
      details: failedRules
        .slice(0, 2)
        .map((rule) => `${rule.code} ${rule.actual || ''}/${rule.threshold || '—'}`.trim()),
    },
    {
      key: 'deployAdmission',
      no: '④',
      title: '发布单准入',
      tone: admissionTone,
      stamp: stampOf(admission?.status, admissionTone),
      value: admission ? `${admission.passedCount ?? 0}/${admissionTotal}` : '—',
      sub: admission
        ? [
            admission.code || '',
            admission.frozen ? '已冻结快照' : '实时评估',
            admission.failedCount ? `${admission.failedCount} 项未通过` : '',
          ]
            .filter(Boolean)
            .join(' · ')
        : '准入由 req 本地事实计算',
      reason: admission?.reason ?? '',
      details: failedItems.slice(0, 2).map((item) => {
        const label = item.name || item.code || '准入项'
        const why = item.detail || (item.status === 'FAIL' ? '未通过' : '')
        return why ? `${label}：${why}` : label
      }),
    },
  ]
})

const emptySegments = computed<SegmentView[]>(() =>
  segments.value.map((seg) => ({
    ...seg,
    tone: 'missing' as Tone,
    stamp: '暂无',
    value: '—',
    sub: '尚未取得链路数据',
    reason: '',
    details: [],
  })),
)

const shown = computed(() => (props.chain ? segments.value : emptySegments.value))
</script>

<template>
  <section class="surface surface--pad flow-chain" data-testid="release-flow-chain" aria-labelledby="flow-chain-title">
    <header class="flow-chain__head">
      <div class="flow-chain__intro">
        <h2 id="flow-chain-title" class="section-title">发版链路</h2>
        <p class="flow-chain__hint">
          测试计划 → 报告三签 → 质量门 → 发布单准入，四段同源；状态由服务端按事实判定，本页只读。
        </p>
      </div>
      <div class="flow-chain__meta">
        <span v-if="levelLabel" class="flow-chain__level">发布等级 {{ levelLabel }}</span>
        <span class="flow-chain__ro">只读</span>
      </div>
    </header>

    <div v-if="loading" class="flow-chain__grid" aria-busy="true" aria-label="链路加载中">
      <div v-for="i in 4" :key="i" class="seg seg--skeleton">
        <span class="skeleton" style="height: 14px; width: 56%" />
        <span class="skeleton" style="height: 22px; width: 40%" />
        <span class="skeleton" style="height: 12px; width: 74%" />
      </div>
    </div>

    <div v-else-if="error" class="flow-chain__error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="link" @click="emit('retry')">重试</button>
    </div>

    <ol v-else class="flow-chain__grid">
      <li v-for="seg in shown" :key="seg.key" class="seg" :class="`seg--${seg.tone}`">
        <header class="seg__head">
          <span class="seg__no" aria-hidden="true">{{ seg.no }}</span>
          <h3 class="seg__title">{{ seg.title }}</h3>
          <span class="seg__stamp">{{ seg.stamp }}</span>
        </header>
        <p class="seg__value">{{ seg.value }}</p>
        <p class="seg__sub" :title="seg.sub">{{ seg.sub }}</p>
        <p v-if="seg.reason" class="seg__reason" :title="seg.reason">{{ seg.reason }}</p>
        <ul v-if="seg.details.length" class="seg__details">
          <li v-for="(line, i) in seg.details" :key="i" :title="line">{{ line }}</li>
        </ul>
      </li>
    </ol>
  </section>
</template>

<style scoped>
/* 5 个令牌按 DESIGN §7.2 命名；本组件自持声明，避免跨任务改 tokens.css */
.flow-chain {
  --h-chain-seg: 132px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.flow-chain__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.flow-chain__hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.flow-chain__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.flow-chain__level {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  font-size: 12px;
  color: var(--text-body);
}
.flow-chain__ro {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.flow-chain__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.seg {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  min-height: var(--h-chain-seg);
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
/* 段间连接：1px 线 + 5px 三角，纯 CSS（不用图标库/SVG） */
.seg + .seg::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 50%;
  width: 12px;
  height: 1px;
  background: var(--border-strong);
}
.seg + .seg::after {
  content: '';
  position: absolute;
  left: -1px;
  top: calc(50% - 3px);
  width: 0;
  height: 0;
  border-left: 5px solid var(--border-strong);
  border-top: 3px solid transparent;
  border-bottom: 3px solid transparent;
}
.seg__head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.seg__no {
  flex: none;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.seg__title {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.seg__stamp {
  flex: none;
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-ctl);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
}
.seg__value {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.seg__sub {
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.seg__reason {
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.seg__details {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.seg__details li {
  font-size: 12px;
  line-height: 16px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 四态：通过 / 待办进行中 / 未通过 / 暂无（缺段不美化） */
.seg--pass .seg__stamp {
  color: var(--success-ink);
  background: var(--color-success-bg);
}
.seg--pass {
  border-color: color-mix(in srgb, var(--color-success) 32%, var(--border));
}
.seg--pending .seg__stamp {
  color: var(--info-ink);
  background: var(--color-info-bg);
}
.seg--pending {
  border-color: var(--border-strong);
}
.seg--fail .seg__stamp {
  color: var(--danger-ink);
  background: var(--color-danger-bg);
}
.seg--fail .seg__value {
  color: var(--danger-ink);
}
.seg--fail {
  border-color: color-mix(in srgb, var(--color-danger) 40%, var(--border));
}
.seg--missing {
  background: var(--bg-hover);
  border: 1px dashed var(--text-muted-aa);
}
.seg--missing .seg__stamp {
  color: var(--text-muted-aa);
  background: var(--bg-card);
  border: 1px solid var(--border);
}
.seg--missing .seg__value {
  color: var(--text-muted-aa);
}
.seg--skeleton {
  justify-content: center;
  gap: 10px;
}
.flow-chain__error {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: var(--h-chain-seg);
  padding: 12px;
  border: 1px dashed var(--text-muted-aa);
  border-radius: var(--r-card);
  background: var(--bg-hover);
  font-size: 12px;
  color: var(--text-muted-aa);
}
/* 断点：≥1200 四列 / 960–1200 2×2 / <960 单列；<1200 连接符无处可指，隐藏 */
@media (max-width: 1199px) {
  .flow-chain__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .seg + .seg::before,
  .seg + .seg::after {
    display: none;
  }
}
@media (max-width: 959px) {
  .flow-chain__grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .flow-chain__head {
    flex-direction: column;
  }
}
@media (prefers-reduced-motion: reduce) {
  .seg {
    transition: none;
  }
}
</style>
