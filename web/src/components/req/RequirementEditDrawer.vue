<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listReleases, searchRequirements, updateRequirement } from '@/api/req'
import type { CreateRequirementPayload, ReleaseRow, RequirementDetail, RequirementRow } from '@/api/types'
import {
  changeErrorText,
  changePermissionsOf,
  changeTypeOfField,
  fieldLabel,
} from '@/views/req/change'
import { PRIORITIES } from '@/views/req/platform'

/**
 * 需求编辑抽屉（560，DESIGN §3.4 / AC-13）。
 *
 * 哪些字段还能直改**只**由服务端 `permissions.editableFields` 决定（早期态表只有后端一份），
 * 前端不复制状态表：锁定 = 不在 editableFields 里，组内给「需发起变更」并预填类型。
 */
const props = defineProps<{ modelValue: boolean; detail: RequirementDetail | null }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'changed'): void
  (event: 'requestChange', payload: { type: ReturnType<typeof changeTypeOfField>; fields: string[] }): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

interface Group {
  key: 'content' | 'days' | 'release' | 'parent'
  label: string
  fields: string[]
}

const GROUPS: Group[] = [
  { key: 'content', label: '内容', fields: ['title', 'description', 'priority', 'expectedDeliveryDate'] },
  { key: 'days', label: '人天', fields: ['estimateDays'] },
  { key: 'release', label: '版本', fields: ['releaseId'] },
  { key: 'parent', label: '父需求', fields: ['parentId'] },
]

const draft = reactive<Record<string, unknown>>({})
const saving = ref(false)
const saveError = ref('')
const conflictFields = ref<string[]>([])

const permissions = computed(() => changePermissionsOf(props.detail))
const editable = computed(() => permissions.value.editableFields)

function isEditable(field: string): boolean {
  if (editable.value === null) return false
  // 父需求人天由子需求汇总：即使后端放行也不给改
  if (field === 'estimateDays' && (props.detail?.children?.length ?? 0) > 0) return false
  return editable.value.includes(field)
}

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

const changed = computed(() => {
  const keys = Object.keys(draft)
  return keys.filter((field) => isEditable(field) && !same(draft[field], current(field)))
})

const groupLocked = (group: Group): boolean => group.fields.every((field) => !isEditable(field))

/** 全部锁定时，「发起变更」升为唯一主键 */
const allLocked = computed(() => GROUPS.every((group) => groupLocked(group)))

/** 被锁字段跨了两个类型时提示「要分两次」——一次变更只能改一类字段 */
const spanningTypes = computed(() => {
  const types = new Set(
    GROUPS.flatMap((group) => group.fields)
      .filter((field) => !isEditable(field))
      .map((field) => changeTypeOfField(field))
      .filter(Boolean),
  )
  return types.size > 1
})

function reset(): void {
  for (const key of Object.keys(draft)) delete draft[key]
  const detail = props.detail
  if (!detail) return
  draft.title = detail.title ?? ''
  draft.description = detail.description ?? ''
  draft.priority = detail.priority ?? ''
  draft.expectedDeliveryDate = detail.expectedDeliveryDate ?? null
  draft.estimateDays = detail.estimateDays ?? null
  draft.releaseId = detail.releaseId ?? null
  draft.parentId = detail.parentId ?? null
  saveError.value = ''
  conflictFields.value = []
}

watch(
  () => props.modelValue,
  (value) => {
    if (value) reset()
  },
)

/* ---------------- 版本候选 ---------------- */

const releaseOptions = ref<ReleaseRow[]>([])
const releaseLoading = ref(false)
async function ensureReleases(): Promise<void> {
  if (releaseOptions.value.length) return
  releaseLoading.value = true
  try {
    const page = await listReleases({ size: 100, domainId: props.detail?.domainId ?? undefined })
    releaseOptions.value = page.items ?? []
  } catch {
    releaseOptions.value = []
  } finally {
    releaseLoading.value = false
  }
}

