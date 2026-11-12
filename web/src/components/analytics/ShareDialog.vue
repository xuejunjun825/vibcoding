<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DictionaryDomain, DictionaryVendor } from '@/api/dictionaries'
import { loadDictionaries } from '@/api/dictionaries'
import { shareReport } from '@/api/analytics'
import type { ReportDetail, ReportScope } from '@/api/analytics'
import { ApiError } from '@/api/client'

/**
 * 共享弹层（AC-10 / D5）：五档固定顺序 + 顶部固定提示语。
 *
 * 只共享「定义」，数据永远按查看者 Scope 重新聚合；因此提示语必须原样出现，
 * 不能由三个调用页各写一句。UI 只展示五个业务档位，`TENANT` 是 `PUBLIC` 的
 * 历史别名（见 `api/analytics.ts` 注释），任何界面文案都不得出现该词。
 *
 * 字典失败时（403/网络）只会拿到空表——下拉禁用并页内提示，绝不跳 `/403`：
 * `loadDictionaries()` 三项各自降级，不会把弹层变成错误页。
 */
const props = defineProps<{ modelValue: boolean; report: ReportDetail | null }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'saved', scope: ReportScope): void
}>()

interface ScopeOption {
  value: ReportScope
  label: string
  hint: string
}

/** 顺序即后端 `ShareVisibility` 的五档顺序（TENANT 只作为 PUBLIC 的入参别名存在于 SDK） */
const SCOPE_OPTIONS: ReadonlyArray<ScopeOption> = [
  { value: 'PRIVATE', label: '私有', hint: '仅自己可见' },
  { value: 'ROLE', label: '角色', hint: '同一角色的人可见' },
  { value: 'DOMAIN', label: '业务域', hint: '该业务域内的人可见' },
  { value: 'VENDOR', label: '厂商', hint: '该厂商的人可见' },
  { value: 'PUBLIC', label: '公开', hint: '整个租户可见' },
]

const TIP = '共享的是报表定义，数据始终按查看者的可见范围重新聚合'
const ROLE_HINT = '后端暂无角色字典（G-2），请填写数字角色 ID'

const scope = ref<ReportScope>('PRIVATE')
const scopeRef = ref<number | null>(null)
const submitting = ref(false)
const errorText = ref('')

const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])
const dictState = ref<'idle' | 'loading' | 'ready'>('idle')

const needRef = computed(() => scope.value === 'ROLE' || scope.value === 'DOMAIN' || scope.value === 'VENDOR')

const refOptions = computed<Array<{ value: number; label: string }>>(() => {
  if (scope.value === 'DOMAIN') return domains.value.map((item) => ({ value: item.id, label: item.name }))
  if (scope.value === 'VENDOR') return vendors.value.map((item) => ({ value: item.id, label: item.name }))
  return []
})

/** 只有「已加载且当前档位的字典为空」才算不可用；加载中不误报 */
const dictHint = computed(() => {
  if (dictState.value !== 'ready') return ''
  if (scope.value === 'DOMAIN' && !domains.value.length) {
    return '业务域字典不可用或当前账号无可见业务域，域共享暂不可选（不会跳转）'
  }
  if (scope.value === 'VENDOR' && !vendors.value.length) {
    return '厂商字典不可用或当前账号无可见厂商，厂商共享暂不可选（不会跳转）'
  }
  return ''
})

const isFirstOption = (value: ReportScope): boolean => SCOPE_OPTIONS[0].value === value

async function ensureDictionaries(): Promise<void> {
  if (dictState.value !== 'idle') return
  dictState.value = 'loading'
  const bundle = await loadDictionaries()
  domains.value = bundle.domains
  vendors.value = bundle.vendors
  dictState.value = 'ready'
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    errorText.value = ''
    submitting.value = false
    scope.value = props.report?.scope ?? 'PRIVATE'
    const raw = props.report?.scopeRef ?? ''
    const parsed = Number(raw)
    scopeRef.value = Number.isInteger(parsed) && parsed > 0 ? parsed : null
    void ensureDictionaries()
  },
  { immediate: true },
)

function selectScope(value: ReportScope): void {
  if (value !== scope.value) {
    // 换档位必须清掉旧主体的 id（域 id 不能带到厂商档），避免「看起来填了、发出去是另一类 id」
    scopeRef.value = null
    errorText.value = ''
  }
  scope.value = value
}

function onRoleId(value: number | null | undefined): void {
  scopeRef.value = typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null
}

function onSelectRef(value: number): void {
  scopeRef.value = typeof value === 'number' ? value : null
}

function close(): void {
  emit('update:modelValue', false)
}

function validateRef(): string {
  if (!needRef.value) return ''
  const id = scopeRef.value
  if (id === null || !Number.isInteger(id) || id <= 0) {
    if (scope.value === 'ROLE') return '请填写正整数角色 ID（例如 3）'
    return scope.value === 'DOMAIN' ? '请选择业务域' : '请选择厂商'
  }
  return ''
}

