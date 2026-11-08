<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/req'
import type { NotificationRow } from '@/api/types'
import { useListQuery } from '@/composables/useListQuery'
import { useShellRefresh } from '@/composables/useShellRefresh'
import { fromNow, formatDateTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'

/**
 * 通知中心：筛选（全部/未读）与分页都写在 URL 上，刷新与分享可复现（AC-20）。
 * 行点击 = 标记已读 + 跳到单据；未读行左侧 6px 主色点，不只靠颜色也有“未读”文字。
 */
const router = useRouter()
const { refresh: refreshShell } = useShellRefresh()

const TYPE_LABELS: Record<string, string> = {
  APPROVAL: '审批',
  APPROVAL_DONE: '审批',
  GATE: '门禁',
  ADMISSION: '准入',
  DEFECT: '缺陷',
  REQUIREMENT: '需求',
  RELEASE: '版本',
  ASSIGN: '转派',
  URGENT: '催办',
  SYSTEM: '系统',
}

const BIZ_ROUTES: Record<string, string> = {
  requirement: '/requirements',
  defect: '/defects',
  deploy: '/deploy-orders',
  release: '/releases',
  approval: '/approvals',
}

const marking = ref<number | null>(null)
const markingAll = ref(false)

const {
  rows,
  total,
  page,
  size,
  loading,
  error,
  filters,
  isFiltered,
  isEmpty,
  load,
  setPage,
  setFilter,
} = useListQuery<NotificationRow>(
  (query) =>
    listNotifications({
      page: query.page,
      size: query.size,
      unreadOnly: query.filters.unread === '1',
    }),
  { unread: '' },
  { size: 10 },
)

const onlyUnread = computed(() => filters.value.unread === '1')
const unreadInPage = computed(() => rows.value.filter((row) => !row.readFlag).length)

function typeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

function targetOf(row: NotificationRow): string {
  const base = row.bizType ? BIZ_ROUTES[row.bizType] : ''
  if (!base || !row.bizId) return ''
  if (base === '/approvals' || base === '/requirements') return `${base}/${row.bizId}`
  return `${base}/${row.bizId}`
}

async function openRow(row: NotificationRow): Promise<void> {
  if (!row.readFlag) await read(row)
  const target = targetOf(row)
  if (!target) return
  // 目标路由尚未注册时留在原页，避免把用户推进 404
  const resolved = router.resolve(target)
  const missing = resolved.matched.some((record) => record.name === 'catch-all')
  if (missing) return
  await router.push(target)
}

async function read(row: NotificationRow): Promise<void> {
  if (marking.value === row.id) return
  marking.value = row.id
  try {
    await markNotificationRead(row.id)
    row.readFlag = true
    refreshShell()
    // 停在「未读」视图时，已读的那条必须离开列表，否则计数与列表自相矛盾
    if (onlyUnread.value) await load()
  } catch {
    ElMessage.error('标记已读失败，请重试')
  } finally {
    marking.value = null
  }
}

async function readAll(): Promise<void> {
  if (markingAll.value) return
  markingAll.value = true
  try {
    await markAllNotificationsRead()
    rows.value.forEach((row) => {
      row.readFlag = true
    })
    refreshShell()
    ElMessage.success('已把全部通知标记为已读')
    await load()
  } catch {
    ElMessage.error('操作失败，请重试')
  } finally {
    markingAll.value = false
  }
}

function switchTab(value: string): void {
  setFilter('unread', value)
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">通知</h1>
        <p class="page-head__sub">审批、门禁与准入的结果都会在这里留一条可回溯的记录</p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="load()">刷新</el-button>
        <el-button
          type="primary"
          plain
          :loading="markingAll"
          :disabled="unreadInPage === 0 && !onlyUnread"
          @click="readAll"
        >
          全部已读
        </el-button>
      </div>
    </header>

    <section class="surface">
      <div class="tabs" role="tablist">
        <button
          type="button"
          class="tabs__item"
          :class="{ 'tabs__item--active': !onlyUnread }"
          role="tab"
          :aria-selected="!onlyUnread"
          @click="switchTab('')"
        >
          全部
        </button>
        <button
          type="button"
          class="tabs__item"
          :class="{ 'tabs__item--active': onlyUnread }"
          role="tab"
          :aria-selected="onlyUnread"
          @click="switchTab('1')"
        >
          未读
          <span v-if="unreadInPage" class="tabs__count">{{ unreadInPage }}</span>
        </button>
      </div>

      <div v-if="loading && rows.length === 0" class="notif__skeleton" aria-hidden="true">
        <div v-for="index in 5" :key="index" class="skeleton notif__skeleton-row" />
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
        :variant="onlyUnread || isFiltered ? 'filtered' : 'empty'"
        :title="onlyUnread ? '没有未读通知' : '还没有收到通知'"
        :description="
          onlyUnread
            ? '切换「全部」可以看到历史通知。'
            : '当审批、门禁或准入有结果时，系统会在这里通知你。'
        "
        :action-text="onlyUnread ? '查看全部通知' : ''"
        icon="inbox"
        @action="switchTab('')"
      />

      <ul v-else class="notif" :class="{ 'notif--loading': loading }">
        <li v-for="row in rows" :key="row.id" class="notif__row" :class="{ 'notif__row--unread': !row.readFlag }">
          <button type="button" class="notif__main" @click="openRow(row)">
            <span class="notif__dot" :class="{ 'notif__dot--on': !row.readFlag }" aria-hidden="true" />
            <span class="notif__body">
              <span class="notif__head">
                <span class="notif__type">{{ typeLabel(row.type) }}</span>
                <span class="notif__title">{{ row.title }}</span>
                <span v-if="!row.readFlag" class="notif__unread-tag">未读</span>
              </span>
              <span class="notif__content">{{ row.content }}</span>
              <span class="notif__time" :title="formatDateTime(row.createdAt)">{{ fromNow(row.createdAt) }}</span>
            </span>
          </button>
          <button
            v-if="!row.readFlag"
            type="button"
            class="notif__action"
            :disabled="marking === row.id"
            @click.stop="read(row)"
          >
            {{ marking === row.id ? '处理中…' : '标记已读' }}
          </button>
        </li>
      </ul>

      <div v-if="total > size" class="notif__pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          background
          small
          @current-change="setPage"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
}
.tabs__item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 10px;
  border: 0;
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: color var(--t-color) var(--ease);
}
.tabs__item:hover {
  color: var(--text-title);
}
.tabs__item--active {
  color: var(--color-primary);
  font-weight: 500;
}
.tabs__item--active::after {
  content: '';
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: -1px;
  height: 2px;
  background: var(--color-primary);
}
.tabs__count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.notif__skeleton {
  padding: 12px 16px;
}
.notif__skeleton-row {
  height: 58px;
  margin-bottom: 8px;
}
.notif {
  margin: 0;
  padding: 0;
  list-style: none;
  transition: opacity var(--t-color) var(--ease);
}
.notif--loading {
  opacity: 0.6;
}
.notif__row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 0 16px 0 12px;
  border-bottom: 1px solid var(--divider);
  transition: background var(--t-color) var(--ease);
}
.notif__row:hover {
  background: var(--bg-hover);
}
.notif__main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex: 1;
  min-width: 0;
  padding: 12px 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
.notif__dot {
  width: 6px;
  height: 6px;
  margin-top: 8px;
  flex: none;
  border-radius: 50%;
  background: transparent;
}
.notif__dot--on {
  background: var(--color-primary);
}
.notif__body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.notif__head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.notif__type {
  flex: none;
  height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--bg-chip);
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 18px;
}
.notif__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notif__unread-tag {
  flex: none;
  height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 11px;
  line-height: 18px;
}
.notif__content {
  font-size: 12px;
  line-height: 19px;
  color: var(--text-muted-aa);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.notif__time {
  font-size: 11px;
  color: var(--text-weak);
}
.notif__action {
  flex: none;
  height: 24px;
  margin-top: 12px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-body);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  opacity: 0;
  transition:
    opacity 120ms var(--ease),
    border-color var(--t-color) var(--ease),
    color var(--t-color) var(--ease);
}
.notif__row:hover .notif__action,
.notif__action:focus-visible {
  opacity: 1;
}
.notif__action:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.notif__pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
}
</style>
