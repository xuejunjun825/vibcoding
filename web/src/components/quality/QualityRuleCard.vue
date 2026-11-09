<script setup lang="ts">
/**
 * 单条门禁规则卡（DESIGN §1 / Q9）：8px 卡、1px 边、FAIL 左 2px 红、WAIVED 左 2px 黄。
 *
 * 规则结果、actual/threshold 全部来自服务端 GateEvaluator（前端不换算）。
 * QG-04 是硬红线：服务端 400 RULE_NOT_WAIVABLE，这里连申请入口都不渲染，
 * 并在卡内写明“不可豁免”的原因 —— 只靠后端拒绝不是约束。
 */
import { computed, ref } from 'vue'
import StatusPill from '@/components/StatusPill.vue'
import WaiverBadge from '@/components/quality/WaiverBadge.vue'
import type { GateRuleResult, WaiverRow } from '@/api/types'

interface RuleCardModel extends GateRuleResult {
  name?: string
  waivable?: boolean
  thresholdText?: string
  lastEvaluatedAt?: string | null
}

const props = withDefaults(
  defineProps<{
    rule: RuleCardModel
    waiver?: WaiverRow | null
    /** 准入清单里内嵌的紧凑模式：默认收起，展开才见证据 */
    compact?: boolean
    defaultOpen?: boolean
    canWaive?: boolean
    /** 快照回放 / 历史版本：只读，不出现豁免入口 */
    readonly?: boolean
    index?: number
  }>(),
  { waiver: null, compact: false, defaultOpen: false, canWaive: true, readonly: false, index: 0 },
)

const emit = defineEmits<{ waiver: [code: string] }>()

const open = ref(props.defaultOpen)
const status = computed(() => String(props.rule.status ?? '').toUpperCase())
const failed = computed(() => status.value === 'FAIL')
const waiverStatus = computed(() => String(props.waiver?.status ?? '').toUpperCase())
const waived = computed(() => status.value === 'WAIVED' || waiverStatus.value === 'ACTIVE')
const hardLine = computed(
  () => props.rule.code === 'QG-04' || props.rule.waivable === false,
)
const canApply = computed(
  () => !props.readonly && props.canWaive && !hardLine.value && !waived.value,
)
const thresholdLabel = computed(() => props.rule.thresholdText ?? props.rule.threshold ?? '—')
const ruleName = computed(() => props.rule.name ?? props.rule.code)

function toggle(): void {
  open.value = !open.value
}
</script>

<template>
  <article
    class="rule-card"
    :class="{
      'rule-card--fail': failed,
      'rule-card--waived': waived,
      'rule-card--compact': compact,
      'is-open': open,
    }"
  >
    <header class="rule-card__head">
      <span class="rule-card__code t-code">{{ rule.code }}</span>
      <span class="rule-card__name" :title="ruleName">{{ ruleName }}</span>
      <StatusPill kind="rule" :value="rule.status" size="sm" />
      <span class="rule-card__spacer" />
      <span v-if="!compact" class="rule-card__numbers t-num">
        <span class="rule-card__actual" :title="rule.actual">{{ rule.actual || '—' }}</span>
        <span class="rule-card__arrow">/</span>
        <span class="rule-card__threshold">{{ thresholdLabel }}</span>
      </span>
      <WaiverBadge
        v-if="waived && waiver"
        :status="waiver.status"
        :expiry-at="waiver.expiryAt"
        compact
      />
      <button
        v-if="canApply"
        type="button"
        class="rule-card__waive"
        @click="emit('waiver', rule.code)"
      >
        申请豁免
      </button>
      <span
        v-else-if="!readonly && hardLine"
        class="rule-card__hardline"
        title="QG-04 是发布红线，规则级豁免不覆盖未关闭 P0/P1"
      >
        不可豁免
      </span>
      <button
        type="button"
        class="rule-card__expand"
        :aria-expanded="open"
        :aria-label="open ? '收起规则证据' : '展开规则证据'"
        @click="toggle"
      >
        <span class="rule-card__caret" :class="{ 'is-open': open }" aria-hidden="true">⌄</span>
      </button>
    </header>

    <div v-if="!open && failed && rule.fixHint" class="rule-card__brief" :title="rule.fixHint">
      {{ rule.fixHint }}
    </div>

    <div v-if="open" class="rule-card__body">
      <dl class="rule-card__grid">
        <div class="rule-card__field">
          <dt>实测</dt>
          <dd class="t-num">{{ rule.actual || '—' }}</dd>
        </div>
        <div class="rule-card__field">
          <dt>阈值</dt>
          <dd class="t-num">{{ thresholdLabel }}</dd>
        </div>
        <div class="rule-card__field">
          <dt>是否必需</dt>
          <dd>{{ rule.required ? '必需' : '可选' }}</dd>
        </div>
        <div class="rule-card__field">
          <dt>最近评估</dt>
          <dd>{{ rule.lastEvaluatedAt || '—' }}</dd>
        </div>
      </dl>
      <p v-if="rule.fixHint" class="rule-card__fix">
        <span class="rule-card__fix-label">修复指引</span>{{ rule.fixHint }}
      </p>
      <div v-if="waiver" class="rule-card__waiver">
        <div class="rule-card__waiver-head">
          <WaiverBadge :status="waiver.status" :expiry-at="waiver.expiryAt" />
          <span class="rule-card__waiver-rule">{{ waiver.ruleCode }}</span>
          <span v-if="waiver.expiryAt" class="rule-card__waiver-expiry">
            到期 {{ waiver.expiryAt }}
          </span>
        </div>
        <p class="rule-card__waiver-reason">{{ waiver.reason }}</p>
        <p v-if="waiver.impactScope" class="rule-card__waiver-scope">
          影响范围：{{ waiver.impactScope }}
        </p>
        <p class="rule-card__waiver-meta">
          申请人 {{ waiver.applicantName || '—' }}
          <template v-if="waiver.approverName"> · 审批人 {{ waiver.approverName }}</template>
        </p>
      </div>
      <p v-if="hardLine && !readonly" class="rule-card__hardline-note">
        QG-04（未关闭 P0/P1）是发布硬红线：只能修复或走 Go/No-Go 决策，不能豁免。
      </p>
    </div>
  </article>
