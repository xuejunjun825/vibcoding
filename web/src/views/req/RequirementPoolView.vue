<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { actOnRequirement, exportRequirementsCsv, getRequirement, listRequirements } from '@/api/req'
import type { RequirementListQuery } from '@/api/req'
import type { PageResponse, RequirementDetail, RequirementRow } from '@/api/types'
import { ApiError } from '@/api/client'
import { useListQuery } from '@/composables/useListQuery'
import type { ListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { useAuthStore } from '@/stores/auth'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDate, formatDays } from '@/utils/format'
import {
  ACTION_IMPACT,
  PRIORITIES,
  childInfo,
  metaOf,
  messageOf,
  normalizeRequirementDetail,
  requirementPath,
  typeLabel,
  usePlatformDict,
} from './platform'

/**
 * P1 需求池（AC-3/AC-5/AC-20）：
 * 左 320 类型 tab + 卡片列表，右详情；聚合/平铺、搜索、筛选、CSV 导出；
 * 筛选与分页写进 URL（useListQuery），刷新与分享都可复现。
 */

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { canAny } = usePermission()
const platform = usePlatformDict()
const { domains, vendors, ensure: ensureDict, domainName, vendorName, userName } = platform

const STATUS_TABS = [
  { value: '', key: 'all', label: '全部' },
  { value: 'PENDING_REVIEW', key: 'review', label: '待评审' },
  { value: 'POOLED', key: 'pooled', label: '已入池' },
  { value: 'CLOSED', key: 'closed', label: '已关闭' },
] as const

const TYPE_TABS = [
  { value: '', label: '全部' },
  { value: 'BUS', label: '业务需求' },
  { value: 'PDR', label: '产品需求' },
  { value: 'RD', label: '研发需求' },
] as const

function numberOrUndefined(value?: string): number | undefined {
  if (!value) return undefined
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : undefined
}

function baseFilters(): RequirementListQuery {
  return {
    type: (filters.value.type || undefined) as RequirementListQuery['type'],
    keyword: filters.value.q || undefined,
    priority: (filters.value.priority || undefined) as RequirementListQuery['priority'],
    domainId: numberOrUndefined(filters.value.domainId),
    vendorId: numberOrUndefined(filters.value.vendorId),
  }
}

function buildParams(query: ListQuery): RequirementListQuery {
  return {
    ...baseFilters(),
    status: query.filters.status || undefined,
    view: query.filters.view === 'agg' ? 'agg' : 'flat',
    page: query.page,
    size: query.size,
  }
}

function fetchPage(query: ListQuery): Promise<PageResponse<RequirementRow>> {
  return listRequirements(buildParams(query))
}

const {
  rows,
  total,
  loading,
  error,
  page,
  size,
  filters,
  isEmpty,
  isFiltered,
  load,
  setPage,
  setFilter,
  reset,
} = useListQuery<RequirementRow>(
  fetchPage,
  { type: '', status: '', domainId: '', vendorId: '', priority: '', q: '', view: 'flat', summary: '' },
  { size: 20 },
)

const searchText = ref(typeof route.query.q === 'string' ? route.query.q : '')
watch(
  () => filters.value.q,
  (value) => {
    searchText.value = value ?? ''
  },
)

const viewMode = computed(() => (filters.value.view === 'agg' ? 'agg' : 'flat'))
const summaryMode = computed(() => filters.value.summary === '1')
const summaryToggle = computed<boolean>({
  get: () => summaryMode.value,
  set: (value) => setFilter('summary', value ? '1' : ''),
})
const activeFilterCount = computed(
  () =>
    [filters.value.domainId, filters.value.vendorId, filters.value.priority, filters.value.summary].filter(
      Boolean,
    ).length,
)

const scopeChip = computed(() => {
  const scope = auth.scope
  if (!scope) return ''
  if (scope.label) return scope.label
  if (scope.type === 'VENDOR') return `范围：${vendorName(scope.vendorId)}`
  if (scope.type === 'DOMAIN' && scope.domainIds?.length) return `范围：${scope.domainIds.length} 个域`
  return ''
})

