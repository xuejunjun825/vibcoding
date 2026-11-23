<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createRequirementChange,
  listReleases,
  previewRequirementChange,
  searchRequirements,
} from '@/api/req'
import type { ReleaseRow, RequirementDetail, RequirementRow } from '@/api/types'
import type { ChangeCreated, ChangePreview, ChangeType } from '@/api/req'
import ChangeDiffTable from '@/components/req/ChangeDiffTable.vue'
import ChangeImpactCards from '@/components/req/ChangeImpactCards.vue'
import ErrorState from '@/components/ErrorState.vue'
import {
  CHANGE_TYPES,
  changeErrorText,
  fieldLabel,
  typeMeta,
  typeLabel,
} from '@/views/req/change'
import { PRIORITIES } from '@/views/req/platform'

/**
 * 四步变更申请抽屉（720，DESIGN §3.2）：类型 → 原因 → 字段 → 影响预览。
 *
 * 第 ④ 步的影响**必须**来自服务端 `preview`（与创建共用 validate+assess），
 * 前端不拼「受影响发布单数」——否则预览与冻结快照会漂移。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    requirementId: number
    detail?: RequirementDetail | null
    /** 409 CHANGE_REQUIRED / 编辑抽屉预填：类型与要改的字段 */
    presetType?: ChangeType | null
    presetFields?: string[]
  }>(),
  { detail: null, presetType: null, presetFields: () => [] },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'created', payload: ChangeCreated): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const step = ref(1)
const changeType = ref<ChangeType | null>(null)
const reason = ref('')
const patch = reactive<Record<string, unknown>>({})
const preview = ref<ChangePreview | null>(null)
const previewError = ref('')
const previewLoading = ref(false)
const submitting = ref(false)
const submitError = ref('')
const dirty = ref(false)

const fields = computed(() => (changeType.value ? typeMeta(changeType.value)?.fields ?? [] : []))
const changedFields = computed(() => fields.value.filter((field) => diffField(field)))

/** 父需求人天由子需求汇总：DAYS 类型直接禁用并给原因，不让人白填一遍 */
const dayTypeDisabled = computed(() => (props.detail?.children?.length ?? 0) > 0)
function typeDisabled(type: ChangeType): string {
  return type === 'DAYS' && dayTypeDisabled.value ? '父需求人天由子需求汇总，不能单独修改' : ''
}

/* ---------------- 字段当前值与草稿 ---------------- */

function current(field: string): unknown {
  const detail = props.detail
  if (!detail) return null
  switch (field) {
    case 'title':
      return detail.title ?? ''
    case 'description':
      return detail.description ?? ''
    case 'priority':
      return detail.priority ?? ''
    case 'expectedDeliveryDate':
      return detail.expectedDeliveryDate ?? null
    case 'estimateDays':
      return detail.estimateDays ?? null
    case 'releaseId':
      return detail.releaseId ?? null
    case 'parentId':
      return detail.parentId ?? null
    default:
      return null
  }
}

function same(a: unknown, b: unknown): boolean {
  if (a === null || a === undefined) return b === null || b === undefined || b === ''
  if (b === null || b === undefined) return a === ''
  if (typeof a === 'string' && typeof b === 'string') return a.trim() === b.trim()
  return String(a) === String(b)
}

/** 该字段是否真的改了：改为当前值 = 不提交（服务端也会判「没有任何字段变化」） */
function diffField(field: string): boolean {
  if (!(field in patch)) return false
  return !same(patch[field], current(field))
}

function draft(field: string): unknown {
  return field in patch ? patch[field] : current(field)
}

/** 当前值文案：空值统一「空」，不能留一片空白让人以为没读到 */
function currentText(field: string): string {
  const value = current(field)
  if (value === null || value === undefined) return '空'
  const text = String(value).trim()
  return text === '' ? '空' : text
}

function setField(field: string, value: unknown): void {
  patch[field] = value
  dirty.value = true
}

/* ---------------- 版本 / 父需求候选 ---------------- */

const releaseOptions = ref<ReleaseRow[]>([])
const releasesLoaded = ref(false)
const releaseLoading = ref(false)
async function ensureReleases(): Promise<void> {
  if (releasesLoaded.value) return
  releaseLoading.value = true
  try {
    const page = await listReleases({ size: 100, domainId: props.detail?.domainId ?? undefined })
    releaseOptions.value = page.items ?? []
    releasesLoaded.value = true
  } catch {
    // 版本候选拉不到不阻断：可手填 id，最终仍以服务端校验为准
    releaseOptions.value = []
  } finally {
    releaseLoading.value = false
  }
}