</template>

<style scoped>
.rule-card {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
  transition:
    border-color var(--t-color) var(--ease),
    box-shadow var(--t-color) var(--ease);
}
.rule-card + .rule-card {
  margin-top: 8px;
}
.rule-card:hover {
  border-color: var(--border-strong);
}
.rule-card--fail {
  border-left: 2px solid var(--color-danger);
}
.rule-card--waived {
  border-left: 2px solid var(--color-warning);
}
.rule-card--compact {
  border-radius: var(--r-ctl);
}
.rule-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 6px 10px 6px 12px;
}
.rule-card--compact .rule-card__head {
  min-height: 38px;
  padding: 4px 8px 4px 10px;
}
.rule-card__code {
  width: var(--w-rule-code, 64px);
  flex: none;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.rule-card__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
}
.rule-card__spacer {
  flex: 1;
}
.rule-card__numbers {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  flex: none;
  font-size: 12px;
  max-width: 36%;
}
.rule-card__actual {
  color: var(--text-title);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rule-card__arrow {
  color: var(--text-weak);
}
.rule-card__threshold {
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.rule-card__waive {
  flex: none;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
  transition:
    color var(--t-color) var(--ease),
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease);
}
.rule-card__waive:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.rule-card__hardline {
  flex: none;
  font-size: 12px;
  color: var(--text-weak);
}
.rule-card__expand {
  flex: none;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  color: var(--text-muted-aa);
  cursor: pointer;
}
.rule-card__expand:hover {
  background: var(--bg-hover);
  color: var(--text-title);
}
.rule-card__expand:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.rule-card__caret {
  display: inline-block;
  transition: transform var(--t-expand) var(--ease);
}
.rule-card__caret.is-open {
  transform: rotate(180deg);
}
.rule-card__brief {
  padding: 0 12px 8px calc(var(--w-rule-code, 64px) + 20px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--danger-ink);
}
.rule-card__body {
  padding: 4px 12px 12px;
  border-top: 1px solid var(--divider);
  background: var(--bg-card);
}
.rule-card__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px 16px;
  margin: 8px 0;
}
.rule-card__field {
  min-width: 0;
}
.rule-card__field dt {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.rule-card__field dd {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rule-card__fix {
  margin: 6px 0 0;
  padding: 8px 10px;
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.rule-card__fix-label {
  margin-right: 6px;
  color: var(--text-muted-aa);
}
.rule-card__waiver {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e9c989;
  border-radius: var(--r-ctl);
  background: #fffdf5;
}
.rule-card__waiver-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.rule-card__waiver-rule {
  font-family: var(--font-mono);
  color: var(--text-muted-aa);
}
.rule-card__waiver-expiry {
  margin-left: auto;
  color: var(--text-muted-aa);
}
.rule-card__waiver-reason {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.rule-card__waiver-scope,
.rule-card__waiver-meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.rule-card__hardline-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