const counts = reactive<Record<string, number>>({ all: 0, review: 0, pooled: 0, closed: 0 })

async function loadCounts(): Promise<void> {
  const base = baseFilters()
  const results = await Promise.all(
    [
      ['all', undefined],
      ['review', 'PENDING_REVIEW'],
      ['pooled', 'POOLED'],
      ['closed', 'CLOSED'],
    ].map(([key, status]) =>
      listRequirements({ ...base, status: status as string | undefined, page: 1, size: 1 })
        .then((result) => [key as string, result.total] as const)
        .catch(() => [key as string, counts[key as string] ?? 0] as const),
    ),
  )
  for (const [key, value] of results) counts[key] = value
}

watch(
  () => [filters.value.type, filters.value.domainId, filters.value.vendorId, filters.value.q],
  () => {
    void loadCounts()
  },
)

onMounted(() => {
  void ensureDict()
  void loadCounts()
})

/* ---------------- 选中需求 ---------------- */

const selectedId = ref<number | null>(null)
const selected = ref<RequirementDetail | null>(null)
const selectedLoading = ref(false)
const selectedError = ref<{ message: string; code?: string; status?: number } | null>(null)
const actionError = ref<{ message: string; code?: string; status?: number } | null>(null)
const actionLoading = ref('')
const confirmOpen = ref(false)
const pendingAction = ref<ReturnType<typeof metaOf> | null>(null)

async function loadSelected(): Promise<void> {
  const id = selectedId.value
  if (!id) {
    selected.value = null
    return
  }
  selectedLoading.value = true
  selectedError.value = null
  try {
    selected.value = normalizeRequirementDetail(await getRequirement(id))
  } catch (err) {
    selected.value = null
    selectedError.value =
      err instanceof ApiError
        ? { message: err.message, code: err.code, status: err.status }
        : { message: messageOf(err) }
  } finally {
    selectedLoading.value = false
  }
}

async function selectRow(row: RequirementRow): Promise<void> {
  if (selectedId.value === row.id) return
  selectedId.value = row.id
  actionError.value = null
  await loadSelected()
}

const allowedActions = computed(() => selected.value?.allowedActions ?? [])

function actionUsable(action: string): boolean {
  const meta = metaOf(action)
  return !meta.permissions || canAny(meta.permissions)
}

function actionDisabledReason(action: string): string {
  const meta = metaOf(action)
  return meta.permissions?.length ? `需要权限：${meta.permissions.join(' 或 ')}` : ''
}

function triggerAction(action: string): void {
  const meta = metaOf(action)
  if (meta.danger) {
    pendingAction.value = meta
    confirmOpen.value = true
    return
  }
  void runAction(action, '')
}

async function runAction(action: string, comment: string): Promise<void> {
  const id = selectedId.value
  if (!id) return
  actionLoading.value = action
  actionError.value = null
  try {
    await actOnRequirement(id, { action, comment: comment || undefined })
    ElMessage.success(`${metaOf(action).label}成功`)
    await Promise.all([load(), loadCounts(), loadSelected()])
  } catch (err) {
    if (err instanceof ApiError && err.isConflict) {
      actionError.value = { message: err.message, code: err.code, status: err.status }
      await loadSelected()
    } else {
      ElMessage.error(messageOf(err))
    }
  } finally {
    actionLoading.value = ''
  }
}

async function confirmAction(payload: { reason: string; acknowledged: boolean }): Promise<void> {
  const meta = pendingAction.value
  confirmOpen.value = false
  if (!meta) return
  await runAction(meta.action, payload.reason)
  pendingAction.value = null
}

const confirmImpact = computed(() => (pendingAction.value ? ACTION_IMPACT[pendingAction.value.action] ?? [] : []))

/* ---------------- 工具行 ---------------- */

