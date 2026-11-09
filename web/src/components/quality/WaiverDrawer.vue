<script setup lang="ts">
/**
 * 规则级豁免抽屉（DESIGN §6 / Q9）：560 宽，两态。
 *
 * - apply：单选一条规则、原因 ≥10 字、影响范围、1–30 天；QG-04 与服务端 waivable=false
 *   的规则直接 disabled 并写明原因（服务端仍会 400 RULE_NOT_WAIVABLE 兜底）。
 * - approve：只展示待审豁免的全文与审批意见，本人自批由服务端 403 SCOPE_DENIED 兜底。
 *
 * 到期日只是“按今天推算”的展示，提交的是 expiryDays，落库时间以服务端为准。
 */
import { computed, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { waiverApi } from '@/api/quality'
import { ApiError } from '@/api/client'
import WaiverBadge from '@/components/quality/WaiverBadge.vue'
import StatusPill from '@/components/StatusPill.vue'
import type { GateRuleResult, WaiverRow } from '@/api/types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    mode?: 'apply' | 'approve'
    releaseId: number
    releaseName?: string
    rules?: Array<GateRuleResult & { waivable?: boolean }>
    waiver?: WaiverRow | null
    presetRule?: string
    readonly?: boolean
  }>(),
  {
    mode: 'apply',
    releaseName: '',
    rules: () => [],
    waiver: null,
    presetRule: '',
    readonly: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submitted: [row: WaiverRow]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = reactive({
  ruleCode: '',
  reason: '',
  impactScope: '',
  expiryDays: 7,
})
const comment = ref('')
const busy = ref(false)
const error = ref<{ message: string; code?: string; status?: number; field?: string } | null>(null)

const selectableRules = computed(() =>
  (props.rules ?? []).map((rule) => ({
    ...rule,
    disabled: rule.code === 'QG-04' || rule.waivable === false,
  })),
)
const selectedRule = computed(
  () => selectableRules.value.find((rule) => rule.code === form.ruleCode) ?? null,
)
const expiryPreview = computed(() =>
  dayjs().add(Math.max(1, Number(form.expiryDays) || 1), 'day').format('YYYY-MM-DD'),
)
const reasonTooShort = computed(() => form.reason.trim().length > 0 && form.reason.trim().length < 10)
const canSubmit = computed(
  () =>
    !props.readonly &&
    !!form.ruleCode &&
    form.reason.trim().length >= 10 &&
    Number(form.expiryDays) >= 1 &&
    Number(form.expiryDays) <= 30,
)
const title = computed(() => (props.mode === 'apply' ? '申请规则级豁免' : '审批豁免'))

function reset(): void {
  form.ruleCode = props.presetRule || ''
  form.reason = ''
  form.impactScope = ''
  form.expiryDays = 7
  comment.value = ''
  error.value = null
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) reset()
  },
)

function codeOf(err: unknown): string | undefined {
  return err instanceof ApiError ? err.code : undefined
}

function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    const detail = err.detail as Record<string, unknown> | undefined
    if (err.code === 'WAIVER_FIELDS_REQUIRED') {
      const field = typeof detail?.field === 'string' ? detail.field : ''
      return field ? `缺少必填项：${field}` : '豁免必填项不完整（规则 / 原因 / 影响范围 / 有效期）'
    }
    if (err.code === 'RULE_NOT_WAIVABLE') return '该规则是发布硬红线，不可豁免'
    if (err.code === 'WAIVER_EXPIRY_INVALID') return '有效期必须在 1–30 天之间'
    if (err.code === 'WAIVER_RULE_SINGLE_ONLY') return '一条豁免只能覆盖一条规则'
    if (err.code === 'SCOPE_DENIED') return '不能审批自己申请的豁免'
    return err.message || err.code || '请求失败'
  }
  return err instanceof Error ? err.message : '请求失败'
}

