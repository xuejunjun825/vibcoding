<script setup lang="ts">
/**
 * MR 评审卡（DESIGN R1 · AC-11/AC-12）。
 *
 * 通栏卡片：MR 头 → 评审人/评审状态/评论数 → 按文件分组的行级评论（内滚 420）。
 * 「刷新」是前端唯一触发 GitLab 外呼的入口；其余读取都走本地同步快照。
 * 已合入/已关闭时评论区只读，不允许再刷新出「新评论」的假象。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError } from '@/api/client'
import { MR_REVIEW_STATE_LABEL, fetchMrComments, refreshMr } from '@/api/req'
import type { MrCommentRow, MrRefreshResult, MrReviewFields } from '@/api/req'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatDateTime, formatNumber } from '@/utils/format'
import '@/views/deliver/shared.css'

export interface MrReviewModel extends MrReviewFields {
  mrIid?: number | null
  mrUrl?: string | null
  title?: string | null
  state?: string | null
  sourceBranch?: string | null
  targetBranch?: string | null
  lastEventAt?: string | null
  mergedAt?: string | null
  stale?: boolean | null
}

const props = withDefaults(
  defineProps<{
    requirementId: number
    mr?: MrReviewModel | null
    loading?: boolean
  }>(),
  { mr: null, loading: false },
)

const emit = defineEmits<{ (event: 'refreshed', payload: MrRefreshResult): void }>()

const comments = ref<MrCommentRow[]>([])
const commentsLoading = ref(false)
const commentsError = ref('')
const nextCursor = ref<string | null>(null)
const refreshing = ref(false)
const refreshError = ref('')
const refreshNote = ref('')

const readOnly = computed(() => props.mr?.state === 'MERGED' || props.mr?.state === 'CLOSED')
const reviewStateLabel = computed(() => {
  const state = props.mr?.reviewState
  if (!state) return '评审状态未同步'
  return MR_REVIEW_STATE_LABEL[state] ?? state
})

const reviewTone = computed(() => {
  switch (props.mr?.reviewState) {
    case 'APPROVED':
      return 'ok'
    case 'CHANGES_REQUESTED':
      return 'warn'
    default:
      return 'idle'
  }
})

const reviewers = computed(() => props.mr?.reviewers ?? [])
const visibleReviewers = computed(() => reviewers.value.slice(0, 3))
const hiddenReviewers = computed(() => Math.max(0, reviewers.value.length - 3))

const commentsCount = computed(() => props.mr?.commentsCount ?? comments.value.length)
const changedFiles = computed(() => props.mr?.changedFiles ?? grouped.value.length)

/** 按文件分组；行级评论按行号升序，文件按路径升序，未挂文件排最后 */
const grouped = computed(() => {
  const map = new Map<string, MrCommentRow[]>()
  for (const row of comments.value) {
    const key = row.filePath?.trim() || ''
    const list = map.get(key) ?? []
    list.push(row)
    map.set(key, list)
  }
  return [...map.entries()]
    .map(([filePath, items]) => ({
      filePath,
      items: items.sort((a, b) => (a.lineNo ?? 0) - (b.lineNo ?? 0)),
    }))
    .sort((a, b) => {
      if (!a.filePath) return 1
      if (!b.filePath) return -1
      return a.filePath.localeCompare(b.filePath)
    })
})

function initials(name?: string | null): string {
  if (!name) return '?'
  return name.trim().slice(0, 1).toUpperCase()
}

function reviewerHint(state?: string | null): string {
  if (!state) return ''
  return MR_REVIEW_STATE_LABEL[state] ?? state
}

async function loadComments(reset = true): Promise<void> {
  if (!props.mr?.mrIid) {
    comments.value = []
    nextCursor.value = null
    return
  }
  commentsLoading.value = true
  commentsError.value = ''
  try {
    const page = await fetchMrComments(props.requirementId, reset ? null : nextCursor.value, 50)
    comments.value = reset ? page.items : [...comments.value, ...page.items]
    nextCursor.value = page.nextCursor ?? null
  } catch (err) {
    commentsError.value = err instanceof ApiError ? err.message : '评论加载失败'
  } finally {
    commentsLoading.value = false
  }
}