function describeError(cause: unknown): string {
  if (!(cause instanceof ApiError)) return String(cause)
  if (cause.isForbidden) return '你没有共享该报表的权限（需属主且持有 REPORT_SHARE），本次修改未生效'
  if (cause.code === 'REPORT_NOT_FOUND') return '报表不存在或已被删除'
  return cause.message || '共享失败，请稍后重试'
}

async function submit(): Promise<void> {
  const report = props.report
  if (!report || submitting.value) return
  const invalid = validateRef()
  if (invalid) {
    errorText.value = invalid
    return
  }
  submitting.value = true
  errorText.value = ''
  try {
    await shareReport(report.id, {
      scope: scope.value,
      scopeRef: needRef.value && scopeRef.value !== null ? String(scopeRef.value) : null,
    })
    emit('saved', scope.value)
    close()
  } catch (cause) {
    errorText.value = describeError(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    width="520px"
    :close-on-click-modal="false"
    data-testid="share-dialog"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <template #header>
      <div class="share__head">
        <span class="share__title">共享报表</span>
        <span v-if="props.report" class="share__name" :title="props.report.name">{{ props.report.name }}</span>
      </div>
    </template>

    <p class="share__tip">{{ TIP }}</p>

    <div class="share__scopes" role="radiogroup" aria-label="共享范围">
      <label
        v-for="option in SCOPE_OPTIONS"
        :key="option.value"
        class="share__scope"
        :class="{ 'share__scope--active': scope === option.value }"
      >
        <input
          class="share__radio"
          type="radio"
          name="report-scope"
          :value="option.value"
          :checked="scope === option.value"
          @change="selectScope(option.value)"
        />
        <span class="share__scope-label">{{ option.label }}</span>
        <span class="share__scope-hint">{{ option.hint }}</span>
        <span v-if="isFirstOption(option.value) && props.report?.scope === option.value" class="share__scope-badge">当前</span>
      </label>
    </div>

    <div v-if="needRef" class="share__ref">
      <template v-if="scope === 'ROLE'">
        <el-input-number
          :model-value="scopeRef"
          class="share__control"
          :min="1"
          :precision="0"
          :controls="false"
          placeholder="角色 ID"
          data-testid="share-role-id"
          @update:model-value="onRoleId"
        />
        <span class="share__hint">{{ ROLE_HINT }}</span>
      </template>
      <template v-else>
        <el-select
          :model-value="scopeRef ?? undefined"
          class="share__control"
          :placeholder="scope === 'DOMAIN' ? '选择业务域' : '选择厂商'"
          :disabled="dictState !== 'ready' || !refOptions.length"
          :loading="dictState === 'loading'"
          filterable
          data-testid="share-scope-ref"
          @update:model-value="onSelectRef"
        >
          <el-option v-for="item in refOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <span v-if="dictHint" class="share__hint share__hint--warn" data-testid="share-dict-hint">{{ dictHint }}</span>
        <span v-else-if="dictState === 'loading'" class="share__hint">字典加载中…</span>
      </template>
    </div>

    <el-alert
      v-if="errorText"
      class="share__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorText"
      data-testid="share-error"
    />

    <template #footer>
      <el-button size="small" @click="close">取消</el-button>
      <el-button
        size="small"
        type="primary"
        :loading="submitting"
        :disabled="!props.report"
        data-testid="share-submit"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.share__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
}

.share__name {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share__tip {
  margin: 0 0 10px;
  padding: 8px 10px;
  background: var(--color-primary-light);
  border-radius: var(--r-ctl);
  color: var(--text-body);
  font-size: 12px;
  line-height: 18px;
}

.share__scopes {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.share__scope {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  cursor: pointer;
  font-size: 13px;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}

.share__scope:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.share__scope:has(.share__radio:focus-visible) {
  border-color: var(--color-primary);
  box-shadow: var(--focus-ring);
}

.share__scope--active,
.share__scope--active:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}

.share__radio {
  accent-color: var(--color-primary);
  flex: none;
  margin: 0;
}

.share__scope-label {
  min-width: 52px;
  font-weight: 500;
  color: var(--text-title);
}

.share__scope-hint {
  flex: 1;
  min-width: 0;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.share__scope-badge {
  flex: none;
  padding: 0 6px;
  height: 18px;
  border-radius: var(--r-pill);
  background: var(--bg-card);
  color: var(--color-primary);
  font-size: 11px;
  line-height: 18px;
}

.share__ref {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.share__control {
  width: 220px;
}

.share__hint {
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 18px;
}

.share__hint--warn {
  flex-basis: 100%;
  padding: 6px 8px;
  background: var(--color-warning-bg);
  border-radius: var(--r-ctl);
  color: var(--warning-ink);
}

.share__error {
  margin-top: 10px;
}
</style>
