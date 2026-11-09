<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { getRequirementIntegration, type RequirementIntegration } from '@/api/integration'
import type { MrCard } from '@/api/integration'
import { fetchCodeQuality } from '@/api/req'
import type { CodeQualityPayload, MrRefreshResult } from '@/api/req'
import { formatDateTime } from '@/utils/format'
import StatusPill from '@/components/StatusPill.vue'
import CodeCard from '@/components/req/CodeCard.vue'
import CodeQualityCard from '@/components/req/CodeQualityCard.vue'
import MrReviewCard from '@/components/req/MrReviewCard.vue'
import type { MrReviewModel } from '@/components/req/MrReviewCard.vue'
import SonarSnapshotDrawer from '@/components/req/SonarSnapshotDrawer.vue'

/**
 * 需求详情第 8 个 tab「集成」（DESIGN I4 / AC-4、AC-5、AC-10）。
 *
 * 一次 GET /requirements/{id}/integration 取回代码卡 + Sonar 卡 + 最近 10 条同步记录，
 * 这些全部是本地事实（mr_sync / quality_gate_snapshot / integration_sync_log），
 * 打开 tab 不会访问 GitLab 或 Sonar——刷新按钮才是唯一的外呼入口。
 */
const props = defineProps<{
  requirementId: number
  fallbackMrUrl?: string
}>()

const emit = defineEmits<{
  (event: 'changed'): void
}>()

const router = useRouter()
const data = ref<RequirementIntegration | null>(null)
const loading = ref(false)
const errorText = ref('')
const snapshotOpen = ref(false)
const snapshotCard = ref<RequirementIntegration['sonar']>(null)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    data.value = await getRequirementIntegration(props.requirementId)
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '读取集成信息失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  void loadQuality()
})

const codeCard = computed(() => data.value?.code ?? null)
const syncRows = computed(() => (data.value?.sync ?? []).slice(0, 10))

/** MR 评审卡的数据来源：MrCard + M8 增列（服务端未下发时保持缺省，不猜） */
const mrModel = computed<MrReviewModel | null>(() =>
  codeCard.value ? ({ ...codeCard.value } as unknown as MrReviewModel) : null,
)

/* 代码质量卡：只读快照，与集成载荷分开取——质量接口失败不影响 MR/同步记录 */
const quality = ref<CodeQualityPayload | null>(null)
const qualityLoading = ref(false)

async function loadQuality(): Promise<void> {
  qualityLoading.value = true
  try {
    quality.value = await fetchCodeQuality(props.requirementId)
  } catch {
    quality.value = { card: null, emptyReason: 'NO_SNAPSHOT' }
  } finally {
    qualityLoading.value = false
  }
}

/** 刷新返回的是 MR 卡本身：合并进旧卡，保留服务端未回传的字段 */
function onMrRefreshed(payload: MrRefreshResult): void {
  if (!data.value) return
  if (payload.card) {
    data.value = { ...data.value, code: { ...(data.value.code as MrCard), ...payload.card } as MrCard }
  }
  emit('changed')
}

function onQualityUpdated(card: RequirementIntegration['sonar']): void {
  if (!card) return
  quality.value = { card, emptyReason: null }
  emit('changed')
}

function onCodeUpdated(card: RequirementIntegration['code']): void {
  if (!data.value || !card) return
  data.value = { ...data.value, code: card }
  emit('changed')
}

function openSnapshot(card: RequirementIntegration['sonar']): void {
  snapshotCard.value = card
  snapshotOpen.value = true
}

function openLogs(correlationId?: string): void {
  void router.push({
    path: '/admin/integrations',
    query: { tab: 'logs', ...(correlationId ? { correlationId } : {}) },
  })
}

function operationText(row: RequirementIntegration['sync'][number]): string {
  return row.operation || '—'
}
</script>