/** 刷新唯一外呼：刷新中保留旧数据，成功后父组件用返回的 card 更新 */
async function onRefresh(): Promise<void> {
  if (readOnly.value) return
  refreshing.value = true
  refreshError.value = ''
  refreshNote.value = ''
  try {
    const result = await refreshMr(props.requirementId)
    emit('refreshed', result)
    const touched = (result.added ?? 0) + (result.updated ?? 0)
    refreshNote.value = touched
      ? `已同步：新增 ${result.added ?? 0} 条 · 更新 ${result.updated ?? 0} 条`
      : '已同步：没有新的变更'
    await loadComments(true)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    refreshError.value = apiError?.message || '刷新失败：GitLab 不可达或未启用，旧数据保留'
  } finally {
    refreshing.value = false
  }
}

onMounted(() => {
  void loadComments(true)
})

watch(
  () => props.mr?.mrIid,
  () => {
    refreshError.value = ''
    refreshNote.value = ''
    nextCursor.value = null
    void loadComments(true)
  },
)
</script>

<template>
  <section class="mr-card" :class="{ 'is-readonly': readOnly }" aria-label="MR 评审">
    <header class="mr-card__head">
      <div class="mr-card__title">
        <span class="mr-card__iid">!{{ mr?.mrIid ?? '—' }}</span>
        <a
          v-if="mr?.mrUrl"
          class="mr-card__link"
          :href="mr.mrUrl"
          target="_blank"
          rel="noopener noreferrer"
          :title="mr.title || ''"
        >
          {{ mr.title || '未命名 MR' }}
        </a>
        <span v-else class="mr-card__link mr-card__link--plain">{{ mr?.title || '还没有关联 MR' }}</span>
        <StatusPill v-if="mr?.state" kind="mr" :value="mr.state" size="sm" />
        <span v-if="mr?.stale" class="mr-card__stale" title="兜底轮询超过阈值未更新，数据可能过期">数据可能过期</span>
      </div>
      <div class="mr-card__actions">
        <el-button
          size="small"
          :loading="refreshing"
          :disabled="!mr?.mrIid || readOnly"
          :title="readOnly ? '已合入/关闭，评论只读' : '从 GitLab 拉取最新评审与评论'"
          @click="onRefresh"
        >
          刷新
        </el-button>
        <span class="mr-card__time">更新于 {{ formatDateTime(mr?.lastEventAt) }}</span>
      </div>
    </header>

    <div class="mr-card__meta">
      <div class="mr-card__reviewers">
        <span class="mr-card__label">评审人</span>
        <template v-if="reviewers.length">
          <span
            v-for="person in visibleReviewers"
            :key="`${person.id ?? person.name}`"
            class="mr-card__reviewer"
            :title="reviewerHint(person.state)"
          >
            <span class="mr-card__avatar">{{ initials(person.name) }}</span>
            <span class="mr-card__reviewer-name">{{ person.name }}</span>
            <span v-if="person.state" class="mr-card__reviewer-dot" :class="`is-${person.state.toLowerCase()}`" />
          </span>
          <span v-if="hiddenReviewers" class="mr-card__more">+{{ hiddenReviewers }}</span>
        </template>
        <span v-else class="mr-card__empty-inline">未指派</span>
      </div>

      <span class="mr-card__badge" :class="`mr-card__badge--${reviewTone}`">{{ reviewStateLabel }}</span>
      <span class="mr-card__stat">{{ formatNumber(commentsCount) }} 条评论</span>
      <span class="mr-card__stat">{{ formatNumber(changedFiles) }} 个文件</span>
      <span class="dl-filters__spacer" />
      <span v-if="mr?.sourceBranch" class="mr-card__branch">
        {{ mr.sourceBranch }} → {{ mr.targetBranch || '—' }}
      </span>
    </div>

    <div v-if="refreshError" class="mr-card__error" role="alert">
      <span>{{ refreshError }}</span>
      <el-button link type="primary" size="small" :loading="refreshing" @click="onRefresh">重试</el-button>
    </div>
    <div v-else-if="refreshNote" class="mr-card__note" role="status">{{ refreshNote }}</div>

    <div v-if="readOnly" class="mr-card__readonly" role="status">已合入，评论只读</div>

    <div class="mr-card__comments" :class="{ 'is-reading': readOnly }">
      <div v-if="commentsError" class="mr-card__error" role="alert">
        <span>{{ commentsError }}</span>
        <el-button link type="primary" size="small" @click="loadComments(true)">重试</el-button>
      </div>

      <div v-else-if="grouped.length" class="mr-card__groups">
        <section v-for="group in grouped" :key="group.filePath || '__general__'" class="mr-file">
          <header class="mr-file__head">
            <span class="mr-file__path">{{ group.filePath || '未关联文件' }}</span>
            <span class="mr-file__count">{{ group.items.length }}</span>
          </header>
          <div v-for="row in group.items" :key="row.id" class="mr-comment">
            <span class="mr-comment__avatar" :title="row.author || '未知作者'">{{ initials(row.author) }}</span>
            <span class="mr-comment__author" :title="row.author || '未知作者'">{{ row.author || '未知作者' }}</span>
            <p class="mr-comment__body">
              {{ row.body }}
              <span v-if="row.lineNo" class="mr-comment__line">L{{ row.lineNo }}</span>
            </p>
            <span class="mr-comment__file" :title="row.filePath || ''">{{ row.filePath || '—' }}</span>
            <span class="mr-comment__state" :class="row.resolved ? 'is-resolved' : 'is-open'">
              {{ row.resolved ? '已解决 ✓' : '未解决' }}
            </span>
          </div>
        </section>
        <div v-if="nextCursor" class="mr-card__more">
          <el-button size="small" :loading="commentsLoading" @click="loadComments(false)">加载更多</el-button>
        </div>
      </div>

      <EmptyState
        v-else-if="mr?.mrIid"
        title="还没有行级评论"
        description="点「刷新」从 GitLab 拉取评审评论；没有评论时保持空态，不显示 0 条假数据。"
        icon="file"
        compact
      />
      <EmptyState
        v-else
        title="还没有关联 MR"
        description="在下方代码卡里关联 GitLab MR 后，这里会展示评审人与行级评论。"
        icon="inbox"
        compact
      />
    </div>
  </section>
