<script setup lang="ts">
/**
 * 质量门面板（DESIGN §6 / Q9 / Q13）：两种模式共用一套渲染。
 *
 * - mode="gate"：5 条规则卡 + 结论条（快照 / 预览未固化标记）；
 * - mode="admission"：发布单准入清单（6 项），其中质量门项把 5 条规则以紧凑卡展开，
 *   历史无门禁快照的单据（legacy）只显示“未启用”，绝不伪装 PASS。
 *
 * 所有结论、规则状态、快照 hash 均为服务端下发值；面板不重算、不排序、不改写状态。
 */
import { computed, ref } from 'vue'
import StatusPill from '@/components/StatusPill.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import QualityRuleCard from '@/components/quality/QualityRuleCard.vue'
import WaiverBadge from '@/components/quality/WaiverBadge.vue'
import type { GateItem, GateRuleResult, WaiverRow } from '@/api/types'

interface GateRuleInput {
  code: string
  name?: string
  waivable?: boolean
  status?: string
  required?: boolean
  actual?: string
  threshold?: number | string | null
  thresholdText?: string
  fixHint?: string
  lastEvaluatedAt?: string | null
}

const props = withDefaults(
  defineProps<{
    mode?: 'gate' | 'admission'
    title?: string
    rules?: GateRuleInput[]
    items?: GateItem[]
    result?: string | null
    evaluatedAt?: string | null
    snapshotId?: number | null
    hash?: string | null
    /** 预评估：snapshotId=0、未固化 */
    preview?: boolean
    rejected?: boolean
    rejectedText?: string | null
    checkedAt?: string | null
    stale?: boolean
    legacy?: boolean
    waivers?: WaiverRow[]
    canWaive?: boolean
    readonly?: boolean
  }>(),
  {
    mode: 'gate',
    title: '',
    rules: () => [],
    items: () => [],
    result: null,
    evaluatedAt: null,
    snapshotId: null,
    hash: null,
    preview: false,
    rejected: false,
    rejectedText: '',
    checkedAt: null,
    stale: false,
    legacy: false,
    waivers: () => [],
    canWaive: true,
    readonly: false,
  },
)

const emit = defineEmits<{ waiver: [code: string] }>()

const expanded = ref<Record<string, boolean>>({})

const gateItems = computed(() => (props.items ?? []).map((item) => ({ ...item })))
/** 历史快照（legacy）根本没有 QUALITY_GATE 项，因此只能按“是否存在该项”判定，不能按项内条件判定 */
const hasQualityGateItem = computed(() => gateItems.value.some((item) => item.code === 'QUALITY_GATE'))
const legacyNotice = computed(() => props.legacy && !hasQualityGateItem.value)
const ruleList = computed<
  Array<GateRuleResult & { waivable?: boolean; thresholdText?: string; lastEvaluatedAt?: string | null }>
>(() =>
  (props.rules ?? []).map((rule) => ({
    code: rule.code,
    status: rule.status ?? 'SKIPPED',
    required: rule.required ?? true,
    actual: rule.actual ?? '—',
    threshold: String(rule.threshold ?? rule.thresholdText ?? '—'),
    fixHint: rule.fixHint ?? '',
    name: rule.name,
    waivable: rule.waivable,
    thresholdText: rule.thresholdText,
    lastEvaluatedAt: rule.lastEvaluatedAt,
  })),
)
const failedRules = computed(() =>
  ruleList.value.filter((rule) => String(rule.status).toUpperCase() === 'FAIL'),
)
const passedCount = computed(
  () =>
    ruleList.value.filter((rule) => {
      const status = String(rule.status).toUpperCase()
      return status === 'PASS' || status === 'WAIVED' || status === 'SKIPPED'
    }).length,
)
const failedCount = computed(
  () =>
    gateItems.value.filter((item) => !item.passed).length ||
    failedRules.value.length,
)
const waiverByRule = computed(() => {
  const map = new Map<string, WaiverRow>()
  for (const waiver of props.waivers ?? []) {
    const status = String(waiver.status).toUpperCase()
    if (status === 'ACTIVE' || status === 'APPROVED' || status === 'PENDING') {
      map.set(waiver.ruleCode, waiver)
    }
  }
  return map
})
const itemLabel = (item: GateItem): string => {
  const detail = item.detail ? ` · ${item.detail}` : ''
  return `${item.name}${detail}`
}

function toggleItem(code: string): void {
  expanded.value = { ...expanded.value, [code]: !expanded.value[code] }
}

function itemHasGateRules(item: GateItem): boolean {
  return Array.isArray(item.rules) && item.rules.length > 0
}
</script>