const parentOptions = ref<RequirementRow[]>([])
const parentLoading = ref(false)
let parentTimer: ReturnType<typeof setTimeout> | null = null
function searchParent(keyword: string): void {
  if (parentTimer) clearTimeout(parentTimer)
  parentTimer = setTimeout(async () => {
    parentLoading.value = true
    try {
      parentOptions.value = keyword ? await searchRequirements(keyword) : []
    } catch {
      parentOptions.value = []
    } finally {
      parentLoading.value = false
    }
  }, 300)
}

/* ---------------- 步骤 ---------------- */

const canNext = computed(() => {
  if (step.value === 1) return changeType.value !== null
  if (step.value === 2) return reason.value.trim().length > 0 && reason.value.length <= 500
  if (step.value === 3) return changedFields.value.length > 0 && titleValid.value
  return Boolean(preview.value) && !previewLoading.value
})

const titleValid = computed(() => {
  if (changeType.value !== 'CONTENT' || !diffField('title')) return true
  return String(patch.title ?? '').trim().length > 0
})

const nextLabel = computed(() => (step.value === 4 ? '提交审批' : '下一步'))

function selectType(type: ChangeType): void {
  if (typeDisabled(type)) return
  changeType.value = type
  dirty.value = true
  if (type === 'RELEASE') void ensureReleases()
}

function goNext(): void {
  if (!canNext.value) return
  if (step.value < 4) {
    step.value += 1
    if (step.value === 4) void loadPreview()
    return
  }
  void submit()
}

function goPrev(): void {
  if (step.value > 1) step.value -= 1
}

async function loadPreview(): Promise<void> {
  if (!changeType.value) return
  previewLoading.value = true
  previewError.value = ''
  try {
    preview.value = await previewRequirementChange(props.requirementId, {
      changeType: changeType.value,
      reason: reason.value.trim(),
      patch: { ...patch },
    })
  } catch (error) {
    preview.value = null
    previewError.value = changeErrorText(error).text
  } finally {
    previewLoading.value = false
  }
}

async function submit(): Promise<void> {
  if (!changeType.value || submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    const created = await createRequirementChange(props.requirementId, {
      changeType: changeType.value,
      reason: reason.value.trim(),
      patch: { ...patch },
    })
    emit('created', created)
    // 成功文案必须写「尚未生效」：审批通过只是 APPROVED，还要显式应用
    ElMessage.success('已提交审批，尚未生效')
    open.value = false
  } catch (error) {
    // 400/409 就地渲染：保留当前步骤与全部输入，绝不 toast 后清空
    submitError.value = changeErrorText(error).text
  } finally {
    submitting.value = false
  }
}

/* ---------------- 开关与重置 ---------------- */

function reset(): void {
  step.value = 1
  changeType.value = null
  reason.value = ''
  for (const key of Object.keys(patch)) delete patch[key]
  preview.value = null
  previewError.value = ''
  previewLoading.value = false
  submitError.value = ''
  dirty.value = false
}

function applyPreset(): void {
  if (!props.presetType) return
  changeType.value = props.presetType
  if (props.presetType === 'RELEASE') void ensureReleases()
  if (props.presetFields.length) {
    for (const field of props.presetFields) {
      if (typeMeta(props.presetType)?.fields.includes(field)) patch[field] = current(field)
    }
    step.value = 3
  } else {
    step.value = 2
  }
}

watch(
  () => props.modelValue,
  (value) => {
    if (!value) return
    reset()
    applyPreset()
  },
)

watch(
  () => props.presetType,
  () => {
    if (props.modelValue) {
      reset()
      applyPreset()
    }
  },
)

async function requestClose(): Promise<void> {
  if (!dirty.value) {
    open.value = false
    return
  }
  try {
    await ElMessageBox.confirm('放弃已填写的内容？', '关闭抽屉', {
      confirmButtonText: '放弃',
      cancelButtonText: '继续填写',
      type: 'warning',
    })
    open.value = false
  } catch {
    // 用户选择继续填写：什么都不做
  }
}

/** ✕ / ESC / 遮罩统一走这里：有改动先问一次，问完才真的关 */
function beforeClose(done: () => void): void {
  if (!dirty.value) {
    done()
    return
  }
  ElMessageBox.confirm('放弃已填写的内容？', '关闭抽屉', {
    confirmButtonText: '放弃',
    cancelButtonText: '继续填写',
    type: 'warning',
  })
    .then(() => done())
    .catch(() => undefined)
}

const stepTitle = computed(() => {
  switch (step.value) {
    case 1:
      return '选择变更类型'
    case 2:
      return '填写变更原因'
    case 3:
      return '修改字段'
    default:
      return '确认影响并提交'
  }
})

const submitHint = computed(() => {
  if (step.value !== 4) return ''
  if (!preview.value) return '影响预览未就绪，不能提交'
  return `将提交 ${changedFields.value.length} 项字段`
})