</template>

<style scoped>
.mr-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-card, 8px);
  background: var(--bg-card, #fff);
}

.mr-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.mr-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.mr-card__iid {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  flex: none;
}

.mr-card__link {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 560px;
}

.mr-card__link:hover {
  color: var(--color-primary, #2b6fe5);
  text-decoration: underline;
}

.mr-card__link--plain {
  color: var(--text-body, #4e5969);
  font-weight: 500;
}

.mr-card__stale {
  flex: none;
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
  border-radius: 3px;
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
  font-size: 11px;
}

.mr-card__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.mr-card__time {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
  white-space: nowrap;
}

.mr-card__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 8px;
  border-top: 1px dashed var(--divider, #f2f3f5);
  font-size: 12px;
  color: var(--text-body, #4e5969);
}

.mr-card__reviewers {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mr-card__label {
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__reviewer {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px 1px 2px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-pill, 999px);
  background: var(--bg-hover, #f7f9fc);
}

.mr-card__avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-primary-light, #eaf1fe);
  color: var(--color-primary, #2b6fe5);
  font-size: 10px;
  line-height: 18px;
  text-align: center;
  flex: none;
}

.mr-card__reviewer-name {
  font-size: 11px;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mr-card__reviewer-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--border-strong, #c9cdd4);
}

.mr-card__reviewer-dot.is-approved {
  background: var(--color-success, #1f9d45);
}

.mr-card__reviewer-dot.is-changes_requested {
  background: var(--color-danger, #d9363e);
}

.mr-card__more {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__empty-inline {
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__badge {
  padding: 1px 8px;
  height: 20px;
  line-height: 18px;
  border-radius: var(--r-pill, 999px);
  border: 1px solid var(--border, #e5e6eb);
  background: var(--color-info-bg, #f2f3f5);
  color: var(--info-ink, #4e5969);
  font-size: 11px;
}

.mr-card__badge--ok {
  background: var(--color-success-bg, #e8f7ec);
  color: var(--success-ink, #157a38);
  border-color: color-mix(in srgb, var(--color-success, #1f9d45) 22%, transparent);
}

.mr-card__badge--warn {
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
  border-color: color-mix(in srgb, var(--color-warning, #c77700) 24%, transparent);
}

.mr-card__stat {
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__branch {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__error,
.mr-card__note {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--r-ctl, 6px);
  font-size: 12px;
  line-height: 18px;
}

.mr-card__error {
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
}

.mr-card__note {
  background: var(--color-success-bg, #e8f7ec);
  border: 1px solid color-mix(in srgb, var(--color-success, #1f9d45) 22%, transparent);
  color: var(--success-ink, #157a38);
}

.mr-card__readonly {
  padding: 5px 10px;
  border-radius: var(--r-ctl, 6px);
  background: var(--bg-hover, #f7f9fc);
  border: 1px dashed var(--border-strong, #c9cdd4);
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.mr-card__comments {
  max-height: var(--w-mr-comments, 420px);
  overflow: auto;
  border: 1px solid var(--divider, #f2f3f5);
  border-radius: var(--r-ctl, 6px);
  background: #fff;
}

.mr-card__comments.is-reading {
  background: var(--bg-table-head, #fafbfc);
}

.mr-card__comments::-webkit-scrollbar {
  width: 8px;
}

.mr-card__comments::-webkit-scrollbar-thumb {
  background: var(--border-strong, #c9cdd4);
  border-radius: 4px;
}

.mr-card__groups {
  display: flex;
  flex-direction: column;
}

.mr-file + .mr-file {
  border-top: 1px solid var(--divider, #f2f3f5);
}

.mr-file__head {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: var(--bg-table-head, #fafbfc);
  border-bottom: 1px solid var(--divider, #f2f3f5);
}

.mr-file__path {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11px;
  color: var(--text-body, #4e5969);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mr-file__count {
  flex: none;
  padding: 0 6px;
  height: 16px;
  line-height: 16px;
  border-radius: 8px;
  background: var(--color-info-bg, #f2f3f5);
  color: var(--text-muted-aa, #6b7280);
  font-size: 10px;
}

.mr-comment {
  display: grid;
  grid-template-columns: 24px 96px minmax(0, 1fr) 200px 72px;
  align-items: start;
  gap: 8px;
  padding: 7px 12px;
  border-bottom: 1px solid var(--divider, #f2f3f5);
  font-size: 12px;
  line-height: 18px;
}

.mr-comment:last-child {
  border-bottom: 0;
}

.mr-comment__avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--color-primary-light, #eaf1fe);
  color: var(--color-primary, #2b6fe5);
  font-size: 11px;
  line-height: 24px;
  text-align: center;
}

.mr-comment__author {
  color: var(--text-title, #1f2329);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mr-comment__body {
  margin: 0;
  color: var(--text-body, #4e5969);
  white-space: pre-wrap;
  word-break: break-word;
}

.mr-comment__line {
  margin-left: 6px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 10px;
  color: var(--color-primary, #2b6fe5);
}

.mr-comment__file {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 10px;
  color: var(--text-muted-aa, #6b7280);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mr-comment__state {
  font-size: 11px;
  text-align: right;
  white-space: nowrap;
}

.mr-comment__state.is-resolved {
  color: var(--success-ink, #157a38);
}

.mr-comment__state.is-open {
  color: var(--warning-ink, #9a5b00);
}

@media (max-width: 1200px) {
  .mr-comment {
    grid-template-columns: 24px 80px minmax(0, 1fr) 120px 64px;
  }
}
</style>