const parentOptions = ref<RequirementRow[]>([])
let parentTimer: ReturnType<typeof setTimeout> | null = null
function searchParent(keyword: string): void {
  if (parentTimer) clearTimeout(parentTimer)
  parentTimer = setTimeout(async () => {
    try {
      parentOptions.value = keyword ? await searchRequirements(keyword) : []
    } catch {
      parentOptions.value = []
    }
  }, 300)
}

function requestChange(type: ReturnType<typeof changeTypeOfField>, fields: string[]): void {
  emit('requestChange', { type, fields })
}

/** 整组锁定 → 用组里第一个字段的类型（同组同类型，内容组也只会是 CONTENT） */
function groupChangeType(group: Group): ReturnType<typeof changeTypeOfField> {
  return changeTypeOfField(group.fields[0])
}

async function save(): Promise<void> {
  const detail = props.detail
  if (!detail || !changed.value.length || saving.value) return
  saving.value = true
  saveError.value = ''
  conflictFields.value = []
  const payload: Record<string, unknown> = {}
  for (const field of changed.value) payload[field] = draft[field]
  try {
    // 只提交真正改了的字段；后端仍会按早期态与白名单复核
    await updateRequirement(detail.id, payload as Partial<CreateRequirementPayload>)
    ElMessage.success('已保存')
    open.value = false
    emit('changed')
  } catch (error) {
    const parsed = changeErrorText(error)
    if (parsed.code === 'CHANGE_REQUIRED') {
      // 409 不 toast 后清空：顶部 banner 点明被拦字段，并给出预填入口
      conflictFields.value = parsed.fields
      saveError.value = '这些字段已不能直接修改'
    } else {
      saveError.value = parsed.text
    }
  } finally {
    saving.value = false
  }
}

function requestFromConflict(): void {
  const fields = conflictFields.value.length ? conflictFields.value : changed.value
  const types = [...new Set(fields.map((field) => changeTypeOfField(field)).filter(Boolean))]
  requestChange(types[0] ?? null, fields)
}
</script>

