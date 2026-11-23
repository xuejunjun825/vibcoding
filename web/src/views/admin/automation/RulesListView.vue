<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  getMeta,
  listRules,
  setRuleEnabled,
  type AutomationMeta,
  type AutomationRule,
} from '@/api/automation'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import AutomationRuleSentence from '@/components/AutomationRuleSentence.vue'
import { skipLabel, verdictLabel } from '@/components/AutomationRunVerdict.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { fromNow } from '@/utils/format'
import { usePlatformDict } from '@/views/req/platform'
import '@/views/admin/admin-shared.css'
import './automation.css'

/**
 * 自动化规则列表（DESIGN §3.1 / §4.1）。
 *
 * 一句话结构：**句式是第一列**，其余四列只放用户要「判断/止血」的东西
 * （优先级、启停、最近执行、入口）。8 个 PRD 字段全部可见 —— 触发与作用域
 * 变成句式 token，而不是被藏进详情页。
 *
 * 三条不可退让的点：
 * 1. 开关常驻、不 hover 才出现：停用是最高频动作；
 * 2. 停用行**不灰掉**（只降 token 字色），停用时恰恰最需要核对配了什么；
 * 3. 筛选与排序进 URL query，刷新/回跳不丢；409 走常驻条 + 静默刷新，不弹 toast。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()
const dict = usePlatformDict()

const list = useListQuery<AutomationRule>(
  ({ page, size, filters, sort }) =>
    listRules({
      keyword: filters.q || undefined,
      triggerType: filters.trigger || undefined,
      enabled: filters.enabled || undefined,
      page,
      size,
      sort: sort || undefined,
    }),
  { q: '', trigger: '', enabled: '' },
)

const meta = ref<AutomationMeta | null>(null)
const metaError = ref<ApiError | null>(null)
const enabledTotal = ref(0)
const actionError = ref<{ message: string; code?: string; conflict?: boolean } | null>(null)
const flashId = ref<number | null>(null)

const canManage = computed(() => perm.canAction('automation.action.manage'))
const manageTitle = computed(
  () => perm.denyActionReason('automation.action.manage', '管理自动化规则') || undefined,
)

/** 关键词输入与已生效筛选分开：300ms 去抖，回车立即生效 */
const keywordInput = ref(list.filters.value.q ?? '')
let debounceTimer: number | undefined

watch(
  () => list.filters.value.q,
  (value) => {
    if ((value ?? '') !== keywordInput.value) keywordInput.value = value ?? ''
  },
)

function onKeywordInput(value: string) {
  keywordInput.value = value
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    void list.setFilter('q', value.trim())
  }, 300)
}

function onKeywordEnter() {
  window.clearTimeout(debounceTimer)
  void list.setFilter('q', keywordInput.value.trim())
}

/** 启停计数：共 N 条 · 启用 M（M 走一次 size=1 的轻查询，不用当前页反推） */
async function loadEnabledTotal() {
  try {
    const response = await listRules({ enabled: 'true', page: 1, size: 1 })
    enabledTotal.value = response?.total ?? 0
  } catch {
    enabledTotal.value = 0
  }
}

watch(
  [() => list.total.value, () => list.filters.value.enabled],
  () => {
    void loadEnabledTotal()
  },
  { immediate: true },
)

/** ≤1280：隐藏「最近执行」列，信息并入句式行尾（12px muted），不丢信息 */
const narrow = ref(false)
let media: MediaQueryList | null = null
function onMediaChange(event: MediaQueryListEvent | MediaQueryList) {
  narrow.value = event.matches
}

onMounted(async () => {
  media = window.matchMedia('(max-width: 1280px)')
  narrow.value = media.matches
  media.addEventListener('change', onMediaChange)
  void dict.ensure()
  try {
    meta.value = await getMeta()
  } catch (error) {
    metaError.value = asApiError(error)
  }
  const flash = Number(route.query.flash)
  if (Number.isFinite(flash) && flash > 0) {
    flashId.value = flash
    window.setTimeout(() => {
      flashId.value = null
      const next = { ...route.query }
      delete next.flash
      void router.replace({ query: next })
    }, 700)
  }
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', onMediaChange)
  window.clearTimeout(debounceTimer)
})

