<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AdmissionItemView } from '@/api/integration'

/**
 * 准入项表（DESIGN I2 / §6.3）。
 *
 * 两列事实：**必开项**（服务端 locked）与**可关项**。区别不是"灰不灰"，而是
 * 能不能改 —— 必开项给锁图标 + 解释（关闭会 409 ITEM_LOCKED），可关项给 switch，
 * 关闭前把影响逐条列出来（关闭 ≠ 跳过：该项不再计 PASS/FAIL/SKIP，分母仍是 9）。
 */
const props = withDefaults(
  defineProps<{
    items: AdmissionItemView[]
    readonly?: boolean
    /** 正在提交的项 code（行内 12px 转圈） */
    saving?: string | null
    /** 提交失败的项 → 行内红字 */
    errors?: Record<string, string>
  }>(),
  { readonly: false, saving: null, errors: () => ({}) },
)

const emit = defineEmits<{
  (event: 'toggle', code: string, enabled: boolean): void
}>()

const expanded = ref<Record<string, boolean>>({})
/** 正在二次确认关闭的行（同一时刻只允许一行） */
const confirming = ref('')

const rows = computed(() => props.items)

function admCode(item: AdmissionItemView): string {
  return `ADM-${String(item.order).padStart(2, '0')}`
}

function lockedHint(item: AdmissionItemView): string {
  return `必开项：${item.name} 是发布红线，关闭会返回 409 ITEM_LOCKED`
}

/** 关闭影响：写清楚会发生什么，而不是"确定要关闭吗" */
function impactOf(item: AdmissionItemView): string[] {
  return [
    `准入第 ${item.order} 项将记 CLOSED（ITEM_OFF），不再计入通过/失败/跳过`,
    '分母仍为 9：确定性不受影响，但这项证据不再被检查',
    `如需恢复：把「${item.name}」重新打开即可（无需重启服务）`,
  ]
}

function onToggle(item: AdmissionItemView, next: string | number | boolean): void {
  const value = Boolean(next)
  // 打开总是安全的；关闭必须先看到影响清单（模板里的 popover 已挡，这里是第二道）
  if (props.readonly || props.saving === item.code) return
  emit('toggle', item.code, value)
}

/** 关闭二次确认：影响逐条列出，确认前不发出任何写请求 */
function confirmClose(item: AdmissionItemView): void {
  confirming.value = ''
  onToggle(item, false)
}

function rowId(code: string): string {
  return `adm-impact-${code}`
}

function toggleExpand(code: string): void {
  expanded.value = { ...expanded.value, [code]: !expanded.value[code] }
}
</script>

