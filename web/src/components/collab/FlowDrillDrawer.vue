<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchFlowTimeline, type FlowDrillRow, type FlowTimelineRow } from '@/api/collab'

/**
 * 阶段下钻抽屉（DESIGN §6 `FlowDrillDrawer`）：720 宽、行 56，列表 → 阶段时间线。
 *
 * 时间线来自 `GET /flow/requirements/{id}/timeline`；`noData` 的行显式写「无数据」，
 * 不显示 0 天——缺口被当成 0 会让 P90 与瓶颈看起来比真实更好。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    stageCode?: string
    stageName?: string
    rows?: FlowDrillRow[]
    total?: number
    page?: number
    size?: number
    loading?: boolean
    error?: string
    window?: { from?: string | null; to?: string | null }
  }>(),
  {
    stageCode: '',
    stageName: '',
    rows: () => [],
    total: 0,
    page: 1,
    size: 8,
    loading: false,
    error: '',
    window: () => ({}),
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'page-change', page: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const active = ref<FlowDrillRow | null>(null)
const timeline = ref<FlowTimelineRow[]>([])
const timelineLoading = ref(false)
const timelineError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil((props.total ?? 0) / (props.size || 8))))

watch(visible, (open) => {
  if (!open) {
    active.value = null
    timeline.value = []
    timelineError.value = ''
  }
})

async function openTimeline(row: FlowDrillRow): Promise<void> {
  active.value = row
  timelineLoading.value = true
  timelineError.value = ''
  timeline.value = []
  try {
    timeline.value = await fetchFlowTimeline(row.id, { from: props.window?.from, to: props.window?.to })
  } catch (error) {
    timelineError.value = error instanceof Error ? error.message : '时间线加载失败'
  } finally {
    timelineLoading.value = false
  }
}

function back(): void {
  active.value = null
  timeline.value = []
  timelineError.value = ''
}

function days(value?: number | null): string {
  if (value === null || value === undefined) return '无数据'
  return `${Number(value).toFixed(1)} 天`
}

function stamp(value?: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <el-drawer v-model="visible" :size="720" direction="rtl" :destroy-on-close="false" class="fdd">
    <template #header>
      <div class="fdd__head">
        <button v-if="active" type="button" class="fdd__back" @click="back">← 返回列表</button>
        <span class="fdd__title">
          {{ active ? `${active.code} · ${active.title}` : `${stageName || stageCode} 下钻` }}
        </span>
        <span class="fdd__count">{{ active ? '阶段时间线' : `共 ${total} 条` }}</span>
      </div>
    </template>

    <div v-if="!active" class="fdd__list">
      <div v-if="error" class="fdd__error" role="alert">{{ error }}</div>
      <div v-if="loading" class="fdd__skeleton" aria-busy="true">
        <div v-for="n in 6" :key="n" class="fdd__skeleton-row" />
      </div>
      <template v-else>
        <button
          v-for="row in rows"
          :key="row.id"
          type="button"
          class="fdd__row"
          @click="openTimeline(row)"
        >
          <span class="fdd__row-main">
            <span class="fdd__code">{{ row.code }}</span>
            <span class="fdd__row-title" :title="row.title">{{ row.title }}</span>
          </span>
          <span class="fdd__row-meta">
            <span class="fdd__status">{{ row.status }}</span>
            <span class="fdd__entered">进入 {{ stamp(row.enteredAt) }}</span>
          </span>
          <span class="fdd__dwell" :class="{ 'fdd__dwell--na': row.dwellDays === null || row.dwellDays === undefined }">
            {{ days(row.dwellDays) }}
          </span>
        </button>
        <div v-if="!rows.length" class="fdd__empty">
          <p>该阶段在当前窗口内没有条目</p>
          <p class="fdd__empty-hint">窗口内没有任何阶段审计时，指标会显示「—」而不是 0。</p>
        </div>
      </template>

      <div v-if="totalPages > 1" class="fdd__pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          background
          @current-change="(p: number) => emit('page-change', p)"
        />
      </div>
    </div>

    <div v-else class="fdd__timeline">
      <div v-if="timelineLoading" class="fdd__skeleton" aria-busy="true">
        <div v-for="n in 5" :key="n" class="fdd__skeleton-row" />
      </div>
      <div v-else-if="timelineError" class="fdd__error" role="alert">{{ timelineError }}</div>
      <ol v-else class="fdd__steps">
        <li
          v-for="(step, index) in timeline"
          :key="`${step.stageCode}-${index}`"
          class="fdd__step"
          :class="{ 'fdd__step--nodata': step.noData }"
        >
          <span class="fdd__dot" aria-hidden="true" />
          <span class="fdd__step-stage">{{ step.stageCode }}</span>
          <span class="fdd__step-time">{{ stamp(step.enteredAt) }} → {{ stamp(step.leftAt) }}</span>
          <span class="fdd__step-dwell">{{ step.noData ? '无数据' : days(step.dwellDays) }}</span>
          <span class="fdd__step-src" :title="step.sourceEvent ?? ''">{{ step.sourceEvent || '—' }}</span>
        </li>
        <li v-if="!timeline.length" class="fdd__step fdd__step--empty">该条目在窗口内没有阶段记录</li>
      </ol>
      <p v-if="timeline.some((s) => s.noData)" class="fdd__note">
        「无数据」表示该阶段缺少审计事件，未计入平均停留与 P90；不会按 0 天参与计算。
      </p>
    </div>
  </el-drawer>
</template>

<style scoped>
.fdd__head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.fdd__back {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.fdd__back:hover {
  text-decoration: underline;
}
.fdd__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fdd__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-weak);
  white-space: nowrap;
}
.fdd__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  height: 100%;
}
.fdd__row {
  flex: none;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: background var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.fdd__row:hover {
  background: var(--color-primary-light);
  border-color: var(--border);
}
.fdd__row:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.fdd__row-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.fdd__code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted-aa);
}
.fdd__row-title {
  font-size: 13px;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fdd__row-meta {
  flex: none;
  width: 200px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--text-weak);
}
.fdd__status {
  color: var(--text-secondary);
}
.fdd__entered {
  font-variant-numeric: tabular-nums;
}
.fdd__dwell {
  flex: none;
  width: 72px;
  text-align: right;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.fdd__dwell--na {
  color: var(--warning-ink);
}
.fdd__error {
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.fdd__empty {
  padding: 48px 0;
  text-align: center;
  color: var(--text-weak);
  font-size: 13px;
}
.fdd__empty-hint {
  font-size: 12px;
}
.fdd__pager {
  flex: none;
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
}
.fdd__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fdd__skeleton-row {
  height: 48px;
  border-radius: var(--r-ctl);
  background: var(--bg-hover);
  animation: fdd-pulse 1.2s ease-in-out infinite;
}
@keyframes fdd-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
.fdd__steps {
  list-style: none;
  margin: 0;
  padding: 0 0 0 4px;
}
.fdd__step {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 8px 8px 8px 22px;
  border-left: 2px solid var(--border);
}
.fdd__step:last-child {
  border-left-color: transparent;
}
.fdd__step--nodata {
  background: repeating-linear-gradient(135deg, #fafbfc 0 6px, #f4f5f7 6px 12px);
}
.fdd__dot {
  position: absolute;
  left: -5px;
  top: 50%;
  width: 8px;
  height: 8px;
  margin-top: -4px;
  border-radius: 50%;
  background: var(--color-primary);
  border: 2px solid var(--bg-card);
}
.fdd__step--nodata .fdd__dot {
  background: var(--border-strong);
}
.fdd__step-stage {
  flex: none;
  width: 132px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fdd__step-time {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 11px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}
.fdd__step-dwell {
  flex: none;
  width: 76px;
  text-align: right;
  font-size: 12px;
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
}
.fdd__step--nodata .fdd__step-dwell {
  color: var(--warning-ink);
}
.fdd__step-src {
  flex: none;
  width: 160px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: right;
}
.fdd__step--empty {
  justify-content: center;
  border-left-color: transparent;
  color: var(--text-weak);
  font-size: 12px;
}
.fdd__note {
  margin: 16px 0 0;
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-muted-aa);
}
</style>
