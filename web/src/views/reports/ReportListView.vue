<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ShareDialog from '@/components/analytics/ShareDialog.vue'
import { copyReport, deleteReport, getReport, listReports, REPORT_ERROR_TEXT } from '@/api/analytics'
import type { ReportDetail, ReportScope, ReportSummary } from '@/api/analytics'
import { ApiError } from '@/api/client'
import { readSession } from '@/api/session'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime } from '@/utils/format'
import '@/views/analytics/shared.css'

/**
 * /reports 报表中心（AC-9 / AC-10 / DESIGN §3.3）。
 *
 * 分页、关键词与 Tab 全部走 `useListQuery`（URL 即状态、自写签名防循环），
 * 深链回来仍是同一段同一页；视图内不保存筛选的「第二份真相」。
 *
 * 操作可见性是**预判**而不是事后兜底（AC-9「不得点了才 403」）：
 * 后端没有 REPORT_WRITE（报表读写都走 REPORT_VIEW），所以
 * 删除 = 属主；共享 = 属主 && REPORT_SHARE（G-4：列表响应不带 canShare）。
 */
const router = useRouter()
const { can } = usePermission()

const SEGMENT_LABEL: Record<string, string> = { MINE: '我的报表', SHARED: '共享给我' }
const SCOPE_LABEL: Record<ReportScope, string> = {
  PRIVATE: '私有',
  ROLE: '角色',
  DOMAIN: '业务域',
  VENDOR: '厂商',
  PUBLIC: '公开',
}
const SCOPE_TAG: Record<ReportScope, 'info' | 'warning' | 'primary' | 'success'> = {
  PRIVATE: 'info',
  ROLE: 'warning',
  DOMAIN: 'primary',
  VENDOR: 'primary',
  PUBLIC: 'success',
}

const list = useListQuery<ReportSummary>(
  (query) =>
    listReports({
      segment: (query.filters.segment as 'MINE' | 'SHARED') || 'MINE',
      keyword: query.filters.keyword || undefined,
      page: query.page,
      size: query.size,
    }),
  { segment: 'MINE', keyword: '' },
)

const keywordInput = ref(list.filters.value.keyword ?? '')
const segment = computed(() => (list.filters.value.segment === 'SHARED' ? 'SHARED' : 'MINE'))
const hasKeyword = computed(() => Boolean(list.filters.value.keyword))
const canShare = computed(() => can('REPORT_SHARE'))
const rows = computed(() => list.rows.value)

/** URL 是筛选的唯一真相：前进/后退或外部改写 query 时，输入框跟着回读 */
watch(
  () => list.filters.value.keyword,
  (value) => {
    const next = value ?? ''
    if (next !== keywordInput.value) keywordInput.value = next
  },
)

const shareOpen = ref(false)
const shareTarget = ref<ReportDetail | null>(null)

type BusyKind = 'copy' | 'share' | 'delete'
const busy = ref<{ id: number; kind: BusyKind } | null>(null)

function isBusy(row: ReportSummary, kind: BusyKind): boolean {
  return busy.value?.id === row.id && busy.value?.kind === kind
}

function rowLocked(row: ReportSummary): boolean {
  return busy.value?.id === row.id
}

function errorTextOf(cause: unknown): string {
  if (cause instanceof ApiError) return REPORT_ERROR_TEXT[cause.code] ?? cause.message
  return cause instanceof Error ? cause.message : String(cause)
}

const loadErrorText = computed(() => {
  const error = list.error.value
  if (!error) return ''
  return REPORT_ERROR_TEXT[error.code] ?? error.message
})

