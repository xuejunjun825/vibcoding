<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { createDeployOrder, deployStats, listDeployOrders, listReleases } from '@/api/req'
import type { DeployOrderRow, ReleaseRow } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, formatDuration, formatPercent } from '@/utils/format'
import { messageOf, releasePath } from '@/views/req/platform'

/**
 * 发布单列表：环境/状态/版本筛选 + 发布统计 + 新建发布单。
 * 统计与列表共用同一组筛选（releaseId/env），点击统计卡回到本页同筛选，不换口径。
 */
const ENVS = [
  { value: 'DEV', label: '开发' },
  { value: 'TEST', label: '测试' },
  { value: 'STAGING', label: '预发' },
  { value: 'PROD', label: '生产' },
] as const

const router = useRouter()
const { can } = usePermission()

const list = useListQuery<DeployOrderRow>(
  (query) =>
    listDeployOrders({
      releaseId: (query.filters.releaseId as string) || undefined,
      env: (query.filters.env as string) || undefined,
      status: (query.filters.status as string) || undefined,
      page: query.page,
      size: query.size,
    }),
  { releaseId: '', env: '', status: '' },
)

const releases = ref<ReleaseRow[]>([])
const stats = ref<{ successRate?: number; avgDurationMinutes?: number; failReasons?: Array<{ reason: string; count: number }> } | null>(null)
const statsError = ref('')

async function loadStats(): Promise<void> {
  statsError.value = ''
  try {
    stats.value = await deployStats({
      releaseId: (list.filters.value.releaseId as string) || undefined,
      env: (list.filters.value.env as string) || undefined,
    })
  } catch (error) {
    stats.value = null
    statsError.value = messageOf(error)
  }
}

onMounted(async () => {
  void loadStats()
  try {
    const page = await listReleases({ page: 1, size: 100 })
    releases.value = page.items ?? []
  } catch {
    releases.value = []
  }
})

const createOpen = ref(false)
const releaseId = ref<number | undefined>()
const env = ref<string>('TEST')
const windowStart = ref('')
const windowEnd = ref('')
const artifactVersion = ref('')
const steps = ref('')
const rollbackPlan = ref('')
const impact = ref('')
const saving = ref(false)
const formError = ref('')

function openCreate(): void {
  createOpen.value = true
  releaseId.value = releases.value[0]?.id
  env.value = 'TEST'
  windowStart.value = ''
  windowEnd.value = ''
  artifactVersion.value = ''
  steps.value = ''
  rollbackPlan.value = ''
  impact.value = ''
  formError.value = ''
}

const canSubmit = computed(
  () =>
    releaseId.value != null &&
    artifactVersion.value.trim().length > 0 &&
    Boolean(windowStart.value) &&
    Boolean(windowEnd.value) &&
    !saving.value,
)

async function submitCreate(): Promise<void> {
  if (!canSubmit.value || releaseId.value == null) return
  if (windowStart.value >= windowEnd.value) {
    formError.value = '发布窗口的结束时间必须晚于开始时间'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const created = await createDeployOrder({
      releaseId: releaseId.value,
      env: env.value,
      windowStart: windowStart.value,
      windowEnd: windowEnd.value,
      artifactVersion: artifactVersion.value.trim(),
      steps: steps.value.trim() || undefined,
      rollbackPlan: rollbackPlan.value.trim() || undefined,
      impact: impact.value.trim() || undefined,
    })
    ElMessage.success(`发布单 ${created.code} 已创建`)
    createOpen.value = false
    await router.push({ name: 'deploy-order-detail', params: { id: created.id } })
  } catch (error) {
    formError.value = planErrorText(error)
  } finally {
    saving.value = false
  }
}

/**
 * 409 PLAN_NOT_APPROVED：不弹 toast（既有约定），在弹窗内就地列出未批准的测试计划号。
 * 503 PLAN_CHECK_UNAVAILABLE 走 messageOf（后端已给「稍后重试」文案）。
 */
function planErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === 'PLAN_NOT_APPROVED') {
    const ids = (error.detail as { planIds?: number[] } | undefined)?.planIds ?? []
    return `发布单引用了未批准的测试计划${ids.length ? `（#${ids.join('、#')}）` : ''}：请先完成计划评审批准，或移除对应需求后重试。`
  }
  return messageOf(error)
}

function envLabel(value?: string): string {
  return ENVS.find((item) => item.value === value)?.label ?? value ?? '—'
}

function releaseName(id?: number): string {
  return releases.value.find((item) => item.id === id)?.name ?? (id != null ? `版本 #${id}` : '—')
}

function goDetail(row: DeployOrderRow): void {
  void router.push({ name: 'deploy-order-detail', params: { id: row.id } })
}

function successRateOf(): string {
  const value = stats.value?.successRate
  if (value == null) return '—'
  return formatPercent(value)
}
</script>

<template>
  <div class="deploy-orders">
    <header class="page-head">
      <div class="page-head__left">
        <h1 class="page-head__title">发布单</h1>
        <span class="count-badge">{{ list.total.value }}</span>
      </div>
      <div class="page-head__actions">
        <el-select
          :model-value="(list.filters.value.releaseId as string) || ''"
          size="small"
          placeholder="全部版本"
          clearable
          filterable
          style="width: 168px"
          @update:model-value="(value: string) => (list.setFilter('releaseId', value ?? ''), loadStats())"
        >
          <el-option v-for="release in releases" :key="release.id" :label="`${release.code} · ${release.name}`" :value="String(release.id)" />
        </el-select>
        <el-select
          :model-value="(list.filters.value.env as string) || ''"
          size="small"
          placeholder="全部环境"
          clearable
          style="width: 110px"
          @update:model-value="(value: string) => (list.setFilter('env', value ?? ''), loadStats())"
        >
          <el-option v-for="item in ENVS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select
          :model-value="(list.filters.value.status as string) || ''"
          size="small"
          placeholder="全部状态"
          clearable
          style="width: 130px"
          @update:model-value="(value: string) => list.setFilter('status', value ?? '')"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="PENDING_APPROVAL" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="执行中" value="EXECUTING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已回滚" value="ROLLED_BACK" />
        </el-select>
        <el-button
          type="primary"
          size="small"
          :disabled="!can('deploy:create')"
          :title="can('deploy:create') ? '' : '需要权限：deploy:create'"
          @click="openCreate"
        >
          新建发布单
        </el-button>
      </div>
    </header>

    <div class="stats" aria-label="发布统计">
      <div class="stat">
        <span class="stat__label">发布成功率</span>
        <span class="stat__value">{{ successRateOf() }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">平均耗时</span>
        <span class="stat__value">{{ stats?.avgDurationMinutes != null ? formatDuration(stats.avgDurationMinutes) : '—' }}</span>
      </div>
      <div class="stat stat--wide">
        <span class="stat__label">失败原因分布</span>
        <div v-if="stats?.failReasons?.length" class="reasons">
          <span v-for="reason in stats.failReasons.slice(0, 3)" :key="reason.reason" class="reason">
            {{ reason.reason }} <b>{{ reason.count }}</b>
          </span>
        </div>
        <span v-else class="muted small">{{ statsError ? '统计暂不可用' : '暂无失败记录' }}</span>
      </div>
    </div>

    <section class="surface surface--flush">
      <div v-if="list.loading.value && list.rows.value.length === 0" class="skeleton-list">
        <div v-for="index in 6" :key="index" class="skeleton skeleton__row" />
      </div>
      <ErrorState v-else-if="list.error.value" title="发布单加载失败" :message="list.error.value?.message ?? '加载失败'" @retry="list.load" />
      <EmptyState
        v-else-if="list.isEmpty.value"
        :variant="list.isFiltered.value ? 'filtered' : 'empty'"
        :title="list.isFiltered.value ? '当前筛选没有发布单' : '还没有发布单'"
        :description="list.isFiltered.value ? '清空筛选或换一个版本/环境。' : '发布单把版本、准入门禁与执行结果串起来；先建一张发布单。'"
        :action-text="list.isFiltered.value ? '清空筛选' : '新建发布单'"
        icon="file"
        @action="list.isFiltered.value ? list.reset() : openCreate()"
      />
      <el-table v-else :data="list.rows.value" size="small" row-key="id" @row-click="goDetail">
        <el-table-column label="发布单" width="170">
          <template #default="{ row }">
            <span class="mono code-link">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <button type="button" class="link" @click.stop="router.push(releasePath(row.releaseId))">
              {{ row.releaseName || releaseName(row.releaseId) }}
            </button>
          </template>
        </el-table-column>
        <el-table-column label="环境" width="82">
          <template #default="{ row }">
            <span class="env-tag" :class="`env-${String(row.env).toLowerCase()}`">{{ envLabel(row.env) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="116">
          <template #default="{ row }">
            <StatusPill kind="deploy" :value="row.status" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="发布窗口" width="230">
          <template #default="{ row }">
            <span class="num muted">{{ formatDateTime(row.windowStart) }} → {{ formatDateTime(row.windowEnd) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="制品版本" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.artifactVersion || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="96">
          <template #default="{ row }">{{ row.ownerName || '—' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <div v-if="list.total.value > list.size.value" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :current-page="list.page.value"
        :page-size="list.size.value"
        :total="list.total.value"
        @current-change="list.setPage"
      />
    </div>

    <el-dialog v-model="createOpen" title="新建发布单" width="600px" append-to-body>
      <el-form label-position="top" size="small">
        <div class="form-row">
          <el-form-item label="版本" required class="grow">
            <el-select v-model="releaseId" filterable placeholder="选择版本" style="width: 100%">
              <el-option v-for="release in releases" :key="release.id" :label="`${release.code} · ${release.name}`" :value="release.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="环境" required>
            <el-select v-model="env" style="width: 120px">
              <el-option v-for="item in ENVS" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="窗口开始" required>
            <el-date-picker v-model="windowStart" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" />
          </el-form-item>
          <el-form-item label="窗口结束" required>
            <el-date-picker v-model="windowEnd" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间" />
          </el-form-item>
        </div>
        <el-form-item label="制品版本" required>
          <el-input v-model="artifactVersion" maxlength="80" placeholder="如：order-service:2.4.0-20261109" />
        </el-form-item>
        <el-form-item label="发布步骤">
          <el-input v-model="steps" type="textarea" :rows="3" maxlength="500" placeholder="按顺序写清每一步；执行时逐条核对" />
        </el-form-item>
        <el-form-item label="回滚方案">
          <el-input v-model="rollbackPlan" type="textarea" :rows="2" maxlength="300" placeholder="失败时如何回滚到上一个可用版本" />
        </el-form-item>
        <el-form-item label="影响范围">
          <el-input v-model="impact" type="textarea" :rows="2" maxlength="300" placeholder="受影响的服务、用户或业务域" />
        </el-form-item>
      </el-form>
      <p v-if="formError" class="dialog-error">{{ formError }}</p>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.deploy-orders {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.stats {
  display: grid;
  grid-template-columns: 180px 180px minmax(0, 1fr);
  gap: 8px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.stat__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.stat__value {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.reasons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.reason {
  font-size: 12px;
  color: var(--warning-ink);
  background: var(--color-warning-bg);
  border-radius: var(--r-pill);
  padding: 2px 8px;
}
.reason b {
  font-variant-numeric: tabular-nums;
}
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
}
.env-tag {
  display: inline-block;
  font-size: 11px;
  padding: 1px 7px;
  border-radius: var(--r-pill);
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.env-prod {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.env-staging {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.pager {
  display: flex;
  justify-content: flex-end;
}
.form-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.grow {
  flex: 1;
  min-width: 240px;
}
.mono {
  font-family: var(--font-mono);
}
.num {
  font-variant-numeric: tabular-nums;
}
.muted {
  color: var(--text-muted-aa);
}
.small {
  font-size: 12px;
}
.code-link {
  color: var(--color-primary);
}
.link {
  border: none;
  background: none;
  padding: 0;
  font-size: inherit;
  color: var(--color-primary);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