async function submitApply(): Promise<void> {
  if (!canSubmit.value || busy.value) return
  busy.value = true
  error.value = null
  try {
    const row = await waiverApi.apply({
      ruleCode: form.ruleCode,
      releaseId: props.releaseId,
      reason: form.reason.trim(),
      impactScope: form.impactScope.trim(),
      expiryDays: Number(form.expiryDays),
    })
    ElMessage.success('豁免申请已提交，等待审批')
    emit('submitted', row)
    visible.value = false
  } catch (err) {
    error.value = {
      message: messageOf(err),
      code: codeOf(err),
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    busy.value = false
  }
}

async function approve(): Promise<void> {
  if (!props.waiver || busy.value) return
  busy.value = true
  error.value = null
  try {
    const row = await waiverApi.approve(props.waiver.id, comment.value.trim() || undefined)
    ElMessage.success('豁免已通过')
    emit('submitted', row)
    visible.value = false
  } catch (err) {
    error.value = { message: messageOf(err), code: codeOf(err) }
  } finally {
    busy.value = false
  }
}

async function reject(): Promise<void> {
  if (!props.waiver || busy.value) return
  if (!comment.value.trim()) {
    error.value = { message: '驳回必须填写审批意见', code: 'COMMENT_REQUIRED' }
    return
  }
  busy.value = true
  error.value = null
  try {
    const row = await waiverApi.reject(props.waiver.id, comment.value.trim())
    ElMessage.success('豁免已驳回')
    emit('submitted', row)
    visible.value = false
  } catch (err) {
    error.value = { message: messageOf(err), code: codeOf(err) }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="title"
    size="560px"
    :close-on-click-modal="!busy"
    class="waiver-drawer"
  >
    <div v-if="error" class="waiver-error" role="alert">
      <span class="waiver-error__code" v-if="error.code">{{ error.code }}</span>
      <span>{{ error.message }}</span>
    </div>

    <!-- 申请 -->
    <template v-if="mode === 'apply'">
      <div class="waiver-context">
        <span class="waiver-context__label">版本</span>
        <span>{{ releaseName || `#${releaseId}` }}</span>
        <span v-if="selectedRule" class="waiver-context__threshold">
          当前 {{ selectedRule.actual || '—' }} / 阈值 {{ selectedRule.threshold || '—' }}
        </span>
      </div>

      <div class="waiver-field">
        <label class="waiver-field__label" for="waiver-rule">
          规则<span class="waiver-field__req">*</span>
        </label>
        <el-select
          id="waiver-rule"
          v-model="form.ruleCode"
          placeholder="选择一条需要豁免的规则"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="rule in selectableRules"
            :key="rule.code"
            :value="rule.code"
            :label="`${rule.code} · ${rule.name || rule.code}`"
            :disabled="rule.disabled"
          >
            <span class="waiver-option__code t-code">{{ rule.code }}</span>
            <span class="waiver-option__name">{{ rule.name || rule.code }}</span>
            <span v-if="rule.disabled" class="waiver-option__tag">不可豁免</span>
          </el-option>
        </el-select>
        <p class="waiver-field__hint">
          一条豁免只覆盖一条规则；QG-04（未关闭 P0/P1）是发布硬红线，永远不会出现在可选项里。
        </p>
      </div>

      <div class="waiver-field">
        <label class="waiver-field__label" for="waiver-reason">
          豁免原因<span class="waiver-field__req">*</span>
        </label>
        <el-input
          id="waiver-reason"
          v-model="form.reason"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="说明为什么本次可以带风险发布（≥10 字），审批人会按此判断"
          :class="{ 'is-invalid': reasonTooShort }"
        />
        <p class="waiver-field__hint" :class="{ 'ink-danger': reasonTooShort }">
          {{ form.reason.trim().length }}/10 字起
        </p>
      </div>

      <div class="waiver-field">
        <label class="waiver-field__label" for="waiver-scope">影响范围</label>
        <el-input
          id="waiver-scope"
          v-model="form.impactScope"
          maxlength="200"
          placeholder="例如：仅影响报表导出，不影响下单主链路"
        />
      </div>

      <div class="waiver-field waiver-field--inline">
        <div>
          <label class="waiver-field__label" for="waiver-days">
            有效期<span class="waiver-field__req">*</span>
          </label>
          <el-input-number
            id="waiver-days"
            v-model="form.expiryDays"
            :min="1"
            :max="30"
            :step="1"
            controls-position="right"
          />
          <span class="waiver-field__unit">天（1–30）</span>
        </div>
        <div class="waiver-expiry">
          <span class="waiver-field__label">到期日（按今天推算）</span>
          <strong>{{ expiryPreview }}</strong>
          <span class="waiver-field__hint">过期后规则恢复为原状态；已发出的快照不受影响</span>
        </div>
      </div>

      <div class="waiver-actions">
        <el-button :disabled="busy" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submitApply">
          提交申请
        </el-button>
      </div>
      <p v-if="!canSubmit && !readonly" class="waiver-disabled-reason">
        需要选择规则、填写 ≥10 字原因，并把有效期设在 1–30 天内。
      </p>
    </template>

    <!-- 审批 -->
    <template v-else>
      <div v-if="waiver" class="waiver-detail">
        <div class="waiver-detail__head">
          <span class="t-code">{{ waiver.ruleCode }}</span>
          <span class="waiver-detail__rule">{{ waiver.ruleName || waiver.ruleCode }}</span>
          <StatusPill kind="waiver" :value="waiver.status" />
          <WaiverBadge :status="waiver.status" :expiry-at="waiver.expiryAt" />
        </div>
        <dl class="waiver-detail__grid">
          <div><dt>版本</dt><dd>{{ releaseName || `#${waiver.releaseId}` }}</dd></div>
          <div><dt>申请人</dt><dd>{{ waiver.applicantName || '—' }}</dd></div>
          <div><dt>到期日</dt><dd>{{ waiver.expiryAt || '—' }}</dd></div>
          <div><dt>申请时间</dt><dd>{{ waiver.createdAt || '—' }}</dd></div>
        </dl>
        <div class="waiver-detail__block">
          <span class="waiver-detail__label">豁免原因</span>
          <p>{{ waiver.reason }}</p>
        </div>
        <div class="waiver-detail__block">
          <span class="waiver-detail__label">影响范围</span>
          <p>{{ waiver.impactScope || '未填写' }}</p>
        </div>
      </div>
      <p v-else class="waiver-empty">没有待审批的豁免。</p>

      <div class="waiver-field">
        <label class="waiver-field__label" for="waiver-comment">审批意见</label>
        <el-input
          id="waiver-comment"
          v-model="comment"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          placeholder="驳回必须填写意见；通过可选填"
        />
        <p class="waiver-field__hint">不能审批自己提交的豁免（服务端 403 SCOPE_DENIED）。</p>
      </div>

      <div class="waiver-actions">
        <el-button :disabled="busy" @click="visible = false">关闭</el-button>
        <el-button
          type="danger"
          plain
          :loading="busy"
          :disabled="!waiver || waiver.status !== 'PENDING'"
          @click="reject"
        >
          驳回
        </el-button>
        <el-button
          type="primary"
          :loading="busy"
          :disabled="!waiver || waiver.status !== 'PENDING'"
          @click="approve"
        >
          通过
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.waiver-error {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 10px;
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  font-size: 12px;
  color: var(--text-body);
}
.waiver-error__code {
  flex: none;
  font-family: var(--font-mono);
  color: var(--danger-ink);
}
.waiver-context {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 10px;
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  font-size: 12px;
  color: var(--text-body);
}
.waiver-context__label {
  color: var(--text-muted-aa);
}
.waiver-context__threshold {
  margin-left: auto;
  color: var(--text-muted-aa);
}
.waiver-field {
  margin-bottom: 14px;
}
.waiver-field__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title);
}
.waiver-field__req {
  margin-left: 2px;
  color: var(--color-danger);
}
.waiver-field__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.waiver-field__unit {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.waiver-field--inline {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}
.waiver-expiry {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.waiver-expiry strong {
  font-size: 14px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.waiver-option__code {
  margin-right: 8px;
  color: var(--text-muted-aa);
}
.waiver-option__name {
  font-size: 12px;
}
.waiver-option__tag {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-weak);
}
.waiver-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid var(--divider);
}
.waiver-disabled-reason {
  margin: 8px 0 0;
  text-align: right;
  font-size: 12px;
  color: var(--text-weak);
}
.waiver-detail {
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}
.waiver-detail__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.waiver-detail__rule {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
}
.waiver-detail__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin: 10px 0;
}
.waiver-detail__grid dt {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.waiver-detail__grid dd {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-title);
}
.waiver-detail__block {
  padding-top: 8px;
  border-top: 1px solid var(--divider);
}
.waiver-detail__label {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.waiver-detail__block p {
  margin: 4px 0 8px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.waiver-empty {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.is-invalid :deep(.el-textarea__inner) {
  border-color: var(--color-warning);
}
</style>