<template>
  <section class="gate-panel" :class="{ 'gate-panel--admission': mode === 'admission' }">
    <header class="gate-panel__head">
      <h3 class="gate-panel__title">{{ title || (mode === 'gate' ? '质量门规则' : '发布准入检查') }}</h3>
      <StatusPill v-if="mode === 'gate'" kind="gate" :value="result || 'NOT_EVALUATED'" />
      <template v-if="mode === 'gate'">
        <span class="gate-panel__count">
          规则 {{ passedCount }}/{{ ruleList.length }} 通过
          <template v-if="failedRules.length"> · {{ failedRules.length }} 条未通过</template>
        </span>
        <span v-if="preview" class="gate-panel__preview">预览（未固化）</span>
        <DigestChip
          v-else-if="hash"
          :hash="hash"
          label="快照"
          class="gate-panel__digest"
        />
        <span v-else class="gate-panel__muted">未固化</span>
        <span v-if="evaluatedAt" class="gate-panel__time">评估于 {{ evaluatedAt }}</span>
      </template>
      <template v-else>
        <span class="gate-panel__count">
          {{ gateItems.length }} 项
          <template v-if="failedCount"> · {{ failedCount }} 项未通过</template>
        </span>
        <span v-if="checkedAt" class="gate-panel__time">
          校验于 {{ checkedAt }}<template v-if="stale"> ｜ 已过期，提交时会重新校验</template>
        </span>
      </template>
      <span class="gate-panel__spacer" />
      <slot name="actions" />
    </header>

    <p v-if="legacyNotice" class="gate-panel__legacy" role="status">
      质量门未启用（历史单据）— 不补写 PASS，也不显示快照 hash。
    </p>

    <div v-if="rejected" class="gate-panel__reject" role="alert">
      <span class="gate-panel__reject-title">准入未通过</span>
      <span class="gate-panel__reject-text">
        {{ rejectedText || '存在未通过项，服务端已拒绝本次提交；请按修复指引处理或走 Go/No-Go 决策。' }}
      </span>
    </div>

    <div v-if="mode === 'gate'" class="gate-panel__rules">
      <p v-if="!ruleList.length" class="gate-panel__empty">
        尚未求值。点击「预评估」先看规则结果（不固化快照），或刷新已固化快照。
      </p>
      <QualityRuleCard
        v-for="rule in ruleList"
        :key="rule.code"
        :rule="rule"
        :waiver="waiverByRule.get(rule.code) ?? null"
        :can-waive="canWaive"
        :readonly="readonly"
        @waiver="emit('waiver', $event)"
      />
    </div>

    <ul v-else-if="gateItems.length" class="gate-panel__items">
      <li
        v-for="(item, index) in gateItems"
        :key="item.code || `${item.name}-${index}`"
        class="gate-item"
        :class="{ 'gate-item--fail': !item.passed }"
      >
        <div class="gate-item__row">
          <span class="gate-item__no t-num">{{ index + 1 }}</span>
          <span
            class="gate-item__dot"
            :class="item.passed ? 'is-pass' : 'is-fail'"
            aria-hidden="true"
          />
          <span class="gate-item__name">{{ item.name }}</span>
          <span class="gate-item__text" :title="itemLabel(item)">{{ item.detail || '' }}</span>
          <WaiverBadge
            v-if="item.forced"
            status="ACTIVE"
            compact
          />
          <StatusPill
            :kind="item.passed ? 'gate' : 'gate'"
            :value="item.passed ? 'PASS' : 'FAIL'"
            :label="item.passed ? '通过' : '未通过'"
            size="sm"
          />
          <button
            v-if="itemHasGateRules(item)"
            type="button"
            class="gate-item__expand"
            :aria-expanded="!!expanded[item.code]"
            @click="toggleItem(item.code)"
          >
            {{ expanded[item.code] ? '收起规则' : `展开 ${item.rules?.length ?? 0} 条规则` }}
          </button>
        </div>
        <p v-if="!item.passed && item.fixHint" class="gate-item__fix">
          <span>修复指引</span>{{ item.fixHint }}
        </p>
        <div v-if="expanded[item.code] && itemHasGateRules(item)" class="gate-item__rules">
          <QualityRuleCard
            v-for="rule in item.rules"
            :key="rule.code"
            :rule="rule"
            :waiver="waiverByRule.get(rule.code) ?? null"
            :can-waive="canWaive"
            :readonly="readonly"
            compact
            @waiver="emit('waiver', $event)"
          />
        </div>
      </li>
    </ul>
    <div v-if="mode === 'admission' && !gateItems.length && !legacyNotice" class="gate-panel__empty">
      暂无准入结果。点击「重新校验」由服务端重新装载事实（门禁超时按 FAIL 处理，不会视为通过）。
    </div>
  </section>
</template>

<style scoped>
.gate-panel {
  min-width: 0;
}
.gate-panel__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--divider);
  margin-bottom: 10px;
}
.gate-panel__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.gate-panel__count {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-panel__spacer {
  flex: 1;
}
.gate-panel__time {
  font-size: 12px;
  color: var(--text-weak);
}
.gate-panel__muted {
  font-size: 12px;
  color: var(--text-weak);
}
.gate-panel__preview {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-ctl);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-panel__digest {
  max-width: 220px;
}
.gate-panel__reject {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
}
.gate-panel__reject-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--danger-ink);
  flex: none;
}
.gate-panel__reject-text {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.gate-panel__rules {
  display: flex;
  flex-direction: column;
}
.gate-panel__empty {
  margin: 0;
  padding: 16px;
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-panel__items {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  overflow: hidden;
}
.gate-item {
  padding: 9px 12px;
  border-bottom: 1px solid var(--divider);
  background: var(--bg-card);
}
.gate-item:last-child {
  border-bottom: 0;
}
.gate-item--fail {
  background: #fffafa;
}
.gate-panel__legacy {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-left: 3px solid var(--color-warning);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  background: var(--color-warning-bg);
  font-size: 12px;
  line-height: 18px;
  color: var(--warning-ink);
}
.gate-item__row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
.gate-item__no {
  width: 18px;
  flex: none;
  font-size: 12px;
  color: var(--text-weak);
}
.gate-item__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.gate-item__dot.is-pass {
  background: var(--color-success);
}
.gate-item__dot.is-fail {
  background: var(--color-danger);
}
.gate-item__name {
  flex: none;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
}
.gate-item__text {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-item__expand {
  flex: none;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
}
.gate-item__expand:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}
.gate-item__fix {
  margin: 4px 0 0 32px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.gate-item__fix span {
  margin-right: 6px;
  color: var(--danger-ink);
}
.gate-item__rules {
  margin: 8px 0 2px 32px;
  padding-left: 10px;
  border-left: 2px solid var(--divider);
}
</style>
