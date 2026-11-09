<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ApiError } from '@/api/client'
import {
  listLogs,
  type IntegrationLogPage,
  type IntegrationLogRow,
  type IntegrationSyncResult,
  type IntegrationSystem,
} from '@/api/integration'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import SyncLogTable from '@/components/integration/SyncLogTable.vue'

/**
 * 同步日志 tab（DESIGN I2 / §6.5）。
 *
 * 这里是"系统到底有没有真的调到"的唯一证据面：所有出站尝试（含重试）与入站回调都写
 * integration_sync_log，前端只做筛选/分页/聚合，不改写内容。
 *
 * 筛选暴露契约支持的六个维度中的四个控件（system / result / 关联号 / 时间区间 / 关键字；
 * 方向与调用类型由表格列直接展示）。时间区间默认近 7 天，与 DESIGN I2 一致；后端全部
 * 参数可空，清空即不限。
 */
const props = withDefaults(
  defineProps<{
    /** 深链：?correlationId=xxx 进来时只筛这条链路 */
    correlationId?: string | null
  }>(),
  { correlationId: null },
)

const emit = defineEmits<{
  (event: 'go-config'): void
  (event: 'clear-correlation'): void
}>()

const SYSTEMS: Array<{ value: IntegrationSystem | ''; label: string }> = [
  { value: '', label: '全部类型' },
  { value: 'JENKINS', label: 'Jenkins' },
  { value: 'GITLAB', label: 'GitLab' },
  { value: 'SONAR', label: 'Sonar' },
  { value: 'NEXUS', label: 'Nexus' },
]

const RESULTS: Array<{ value: IntegrationSyncResult | ''; label: string }> = [
  { value: '', label: '全部结果' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'IGNORED', label: '已忽略' },
  { value: 'RETRY_EXHAUSTED', label: '重试耗尽' },
]

const filters = reactive<{ system: IntegrationSystem | ''; result: IntegrationSyncResult | ''; correlationId: string; keyword: string }>({
  system: '',
  result: '',
  correlationId: props.correlationId ?? '',
  keyword: '',
})

/** 默认时间区间：近 7 天（DESIGN I2）；清空后表示不限。 */
function defaultRange(): [string, string] {
  const to = new Date()
  const from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000)
  const fmt = (value: Date): string => {
    const pad = (n: number): string => String(n).padStart(2, '0')
    return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  }
  return [fmt(from), fmt(to)]
}

const dateRange = ref<[string, string] | null>(defaultRange())

