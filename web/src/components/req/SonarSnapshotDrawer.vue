<script setup lang="ts">
import { computed } from 'vue'
import StatusPill from '@/components/StatusPill.vue'
import type { SonarCard, SonarMetricKey } from '@/api/integration'
import { formatDateTime } from '@/utils/format'

/**
 * Sonar 快照只读抽屉（DESIGN I8 / AC-5.4）。
 *
 * 打开它不触发任何外呼：展示的是 quality_gate_snapshot 里 source=SONAR 的那一行，
 * 与准入第 8 项读到的是同一份证据，不存在「页面显示通过、准入判失败」的第二源头。
 */
const props = defineProps<{
  modelValue: boolean
  card: SonarCard | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const METRICS: { key: SonarMetricKey; label: string; percent?: boolean }[] = [
  { key: 'coverage', label: '覆盖率', percent: true },
  { key: 'bugs', label: 'Bugs' },
  { key: 'vulnerabilities', label: '漏洞' },
  { key: 'codeSmells', label: '代码异味' },
  { key: 'duplicatedLinesDensity', label: '重复率', percent: true },
]

function metricValue(key: SonarMetricKey): string {
  const value = props.card?.metrics?.[key]
  if (value == null) return '—'
  return METRICS.find((m) => m.key === key)?.percent ? `${value}%` : String(value)
}

function thresholdText(key: SonarMetricKey): string {
  const threshold = props.card?.thresholds?.[key]
  return threshold == null || threshold === '' ? '—' : String(threshold)
}
</script>

<template>
  <el-drawer
    v-model="open"
    :size="560"
    title="Sonar 快照"
    append-to-body
    destroy-on-close
    class="sonar-drawer"
  >
    <template v-if="card">
      <dl class="sonar-drawer__meta">
        <div class="sonar-drawer__row">
          <dt>项目 key</dt>
          <dd class="mono">{{ card.projectKey || '未绑定' }}</dd>
        </div>
        <div class="sonar-drawer__row">
          <dt>结论</dt>
          <dd><StatusPill kind="sonar" :value="card.status" /></dd>
        </div>
        <div class="sonar-drawer__row">
          <dt>拉取时间</dt>
          <dd>{{ card.fetchedAt ? formatDateTime(card.fetchedAt) : '—' }}</dd>
        </div>
        <div class="sonar-drawer__row">
          <dt>快照 hash</dt>
          <dd class="mono sonar-drawer__hash">{{ card.hash || '无' }}</dd>
        </div>
        <div class="sonar-drawer__row">
          <dt>快照 id</dt>
          <dd class="mono">{{ card.snapshotId ?? '—' }}</dd>
        </div>
        <div v-if="card.skipReason" class="sonar-drawer__row">
          <dt>跳过原因</dt>
          <dd>{{ card.skipReason }}</dd>
        </div>
      </dl>

      <p v-if="card.stale" class="sonar-drawer__stale">
        该快照已超过刷新周期，准入读取时同样按 stale 处理，不会把它当成最新结论。
      </p>

      <h4 class="sonar-drawer__subtitle">五度量与阈值</h4>
      <table class="sonar-table">
        <thead>
          <tr>
            <th>度量</th>
            <th class="sonar-table__num">实测值</th>
            <th class="sonar-table__num">阈值</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="metric in METRICS" :key="metric.key">
            <td>{{ metric.label }}</td>
            <td class="sonar-table__num mono" :class="{ 'is-missing': card.metrics?.[metric.key] == null }">
              {{ metricValue(metric.key) }}
            </td>
            <td class="sonar-table__num mono">{{ thresholdText(metric.key) }}</td>
          </tr>
        </tbody>
      </table>

      <p class="sonar-drawer__foot">
        此快照写入 quality_gate_snapshot（source=SONAR），与 M6 的质量门快照共享一张表但用
        source 维度隔离；准入第 8 项只读这一份本地证据，不在此处发起外呼。
      </p>
    </template>
    <p v-else class="sonar-drawer__empty">暂无快照。先在需求集成页点「刷新质量门」。</p>
  </el-drawer>
</template>

<style scoped>
.sonar-drawer__meta {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--divider);
  border-radius: var(--r-card);
  background: var(--bg-sunken);
}
.sonar-drawer__row {
  display: flex;
  gap: 12px;
  padding: 4px 0;
  font-size: 12px;
  line-height: 20px;
}
.sonar-drawer__row dt {
  width: 76px;
  flex: none;
  color: var(--text-weak);
}
.sonar-drawer__row dd {
  margin: 0;
  min-width: 0;
  color: var(--text-body);
  overflow-wrap: anywhere;
}
.sonar-drawer__hash {
  font-size: 11px;
}
.sonar-drawer__stale {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-left: 2px solid var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.sonar-drawer__subtitle {
  margin: 16px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-title);
}
.sonar-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.sonar-table th {
  height: 30px;
  padding: 0 8px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  text-align: left;
}
.sonar-table td {
  height: 36px;
  padding: 0 8px;
  border-bottom: 1px solid var(--divider);
  color: var(--text-body);
}
.sonar-table__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.sonar-table .is-missing {
  color: var(--text-weak);
}
.sonar-drawer__foot {
  margin: 14px 0 0;
  color: var(--text-weak);
  font-size: 11px;
  line-height: 18px;
}
.sonar-drawer__empty {
  padding: 40px 0;
  text-align: center;
  color: var(--text-weak);
  font-size: 12px;
}
</style>