const code = computed(() => props.detail?.code ?? `#${props.requirementId}`)
</script>

<template>
  <el-drawer
    v-model="open"
    :size="720"
    :close-on-click-modal="false"
    append-to-body
    destroy-on-close
    class="change-drawer"
    :before-close="beforeClose"
    @closed="reset"
  >
    <template #header>
      <div class="change-drawer__head">
        <span class="change-drawer__title">发起需求变更</span>
        <span class="change-drawer__code mono">{{ code }}</span>
      </div>
    </template>

    <nav class="change-steps" aria-label="变更申请步骤">
      <div
        v-for="index in 4"
        :key="index"
        class="change-steps__item"
        :class="{ 'is-current': step === index, 'is-done': step > index }"
        :aria-current="step === index ? 'step' : undefined"
      >
        <span class="change-steps__no">{{ step > index ? '✓' : index }}</span>
        <span class="change-steps__label">{{ ['类型', '原因', '字段', '影响'][index - 1] }}</span>
      </div>
    </nav>

    <h3 class="change-drawer__step-title">{{ stepTitle }}</h3>

    <!-- ① 类型 -->
    <section v-if="step === 1" class="change-grid" role="radiogroup" aria-label="变更类型">
      <button
        v-for="item in CHANGE_TYPES"
        :key="item.type"
        type="button"
        role="radio"
        class="type-card"
        :class="{ 'is-active': changeType === item.type, 'is-disabled': Boolean(typeDisabled(item.type)) }"
        :aria-checked="changeType === item.type"
        :disabled="Boolean(typeDisabled(item.type))"
        :title="typeDisabled(item.type)"
        @click="selectType(item.type)"
      >
        <span class="type-card__label">{{ typeLabel(item.type) }}</span>
        <span class="type-card__hint">{{ typeDisabled(item.type) || item.hint }}</span>
        <span v-if="changeType === item.type" class="type-card__check" aria-hidden="true">✓ 已选</span>
      </button>
    </section>

    <!-- ② 原因 -->
    <section v-else-if="step === 2">
      <label class="field-label" for="change-reason">变更原因（写清「为什么改」，会进审批与审计）</label>
      <el-input
        id="change-reason"
        v-model="reason"
        type="textarea"
        :rows="5"
        maxlength="500"
        show-word-limit
        placeholder="例：联调发现对账口径调整，交付时间需提前两周"
      />
    </section>

    <!-- ③ 字段 -->
    <section v-else-if="step === 3" class="field-rows">
      <p v-if="changedFields.length === 0" class="change-hint">没有任何字段变化——改一个字段才能提交</p>
      <div v-for="field in fields" :key="field" class="field-row" :class="{ 'is-changed': diffField(field) }">
        <div class="field-row__label">{{ fieldLabel(field) }}</div>
        <div class="field-row__current" :title="currentText(field)">{{ currentText(field) }}</div>
        <div class="field-row__arrow" aria-hidden="true">→</div>
        <div class="field-row__control">
          <el-input
            v-if="field === 'title'"
            :model-value="String(draft('title') ?? '')"
            maxlength="200"
            show-word-limit
            @update:model-value="(value: string) => setField('title', value)"
          />
          <el-input
            v-else-if="field === 'description'"
            :model-value="String(draft('description') ?? '')"
            type="textarea"
            :rows="4"
            maxlength="4000"
            @update:model-value="(value: string) => setField('description', value)"
          />
          <el-select
            v-else-if="field === 'priority'"
            :model-value="String(draft('priority') ?? 'P2')"
            class="control--narrow"
            @update:model-value="(value: string) => setField('priority', value)"
          >
            <el-option v-for="item in PRIORITIES" :key="item" :label="item" :value="item" />
          </el-select>
          <el-date-picker
            v-else-if="field === 'expectedDeliveryDate'"
            :model-value="(draft('expectedDeliveryDate') as string | null)"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择期望交付日"
            class="control--narrow"
            @update:model-value="(value: string | null) => setField('expectedDeliveryDate', value)"
          />
          <el-input-number
            v-else-if="field === 'estimateDays'"
            :model-value="(draft('estimateDays') as number | null)"
            :min="0"
            :step="0.5"
            :precision="1"
            class="control--narrow"
            @update:model-value="(value: number | null) => setField('estimateDays', value)"
          />
          <el-select
            v-else-if="field === 'releaseId'"
            :model-value="draft('releaseId') as number | null"
            filterable
            clearable
            :loading="releaseLoading"
            class="control--narrow"
            placeholder="选择目标版本"
            @update:model-value="(value: number | null) => setField('releaseId', value)"
          >
            <el-option
              v-for="item in releaseOptions"
              :key="item.id"
              :label="`${item.name}（${item.status}）`"
              :value="item.id"
            />
          </el-select>
          <el-select
            v-else-if="field === 'parentId'"
            :model-value="draft('parentId') as number | null"
            filterable
            remote
            clearable
            :remote-method="searchParent"
            :loading="parentLoading"
            class="control--narrow"
            placeholder="搜索父需求编号或标题"
            @update:model-value="(value: number | null) => setField('parentId', value)"
          >
            <el-option
              v-for="item in parentOptions"
              :key="item.id"
              :label="`${item.code} ${item.title}`"
              :value="item.id"
            />
          </el-select>
          <p v-if="field === 'title' && !titleValid" class="change-hint change-hint--error">标题不能为空</p>
        </div>
      </div>
    </section>

    <!-- ④ 影响 -->
    <section v-else class="preview">
      <p v-if="changedFields.length === 0" class="change-hint">没有任何字段变化——返回上一步改一个字段</p>
      <ErrorState
        v-else-if="previewError"
        compact
        title="影响预览失败"
        :message="previewError"
        @retry="loadPreview"
      />
      <template v-else>
        <ChangeImpactCards :impact="preview?.impact ?? null" mode="preview" :loading="previewLoading" />
        <div class="preview__diff">
          <h4 class="preview__title">字段对照</h4>
          <ChangeDiffTable
            v-if="preview"
            :before="preview.before"
            :after="preview.after"
            :fields="preview.fields"
            compact
          />
          <div v-else class="skeleton skeleton__row" />
        </div>
        <p class="preview__note">
          审批将走两级：产品负责人 → 域负责人。通过后仍需在本页点「应用变更」才写入需求字段。
        </p>
      </template>
    </section>

    <p v-if="submitError" class="change-hint change-hint--error" role="alert">{{ submitError }}</p>

    <template #footer>
      <div class="change-footer">
        <span class="change-footer__hint">{{ submitHint || `已选类型：${changeType ? typeLabel(changeType) : '未选'}` }}</span>
        <div class="change-footer__actions">
          <el-button size="small" @click="requestClose">取消</el-button>
          <el-button v-if="step > 1" size="small" @click="goPrev">上一步</el-button>
          <el-button type="primary" size="small" :disabled="!canNext || submitting" :loading="submitting" @click="goNext">
            {{ submitting ? '提交中…' : nextLabel }}
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.change-drawer__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.change-drawer__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
}