<template>
  <el-drawer
    v-model="open"
    :size="560"
    title="编辑需求"
    append-to-body
    destroy-on-close
    class="req-edit-drawer"
  >
    <div v-if="!detail" class="edit-empty">加载中…</div>

    <template v-else>
      <p v-if="saveError" class="edit-banner" role="alert">
        <span>{{ saveError }}</span>
        <template v-if="conflictFields.length">
          ：{{ conflictFields.map((field) => fieldLabel(field)).join('、') }}
          <button type="button" class="edit-banner__link" @click="requestFromConflict">发起变更（预填）</button>
        </template>
      </p>

      <section v-for="group in GROUPS" :key="group.key" class="edit-group" :class="{ 'is-locked': groupLocked(group) }">
        <header class="edit-group__head">
          <h3 class="edit-group__title">{{ group.label }}</h3>
          <template v-if="groupLocked(group)">
            <span class="edit-group__locked">需发起变更</span>
            <button
              type="button"
              class="edit-group__link"
              @click="requestChange(groupChangeType(group), group.fields)"
            >
              发起变更
            </button>
          </template>
        </header>

        <div v-for="field in group.fields" :key="field" class="edit-field">
          <label class="edit-field__label" :for="`edit-${field}`">{{ fieldLabel(field) }}</label>
          <div class="edit-field__control">
            <el-input
              v-if="field === 'title'"
              :id="`edit-${field}`"
              :model-value="String(draft.title ?? '')"
              :disabled="!isEditable('title')"
              maxlength="200"
              show-word-limit
              @update:model-value="(value: string) => (draft.title = value)"
            />
            <el-input
              v-else-if="field === 'description'"
              :id="`edit-${field}`"
              :model-value="String(draft.description ?? '')"
              type="textarea"
              :rows="4"
              :disabled="!isEditable('description')"
              @update:model-value="(value: string) => (draft.description = value)"
            />
            <el-select
              v-else-if="field === 'priority'"
              :model-value="String(draft.priority ?? '')"
              :disabled="!isEditable('priority')"
              @update:model-value="(value: string) => (draft.priority = value)"
            >
              <el-option v-for="item in PRIORITIES" :key="item" :label="item" :value="item" />
            </el-select>
            <el-date-picker
              v-else-if="field === 'expectedDeliveryDate'"
              :model-value="(draft.expectedDeliveryDate as string | null)"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择期望交付日"
              :disabled="!isEditable('expectedDeliveryDate')"
              @update:model-value="(value: string | null) => (draft.expectedDeliveryDate = value)"
            />
            <el-input-number
              v-else-if="field === 'estimateDays'"
              :model-value="(draft.estimateDays as number | null)"
              :min="0"
              :step="0.5"
              :precision="1"
              :disabled="!isEditable('estimateDays')"
              @update:model-value="(value: number | null) => (draft.estimateDays = value)"
            />
            <el-select
              v-else-if="field === 'releaseId'"
              :model-value="(draft.releaseId as number | null)"
              filterable
              clearable
              :loading="releaseLoading"
              :disabled="!isEditable('releaseId')"
              placeholder="选择版本"
              @focus="ensureReleases"
              @update:model-value="(value: number | null) => (draft.releaseId = value)"
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
              :model-value="(draft.parentId as number | null)"
              filterable
              remote
              clearable
              :disabled="!isEditable('parentId')"
              :remote-method="searchParent"
              placeholder="搜索父需求"
              @update:model-value="(value: number | null) => (draft.parentId = value)"
            >
              <el-option
                v-for="item in parentOptions"
                :key="item.id"
                :label="`${item.code} ${item.title}`"
                :value="item.id"
              />
            </el-select>

            <p v-if="field === 'estimateDays' && (detail.children?.length ?? 0) > 0" class="edit-field__hint">
              父需求人天由子需求汇总
            </p>
          </div>
        </div>
      </section>

      <p v-if="spanningTypes" class="edit-note">
        被锁字段分属不同变更类型（内容 / 人天 / 版本 / 父需求），一次变更只能改一类，需要分别发起。
      </p>
    </template>

    <template #footer>
      <div class="edit-footer">
        <el-button size="small" @click="open = false">取消</el-button>
        <el-button
          v-if="allLocked"
          type="primary"
          size="small"
          @click="requestChange(groupChangeType(GROUPS[0]), GROUPS.flatMap((group) => group.fields))"
        >
          发起变更
        </el-button>
        <template v-else>
          <el-button
            v-if="!allLocked && GROUPS.some((group) => groupLocked(group))"
            size="small"
            @click="requestChange(groupChangeType(GROUPS.find((group) => groupLocked(group)) ?? GROUPS[0]), (GROUPS.find((group) => groupLocked(group)) ?? GROUPS[0]).fields)"
          >
            发起变更
          </el-button>
          <el-button
            v-if="changed.length"
            type="primary"
            size="small"
            :loading="saving"
            :disabled="saving"
            @click="save"
          >
            保存
          </el-button>
        </template>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.edit-empty {
  padding: 32px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted-aa);
}

.edit-banner {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: var(--r-cell);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 13px;
  line-height: 20px;
}

.edit-banner__link {
  border: 0;
  padding: 0;
  margin-left: 6px;
  background: none;
  color: var(--color-primary);
  font-size: 13px;
  text-decoration: underline;
  cursor: pointer;
}

.edit-group {
  margin-bottom: 20px;
}

.edit-group__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.edit-group__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}

.edit-group__locked {
  font-size: 12px;
  color: var(--color-warning-ink, var(--warning-ink));
}

.edit-group__link {
  border: 0;
  padding: 0;
  background: none;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}

.edit-group__link:hover {
  text-decoration: underline;
}

.edit-group.is-locked :deep(.el-input__wrapper),
.edit-group.is-locked :deep(.el-textarea__inner),
.edit-group.is-locked :deep(.el-select__wrapper) {
  background: var(--bg-hover);
}

.edit-field {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  margin-bottom: 10px;
}

.edit-field__label {
  font-size: 12px;
  line-height: 22px;
  color: var(--text-muted-aa);
}

.edit-field__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.edit-note {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}

.edit-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
