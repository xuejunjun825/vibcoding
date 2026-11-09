<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '@/api/client'
import StatusPill from '@/components/StatusPill.vue'
import { refreshSonar, type SonarCard, type SonarMetricKey } from '@/api/integration'
import { formatDateTime } from '@/utils/format'

/**
 * 需求详情「集成」tab 的 Sonar 质量门卡片（DESIGN I4 / AC-5）。
 *
 * 只读快照：卡片本身不发首屏请求，最近一次快照由父级一次性载荷提供；
 * 「刷新质量门」才外呼 Sonar，并写 quality_gate_snapshot（source=SONAR）。
 * 失败态必须明写「准入第 8 项计为失败」，不能让用户把红条理解成提示。
 */
const props = withDefaults(
  defineProps<{
    requirementId: number
    card: SonarCard | null
    loading?: boolean
    editableKey?: boolean
  }>(),
  { loading: false, editableKey: false },
)

const emit = defineEmits<{
  (event: 'updated', card: SonarCard): void
  (event: 'open-snapshot', card: SonarCard): void
}>()

const refreshing = ref(false)
const errorText = ref('')

const METRICS: { key: SonarMetricKey; label: string; percent?: boolean }[] = [
  { key: 'coverage', label: '覆盖率', percent: true },
  { key: 'bugs', label: 'Bugs' },
  { key: 'vulnerabilities', label: '漏洞' },
  { key: 'codeSmells', label: '代码异味' },
  { key: 'duplicatedLinesDensity', label: '重复率', percent: true },
]

const status = computed(() => props.card?.status ?? 'UNBOUND')
const failed = computed(() => status.value === 'ERROR')
const unavailable = computed(() => status.value === 'UNAVAILABLE')

const statusHint = computed(() => {
  if (failed.value) return '质量门未通过：准入第 8 项计为失败'
  if (unavailable.value) return 'Sonar 暂不可用：本次准入第 8 项记为跳过（不等于通过）'
  if (status.value === 'UNBOUND') return '尚未绑定 Sonar 项目：准入第 8 项记为跳过'
  return ''
})

function metricValue(key: SonarMetricKey): string {
  const value = props.card?.metrics?.[key]
  if (value == null) return '—'
  return METRICS.find((m) => m.key === key)?.percent ? `${value}%` : String(value)
}

function thresholdText(key: SonarMetricKey): string {
  const threshold = props.card?.thresholds?.[key]
  if (threshold == null || threshold === '') return '—'
  return String(threshold)
}

async function refresh(): Promise<void> {
  refreshing.value = true
  errorText.value = ''
  try {
    const card = await refreshSonar(props.requirementId)
    emit('updated', card)
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '刷新失败，请稍后重试'
  } finally {
    refreshing.value = false
  }
}
</script>

<template>
  <section class="sonar-card" aria-labelledby="sonar-card-title">
    <header class="sonar-card__head">
      <h3 id="sonar-card-title" class="sonar-card__title">质量门（Sonar）</h3>
      <StatusPill kind="sonar" :value="status" />
      <span class="sonar-card__spacer" />
      <el-button size="small" :loading="refreshing" @click="refresh">刷新质量门</el-button>
    </header>

    <div class="sonar-card__key">
      <span class="sonar-card__key-label">项目 key</span>
      <span v-if="card?.projectKey" class="mono sonar-card__key-value">{{ card.projectKey }}</span>
      <span v-else class="sonar-card__key-empty">未配置</span>
      <span class="sonar-card__key-hint">
        {{ editableKey ? '需求单独配置优先，否则继承版本默认' : '需求未单独配置时继承版本默认 key' }}
      </span>
    </div>

    <div v-if="statusHint" class="sonar-card__hint" :class="{ 'is-fail': failed, 'is-warn': unavailable || status === 'UNBOUND' }" role="status">
      <span>{{ statusHint }}</span>
      <span v-if="card?.skipReason" class="sonar-card__reason">{{ card.skipReason }}</span>
    </div>

    <dl class="sonar-card__metrics">
      <div v-for="metric in METRICS" :key="metric.key" class="sonar-card__metric">
        <dt>{{ metric.label }}</dt>
        <dd class="mono" :class="{ 'is-missing': card?.metrics?.[metric.key] == null }">
          {{ metricValue(metric.key) }}
        </dd>
        <span class="sonar-card__threshold">阈值 {{ thresholdText(metric.key) }}</span>
      </div>
    </dl>

    <footer class="sonar-card__foot">
      <span v-if="card">
        快照 {{ card.fetchedAt ? formatDateTime(card.fetchedAt) : '—' }}
        <span class="mono sonar-card__hash" :title="card.hash ?? undefined">{{ card.hash ? card.hash.slice(0, 12) : '无 hash' }}</span>
        <span v-if="card.stale" class="sonar-card__stale">已过期</span>
      </span>
      <span v-else class="sonar-card__stale">还没有快照</span>
      <span class="sonar-card__spacer" />
      <el-button v-if="card" link type="primary" size="small" @click="emit('open-snapshot', card)">
        查看快照
      </el-button>
    </footer>

    <p v-if="errorText" class="sonar-card__error" role="alert">{{ errorText }}</p>
  </section>
</template>

<style scoped>
.sonar-card {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-surface);
  padding: 14px 16px;
}
.sonar-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sonar-card__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.sonar-card__spacer {
  flex: 1;
}
.sonar-card__key {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 10px;
  min-width: 0;
  font-size: 12px;
}
.sonar-card__key-label {
  flex: none;
  color: var(--text-weak);
}
.sonar-card__key-value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-body);
}
.sonar-card__key-empty {
  color: var(--text-weak);
}
.sonar-card__key-hint {
  flex: none;
  color: var(--text-weak);
  font-size: 11px;
}
.sonar-card__hint {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--divider);
  border-left: 3px solid var(--color-info);
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 12px;
  line-height: 18px;
}
.sonar-card__hint.is-fail {
  border-color: #f7d4d5;
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.sonar-card__hint.is-warn {
  border-color: #f0dcae;
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.sonar-card__reason {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  opacity: 0.85;
}
.sonar-card__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 0;
}
.sonar-card__metric {
  padding: 8px 10px;
  border: 1px solid var(--divider);
  border-radius: var(--r-ctl);
  background: var(--bg-sunken);
}
.sonar-card__metric dt {
  color: var(--text-weak);
  font-size: 11px;
}
.sonar-card__metric dd {
  margin: 2px 0 0;
  color: var(--text-title);
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.sonar-card__metric dd.is-missing {
  color: var(--text-weak);
}
.sonar-card__threshold {
  display: block;
  margin-top: 2px;
  color: var(--text-weak);
  font-size: 10px;
}
.sonar-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  color: var(--text-weak);
  font-size: 11px;
}
.sonar-card__hash {
  margin-left: 4px;
  color: var(--text-muted-aa);
}
.sonar-card__stale {
  margin-left: 6px;
  color: var(--warning-ink);
}
.sonar-card__error {
  margin: 8px 0 0;
  color: var(--danger-ink);
  font-size: 12px;
}
</style>
