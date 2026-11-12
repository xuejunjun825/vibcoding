<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Dataset, DatasetField, FieldKind, ReportDefinition, ReportFilter, ReportFilterOp } from '@/api/analytics'
import { ElMessage } from 'element-plus'
import {
  addField,
  FILTER_OPS,
  normalizeFilterValue,
  removeField,
  REPORT_LIMITS,
  SLOT_LABEL,
  slotLimit,
  type SlotKey,
} from '@/components/reports/limits'

/**
 * 左字段池 + 中四槽（AC-12 / AC-13）。
 *
 * 交互只有三种：拖拽入槽、双击入默认槽、槽内移除/排序。合法性统一走 `limits.ts`
 * 的纯函数，编辑器本身不判断限额——这样「超限不发请求」只有一处实现。
 */
const props = defineProps<{ modelValue: ReportDefinition; dataset: Dataset | null }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: ReportDefinition): void }>()

const SLOTS: SlotKey[] = ['rows', 'columns', 'measures', 'filters']

const search = ref('')
const hoverSlot = ref<SlotKey | null>(null)

const fields = computed<DatasetField[]>(() => props.dataset?.fields ?? [])

const filteredFields = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return fields.value
  return fields.value.filter(
    (field) => field.label.toLowerCase().includes(keyword) || field.key.toLowerCase().includes(keyword),
  )
})

const GROUPS: Array<{ kind: FieldKind; label: string }> = [
  { kind: 'DIM', label: '维度' },
  { kind: 'TIME', label: '时间' },
  { kind: 'MEASURE', label: '度量' },
]

function groupFields(kind: FieldKind): DatasetField[] {
  return filteredFields.value.filter((field) => field.kind === kind)
}

function labelOf(key: string): string {
  return fields.value.find((field) => field.key === key)?.label ?? key
}

function update(def: ReportDefinition): void {
  emit('update:modelValue', def)
}

function add(slot: SlotKey, field: DatasetField): void {
  const result = addField(props.modelValue, slot, field)
  if (!result.ok) {
    ElMessage.warning(result.reason ?? '该字段不能放入此槽位')
    return
  }
  update(result.def)
}

/** 双击入默认槽：度量进「值」，维度/时间进行。 */
function addDefault(field: DatasetField): void {
  add(field.kind === 'MEASURE' ? 'measures' : 'rows', field)
}