function applySearch(): void {
  setFilter('q', searchText.value.trim())
}

function switchStatus(status: string): void {
  setFilter('status', status)
}

function switchType(type: string): void {
  setFilter('type', type)
}

function switchView(view: 'agg' | 'flat'): void {
  setFilter('view', view)
}

function onDomain(value: unknown): void {
  setFilter('domainId', value === null || value === undefined ? '' : String(value))
}

function onVendor(value: unknown): void {
  setFilter('vendorId', value === null || value === undefined ? '' : String(value))
}

function onPriority(value: unknown): void {
  setFilter('priority', value === null || value === undefined ? '' : String(value))
}

const exporting = ref(false)
async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    await exportRequirementsCsv({ ...baseFilters(), view: viewMode.value })
    ElMessage.success('CSV 已开始下载（首行含导出人与筛选条件）')
  } catch (err) {
    ElMessage.error(`导出失败：${messageOf(err)}`)
  } finally {
    exporting.value = false
  }
}

function openDetail(): void {
  if (selectedId.value) void router.push(requirementPath(selectedId.value))
}

async function openRowMore(command: string, row: RequirementRow): Promise<void> {
  if (command === 'open') {
    await router.push(requirementPath(row.id))
    return
  }
  try {
    await navigator.clipboard.writeText(`${row.code} ${row.title}`)
    ElMessage.success('已复制编号与标题')
  } catch {
    ElMessage.info('当前浏览器不允许复制')
  }
}
</script>