function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('UNKNOWN', '加载失败，请重试', 0, error)
}

/** 服务端未声明 sort 参数：同时把排序写进 query，并对当前页做稳定排序（可见反馈） */
const displayRows = computed(() => {
  const order = list.sort.value
  if (!order.startsWith('priority,')) return list.rows.value
  const desc = order.endsWith(',desc')
  const copy = [...list.rows.value]
  copy.sort((left, right) => {
    const delta = desc ? right.priority - left.priority : left.priority - right.priority
    return delta !== 0 ? delta : left.id - right.id
  })
  return copy
})

function onSortChange(payload: { prop: string; order: string | null }) {
  if (payload.prop !== 'priority' || !payload.order) {
    void list.setSort('')
    return
  }
  void list.setSort(`priority,${payload.order === 'ascending' ? 'asc' : 'desc'}`)
}

function rowClass({ row }: { row: AutomationRule }): string {
  const classes: string[] = []
  if (!row.enabled) classes.push('is-off')
  if (flashId.value === row.id) classes.push('is-flash')
  return classes.join(' ')
}

function openCreate() {
  if (!canManage.value) return
  void router.push('/automation/rules/new')
}

function goTab(key: 'rules' | 'runs') {
  if (key === 'rules') return
  void router.push('/automation/runs')
}

async function onTabKey(event: KeyboardEvent, key: 'rules' | 'runs') {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const target = key === 'rules' ? 'runs' : 'rules'
  goTab(target as 'rules' | 'runs')
  await nextTick()
  document.getElementById(`auto-tab-${target}`)?.focus()
}

function clearFilters() {
  void list.setFilters({ q: '', trigger: '', enabled: '' })
}

/* ---------------- 启停（ConfirmDialog + version 乐观锁） ---------------- */
const pending = ref<{ row: AutomationRule; next: boolean } | null>(null)
const toggleOpen = ref(false)
const togglingId = ref<number | null>(null)

const toggleTitle = computed(() => (pending.value?.next ? '启用规则' : '停用规则'))
const toggleDesc = computed(() =>
  pending.value?.next
    ? '启用后，作用域内触发类型匹配的事件将立即开始执行。'
    : '停用后，下一次事件起不再执行；已产生的执行日志与审计保留。',
)

function askToggle(row: AutomationRule, value: string | number | boolean) {
  if (!canManage.value || togglingId.value !== null) return
  pending.value = { row, next: Boolean(value) }
  toggleOpen.value = true
}

function cancelToggle() {
  pending.value = null
}

async function confirmToggle() {
  const current = pending.value
  if (!current) return
  const { row, next } = current
  togglingId.value = row.id
  actionError.value = null
  try {
    const updated = await setRuleEnabled(row.id, next, row.version)
    list.rows.value = list.rows.value.map((item) =>
      item.id === updated.id ? { ...updated, lastRun: updated.lastRun ?? item.lastRun } : item,
    )
    flashId.value = updated.id
    window.setTimeout(() => {
      if (flashId.value === updated.id) flashId.value = null
    }, 700)
    ElMessage.success(next ? '规则已启用' : '规则已停用')
    toggleOpen.value = false
    pending.value = null
  } catch (error) {
    const caught = asApiError(error)
    toggleOpen.value = false
    pending.value = null
    if (caught.isConflict || caught.status === 409) {
      actionError.value = {
        message: '规则已被他人修改，列表已为你刷新',
        code: caught.code,
        conflict: true,
      }
      await list.refresh()
    } else {
      actionError.value = { message: caught.message, code: caught.code }
    }
  } finally {
    togglingId.value = null
  }
}

function lastRunText(row: AutomationRule): string {
  const at = row.lastRun?.at
  if (!at) return '从未执行'
  const status = row.lastRun?.status ? verdictLabel(row.lastRun.status) : ''
  const reason = row.lastRun?.skipReason ? skipLabel(row.lastRun.skipReason) : ''
  return [fromNow(at), status, reason].filter(Boolean).join(' · ')
}

function lastRunTone(row: AutomationRule): string {
  const status = String(row.lastRun?.status ?? '').toUpperCase()
  if (status === 'SUCCESS') return 'ink-success'
  if (status === 'PARTIAL') return 'ink-warning'
  if (status === 'FAILED') return 'ink-danger'
  return 'ink-muted'
}