/** 空态分三种：我的没有、别人没共享、关键词无果——主操作完全不同（DESIGN §3.3） */
const emptyState = computed(() => {
  if (hasKeyword.value) {
    return {
      title: '没有匹配的报表',
      description: `关键词「${list.filters.value.keyword}」在${SEGMENT_LABEL[segment.value]}中没有结果`,
      actionText: '清空筛选',
      variant: 'filtered' as const,
      icon: 'search' as const,
    }
  }
  if (segment.value === 'SHARED') {
    return {
      title: '还没有人共享给你',
      description: '共享范围由报表属主设置；被共享后可在此打开',
      actionText: '',
      variant: 'empty' as const,
      icon: 'inbox' as const,
    }
  }
  return {
    title: '还没有报表',
    description: '选择数据集与字段，保存后即可在门户固定',
    actionText: '新建报表',
    variant: 'empty' as const,
    icon: 'chart' as const,
  }
})

function changeSegment(next: string): void {
  if (next === segment.value) return
  void list.setFilter('segment', next)
}

function search(): void {
  void list.setFilter('keyword', keywordInput.value.trim())
}

function clearKeyword(): void {
  keywordInput.value = ''
  void list.setFilter('keyword', '')
}

function onEmptyAction(): void {
  if (hasKeyword.value) {
    clearKeyword()
    return
  }
  void router.push('/reports/new')
}

function ownerOf(row: ReportSummary): boolean {
  return row.ownerId === (readSession()?.user?.id ?? 0)
}

function canShareRow(row: ReportSummary): boolean {
  return canShare.value && ownerOf(row)
}

function canDeleteRow(row: ReportSummary): boolean {
  return ownerOf(row)
}

async function openRow(row: ReportSummary): Promise<void> {
  await router.push(`/reports/${row.id}`)
}

async function copyRow(row: ReportSummary): Promise<void> {
  if (busy.value) return
  busy.value = { id: row.id, kind: 'copy' }
  try {
    const copy = await copyReport(row.id)
    ElMessage.success(`已复制为「${copy.name}」`)
    await list.refresh()
  } catch (cause) {
    ElMessage.error(errorTextOf(cause))
  } finally {
    busy.value = null
  }
}

async function openShare(row: ReportSummary): Promise<void> {
  if (busy.value) return
  busy.value = { id: row.id, kind: 'share' }
  try {
    shareTarget.value = await getReport(row.id)
    shareOpen.value = true
  } catch (cause) {
    ElMessage.error(errorTextOf(cause))
  } finally {
    busy.value = null
  }
}