<template>
  <div class="pool">
    <header class="page-head">
      <div class="page-head__left">
        <h1 class="t-page-title">需求池</h1>
        <span class="count-badge">{{ total }}</span>
        <ScopeChip v-if="scopeChip" :label="scopeChip" />
      </div>
      <div class="page-head__actions">
        <el-button :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        <el-button type="primary" @click="router.push('/requirements/new')">提报需求</el-button>
      </div>
    </header>

    <section class="surface pool__stats" aria-label="需求统计">
      <button
        v-for="tab in STATUS_TABS"
        :key="tab.key"
        type="button"
        class="chip-count"
        :class="{ 'is-active': (filters.status || '') === tab.value }"
        @click="switchStatus(tab.value)"
      >
        {{ tab.label }}
        <span class="count-badge">{{ counts[tab.key] }}</span>
      </button>
      <span class="filter-bar__spacer" />
      <span class="pool__hint">统计随类型/域/厂商/搜索联动；点击即筛选</span>
    </section>

    <div v-if="summaryMode" class="summary-chip summary-chip--bar" role="status">
      摘要视图 · 仅显示标题 / 摘要 / 域 / 状态，人天与研发细节不渲染
    </div>

    <div class="pool__body">
      <section class="surface pool__list">
        <div class="pool__type-tabs" role="tablist" aria-label="需求类型">
          <button
            v-for="tab in TYPE_TABS"
            :key="tab.value || 'all'"
            type="button"
            role="tab"
            class="pool__type-tab"
            :class="{ 'pool__type-tab--active': (filters.type || '') === tab.value }"
            :aria-selected="(filters.type || '') === tab.value"
            @click="switchType(tab.value)"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="filter-bar">
          <el-input
            v-model="searchText"
            placeholder="搜索编号 / 标题，回车确认"
            clearable
            style="width: 180px"
            aria-label="搜索需求"
            @keyup.enter="applySearch"
            @clear="applySearch"
          />
          <el-popover placement="bottom-start" :width="280" trigger="click">
            <template #reference>
              <el-button :type="activeFilterCount ? 'primary' : 'default'" plain>
                筛选<span v-if="activeFilterCount" class="count-badge">{{ activeFilterCount }}</span>
              </el-button>
            </template>
            <div class="pool__filters">
              <label class="pool__field">
                <span>业务域</span>
                <el-select :model-value="filters.domainId" placeholder="全部域" clearable @change="onDomain">
                  <el-option v-for="item in domains" :key="item.id" :label="item.name" :value="String(item.id)" />
                </el-select>
              </label>
              <label class="pool__field">
                <span>厂商</span>
                <el-select :model-value="filters.vendorId" placeholder="全部厂商" clearable @change="onVendor">
                  <el-option v-for="item in vendors" :key="item.id" :label="item.name" :value="String(item.id)" />
                </el-select>
              </label>
              <label class="pool__field">
                <span>优先级</span>
                <el-select :model-value="filters.priority" placeholder="全部优先级" clearable @change="onPriority">
                  <el-option v-for="item in PRIORITIES" :key="item" :label="item" :value="item" />
                </el-select>
              </label>
              <el-checkbox v-model="summaryToggle" label="摘要视图（受限账号）" />
              <div class="pool__filters-foot">
                <el-button size="small" @click="reset()">重置</el-button>
              </div>
            </div>
          </el-popover>

          <div class="pool__switch" role="group" aria-label="视图切换">
            <button
              type="button"
              class="pool__switch-btn"
              :class="{ 'is-active': viewMode === 'agg' }"
              @click="switchView('agg')"
            >
              父需求聚合
            </button>
            <button
              type="button"
              class="pool__switch-btn"
              :class="{ 'is-active': viewMode === 'flat' }"
              @click="switchView('flat')"
            >
              平铺
            </button>
          </div>
          <span class="filter-bar__spacer" />
          <span v-if="isFiltered" class="pool__filtered">筛选后 {{ total }} 条</span>
        </div>

        <div class="scroll-y pool__cards" aria-live="polite">
          <div v-if="loading && rows.length === 0" class="pool__skeleton" aria-hidden="true">
            <div v-for="index in 8" :key="index" class="skeleton skeleton__row" />
          </div>

          <ErrorState
            v-else-if="error"
            :message="error.message"
            :code="error.code"
            :status="error.status"
            @retry="load()"
          />

          <EmptyState
            v-else-if="isEmpty"
            :variant="isFiltered ? 'filtered' : 'empty'"
            :title="isFiltered ? '当前筛选没有需求' : '需求池还是空的'"
            :description="
              isFiltered ? '清空筛选或换个类型再看。' : '从「提报需求」开始，业务、产品、研发三类需求都会进入这里。'
            "
            :action-text="isFiltered ? '清空筛选' : '提报需求'"
            icon="inbox"
            @action="isFiltered ? reset() : router.push('/requirements/new')"
          />

          <ul v-else class="req-cards">
            <li v-for="row in rows" :key="row.id">
              <button
                type="button"
                class="req-card"
                :class="{ 'req-card--active': selectedId === row.id }"
                @click="selectRow(row)"
              >
                <span class="req-card__top">
                  <span class="req-card__code">{{ row.code }}</span>
                  <span class="req-card__type">{{ typeLabel(row.type) }}</span>
                  <StatusPill kind="requirement" :value="row.status" size="sm" />
                </span>
                <span class="req-card__title" :title="row.title">{{ row.title }}</span>
                <span class="req-card__meta">
                  <PriorityDot :value="row.priority" />
                  <span v-if="row.domainName || row.domainId" class="req-card__tag">
                    {{ row.domainName ?? domainName(row.domainId) }}
                  </span>
                  <span v-if="!summaryMode && (row.vendorName || row.vendorId)" class="req-card__tag">
                    {{ row.vendorName ?? vendorName(row.vendorId) }}
                  </span>
                </span>
                <span class="req-card__foot">
                  <span v-if="childInfo(row).childCount">
                    子 {{ childInfo(row).doneCount }}/{{ childInfo(row).childCount }}
                  </span>
                  <span v-if="!summaryMode && row.estimateDays != null">{{ formatDays(row.estimateDays) }}</span>
                  <span class="req-card__time">{{ formatDate(row.updatedAt ?? row.createdAt) }}</span>
                </span>
              </button>
              <el-dropdown class="req-card__more" trigger="click" @command="(cmd: string) => openRowMore(cmd, row)">
                <button type="button" class="req-card__more-btn" :aria-label="`更多操作 ${row.code}`" @click.stop>
                  ⋯
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="open">打开详情</el-dropdown-item>
                    <el-dropdown-item command="copy">复制编号与标题</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </li>
          </ul>
        </div>

        <div v-if="total > size" class="pool__pager">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="setPage"
          />
        </div>
      </section>

      <section class="surface pool__detail" aria-label="需求详情预览">
        <div v-if="!selectedId" class="pool__placeholder">
          <EmptyState
            title="从左侧选择一条需求"
            description="选中后在这里预览字段、子需求进度与可执行动作，不离开列表即可推进。"
            icon="search"
            compact
          />
        </div>

        <div v-else-if="selectedLoading && !selected" class="pool__detail-skeleton" aria-hidden="true">
          <div v-for="index in 6" :key="index" class="skeleton skeleton__row" />
        </div>

        <ErrorState
          v-else-if="selectedError"
          :message="selectedError.message"
          :code="selectedError.code"
          :status="selectedError.status"
          @retry="loadSelected()"
        />

        <article v-else-if="selected" class="pool__preview">
          <header class="pool__preview-head">
            <div class="pool__preview-titleline">
              <span class="mono">{{ selected.code }}</span>
              <StatusPill kind="requirement" :value="selected.status" />
              <PriorityDot :value="selected.priority" />
              <span class="pool__preview-type">{{ typeLabel(selected.type) }}</span>
            </div>
            <h2 class="pool__preview-title">{{ selected.title }}</h2>
            <p v-if="selected.description" class="pool__preview-desc">{{ selected.description }}</p>
          </header>

          <div v-if="actionError" class="action-banner" role="alert">
            <strong>操作被拒绝（{{ actionError.status }}）：{{ actionError.message }}</strong>
            <span>清单已刷新为当前可执行动作；若仍失败请联系管理员。</span>
            <el-button text size="small" @click="actionError = null">知道了</el-button>
          </div>

          <dl class="pool__facts">
            <div>
              <dt>业务域</dt>
              <dd>{{ selected.domainName ?? domainName(selected.domainId) }}</dd>
            </div>
            <div v-if="!summaryMode">
              <dt>厂商</dt>
              <dd>{{ selected.vendorName ?? vendorName(selected.vendorId) }}</dd>
            </div>
            <div v-if="!summaryMode">
              <dt>负责人</dt>
              <dd>{{ selected.ownerName ?? userName(selected.ownerId) }}</dd>
            </div>
            <div>
              <dt>优先级</dt>
              <dd>{{ selected.priority }}</dd>
            </div>
            <div v-if="!summaryMode">
              <dt>期望交付</dt>
              <dd>{{ formatDate(selected.expectedDeliveryDate) }}</dd>
            </div>
            <div v-if="!summaryMode">
              <dt>时间</dt>
              <dd>{{ formatDate(selected.updatedAt ?? selected.createdAt) }}</dd>
            </div>
          </dl>

          <div v-if="!summaryMode" class="pool__rollup">
            <span>
              子需求完成
              <strong>{{ childInfo(selected).doneCount }}/{{ childInfo(selected).childCount }}</strong>
            </span>
            <span v-if="selected.estimateDays != null">预估 {{ formatDays(selected.estimateDays) }}</span>
            <span v-if="selected.actualDays != null">实际 {{ formatDays(selected.actualDays) }}</span>
          </div>

          <footer class="pool__preview-foot">
            <div class="pool__actions">
              <el-button
                v-for="action in allowedActions"
                :key="action"
                size="small"
                :type="metaOf(action).danger ? 'danger' : 'default'"
                plain
                :disabled="!actionUsable(action)"
                :title="actionUsable(action) ? '' : actionDisabledReason(action)"
                :loading="actionLoading === action"
                @click="triggerAction(action)"
              >
                {{ metaOf(action).label }}
              </el-button>
              <span v-if="allowedActions.length === 0" class="pool__no-action">当前状态无流转动作</span>
            </div>
            <el-button text type="primary" @click="openDetail">打开完整详情 →</el-button>
          </footer>
        </article>
      </section>
    </div>

    <ConfirmDialog
      v-model="confirmOpen"
      :title="`${pendingAction?.label ?? '确认'} · ${selected?.code ?? ''}`"
      :description="`对需求「${selected?.title ?? ''}」执行${pendingAction?.label ?? ''}，请填写原因。`"
      :impact="confirmImpact"
      :confirm-text="pendingAction?.label ?? '确认'"
      tone="danger"
      :loading="!!actionLoading"
      @confirm="confirmAction"
    />
  </div>