function onDragStart(event: DragEvent, field: DatasetField): void {
  event.dataTransfer?.setData('text/plain', field.key)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

function onDrop(event: DragEvent, slot: SlotKey): void {
  hoverSlot.value = null
  const key = event.dataTransfer?.getData('text/plain')
  if (!key) return
  const field = fields.value.find((item) => item.key === key)
  if (!field) return
  add(slot, field)
}

function onDragOver(slot: SlotKey): void {
  hoverSlot.value = slot
}

/** dragleave 会在子元素间冒泡触发，用 relatedTarget 判断是否真的离开了槽位（否则高亮闪烁）。 */
function onDragLeave(event: DragEvent, slot: SlotKey): void {
  const next = event.relatedTarget as Node | null
  if (next && (event.currentTarget as HTMLElement).contains(next)) return
  if (hoverSlot.value === slot) hoverSlot.value = null
}

function remove(slot: SlotKey, key: string): void {
  update(removeField(props.modelValue, slot, key))
}

function move(slot: 'rows' | 'columns' | 'measures', index: number, delta: number): void {
  const list = [...props.modelValue[slot]]
  const next = index + delta
  if (next < 0 || next >= list.length) return
  const [item] = list.splice(index, 1)
  list.splice(next, 0, item)
  update({ ...props.modelValue, [slot]: list })
}

function addFilter(field: DatasetField): void {
  add('filters', field)
}

function patchFilter(index: number, patch: Partial<ReportFilter>): void {
  const filters = props.modelValue.filters.map((filter, position) =>
    position === index ? { ...filter, ...patch } : filter,
  )
  update({ ...props.modelValue, filters })
}

function filterValueText(value: string | string[]): string {
  return Array.isArray(value) ? value.join(',') : value
}

function isEmptyFilter(filter: ReportFilter): boolean {
  return filterValueText(filter.value).trim().length === 0
}

function setFilterValue(index: number, raw: string, op: ReportFilterOp): void {
  patchFilter(index, { value: raw, op })
}

/** 失焦时把 IN 归一成数组、其它算子去空白（与 limits.normalizeFilterValue 同一实现）。 */
function normalizeOnBlur(index: number): void {
  const filter = props.modelValue.filters[index]
  if (!filter) return
  patchFilter(index, { value: normalizeFilterValue(filter.op, filterValueText(filter.value)) })
}
</script>

<template>
  <div class="sb" data-testid="slot-board">
    <aside class="sb__pool" data-testid="field-pool">
      <div class="sb__pool-head">
        <span class="sb__title">字段</span>
        <span class="an-hint">拖拽或双击添加</span>
      </div>
      <el-input v-model="search" size="small" placeholder="搜索字段" clearable class="sb__search" />
      <p v-if="!props.dataset" class="an-hint">先选择一个数据集，字段池才会出现。</p>
      <template v-else>
        <div v-for="group in GROUPS" :key="group.kind" class="sb__group">
          <p class="sb__group-title">{{ group.label }}</p>
          <div class="sb__fields">
            <button
              v-for="field in groupFields(group.kind)"
              :key="field.key"
              type="button"
              class="sb__field"
              :draggable="true"
              :data-field="field.key"
              @dragstart="onDragStart($event, field)"
              @dblclick="addDefault(field)"
            >
              {{ field.label }}
            </button>
            <span v-if="!groupFields(group.kind).length" class="an-hint">无匹配字段</span>
          </div>
        </div>
      </template>
    </aside>

    <section class="sb__slots">
      <div
        v-for="slot in SLOTS"
        :key="slot"
        class="an-slot"
        :class="{ 'an-slot--over': hoverSlot === slot }"
        :data-slot="slot"
        :data-testid="`slot-${slot}`"
        @dragover.prevent="onDragOver(slot)"
        @dragleave="onDragLeave($event, slot)"
        @drop.prevent="onDrop($event, slot)"
      >
        <header class="an-slot__head">
          <span class="an-slot__title">{{ SLOT_LABEL[slot] }}</span>
          <span class="an-hint">{{ (slot === 'filters' ? props.modelValue.filters.length : (props.modelValue[slot] as string[]).length) }}/{{ slotLimit(slot) }}</span>
        </header>

        <p v-if="slot === 'filters' && !props.modelValue.filters.length" class="an-hint">
          拖入维度/时间做筛选（{{
            FILTER_OPS.map((item) => item.label).join(' ')
          }}）
        </p>

        <ul v-if="slot !== 'filters'" class="an-slot__items">
          <li
            v-for="(key, index) in props.modelValue[slot] as string[]"
            :key="key"
            class="an-slot__chip"
          >
            <span class="an-slot__label">{{ labelOf(key) }}</span>
            <span class="an-slot__ops">
              <button type="button" title="上移" @click="move(slot as 'rows' | 'columns' | 'measures', index, -1)">↑</button>
              <button type="button" title="下移" @click="move(slot as 'rows' | 'columns' | 'measures', index, 1)">↓</button>
              <button type="button" title="移除" class="an-slot__remove" @click="remove(slot, key)">×</button>
            </span>
          </li>
          <li v-if="!(props.modelValue[slot] as string[]).length" class="an-hint">
            {{ slot === 'measures' ? `至少 1 个，最多 ${REPORT_LIMITS.measures} 个` : '拖入字段' }}
          </li>
        </ul>

        <ul v-else class="an-slot__filters">
          <li v-for="(filter, index) in props.modelValue.filters" :key="filter.field" class="an-slot__filter">
            <span class="an-slot__label">{{ labelOf(filter.field) }}</span>
            <el-select
              :model-value="filter.op"
              size="small"
              class="an-slot__op"
              @update:model-value="(value: ReportFilterOp) => patchFilter(index, { op: value })"
            >
              <el-option v-for="op in FILTER_OPS" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <el-input
              :model-value="filterValueText(filter.value)"
              size="small"
              :placeholder="filter.op === 'IN' ? '逗号分隔多个值' : '值'"
              class="an-slot__value"
              :class="{ 'an-slot__value--empty': isEmptyFilter(filter) }"
              @update:model-value="(value: string) => setFilterValue(index, value, filter.op)"
              @blur="normalizeOnBlur(index)"
            />
            <button type="button" class="an-slot__remove" title="移除" @click="remove('filters', filter.field)">×</button>
          </li>
          <li class="an-hint">
            最多 {{ REPORT_LIMITS.filters }} 个；用「筛选」区的维度字段下拉可直接加一行
            <el-select
              size="small"
              placeholder="+ 添加筛选"
              class="an-slot__add"
              :model-value="undefined"
              @update:model-value="(key: string) => { const f = fields.find((item) => item.key === key); if (f) addFilter(f) }"
            >
              <el-option
                v-for="field in fields.filter((item) => item.kind !== 'MEASURE')"
                :key="field.key"
                :label="field.label"
                :value="field.key"
              />
            </el-select>
          </li>
        </ul>
      </div>
      <p class="an-hint sb__limits">
        行 ≤{{ REPORT_LIMITS.rows }} · 列 ≤{{ REPORT_LIMITS.columns }} · 行+列 ≤{{ REPORT_LIMITS.dims }} ·
        值 1–{{ REPORT_LIMITS.measures }} · 筛选 ≤{{ REPORT_LIMITS.filters }}；超限不会发请求
      </p>
    </section>
  </div>
</template>

<style scoped>
.sb {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
}

.sb__pool {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  background: #fff;
  min-width: 0;
}

.sb__pool-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.sb__title {
  font-size: 13px;
  font-weight: 600;
}

.sb__search {
  margin-bottom: 8px;
}

.sb__group {
  margin-bottom: 8px;
}

.sb__group-title {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.sb__fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sb__field {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  cursor: grab;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sb__field:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.sb__slots {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.an-slot__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.an-slot__title {
  font-size: 13px;
  font-weight: 600;
}

.an-slot__items,
.an-slot__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.an-slot__chip,
.an-slot__filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: #f7f9fc;
  font-size: 12px;
}

.an-slot__label {
  font-weight: 500;
}

.an-slot__ops {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.an-slot__ops button,
.an-slot__remove {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--text-muted-aa, #6b7280);
  font-size: 12px;
  line-height: 16px;
  padding: 0 2px;
}

.an-slot__ops button:hover,
.an-slot__remove:hover {
  color: var(--color-primary);
}

.an-slot__op {
  width: 78px;
}

.an-slot__value {
  width: 150px;
}

/* 新增筛选默认空值：用告警色标出「还没填」，保存前由 limits.definitionProblems 拦下 */
.an-slot__value--empty :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--color-warning, #e6a23c) inset;
}

.an-slot__add {
  width: 130px;
  margin-left: 4px;
}

.sb__limits {
  margin: 0;
}
</style>
