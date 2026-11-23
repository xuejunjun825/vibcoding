<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ApiError } from '@/api/client'
import {
  getMeta,
  listRules,
  listRuns,
  triggerLabel,
  type AutomationMeta,
  type AutomationRule,
  type AutomationRun,
} from '@/api/automation'
import { useListQuery } from '@/composables/useListQuery'
import AutomationRunVerdict, { verdictLabel } from '@/components/AutomationRunVerdict.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import { requirementPath } from '@/views/req/platform'
import '@/views/admin/admin-shared.css'
import './automation.css'

/**
 * 执行日志（DESIGN §3.3 / §4.3）。
 *
 * 两条产品语义在这里变成界面约束：
 * 1. 这里记录的是**进入判定的候选规则**——条件没命中也有一行并写明原因，
 *    否则用户会误以为「规则没触发」（其实是判了、没命中）。
 * 2. 默认最近 7 天必须**显示为可移除 chip**：空表时用户要能分清「筛掉了」还是「真没有」。
 *    不自动刷新，只给「刷新 + 更新于 hh:mm:ss」——轮询会被误读成实时因果。
 */
const route = useRoute()
const router = useRouter()

const RANGES = [
  { key: '7d', label: '最近 7 天' },
  { key: '30d', label: '最近 30 天' },
  { key: 'all', label: '全部时间' },
]
const STATUS_OPTIONS = ['RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED']

const list = useListQuery<AutomationRun>(
  ({ page, size, filters }) => {
    const range = filters.range || '7d'
    const to = dayjs()
    const from =
      range === 'all' ? null : range === '30d' ? to.subtract(29, 'day') : to.subtract(6, 'day')
    return listRuns({
      ruleId: filters.ruleId ? Number(filters.ruleId) : undefined,
      status: filters.status || undefined,
      requirementCode: filters.requirementCode || undefined,
      from: from ? from.format('YYYY-MM-DD') : undefined,
      to: from ? to.format('YYYY-MM-DD') : undefined,
      page,
      size,
    })
  },
  { ruleId: '', status: '', requirementCode: '', range: '7d' },
)

const meta = ref<AutomationMeta | null>(null)
const ruleOptions = ref<AutomationRule[]>([])
const error = ref<ApiError | null>(null)
const updatedAt = ref<Date | null>(null)
const expandedId = ref<number | null>(null)
const codeInput = ref(list.filters.value.requirementCode ?? '')
let debounceTimer: number | undefined