</template>

<style scoped>
.pool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pool__stats {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
}
.pool__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__body {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
  min-height: 520px;
}
.pool__list {
  display: flex;
  flex-direction: column;
  min-height: 520px;
  overflow: hidden;
}
.pool__type-tabs {
  display: flex;
  gap: 2px;
  padding: 8px 12px 0;
  border-bottom: 1px solid var(--divider);
}
.pool__type-tab {
  height: 32px;
  padding: 0 10px;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--text-body);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.pool__type-tab:hover {
  color: var(--color-primary);
}
.pool__type-tab--active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 500;
}
.pool__type-tab:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.filter-bar {
  padding: 8px 12px;
}
.pool__switch {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  overflow: hidden;
}
.pool__switch-btn {
  height: 30px;
  padding: 0 10px;
  border: 0;
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
  transition: background var(--t-color) var(--ease), color var(--t-color) var(--ease);
}
.pool__switch-btn + .pool__switch-btn {
  border-left: 1px solid var(--border);
}
.pool__switch-btn:hover {
  background: var(--bg-hover);
}
.pool__switch-btn.is-active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}
.pool__switch-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.pool__filtered {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__cards {
  flex: 1;
  min-height: 0;
  padding: 4px 8px 8px;
}
.pool__skeleton,
.pool__detail-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}
.req-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.req-cards li {
  position: relative;
}
.req-card {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.req-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.req-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.req-card--active {
  border-color: var(--color-primary);
  background: var(--bg-selected);
  box-shadow: inset 2px 0 0 var(--color-primary);
}
.req-card__top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.req-card__code {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  color: var(--text-body);
}
.req-card__type {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.req-card__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.req-card__meta,
.req-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.req-card__tag {
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  color: var(--text-body);
  line-height: 18px;
}
.req-card__time {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.req-card__more {
  position: absolute;
  top: 6px;
  right: 6px;
  opacity: 0;
  transition: opacity var(--t-color) var(--ease);
}
.req-cards li:hover .req-card__more,
.req-card__more:focus-within {
  opacity: 1;
}
.req-card__more-btn {
  width: 24px;
  height: 24px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-body);
  cursor: pointer;
  line-height: 1;
}
.req-card__more-btn:hover {
  background: var(--bg-hover);
}
.pool__pager {
  padding: 8px 12px;
  border-top: 1px solid var(--divider);
  display: flex;
  justify-content: flex-end;
}
.pool__detail {
  min-height: 520px;
  padding: 16px;
  overflow: hidden;
}
.pool__placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.pool__preview {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
}
.pool__preview-titleline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-body);
}
.pool__preview-type {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__preview-title {
  margin: 8px 0 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title);
}
.pool__preview-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-body);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pool__facts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px 16px;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-page);
}
.pool__facts div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pool__facts dt {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__facts dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-title);
}
.pool__rollup {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-body);
}
.pool__rollup strong {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.pool__preview-foot {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
}
.pool__actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.pool__no-action {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.pool__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.pool__filters-foot {
  display: flex;
  justify-content: flex-end;
}
.action-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-cell);
  background: var(--color-danger-bg);
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
