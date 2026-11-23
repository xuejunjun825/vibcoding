<script setup lang="ts">
import { computed, ref } from 'vue'
import { fieldLabel, formatDaysDelta } from '@/views/req/change'

/**
 * 变更前后对照（DESIGN §3.3）：
 * 证据不能省略号——值换行显示，最长 6 行后给「展开全文」，不用 ellipsis 截断。
 * 视觉重量只给「变更后」值（13/600），字段名与箭头退到辅助灰。
 */
const props = withDefaults(
  defineProps<{
    before: Record<string, unknown>
    after: Record<string, unknown>
    fields: string[]
    /** 抽屉第④步的紧凑变体：行距更小、默认全部展开 */
    compact?: boolean
  }>(),
  { compact: false },
)

const expanded = ref<Set<string>>(new Set())

function toggle(field: string): void {
  const next = new Set(expanded.value)
  if (next.has(field)) next.delete(field)
  else next.add(field)
  expanded.value = next
}

function raw(value: unknown): unknown {
  return value === undefined ? null : value
}

function textOf(field: string, value: unknown): string {
  const rawValue = raw(value)
  if (rawValue === null) return '空'
  if (field === 'estimateDays' && typeof rawValue === 'number') return `${rawValue.toFixed(1)} 天`
  if (field === 'priority') return String(rawValue)
  if (typeof rawValue === 'boolean') return rawValue ? '是' : '否'
  if (Array.isArray(rawValue)) {
    if (rawValue.length === 0) return '空'
    return rawValue.map((item) => (typeof item === 'object' ? JSON.stringify(item) : String(item))).join('、')
  }
  if (typeof rawValue === 'object') return JSON.stringify(rawValue, null, 0)
  const text = String(rawValue)
  return text === '' ? '空' : text
}

/** 长值才出「展开全文」：短值多一个按钮只是噪声 */
function clampable(field: string, value: unknown): boolean {
  if (props.compact) return false
  const text = textOf(field, value)
  return text.length > 160 || text.split('\n').length > 3
}

const rows = computed(() =>
  props.fields.map((field) => {
    const before = raw(props.before?.[field])
    const after = raw(props.after?.[field])
    const delta =
      field === 'estimateDays' && typeof before === 'number' && typeof after === 'number'
        ? after - before
        : null
    return {
      field,
      label: fieldLabel(field),
      before: textOf(field, before),
      after: textOf(field, after),
      delta,
      changed: textOf(field, before) !== textOf(field, after),
    }
  }),
)
</script>

<template>
  <div class="diff" :class="{ 'diff--compact': props.compact }" data-test="change-diff">
    <div class="diff__head" aria-hidden="true">
      <span>字段</span>
      <span>变更前</span>
      <span />
      <span>变更后</span>
    </div>
    <div v-for="row in rows" :key="row.field" class="diff__row" :class="{ 'is-changed': row.changed }">
      <div class="diff__field" :title="row.field">{{ row.label }}</div>
      <div class="diff__value diff__value--before" :class="{ 'is-clamped': clampable(row.field, before[row.field]) && !expanded.has(row.field) }">
        {{ row.before }}
      </div>
      <div class="diff__arrow" aria-hidden="true">→</div>
      <div class="diff__value diff__value--after">
        <span :class="{ 'is-clamped': clampable(row.field, after[row.field]) && !expanded.has(row.field) }">{{ row.after }}</span>
        <button
          v-if="clampable(row.field, after[row.field])"
          type="button"
          class="diff__more"
          @click="toggle(row.field)"
        >
          {{ expanded.has(row.field) ? '收起' : '展开全文' }}
        </button>
        <span v-if="row.delta !== null" class="diff__delta mono">{{ formatDaysDelta(row.delta) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diff {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.diff__head,
.diff__row {
  display: grid;
  grid-template-columns: var(--w-change-field) minmax(0, 1fr) var(--w-change-arrow) minmax(0, 1fr);
  align-items: start;
  column-gap: 8px;
  padding: 10px 12px;
}

.diff__head {
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-muted-aa);
  padding-top: 7px;
  padding-bottom: 7px;
}

.diff__row + .diff__row {
  border-top: 1px solid var(--divider);
}

.diff__field {
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 20px;
  padding-top: 1px;
}

.diff__value {
  font-size: 13px;
  line-height: 20px;
  color: var(--text-body);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.diff__value--after {
  color: var(--text-title);
  font-weight: 600;
}

.diff__value .is-clamped {
  display: -webkit-box;
  -webkit-line-clamp: 6;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.diff__arrow {
  color: var(--text-weak);
  font-size: 13px;
  line-height: 20px;
  text-align: center;
}

.diff__more {
  display: block;
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}

.diff__more:hover {
  color: var(--color-primary-hover);
  text-decoration: underline;
}

.diff__delta {
  display: inline-block;
  margin-top: 2px;
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
  font-size: 12px;
  font-weight: 500;
}

.diff--compact .diff__head,
.diff--compact .diff__row {
  padding: 6px 10px;
}

@media (max-width: 1280px) {
  .diff__head,
  .diff__row {
    grid-template-columns: var(--w-change-field) minmax(0, 1fr) var(--w-change-arrow) minmax(0, 1fr);
  }
}
</style>