<template>
  <ul class="admtable" role="list">
    <li class="admtable__head" role="presentation">
      <span>准入项</span>
      <span>代码</span>
      <span class="admtable__head-switch">开关</span>
      <span>说明</span>
    </li>
    <li
      v-for="item in rows"
      :key="item.code"
      class="admtable__row"
      :class="{ 'admtable__row--closed': !item.enabled, 'admtable__row--locked': item.locked }"
      :style="{ minHeight: expanded[item.code] ? 'var(--h-admission-row-open)' : 'var(--h-admission-row)' }"
    >
      <div class="admtable__name-cell">
        <span class="admtable__order" aria-hidden="true">{{ item.order }}</span>
        <span class="admtable__name">{{ item.name }}</span>
      </div>
      <code class="admtable__code">{{ admCode(item) }}</code>
      <div class="admtable__switch">
        <el-tooltip v-if="item.locked" :content="lockedHint(item)" placement="top" :show-after="150">
          <span class="admtable__locked" :aria-describedby="rowId(item.code)">
            <svg viewBox="0 0 12 12" width="11" height="11" aria-hidden="true" focusable="false">
              <path
                d="M3.6 5V3.9a2.4 2.4 0 0 1 4.8 0V5M2.8 5h6.4v4.4H2.8z"
                fill="none"
                stroke="currentColor"
                stroke-width="1.1"
                stroke-linejoin="round"
              />
            </svg>
            必开
          </span>
        </el-tooltip>
        <el-popover
          v-else-if="item.enabled && !readonly"
          :visible="confirming === item.code"
          :width="320"
          placement="left"
          trigger="click"
          @update:visible="(visible: boolean) => (confirming = visible ? item.code : '')"
        >
          <template #reference>
            <span class="admtable__switch-wrap">
              <el-switch
                :model-value="item.enabled"
                :loading="saving === item.code"
                :aria-label="`关闭准入项 ${item.name}（需确认）`"
              />
            </span>
          </template>
          <div class="admtable__confirm">
            <p class="admtable__confirm-title">关闭「{{ item.name }}」？</p>
            <ul class="admtable__confirm-list">
              <li v-for="line in impactOf(item)" :key="line">{{ line }}</li>
            </ul>
            <div class="admtable__confirm-actions">
              <el-button size="small" @click="confirming = ''">取消</el-button>
              <el-button
                size="small"
                type="danger"
                :loading="saving === item.code"
                @click="confirmClose(item)"
              >
                关闭此项
              </el-button>
            </div>
          </div>
        </el-popover>
        <el-switch
          v-else
          :model-value="item.enabled"
          :disabled="readonly || item.locked || saving === item.code"
          :loading="saving === item.code"
          :aria-label="`${item.enabled ? '关闭' : '开启'}准入项 ${item.name}`"
          :aria-describedby="rowId(item.code)"
          @change="(next: string | number | boolean) => onToggle(item, next)"
        />
        <span v-if="item.locked" :id="rowId(item.code)" class="admtable__sr">{{ lockedHint(item) }}</span>
      </div>
      <div class="admtable__desc-cell">
        <p class="admtable__desc" :title="item.description || undefined">{{ item.description || '—' }}</p>
        <button
          v-if="!item.locked"
          type="button"
          class="admtable__impact-toggle"
          :aria-expanded="!!expanded[item.code]"
          :aria-controls="rowId(item.code)"
          @click="toggleExpand(item.code)"
        >
          {{ expanded[item.code] ? '收起影响' : '关闭影响' }}
        </button>
        <p v-if="expanded[item.code]" :id="rowId(item.code)" class="admtable__impact">
          <span v-for="line in impactOf(item)" :key="line" class="admtable__impact-line">· {{ line }}</span>
        </p>
        <p v-if="errors[item.code]" class="admtable__err" role="alert">{{ errors[item.code] }}</p>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.admtable {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.admtable__head,
.admtable__row {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) 88px 96px minmax(240px, 2.4fr);
  align-items: start;
  gap: 12px;
  padding: 0 16px;
}

.admtable__head {
  align-items: center;
  height: 36px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-size: 12px;
}

.admtable__head-switch {
  text-align: left;
}

.admtable__row {
  align-items: center;
  padding-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--divider);
  transition: background var(--t-color) var(--ease);
}

.admtable__row:last-child {
  border-bottom: 0;
}

.admtable__row:hover {
  background: var(--bg-hover);
}

.admtable__row--closed {
  background: var(--intg-unconfigured-bg);
}

.admtable__name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.admtable__order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--color-info-bg);
  color: var(--text-secondary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.admtable__name {
  overflow: hidden;
  color: var(--text-title);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admtable__code {
  color: var(--text-muted-aa);
  font-family: var(--font-mono);
  font-size: 11px;
}

.admtable__switch {
  display: flex;
  align-items: center;
  gap: 6px;
}

.admtable__switch-wrap {
  display: inline-flex;
}

.admtable__locked {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-pill);
  color: var(--text-muted-aa);
  font-size: 11px;
  cursor: not-allowed;
}

.admtable__desc-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.admtable__desc {
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admtable__impact-toggle {
  align-self: flex-start;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-muted-aa);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
}

.admtable__impact-toggle:hover {
  color: var(--color-primary);
  text-decoration: underline;
}

.admtable__impact-toggle:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}

.admtable__impact {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin: 4px 0 0;
  padding: 6px 8px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-cell);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  line-height: 1.5;
}

.admtable__impact-line {
  display: block;
}

.admtable__err {
  margin: 2px 0 0;
  color: var(--danger-ink);
  font-size: 11px;
}

.admtable__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

/* 关闭二次确认：影响逐条列出，确认按钮才发出写请求 */
.admtable__confirm {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.admtable__confirm-title {
  margin: 0;
  color: var(--text-title);
  font-size: 13px;
  font-weight: 600;
}

.admtable__confirm-list {
  margin: 0;
  padding: 6px 8px 6px 20px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-cell);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  line-height: 1.55;
}

.admtable__confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 960px) {
  .admtable__head,
  .admtable__row {
    grid-template-columns: minmax(140px, 1.2fr) 80px 80px minmax(180px, 1.6fr);
  }
}
</style>
