<script setup lang="ts">
/**
 * 代码质量卡（DESIGN R1 · AC-13）。
 *
 * 只读本地 Sonar 快照：打开页面零外呼。无快照时是虚线空态，
 * 绝不显示 0 / 0% / 绿色——没有数据不等于质量达标。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { refreshSonar } from '@/api/integration'
import type { SonarCard, SonarMetricKey } from '@/api/integration'
import '@/views/deliver/shared.css'

const props = withDefaults(
  defineProps<{
    card?: SonarCard | null
    emptyReason?: string | null
    loading?: boolean
    /** 传了才显示「刷新质量门」：这是唯一的外呼入口，打开页面仍然零外呼 */
    requirementId?: number | null
  }>(),
  { card: null, emptyReason: null, loading: false, requirementId: null },
)

const emit = defineEmits<{
  (event: 'updated', card: SonarCard): void
  (event: 'open-snapshot', card: SonarCard): void
}>()

const router = useRouter()
const refreshing = ref(false)
const refreshError = ref('')

async function refresh(): Promise<void> {
  if (!props.requirementId || refreshing.value) return
  refreshing.value = true
  refreshError.value = ''
  try {
    const card = await refreshSonar(props.requirementId)
    emit('updated', card)
    ElMessage.success('已同步 Sonar 快照')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    refreshError.value = apiError?.message || '同步失败：Sonar 未启用或不可达'
  } finally {
    refreshing.value = false
  }
}


interface MetricCell {
  key: SonarMetricKey
  label: string
  suffix: string
  digits: number
}

const CELLS: MetricCell[] = [
  { key: 'bugs', label: '缺陷', suffix: '', digits: 0 },
  { key: 'vulnerabilities', label: '漏洞', suffix: '', digits: 0 },
  { key: 'codeSmells', label: '坏味道', suffix: '', digits: 0 },
  { key: 'coverage', label: '覆盖率', suffix: '%', digits: 1 },
  { key: 'duplicatedLinesDensity', label: '重复率', suffix: '%', digits: 1 },
]

const metrics = computed(() => props.card?.metrics ?? null)

function displayValue(cell: MetricCell): string {
  const raw = metrics.value?.[cell.key]
  if (raw === null || raw === undefined) return '—'
  const numeric = Number(raw)
  if (!Number.isFinite(numeric)) return '—'
  return `${numeric.toFixed(cell.digits)}${cell.suffix}`
}

const statusTone = computed(() => {
  switch (props.card?.status) {
    case 'OK':
      return 'ok'
    case 'ERROR':
      return 'bad'
    default:
      return 'idle'
  }
})

const statusLabel = computed(() => {
  switch (props.card?.status) {
    case 'OK':
      return '门禁通过'
    case 'ERROR':
      return '门禁未通过'
    case 'UNBOUND':
      return '未绑定 Sonar 项目'
    case 'UNAVAILABLE':
      return 'Sonar 不可用'
    default:
      return '无快照'
  }
})

const emptyText = computed(() => {
  if (props.emptyReason === 'INTEGRATION_DISABLED') return 'Sonar 集成未启用，暂无可读快照'
  if (props.emptyReason === 'NO_SNAPSHOT') return '还没有质量快照：同步一次 Sonar 后这里才有数'
  return '暂无质量快照'
})

const emptyAction = computed(() => {
  if (props.emptyReason === 'INTEGRATION_DISABLED') return '去启用 Sonar'
  if (props.emptyReason === 'NO_SNAPSHOT') return '去配置 Sonar'
  return '去集成配置'
})

function goIntegration(): void {
  void router.push({ name: 'admin-integrations' })
}
</script>