async function removeRow(row: ReportSummary): Promise<void> {
  if (busy.value) return
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」后不可恢复；引用它的工作台卡片也会消失。`,
      '删除报表',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = { id: row.id, kind: 'delete' }
  try {
    await deleteReport(row.id)
    ElMessage.success('报表已删除')
    // 删掉本页最后一行时退回上一页，避免停在一张空页上
    if (rows.value.length === 1 && list.page.value > 1) await list.setPage(list.page.value - 1)
    else await list.refresh()
  } catch (cause) {
    ElMessage.error(errorTextOf(cause))
  } finally {
    busy.value = null
  }
}

async function afterShared(scope: ReportScope): Promise<void> {
  ElMessage.success(`共享范围已更新为「${SCOPE_LABEL[scope]}」`)
  await list.refresh()
}
</script>

<template>
  <div class="an-page" data-page="report-list">
    <header class="an-head">
      <div>
        <h1 class="an-head__title">报表中心</h1>
        <p class="an-head__sub">共享的是报表定义，数据按查看者的可见范围重新聚合</p>
      </div>
      <div class="an-head__actions">
        <el-button size="small" type="primary" @click="router.push('/reports/new')">新建报表</el-button>
      </div>
    </header>

    <div class="an-filters">
      <el-radio-group
        :model-value="segment"
        size="small"
        @update:model-value="(value: string | number | boolean) => changeSegment(String(value))"
      >
        <el-radio-button value="MINE">我的报表</el-radio-button>
        <el-radio-button value="SHARED">共享给我</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keywordInput"
        size="small"
        placeholder="搜索报表名称"
        clearable
        class="an-filters__search"
        @keyup.enter="search"
        @clear="clearKeyword"
      />
      <el-button size="small" @click="search">查询</el-button>
      <span class="an-filters__spacer" />
      <span class="an-hint">共 {{ list.total.value }} 张</span>
    </div>

    <!-- 翻页/刷新失败时保留旧行，只加一条可重试的提示，不让整页变空白 -->
    <div v-if="list.error.value && rows.length" class="an-hint an-hint--warn" role="alert">
      <span>{{ loadErrorText }}</span>
      <button type="button" class="an-link" @click="list.refresh()">重试</button>
    </div>

    <ErrorState
      v-else-if="list.error.value"
      :message="loadErrorText"
      :code="list.error.value.code"
      :status="list.error.value.status"
      @retry="list.refresh()"
    />

    <template v-else>
      <div v-loading="list.loading.value" class="an-card">
        <table v-if="rows.length" class="an-table an-table--reports">
          <thead>
            <tr>
              <th>名称</th>
              <th>数据集</th>
              <th>共享范围</th>
              <th>更新时间</th>
              <th>属主</th>
              <th class="an-table__ops-head">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :class="{ 'is-busy': rowLocked(row) }">
              <td>
                <button type="button" class="an-link an-link--name" @click="openRow(row)">{{ row.name }}</button>
                <span class="an-hint"> · {{ row.dimCount }} 维 / {{ row.measureCount }} 值</span>
              </td>
              <td>{{ row.datasetLabel }}</td>
              <td>
                <el-tag size="small" effect="plain" :type="SCOPE_TAG[row.scope]">
                  {{ SCOPE_LABEL[row.scope] }}
                </el-tag>
              </td>
              <td>{{ formatDateTime(row.updatedAt) }}</td>
              <td>{{ row.ownerName }}</td>
              <td class="an-table__ops">
                <button type="button" class="an-link" :disabled="rowLocked(row)" @click="openRow(row)">打开</button>
                <button type="button" class="an-link" :disabled="rowLocked(row)" @click="copyRow(row)">
                  {{ isBusy(row, 'copy') ? '复制中…' : '复制' }}
                </button>
                <button
                  v-if="canShareRow(row)"
                  type="button"
                  class="an-link"
                  :disabled="rowLocked(row)"
                  @click="openShare(row)"
                >
                  {{ isBusy(row, 'share') ? '打开中…' : '共享' }}
                </button>
                <button
                  v-if="canDeleteRow(row)"
                  type="button"
                  class="an-link an-link--danger"
                  :disabled="rowLocked(row)"
                  @click="removeRow(row)"
                >
                  {{ isBusy(row, 'delete') ? '删除中…' : '删除' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <EmptyState
          v-else-if="list.isEmpty.value"
          :variant="emptyState.variant"
          :title="emptyState.title"
          :description="emptyState.description"
          :action-text="emptyState.actionText"
          :icon="emptyState.icon"
          @action="onEmptyAction"
        />
      </div>

      <div v-if="list.total.value > list.size.value" class="an-table__pager">
        <el-pagination
          :current-page="list.page.value"
          :page-size="list.size.value"
          :total="list.total.value"
          layout="prev, pager, next, total"
          small
          @current-change="(value: number) => list.setPage(value)"
        />
      </div>
    </template>

    <ShareDialog v-model="shareOpen" :report="shareTarget" @saved="afterShared" />
  </div>
</template>

<style scoped>
.an-filters__search {
  width: 220px;
}

.an-table--reports td {
  height: 48px;
  vertical-align: middle;
}

.an-table--reports tr.is-busy td {
  opacity: 0.6;
}

.an-table__ops-head {
  width: 200px;
}

.an-table__ops {
  display: flex;
  gap: 10px;
  align-items: center;
}

.an-link {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--color-primary);
  font-size: 13px;
  cursor: pointer;
  border-radius: 2px;
}

.an-link:hover:not(:disabled) {
  text-decoration: underline;
}

.an-link:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.an-link:disabled {
  color: var(--text-secondary);
  cursor: progress;
}

.an-link--name {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.an-link--danger {
  color: var(--color-danger);
}
</style>