const rows = ref<IntegrationLogRow[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref<unknown>(null)
const autoRefresh = ref(false)

let timer: number | null = null
let inFlight = false
let disposed = false

const filtered = computed(
  () =>
    !!filters.system ||
    !!filters.result ||
    !!filters.correlationId ||
    !!filters.keyword.trim() ||
    !!dateRange.value,
)
const empty = computed(() => !loading.value && !error.value && rows.value.length === 0)

function toApiError(value: unknown): ApiError {
  return value instanceof ApiError
    ? value
    : new ApiError('UNKNOWN', value instanceof Error ? value.message : '请求失败')
}

async function load(): Promise<void> {
  if (inFlight || disposed) return
  inFlight = true
  loading.value = true
  error.value = null
  try {
    const query: Parameters<typeof listLogs>[0] = { page: page.value, size: size.value }
    if (filters.system) query.system = filters.system
    if (filters.result) query.result = filters.result
    if (filters.correlationId.trim()) query.correlationId = filters.correlationId.trim()
    if (filters.keyword.trim()) query.keyword = filters.keyword.trim()
    if (dateRange.value) {
      query.from = dateRange.value[0]
      query.to = dateRange.value[1]
    }
    const response: IntegrationLogPage = await listLogs(query)
    rows.value = response.items
    total.value = response.total
  } catch (value) {
    // 降级：保留上一页数据（不闪成空态），错误交给页面渲染
    error.value = value
  } finally {
    inFlight = false
    loading.value = false
  }
}

function applyFilters(): void {
  page.value = 1
  void load()
}

function resetFilters(): void {
  filters.system = ''
  filters.result = ''
  filters.correlationId = ''
  filters.keyword = ''
  dateRange.value = defaultRange()
  page.value = 1
  emit('clear-correlation')
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

function setAutoRefresh(value: string | number | boolean): void {
  autoRefresh.value = Boolean(value)
}

function tick(): void {
  if (disposed || document.hidden) return // 页面不可见不发请求
  void load()
}

watch(autoRefresh, (on) => {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  if (!on) return
  timer = window.setInterval(tick, 30000)
})

watch(
  () => props.correlationId,
  (value) => {
    if (value && value !== filters.correlationId) {
      filters.correlationId = value
      page.value = 1
      void load()
    }
  },
)

function onVisibilityChange(): void {
  if (document.hidden || !autoRefresh.value) return
  void load() // 回到页面立刻补拉一次
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  void load()
})

onUnmounted(() => {
  disposed = true
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <div class="logtab">
    <div class="logtab__toolbar">
      <el-select v-model="filters.system" class="logtab__select" aria-label="按类型筛选" @change="applyFilters">
        <el-option v-for="item in SYSTEMS" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="filters.result" class="logtab__select" aria-label="按结果筛选" @change="applyFilters">
        <el-option v-for="item in RESULTS" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        class="logtab__range"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        aria-label="按时间范围筛选（默认近 7 天）"
        @change="applyFilters"
      />
      <el-input
        v-model="filters.keyword"
        class="logtab__input"
        placeholder="关键字（操作/URL/摘要/错误码）"
        clearable
        aria-label="按关键字筛选"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-input
        v-model="filters.correlationId"
        class="logtab__input"
        placeholder="correlation_id（精确匹配）"
        clearable
        aria-label="按关联号筛选"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-button @click="applyFilters">查询</el-button>
      <span class="logtab__spacer" />
      <label class="logtab__auto">
        <el-switch
          :model-value="autoRefresh"
          size="small"
          aria-label="每 30 秒自动刷新"
          @change="setAutoRefresh"
        />
        <span>每 30s 自动刷新</span>
      </label>
    </div>

    <p class="logtab__hint">
      展示最近 20 条 / 页；同一 correlation_id 的多次尝试聚合为一行。筛选维度以服务端契约为准（类型 / 结果 / 时间区间 / 关键字 / 关联号），时间区间默认近 7 天。
    </p>

    <ErrorState
      v-if="error && !rows.length"
      title="同步日志加载失败"
      :message="toApiError(error).message"
      :code="toApiError(error).code"
      :status="toApiError(error).status"
      @retry="load"
    />

    <template v-else>
      <p v-if="error" class="logtab__degrade" role="status">
        刷新失败（{{ toApiError(error).code }}）：下面是上一次取到的数据，稍后自动重试或点「查询」。
      </p>

      <div v-if="loading && !rows.length" class="logtab__skeleton" aria-busy="true" aria-label="正在加载同步日志">
        <span v-for="index in 8" :key="index" class="logtab__skeleton-row" />
      </div>

      <EmptyState
        v-else-if="empty && filtered"
        variant="filtered"
        title="没有符合筛选的记录"
        description="换一个类型/结果，或清空关联号再试。"
        action-text="清空筛选"
        @action="resetFilters"
      />

      <EmptyState
        v-else-if="empty"
        title="还没有同步记录"
        description="四类集成默认关闭：配置并启用后，出站调用与入站回调才会写这里。"
        action-text="去集成配置"
        @action="emit('go-config')"
      />

      <template v-else>
        <div v-if="loading" class="logtab__progress" role="progressbar" aria-label="正在刷新" />
        <SyncLogTable :rows="rows" :loading="loading" :highlight="filters.correlationId.trim() || null" />
        <div class="logtab__pager">
          <span class="logtab__total">共 {{ total }} 条</span>
          <el-pagination
            :current-page="page"
            :page-size="size"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="changePage"
          />
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.logtab {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.logtab__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.logtab__select {
  width: 132px;
}

.logtab__input {
  width: 240px;
}

.logtab__range {
  flex: 0 0 360px;
  max-width: 100%;
}

.logtab__spacer {
  flex: 1;
}

.logtab__auto {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.logtab__hint {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.logtab__degrade {
  margin: 0;
  padding: 6px 10px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}

.logtab__progress {
  height: 2px;
  border-radius: var(--r-pill);
  background: linear-gradient(90deg, transparent, var(--color-primary), transparent);
  background-size: 40% 100%;
  animation: logtab-run 1s linear infinite;
}

@keyframes logtab-run {
  0% {
    background-position: -40% 0;
  }
  100% {
    background-position: 140% 0;
  }
}

.logtab__skeleton {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.logtab__skeleton-row {
  height: 36px;
  border-bottom: 1px solid var(--divider);
  background: linear-gradient(90deg, var(--divider), var(--bg-page), var(--divider));
  background-size: 200% 100%;
  animation: logtab-shimmer 1.2s linear infinite;
}

.logtab__pager {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logtab__total {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

@media (prefers-reduced-motion: reduce) {
  .logtab__progress,
  .logtab__skeleton-row {
    animation: none;
  }
}
</style>