function runsLink(row: AutomationRule): string {
  return `/automation/runs?ruleId=${row.id}`
}

const pageError = computed(() => metaError.value ?? list.error.value)
const forbidden = computed(
  () => pageError.value?.status === 403 || pageError.value?.code === 'FORBIDDEN',
)
</script>

<template>
  <div class="auto-page">
    <div class="auto-head">
      <div class="auto-tabs" role="tablist" aria-label="自动化规则视图">
        <button
          id="auto-tab-rules"
          type="button"
          role="tab"
          class="auto-tab is-on"
          aria-selected="true"
          tabindex="0"
          @click="goTab('rules')"
          @keydown="onTabKey($event, 'rules')"
        >
          规则
        </button>
        <button
          id="auto-tab-runs"
          type="button"
          role="tab"
          class="auto-tab"
          aria-selected="false"
          tabindex="-1"
          @click="goTab('runs')"
          @keydown="onTabKey($event, 'runs')"
        >
          执行日志
        </button>
      </div>
      <el-button
        :type="canManage ? 'primary' : 'default'"
        :disabled="!canManage"
        :title="manageTitle"
        @click="openCreate"
      >
        新建规则
      </el-button>
    </div>

    <section class="surface">
      <div class="filter-bar">
        <el-input
          :model-value="keywordInput"
          placeholder="搜索规则名称"
          clearable
          style="width: 240px"
          @update:model-value="onKeywordInput"
          @keyup.enter="onKeywordEnter"
          @clear="onKeywordEnter"
        />
        <el-select
          :model-value="list.filters.value.trigger"
          placeholder="触发类型"
          style="width: 132px"
          @update:model-value="(value: string) => list.setFilter('trigger', value ?? '')"
        >
          <el-option label="全部触发" value="" />
          <el-option
            v-for="trigger in meta?.triggers ?? []"
            :key="trigger.code"
            :label="trigger.label"
            :value="trigger.code"
          />
        </el-select>
        <el-select
          :model-value="list.filters.value.enabled"
          placeholder="启停"
          style="width: 120px"
          @update:model-value="(value: string) => list.setFilter('enabled', value ?? '')"
        >
          <el-option label="全部" value="" />
          <el-option label="启用" value="true" />
          <el-option label="已停用" value="false" />
        </el-select>
        <span class="filter-bar__spacer" />
        <span class="auto-count">
          共 <strong>{{ list.total.value }}</strong> 条 · 启用
          <strong>{{ enabledTotal }}</strong>
        </span>
      </div>

      <div v-if="actionError" class="auto-banner" role="alert" style="margin: 12px 16px 0">
        <span class="auto-banner__code">{{ actionError.code }}</span>
        <span>{{ actionError.message }}</span>
        <span class="auto-banner__spacer" />
        <el-button v-if="actionError.conflict" size="small" @click="list.refresh()">刷新</el-button>
        <el-button v-else size="small" text @click="actionError = null">知道了</el-button>
      </div>

      <EmptyState
        v-if="forbidden"
        variant="forbidden"
        icon="lock"
        title="仅平台管理员与内部 PMO 可管理自动化规则"
        description="厂商账号不能作为规则的操作者；需要平台侧自动化请联系 PMO。"
      />

      <ErrorState
        v-else-if="pageError && !list.rows.value.length"
        :message="pageError.message"
        :code="pageError.code"
        :status="pageError.status"
        @retry="list.load()"
      />

      <template v-else>
        <div v-if="list.loading.value && !list.rows.value.length" class="auto-skel" aria-busy="true">
          <div v-for="index in 4" :key="index" class="auto-skel__row">
            <span class="auto-skel__bar auto-skel__bar--name" />
            <span class="auto-skel__bar auto-skel__bar--sentence" />
            <span class="auto-skel__bar auto-skel__bar--cell" />
            <span class="auto-skel__bar auto-skel__bar--cell" />
          </div>
        </div>

        <EmptyState
          v-else-if="list.isEmpty.value"
          :variant="list.isFiltered.value ? 'filtered' : 'empty'"
          :icon="list.isFiltered.value ? 'filter' : 'inbox'"
          :title="list.isFiltered.value ? '没有匹配的规则' : '还没有自动化规则'"
          :description="
            list.isFiltered.value
              ? '换一个关键词或清除筛选，看看全部规则。'
              : '规则 = 触发 + 条件 + 动作；保存即生效。可以先看两条预置规则的写法：P0/P1 新建通知域负责人、人天 > 10 打标。'
          "
          :action-text="list.isFiltered.value ? '清除筛选' : canManage ? '新建规则' : undefined"
          @action="list.isFiltered.value ? clearFilters() : openCreate()"
        >
          <template v-if="!canManage && !list.isFiltered.value" #default>
            <p class="auto-count">没有「管理自动化规则」的操作权限，你仍可查看规则与执行日志。</p>
          </template>
        </EmptyState>

        <template v-else>
          <el-table
            v-loading="list.loading.value"
            :data="displayRows"
            row-key="id"
            class="auto-table"
            :row-class-name="rowClass"
            @sort-change="onSortChange"
          >
            <el-table-column label="规则" min-width="380">
              <template #default="{ row }">
                <div class="rule-cell">
                  <div class="rule-name-row">
                    <router-link class="rule-name" :to="`/automation/rules/${row.id}/edit`" :title="row.name">
                      {{ row.name }}
                    </router-link>
                    <span class="rule-version" title="规则版本（每次保存 +1）">v{{ row.version }}</span>
                    <span v-if="narrow" class="rule-tail" :class="lastRunTone(row)">
                      · {{ lastRunText(row) }}
                    </span>
                  </div>
                  <AutomationRuleSentence
                    :rule="row"
                    :meta="meta"
                    :dense="narrow"
                    :user-name="dict.userName"
                  />
                </div>
              </template>
            </el-table-column>

            <el-table-column
              prop="priority"
              label="优先级"
              width="76"
              sortable="custom"
              :sort-orders="['ascending', 'descending', null]"
            >
              <template #default="{ row }">
                <span
                  class="cell-mono"
                  title="数值小者先执行；同优先级按创建先后"
                  >{{ row.priority }}</span
                >
              </template>
            </el-table-column>

            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <div class="auto-state">
                  <el-switch
                    :model-value="row.enabled"
                    size="small"
                    :loading="togglingId === row.id"
                    :disabled="!canManage || togglingId !== null"
                    :aria-label="`启用规则 ${row.name}`"
                    :title="canManage ? (row.enabled ? '停用规则' : '启用规则') : manageTitle"
                    @change="(value: string | number | boolean) => askToggle(row, value)"
                  />
                  <span
                    class="auto-state__pill"
                    :class="row.enabled ? 'auto-state__pill--on' : 'auto-state__pill--off'"
                  >
                    {{ row.enabled ? '启用' : '已停用' }}
                  </span>
                </div>
              </template>
            </el-table-column>

            <el-table-column v-if="!narrow" label="最近执行" width="150">
              <template #default="{ row }">
                <router-link
                  class="cell-sub"
                  :class="lastRunTone(row)"
                  :to="runsLink(row)"
                  :title="row.lastRun?.at ?? '从未执行'"
                  style="text-decoration: none"
                >
                  {{ lastRunText(row) }}
                </router-link>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="132" fixed="right">
              <template #default="{ row }">
                <span class="auto-row-actions">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :disabled="!canManage"
                    :title="manageTitle"
                    @click="canManage && router.push(`/automation/rules/${row.id}/edit`)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="router.push(runsLink(row))"
                  >
                    日志
                  </el-button>
                </span>
              </template>
            </el-table-column>
          </el-table>

          <div class="adm-pager">
            <el-pagination
              layout="prev, pager, next"
              :current-page="list.page.value"
              :page-size="list.size.value"
              :total="list.total.value"
              background
              small
              @current-change="(value: number) => list.setPage(value)"
            />
          </div>
        </template>
      </template>
    </section>

    <ConfirmDialog
      v-model="toggleOpen"
      :title="toggleTitle"
      :description="toggleDesc"
      :tone="pending?.next ? 'primary' : 'danger'"
      :reason-required="false"
      :confirm-text="pending?.next ? '启用' : '停用'"
      :loading="togglingId !== null"
      @confirm="confirmToggle"
      @cancel="cancelToggle"
    />
  </div>
</template>
