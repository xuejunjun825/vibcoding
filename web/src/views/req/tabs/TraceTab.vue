<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { traceDetail } from '@/api/deliver'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import { messageOf, releasePath } from '@/views/req/platform'

/**
 * P2 · 追溯 tab（AC-15）：需求 → 任务 → MR → 测试 → 缺陷 → 发布 → 版本 六环。
 * 缺口与「齐备」在同一份数据上计算：cells 里 state=GAP 即缺口，不接受 single `complete` 布尔。
 */
const props = defineProps<{ requirementId: number; releaseId?: number | null }>()
const emit = defineEmits<{ (event: 'open', tab: string): void }>()

const router = useRouter()

type CellState = 'COMPLETE' | 'GAP' | 'NA'

interface StageCell {
  key: string
  label: string
  state: CellState
  detail: string
}

const ORDER: Array<{ key: string; label: string }> = [
  { key: 'task', label: '任务' },
  { key: 'mr', label: 'MR' },
  { key: 'test', label: '测试' },
  { key: 'defect', label: '缺陷' },
  { key: 'release', label: '发布' },
  { key: 'version', label: '版本' },
]

const raw = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const errorMessage = ref('')

function cellStateOf(value: unknown): CellState {
  if (value == null) return 'NA'
  if (typeof value === 'string') {
    return value === 'COMPLETE' || value === 'GAP' || value === 'NA' ? value : 'NA'
  }
  const record = value as { state?: unknown; complete?: unknown; gaps?: unknown }
  if (record.state === 'COMPLETE' || record.state === 'GAP' || record.state === 'NA') return record.state
  if (typeof record.complete === 'boolean') return record.complete ? 'COMPLETE' : 'GAP'
  if (Array.isArray(record.gaps)) return record.gaps.length ? 'GAP' : 'COMPLETE'
  return 'NA'
}

function cellDetailOf(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return ''
  const record = value as { label?: unknown; count?: unknown; detail?: unknown }
  if (typeof record.detail === 'string') return record.detail
  if (typeof record.label === 'string') return record.label
  if (typeof record.count === 'number') return `${record.count} 项`
  return ''
}

const stages = computed<StageCell[]>(() => {
  const body = raw.value ?? {}
  const cells = (body.cells ?? body.stages ?? {}) as Record<string, unknown>
  return ORDER.map(({ key, label }) => {
    const value = cells[key] ?? (body[key] as unknown)
    return { key, label, state: cellStateOf(value), detail: cellDetailOf(value) }
  })
})

const gaps = computed(() => stages.value.filter((stage) => stage.state === 'GAP'))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    raw.value = await traceDetail(props.requirementId)
  } catch (error) {
    raw.value = null
    errorMessage.value = messageOf(error)
  } finally {
    loading.value = false
  }
}

function stateText(state: CellState): string {
  return state === 'COMPLETE' ? '✓ 齐备' : state === 'GAP' ? '! 缺口' : '– 不适用'
}

function jump(stage: StageCell): void {
  if (stage.key === 'task') emit('open', 'tasks')
  else if (stage.key === 'test') emit('open', 'testing')
  else if (stage.key === 'defect') emit('open', 'defects')
  else if (stage.key === 'release' && props.releaseId != null) {
    void router.push(releasePath(props.releaseId))
  } else if (stage.key === 'version' && props.releaseId != null) {
    void router.push(releasePath(props.releaseId))
  } else if (stage.key === 'mr') {
    emit('open', 'overview')
  }
}

onMounted(load)
</script>

<template>
  <div class="trace">
    <div v-if="loading" class="skeleton skeleton__row" />
    <ErrorState v-else-if="errorMessage" compact title="追溯数据暂不可用" :message="errorMessage" @retry="load" />

    <EmptyState
      v-else-if="!raw"
      title="还没有追溯数据"
      description="任务、执行、缺陷产生后，六环链路会自动汇总到这里。"
      icon="chart"
      compact
    />

    <template v-else>
      <section class="surface surface--pad">
        <header class="trace__head">
          <h3 class="trace__title">六环链路</h3>
          <span class="trace__hint">缺口与「齐备」在同一份数据上计算，不采信单一 complete 布尔</span>
        </header>
        <div class="trace__chain">
          <template v-for="(stage, index) in stages" :key="stage.key">
            <button
              type="button"
              class="trace__cell"
              :class="[`is-${stage.state.toLowerCase()}`]"
              :title="`${stage.label}：${stateText(stage.state)}${stage.detail ? ` · ${stage.detail}` : ''}`"
              :aria-label="`${stage.label} ${stateText(stage.state)}`"
              @click="jump(stage)"
            >
              <span class="trace__label">{{ stage.label }}</span>
              <span class="trace__state">{{ stateText(stage.state) }}</span>
              <span class="trace__detail">{{ stage.detail || '\u00a0' }}</span>
            </button>
            <span v-if="index < stages.length - 1" class="trace__arrow" aria-hidden="true">→</span>
          </template>
        </div>
      </section>

      <section class="surface surface--pad">
        <header class="trace__head">
          <h3 class="trace__title">缺口</h3>
          <span class="trace__hint">{{ gaps.length ? `${gaps.length} 个环节缺失，逐项补齐后整行才会显示齐备` : '六环齐备' }}</span>
        </header>
        <ul v-if="gaps.length" class="trace__gaps">
          <li v-for="gap in gaps" :key="gap.key" class="trace__gap">
            <span class="trace__gap-name">{{ gap.label }}</span>
            <span class="trace__gap-detail">{{ gap.detail || '该环节还没有记录' }}</span>
            <el-button text type="primary" size="small" @click="jump(gap)">去补齐 →</el-button>
          </li>
        </ul>
        <p v-else class="trace__ok">任务、MR、测试、缺陷、发布、版本六个环节均有记录。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.trace {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.trace__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.trace__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.trace__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.trace__chain {
  display: flex;
  align-items: stretch;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.trace__cell {
  flex: 1 1 0;
  min-width: 112px;
  height: 74px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.trace__cell:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.trace__cell:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.trace__cell.is-gap {
  border-left: 3px solid var(--color-warning);
  background: var(--color-warning-bg);
}
.trace__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.trace__state {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.trace__cell.is-gap .trace__state {
  color: var(--warning-ink);
}
.trace__cell.is-complete .trace__state {
  color: var(--success-ink);
}
.trace__cell.is-na .trace__state {
  color: var(--text-muted-aa);
}
.trace__detail {
  font-size: 11px;
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace__arrow {
  align-self: center;
  color: var(--text-weak);
  font-size: 12px;
}
.trace__gaps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trace__gap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 4px 12px;
  border: 1px solid var(--border);
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-cell);
  background: var(--color-warning-bg);
  font-size: 12px;
}
.trace__gap-name {
  font-weight: 600;
  color: var(--warning-ink);
  min-width: 48px;
}
.trace__gap-detail {
  flex: 1;
  color: var(--text-body);
}
.trace__ok {
  margin: 0;
  font-size: 12px;
  color: var(--success-ink);
}
</style>