const range = computed(() => list.filters.value.range || '7d')
const rangeLabel = computed(() => RANGES.find((item) => item.key === range.value)?.label ?? '最近 7 天')
const clock = computed(() => {
  const at = updatedAt.value
  if (!at) return '—'
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`
})

/** 默认 7 天不算「用户筛选」：空表要显示「真的没有」，而不是「筛掉了」 */
const isFiltered = computed(
  () =>
    Boolean(list.filters.value.ruleId) ||
    Boolean(list.filters.value.status) ||
    Boolean(list.filters.value.requirementCode) ||
    range.value !== '7d',
)

watch(
  () => list.loading.value,
  (now, before) => {
    if (before && !now) updatedAt.value = new Date()
  },
)

watch(
  () => list.filters.value.requirementCode,
  (value) => {
    if ((value ?? '') !== codeInput.value) codeInput.value = value ?? ''
  },
)

onMounted(async () => {
  try {
    meta.value = await getMeta()
  } catch (caught) {
    error.value = asApiError(caught)
  }
  try {
    const response = await listRules({ page: 1, size: 200 })
    ruleOptions.value = response.items ?? []
  } catch {
    ruleOptions.value = []
  }
  const ruleId = route.query.ruleId
  if (ruleId && !list.filters.value.ruleId) {
    void list.setFilter('ruleId', String(ruleId))
  }
})

function asApiError(caught: unknown): ApiError {
  return caught instanceof ApiError
    ? caught
    : new ApiError('UNKNOWN', '加载失败，请重试', 0, caught)
}

function onCodeInput(value: string) {
  codeInput.value = value
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    void list.setFilter('requirementCode', value.trim())
  }, 300)
}

function onCodeEnter() {
  window.clearTimeout(debounceTimer)
  void list.setFilter('requirementCode', codeInput.value.trim())
}

function setRange(key: string) {
  void list.setFilter('range', key)
}

function clearFilters() {
  void list.setFilters({ ruleId: '', status: '', requirementCode: '', range: '7d' })
  expandedId.value = null
}

function toggle(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

function goTab(key: 'rules' | 'runs') {
  if (key === 'runs') return
  void router.push('/automation/rules')
}

async function onTabKey(event: KeyboardEvent, key: 'rules' | 'runs') {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const target = key === 'rules' ? 'runs' : 'rules'
  goTab(target as 'rules' | 'runs')
  await nextTick()
  document.getElementById(`auto-tab-${target}`)?.focus()
}

function triggerText(run: AutomationRun): string {
  if (!run.eventType) return '—'
  return triggerLabel(meta.value, run.eventType)
}

function durationText(run: AutomationRun): string {
  return run.durationMs === undefined || run.durationMs === null ? '—' : `${run.durationMs} ms`
}

const ACTION_STATUS: Record<string, string> = {
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
  SKIPPED: '跳过',
  NOOP: '无变化',
}

function actionStatusLabel(status?: string | null): string {
  if (!status) return '—'
  return ACTION_STATUS[String(status).toUpperCase()] ?? String(status)
}

function actionStatusTone(status?: string | null): string {
  const value = String(status ?? '').toUpperCase()
  if (value === 'SUCCESS') return 'ink-success'
  if (value === 'PARTIAL') return 'ink-warning'
  if (value === 'FAILED') return 'ink-danger'
  return 'ink-muted'
}

/** 日志页永不显示完整 URL：webhook 只留路径（AC-12） */
function safeTarget(target?: string | null): string {
  if (!target) return ''
  const value = String(target)
  if (!/^https?:\/\//i.test(value)) return value
  try {
    const url = new URL(value)
    return `${url.pathname}${url.search}`
  } catch {
    return value.replace(/^https?:\/\/[^/]+/i, '')
  }
}

interface ActionResultRow {
  type?: string
  status?: string
  message?: string | null
  target?: string | null
}

function actionTarget(action: ActionResultRow): string {
  return safeTarget(action.target) || safeTarget(action.message)
}

const pageError = computed(() => error.value ?? list.error.value)
const forbidden = computed(() => pageError.value?.status === 403 || pageError.value?.code === 'FORBIDDEN')
</script>

<template>
  <div class="auto-page">
    <div class="auto-head">
      <div class="auto-tabs" role="tablist" aria-label="自动化规则视图">
        <button
          id="auto-tab-rules"
          type="button"
          role="tab"
          class="auto-tab"
          aria-selected="false"
          tabindex="-1"
          @click="goTab('rules')"
          @keydown="onTabKey($event, 'rules')"
        >
          规则
        </button>
        <button
          id="auto-tab-runs"
          type="button"
          role="tab"
          class="auto-tab is-on"
          aria-selected="true"
          tabindex="0"
          @click="goTab('runs')"
          @keydown="onTabKey($event, 'runs')"
        >
          执行日志
        </button>
      </div>
      <span class="auto-count">更新于 {{ clock }}</span>
    </div>

    <p class="auto-note">规则自身产生的事件不会再次触发规则，因此这里不会出现它们。</p>

    <section class="surface">
      <div class="filter-bar">
        <el-select
          :model-value="list.filters.value.ruleId"
          placeholder="全部规则"
          clearable
          filterable
          style="width: 180px"
          @update:model-value="(value: string) => list.setFilter('ruleId', value ?? '')"
        >
          <el-option label="全部规则" value="" />
          <el-option
            v-for="rule in ruleOptions"
            :key="rule.id"
            :label="`${rule.name}（v${rule.version}）`"
            :value="String(rule.id)"
          />
        </el-select>
        <el-select
          :model-value="list.filters.value.status"
          placeholder="全部结果"
          clearable
          style="width: 132px"
          @update:model-value="(value: string) => list.setFilter('status', value ?? '')"
        >
          <el-option label="全部结果" value="" />
          <el-option
            v-for="status in STATUS_OPTIONS"
            :key="status"
            :label="verdictLabel(status)"
            :value="status"
          />
        </el-select>
        <el-input
          :model-value="codeInput"
          placeholder="需求编号"
          clearable
          style="width: 160px"
          @update:model-value="onCodeInput"
          @keyup.enter="onCodeEnter"
          @clear="onCodeEnter"
        />
        <el-select
          :model-value="range"
          style="width: 132px"
          @update:model-value="(value: string) => setRange(value ?? '7d')"
        >
          <el-option v-for="item in RANGES" :key="item.key" :label="item.label" :value="item.key" />
        </el-select>
        <span v-if="range !== 'all'" class="range-chip">
          时间：{{ rangeLabel }}
          <button
            type="button"
            class="range-chip__x"
            aria-label="清除时间范围"
            title="清除时间范围"
            @click="setRange('all')"
          >
            ×
          </button>
        </span>
        <span class="filter-bar__spacer" />
        <el-button size="small" @click="list.refresh()">刷新</el-button>
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
          <div v-for="index in 8" :key="index" class="auto-skel__row auto-skel__row--run">
            <span class="auto-skel__bar auto-skel__bar--name" />
            <span class="auto-skel__bar auto-skel__bar--sentence" />
            <span class="auto-skel__bar auto-skel__bar--cell" />
          </div>
        </div>

        <EmptyState
          v-else-if="list.isEmpty.value"
          :variant="isFiltered ? 'filtered' : 'empty'"
          :icon="isFiltered ? 'filter' : 'chart'"
          :title="isFiltered ? '没有匹配的执行记录' : '规则命中后会在这里出现'"
          :description="
            isFiltered
              ? '换个规则或结果，或者把时间范围放宽。'
              : '这里只记录进入判定的候选规则——条件没命中也有一行，并写明原因。'
          "
          :action-text="isFiltered ? '清除筛选' : undefined"
          @action="clearFilters"
        />

        <div v-else class="run-table-wrap">
          <table class="run-table">
            <colgroup>
              <col style="width: 140px" />
              <col style="width: 180px" />
              <col style="width: 120px" />
              <col style="width: 96px" />
              <col />
              <col style="width: 64px" />
              <col style="width: 36px" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">时间</th>
                <th scope="col">规则</th>
                <th scope="col">需求</th>
                <th scope="col">触发</th>
                <th scope="col">结果</th>
                <th scope="col" class="run-num">耗时</th>
                <th scope="col"><span class="run-sr">展开</span></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="run in list.rows.value" :key="run.id">
                <tr class="run-row">
                  <td class="cell-mono">{{ formatDateTime(run.createdAt) }}</td>
                  <td>
                    <router-link
                      class="run-rule-link"
                      :to="`/automation/rules/${run.ruleId}/edit`"
                      :title="run.ruleName ?? `规则 #${run.ruleId}`"
                    >
                      {{ run.ruleName ?? `规则 #${run.ruleId}` }}
                    </router-link>
                    <span class="run-version">v{{ run.ruleVersion ?? '?' }}</span>
                  </td>
                  <td>
                    <router-link
                      v-if="run.requirementId"
                      class="run-req-link"
                      :to="requirementPath(run.requirementId)"
                      :title="run.requirementCode ?? ''"
                    >
                      {{ run.requirementCode ?? `#${run.requirementId}` }}
                    </router-link>
                    <span v-else class="ink-muted">—</span>
                  </td>
                  <td class="cell-sub">{{ triggerText(run) }}</td>
                  <td>
                    <AutomationRunVerdict
                      :status="run.status"
                      :skip-reason="run.skipReason"
                      show-reason
                    />
                  </td>
                  <td class="cell-mono run-num">{{ durationText(run) }}</td>
                  <td>
                    <button
                      type="button"
                      class="run-expand-btn"
                      :aria-expanded="expandedId === run.id"
                      :aria-controls="`run-detail-${run.id}`"
                      :aria-label="expandedId === run.id ? '收起执行详情' : '展开执行详情'"
                      :title="expandedId === run.id ? '收起' : '展开'"
                      @click="toggle(run.id)"
                    >
                      {{ expandedId === run.id ? '▾' : '▸' }}
                    </button>
                  </td>
                </tr>
                <tr
                  v-if="expandedId === run.id"
                  :id="`run-detail-${run.id}`"
                  class="run-detail-row"
                >
                  <td colspan="7">
                    <div class="run-expand">
                      <table v-if="run.actions && run.actions.length" class="run-mini">
                        <thead>
                          <tr>
                            <th scope="col" style="width: 32px">#</th>
                            <th scope="col" style="width: 132px">动作</th>
                            <th scope="col" style="width: 96px">结果</th>
                            <th scope="col">说明</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(action, index) in run.actions" :key="index">
                            <td class="cell-mono">{{ index + 1 }}</td>
                            <td>{{ action.type }}</td>
                            <td :class="actionStatusTone(action.status)">
                              {{ actionStatusLabel(action.status) }}
                            </td>
                            <td class="cell-sub">
                              {{ actionTarget(action) || '—' }}
                            </td>
                          </tr>
                        </tbody>
                      </table>
                      <p v-else class="run-empty">未执行动作（判定阶段结束）</p>

                      <p class="run-env">
                        run {{ run.id }} · rule v{{ run.ruleVersion ?? '?' }} · event
                        {{ run.eventId ?? '—' }} · {{ formatDateTime(run.createdAt) }} · depth
                        {{ run.chainDepth ?? '—' }}
                      </p>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>

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
        </div>
      </template>
    </section>
  </div>
</template>