.change-drawer__code {
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-steps {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0;
  margin-bottom: 20px;
}

.change-steps__item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-steps__no {
  width: 24px;
  height: 24px;
  border-radius: var(--r-pill);
  background: var(--bg-page);
  color: var(--text-muted-aa);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  transition: background 120ms var(--ease), color 120ms var(--ease);
}

.change-steps__item.is-current {
  color: var(--text-title);
  font-weight: 600;
}

.change-steps__item.is-current .change-steps__no {
  background: var(--color-primary);
  color: #fff;
}

.change-steps__item.is-done .change-steps__no {
  background: var(--color-success-bg);
  color: var(--success-ink);
}

.change-drawer__step-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}

.change-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.type-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 76px;
  padding: 12px 14px;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  cursor: pointer;
  transition: border-color 120ms var(--ease), background 120ms var(--ease);
}

.type-card:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.type-card.is-active {
  border-color: var(--color-primary);
  box-shadow: inset 0 0 0 1px var(--color-primary);
  background: var(--bg-selected);
}

.type-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.type-card.is-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.type-card__label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}

.type-card__hint {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}

.type-card__check {
  position: absolute;
  top: 10px;
  right: 12px;
  font-size: 11px;
  color: var(--color-primary);
}

.field-label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-body);
}

.field-rows {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-row {
  display: grid;
  grid-template-columns: var(--change-label-w) var(--w-change-current) var(--w-change-arrow) minmax(0, 1fr);
  align-items: start;
  gap: 8px;
}

.field-row__label {
  font-size: 12px;
  line-height: 22px;
  color: var(--text-muted-aa);
}

.field-row__current {
  padding: 6px 10px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.field-row__arrow {
  font-size: 13px;
  line-height: 22px;
  text-align: center;
  color: var(--text-weak);
}

.field-row.is-changed .field-row__label {
  color: var(--color-primary);
}

.control--narrow {
  width: 220px;
}

.change-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-hint--error {
  color: var(--danger-ink);
}

.preview {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.preview__title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}

.preview__note {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-muted-aa);
}

.change-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.change-footer__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-footer__actions {
  display: flex;
  gap: 8px;
}

@media (max-width: 1280px) {
  .field-row {
    grid-template-columns: var(--change-label-w) minmax(0, 1fr);
  }

  .field-row__arrow {
    display: none;
  }
}
</style>