<template>
  <section class="cq-card" aria-label="代码质量">
    <header class="cq-card__head">
      <h2 class="cq-card__title">代码质量</h2>
      <span class="cq-card__source">Sonar · 只读快照</span>
      <span v-if="card" class="cq-card__status" :class="`cq-card__status--${statusTone}`">{{ statusLabel }}</span>
      <span class="cq-card__spacer" />
      <el-button v-if="card" link type="primary" size="small" @click="emit('open-snapshot', card)">
        快照记录
      </el-button>
      <el-button
        v-if="requirementId"
        size="small"
        :loading="refreshing"
        title="这是唯一触发 Sonar 外呼的入口；打开页面不会外呼"
        @click="refresh"
      >
        刷新质量门
      </el-button>
    </header>

    <div v-if="loading" class="cq-card__skeleton" aria-busy="true">
      <span v-for="cell in CELLS" :key="cell.key" class="cq-skel" />
    </div>

    <template v-else-if="card">
      <div class="cq-metrics">
        <div v-for="cell in CELLS" :key="cell.key" class="cq-metric">
          <span class="cq-metric__label">{{ cell.label }}</span>
          <span class="cq-metric__value" :class="{ 'is-missing': displayValue(cell) === '—' }">
            {{ displayValue(cell) }}
          </span>
        </div>
      </div>

      <footer class="cq-card__foot">
        <span class="cq-card__project" :title="card.projectKey || ''">
          {{ card.projectKey || '未返回项目 key' }}
        </span>
        <span class="cq-card__time">快照 {{ card.fetchedAt ? card.fetchedAt : '—' }}</span>
        <span v-if="card.stale" class="cq-card__stale" title="快照超过刷新阈值未更新">可能过期</span>
      </footer>
      <p v-if="card.skipReason" class="cq-card__reason">{{ card.skipReason }}</p>
      <p v-if="refreshError" class="cq-card__error" role="alert">{{ refreshError }}</p>
    </template>

    <div v-else class="cq-empty">
      <p class="cq-empty__title">{{ emptyText }}</p>
      <p class="cq-empty__hint">没有数据时不显示 0 或绿色：口径由 Sonar 快照决定，页面不补数。</p>
      <el-button size="small" @click="goIntegration">{{ emptyAction }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.cq-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-card, 8px);
  background: var(--bg-card, #fff);
}

.cq-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cq-card__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}

.cq-card__source {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.cq-card__spacer {
  flex: 1;
}

.cq-card__error {
  margin: 0;
  padding: 5px 8px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
  font-size: 11px;
  line-height: 16px;
}

.cq-card__status {
  margin-left: auto;
  padding: 1px 8px;
  height: 20px;
  line-height: 18px;
  border-radius: var(--r-pill, 999px);
  border: 1px solid var(--border, #e5e6eb);
  background: var(--color-info-bg, #f2f3f5);
  color: var(--info-ink, #4e5969);
  font-size: 11px;
  white-space: nowrap;
}

.cq-card__status--ok {
  background: var(--color-success-bg, #e8f7ec);
  color: var(--success-ink, #157a38);
  border-color: color-mix(in srgb, var(--color-success, #1f9d45) 22%, transparent);
}

.cq-card__status--bad {
  background: var(--color-danger-bg, #fdecec);
  color: var(--danger-ink, #b3262e);
  border-color: color-mix(in srgb, var(--color-danger, #d9363e) 22%, transparent);
}

.cq-card__status--idle {
  background: var(--bg-table-head, #fafbfc);
  color: var(--text-muted-aa, #6b7280);
  border-style: dashed;
}

.cq-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.cq-metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 8px;
  border-radius: var(--r-ctl, 6px);
  background: var(--bg-table-head, #fafbfc);
  border: 1px solid var(--divider, #f2f3f5);
  text-align: center;
}

.cq-metric__label {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.cq-metric__value {
  font-size: 18px;
  line-height: 24px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
  font-variant-numeric: tabular-nums;
}

.cq-metric__value.is-missing {
  color: var(--text-muted-aa, #6b7280);
  font-weight: 500;
}

.cq-card__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.cq-card__project {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}

.cq-card__time {
  flex: none;
}

.cq-card__stale {
  flex: none;
  padding: 0 6px;
  height: 16px;
  line-height: 16px;
  border-radius: 3px;
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
}

.cq-card__reason {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.cq-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px;
  border: 1px dashed var(--border-strong, #c9cdd4);
  border-radius: var(--r-ctl, 6px);
  background: var(--bg-table-head, #fafbfc);
}

.cq-empty__title {
  margin: 0;
  font-size: 13px;
  color: var(--text-body, #4e5969);
}

.cq-empty__hint {
  margin: 0 0 4px;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-muted-aa, #6b7280);
}

.cq-card__skeleton {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.cq-skel {
  height: 62px;
  border-radius: var(--r-ctl, 6px);
  background: linear-gradient(90deg, #f4f6f9 25%, #eceff3 37%, #f4f6f9 63%);
  background-size: 400% 100%;
  animation: cq-skel 1.4s ease infinite;
}

@keyframes cq-skel {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cq-skel {
    animation: none;
  }
}
</style>