<template>
  <div class="integration-tab">
    <header class="integration-tab__head">
      <div>
        <h2 class="integration-tab__title">集成</h2>
        <p class="integration-tab__sub">
          MR 评审、行级评论、代码卡与质量快照都读本地数据；只有「刷新」会访问 GitLab / Sonar。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">重新读取</el-button>
    </header>

    <div v-if="errorText" class="integration-tab__error" role="alert">
      <span>{{ errorText }}</span>
      <el-button link type="primary" size="small" @click="load">重试</el-button>
    </div>

    <div v-if="loading && !data" class="integration-tab__grid">
      <div v-for="index in 2" :key="index" class="integration-tab__skeleton" />
    </div>

    <!-- 失败与"未绑定"必须可区分：没有数据时不渲染空卡片，避免把加载失败读成"未同步"。 -->
    <p v-else-if="!data && errorText" class="integration-tab__failed" role="status">
      读取失败，未展示任何卡片；请点上方「重试」重新读取本地快照。
    </p>

    <template v-else>
      <!-- 通栏 MR 评审卡：评论按文件分组内滚 420；刷新是唯一外呼入口 -->
      <MrReviewCard :requirement-id="requirementId" :mr="mrModel" :loading="loading" @refreshed="onMrRefreshed" />

      <div class="integration-tab__grid">
        <CodeCard
          :requirement-id="requirementId"
          :card="codeCard"
          :fallback-url="fallbackMrUrl || ''"
          :loading="loading"
          @updated="onCodeUpdated"
        />
        <CodeQualityCard
          :requirement-id="requirementId"
          :card="quality?.card ?? null"
          :empty-reason="quality?.emptyReason ?? null"
          :loading="qualityLoading"
          @updated="onQualityUpdated"
          @open-snapshot="openSnapshot"
        />
      </div>

      <section class="integration-tab__logs" aria-labelledby="integration-logs-title">
        <header class="integration-tab__logs-head">
          <h3 id="integration-logs-title" class="integration-tab__logs-title">同步记录</h3>
          <span class="integration-tab__logs-hint">最近 10 条 · 摘要已脱敏</span>
          <span class="integration-tab__spacer" />
          <el-button link type="primary" size="small" @click="openLogs()">全部日志 ›</el-button>
        </header>

        <table v-if="syncRows.length" class="sync-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>系统</th>
              <th>操作</th>
              <th class="sync-table__num">耗时</th>
              <th>结果</th>
              <th class="sync-table__corr">correlation</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in syncRows" :key="row.id">
              <td class="sync-table__time">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</td>
              <td class="mono sync-table__system">{{ row.system }}</td>
              <td class="sync-table__op" :title="row.targetUrl ?? undefined">{{ operationText(row) }}</td>
              <td class="sync-table__num">{{ row.durationMs != null ? `${row.durationMs} ms` : '—' }}</td>
              <td><StatusPill kind="sync" :value="row.result" /></td>
              <td class="sync-table__corr">
                <button type="button" class="sync-table__link mono" @click="openLogs(row.correlationId)">
                  {{ row.correlationId ? row.correlationId.slice(0, 12) : '—' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="integration-tab__empty">
          还没有同步记录。关联 MR 或刷新质量门后，这里会出现带脱敏摘要的调用记录。
        </div>
      </section>
    </template>

    <SonarSnapshotDrawer v-model="snapshotOpen" :card="snapshotCard" />
  </div>
</template>

<style scoped>
.integration-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.integration-tab__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.integration-tab__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.integration-tab__sub {
  margin: 4px 0 0;
  color: var(--text-weak);
  font-size: 12px;
}
.integration-tab__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid #f7d4d5;
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.integration-tab__failed {
  margin: 0;
  padding: 24px 16px;
  border: 1px dashed var(--border);
  border-radius: var(--r-card);
  color: var(--text-weak);
  font-size: 13px;
  text-align: center;
}
.integration-tab__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1180px) {
  .integration-tab__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.integration-tab__skeleton {
  height: 220px;
  border-radius: var(--r-card);
  background: var(--bg-hover);
  animation: integration-pulse 1.2s var(--ease) infinite;
}
.integration-tab__logs {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-surface);
  padding: 12px 16px 4px;
}
.integration-tab__logs-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
.integration-tab__logs-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.integration-tab__logs-hint {
  color: var(--text-weak);
  font-size: 11px;
}
.integration-tab__spacer {
  flex: 1;
}
.sync-table {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 12px;
  table-layout: fixed;
}
.sync-table th {
  height: 30px;
  padding: 0 8px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  text-align: left;
}
.sync-table td {
  height: 40px;
  padding: 0 8px;
  border-bottom: 1px solid var(--divider);
  color: var(--text-body);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-table__num {
  width: 88px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.sync-table__time {
  width: 148px;
  color: var(--text-muted-aa);
}
.sync-table__system {
  width: 88px;
  color: var(--text-weak);
}
.sync-table__corr {
  width: 130px;
}
.sync-table__link {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--text-muted-aa);
  font-family: var(--font-mono);
  font-size: 11px;
  cursor: pointer;
}
.sync-table__link:hover {
  color: var(--color-primary, #2f6fed);
  text-decoration: underline;
}
.integration-tab__empty {
  padding: 28px 0 24px;
  color: var(--text-weak);
  font-size: 12px;
  text-align: center;
}
@keyframes integration-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 0.85;
  }
}
</style>
