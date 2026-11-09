<script setup lang="ts">
/**
 * 用例评审抽屉（DESIGN T1 · AC-14/AC-15/AC-16）。
 *
 * 三条硬规则：
 * 1. 评审钉在提交时的 case_version 上；用例升版后旧评审即失效（服务端 INVALIDATED，前端只渲染）。
 * 2. NEED_CHANGE / REJECT 必须写 ≥10 字意见——按钮在字数不够时 disabled，并给出「还需 n 字」。
 * 3. 只有被指派的评审人能提交结论；其他人看到的是只读卡片与禁用按钮（服务端 403 兜底）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { listUsers } from '@/api/admin'
import type { UserRow } from '@/api/admin'
import {
  CASE_REVIEW_CONCLUSION_LABEL,
  CASE_REVIEW_STATUS_LABEL,
  decideCaseReview,
  fetchCaseReviews,
  submitCaseReview,
} from '@/api/deliver'
import type { CaseReviewConclusion, CaseReviewRow } from '@/api/deliver'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'

const props = defineProps<{
  visible: boolean
  caseId: number
  caseVersion: number
  caseTitle?: string
  caseCode?: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'changed'): void
}>()

const MIN_COMMENT = 10

const auth = useAuthStore()
const { can } = usePermission()
const canSubmit = computed(() => can('CASE_REVIEW_SUBMIT'))
const canDecide = computed(() => can('CASE_REVIEW_DECIDE'))

const reviews = ref<CaseReviewRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const notice = ref('')
const busy = ref(false)

const reviewers = ref<UserRow[]>([])
const reviewerId = ref<number | null>(null)

const conclusion = ref<CaseReviewConclusion>('PASS')
const comment = ref('')
const commentTouched = ref(false)

const open = computed(() => props.visible)

/** 当前版本上的评审中记录（钉版本 = 与 caseVersion 相等且未结束） */
const activeReview = computed<CaseReviewRow | null>(
  () => reviews.value.find((row) => row.status === 'SUBMITTED' && row.caseVersion === props.caseVersion) ?? null,
)

/** 上一版本还没结论就被升版淘汰的记录：横幅提示必须重新发起 */
const staleReview = computed<CaseReviewRow | null>(() => {
  const candidates = reviews.value.filter(
    (row) => row.status === 'INVALIDATED' && row.caseVersion < props.caseVersion,
  )
  return candidates.length ? candidates[0] : null
})

const myId = computed(() => Number(auth.user?.id ?? 0))
const isReviewer = computed(() => Boolean(activeReview.value && activeReview.value.reviewerId === myId.value))

const commentNeeded = computed(() => {
  if (conclusion.value === 'PASS') return 0
  const used = comment.value.trim().length
  return used >= MIN_COMMENT ? 0 : MIN_COMMENT - used
})

const decideDisabled = computed(
  () => !activeReview.value || !isReviewer.value || busy.value || commentNeeded.value > 0,
)

const historyRows = computed(() =>
  [...reviews.value].sort((a, b) => {
    if (a.caseVersion !== b.caseVersion) return b.caseVersion - a.caseVersion
    return (b.createdAt ?? '').localeCompare(a.createdAt ?? '')
  }),
)

function close(): void {
  emit('update:visible', false)
}

function conclusionLabel(value?: string | null): string {
  if (!value) return '—'
  return CASE_REVIEW_CONCLUSION_LABEL[value as CaseReviewConclusion] ?? value
}

function statusLabel(value: string): string {
  return CASE_REVIEW_STATUS_LABEL[value as keyof typeof CASE_REVIEW_STATUS_LABEL] ?? value
}

function statusClass(value: string): string {
  if (value === 'INVALIDATED') return 'cr-chip cr-chip--stale'
  if (value === 'DONE') return 'cr-chip cr-chip--done'
  return 'cr-chip cr-chip--open'
}

function reviewerName(row: CaseReviewRow): string {
  if (row.reviewerName) return row.reviewerName
  const hit = reviewers.value.find((item) => item.id === row.reviewerId)
  return hit ? hit.name : `用户 #${row.reviewerId}`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  notice.value = ''
  try {
    reviews.value = await fetchCaseReviews(props.caseId)
    if (!reviewers.value.length) await loadReviewers()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message || '评审记录加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
    reviews.value = []
  } finally {
    loading.value = false
  }
}

async function loadReviewers(): Promise<void> {
  try {
    const page = await listUsers({ page: 1, size: 200 })
    reviewers.value = page.items ?? []
  } catch {
    reviewers.value = []
  }
}

function resetDecision(): void {
  conclusion.value = 'PASS'
  comment.value = ''
  commentTouched.value = false
}

async function startReview(): Promise<void> {
  if (!canSubmit.value) return
  if (!reviewerId.value) {
    notice.value = '先选择一位评审人再发起评审'
    return
  }
  busy.value = true
  notice.value = ''
  try {
    const row = await submitCaseReview(props.caseId, { reviewerId: reviewerId.value })
    reviews.value = [row, ...reviews.value]
    ElMessage.success(`已发起评审，钉在 v${row.caseVersion}`)
    resetDecision()
    emit('changed')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    await handleReviewError(apiError)
  } finally {
    busy.value = false
  }
}

async function submitDecision(): Promise<void> {
  const review = activeReview.value
  if (!review || !isReviewer.value) return
  if (commentNeeded.value > 0) {
    commentTouched.value = true
    return
  }
  busy.value = true
  notice.value = ''
  try {
    const row = await decideCaseReview(review.id, {
      conclusion: conclusion.value,
      comment: comment.value.trim() || undefined,
    })
    reviews.value = reviews.value.map((item) => (item.id === row.id ? row : item))
    ElMessage.success(`评审结论已提交：${conclusionLabel(row.conclusion)}`)
    resetDecision()
    emit('changed')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    await handleReviewError(apiError)
  } finally {
    busy.value = false
  }
}

async function handleReviewError(apiError: ApiError | null): Promise<void> {
  const code = apiError?.code ?? ''
  if (code === 'CASE_REVIEW_VERSION_STALE' || code === 'CASE_REVIEW_INVALIDATED') {
    notice.value = '用例已升版，这次评审已失效——请重新发起评审。'
    await load()
    return
  }
  if (code === 'CASE_REVIEW_DUPLICATE') {
    notice.value = '该版本已有一条评审中的记录，不能重复发起。'
    await load()
    return
  }
  if (code === 'CASE_REVIEW_NOT_REVIEWER') {
    notice.value = '只有被指派的评审人可以提交结论。'
    return
  }
  if (code === 'CASE_REVIEW_COMMENT_REQUIRED') {
    commentTouched.value = true
    notice.value = `需修改 / 驳回必须写清原因（至少 ${MIN_COMMENT} 个字）。`
    return
  }
  notice.value = apiError?.message || '操作失败，请重试'
}

watch(
  () => [props.visible, props.caseId, props.caseVersion],
  ([visible]) => {
    if (!visible) return
    reviewerId.value = null
    resetDecision()
    void load()
  },
)
</script>

<template>
  <el-drawer
    :model-value="open"
    :size="720"
    :with-header="false"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="close"
  >
    <div class="cr-page">
      <header class="cr-head">
        <div>
          <h2 class="cr-title">用例评审</h2>
          <p class="cr-sub">
            <span class="t-code">{{ caseCode || `TC-${caseId}` }}</span>
            <span v-if="caseTitle"> · {{ caseTitle }}</span>
            <span class="cr-pin">钉在 v{{ caseVersion }}</span>
          </p>
        </div>
        <el-button link @click="close">关闭</el-button>
      </header>

      <div v-if="staleReview" class="cr-banner cr-banner--warn" role="status">
        v{{ staleReview.caseVersion }} 的评审结论对 v{{ caseVersion }} 无效，请重新发起评审。
        <span class="cr-banner__hint">用例内容已变更，旧结论不能再看作用例已通过。</span>
      </div>

      <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

      <template v-else>
        <div v-if="notice" class="cr-banner cr-banner--inline" role="alert">{{ notice }}</div>

        <!-- 评审中：被指派人提交结论 -->
        <section v-if="activeReview" class="cr-open">
          <div class="cr-open__head">
            <div class="cr-open__who">
              <span class="cr-avatar">{{ reviewerName(activeReview).slice(0, 1) }}</span>
              <div>
                <div class="cr-open__name">{{ reviewerName(activeReview) }}</div>
                <div class="cr-meta">指派于 {{ formatDateTime(activeReview.createdAt) }} · 评审中</div>
              </div>
            </div>
            <span :class="statusClass(activeReview.status)">{{ statusLabel(activeReview.status) }}</span>
          </div>

          <div v-if="isReviewer" class="cr-decide">
            <div class="cr-decide__row" role="radiogroup" aria-label="评审结论">
              <button
                v-for="item in (['PASS', 'NEED_CHANGE', 'REJECT'] as CaseReviewConclusion[])"
                :key="item"
                type="button"
                class="cr-seg"
                :class="{ 'is-active': conclusion === item, [`is-${item.toLowerCase()}`]: true }"
                :aria-pressed="conclusion === item"
                :disabled="busy || !canDecide"
                @click="conclusion = item"
              >
                {{ CASE_REVIEW_CONCLUSION_LABEL[item] }}
              </button>
            </div>

            <el-input
              v-model="comment"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              :placeholder="conclusion === 'PASS' ? '补充说明（可选）' : '写清问题与期望，至少 10 个字（必填）'"
              @blur="commentTouched = true"
            />
            <div class="cr-decide__foot">
              <span v-if="commentNeeded > 0" class="cr-need">
                还需 {{ commentNeeded }} 字{{ commentTouched ? '' : '（结论为需修改/驳回时必填）' }}
              </span>
              <span v-else class="cr-hint">结论会写入审计，并让该版本评审结束。</span>
              <el-button
                type="primary"
                size="small"
                :loading="busy"
                :disabled="decideDisabled"
                @click="submitDecision"
              >
                提交结论
              </el-button>
            </div>
          </div>

          <p v-else class="cr-hint cr-hint--block">
            评审已指派给 {{ reviewerName(activeReview) }}，只有 TA 能提交结论（你的界面只是只读）。
          </p>
        </section>

        <!-- 发起评审：当前版本没有评审中记录 -->
        <section v-else class="cr-start">
          <div class="cr-start__head">
            <div>
              <div class="cr-start__title">为 v{{ caseVersion }} 发起评审</div>
              <div class="cr-meta">评审钉住当前版本；用例之后再升版，这次评审会自动失效。</div>
            </div>
            <span class="cr-chip cr-chip--stale">未发起</span>
          </div>
          <div class="cr-start__row">
            <el-select
              v-model="reviewerId"
              size="small"
              filterable
              clearable
              placeholder="选择评审人（通常为 QA 负责人）"
              class="cr-start__select"
              :disabled="!canSubmit || busy"
            >
              <el-option v-for="user in reviewers" :key="user.id" :label="user.name" :value="user.id" />
            </el-select>
            <el-button type="primary" size="small" :loading="busy" :disabled="!canSubmit" @click="startReview">
              发起评审
            </el-button>
          </div>
          <p v-if="!canSubmit" class="cr-hint">你没有提交评审的权限（CASE_REVIEW_SUBMIT）。</p>
        </section>

        <!-- 历史 -->
        <section class="cr-history">
          <div class="cr-history__head">
            <h3 class="cr-history__title">评审记录</h3>
            <span class="dl-hint">倒序；过期记录保留但不参与结论。</span>
          </div>

          <div v-if="loading" class="cr-hint">加载评审记录…</div>
          <EmptyState
            v-else-if="!reviews.length"
            title="还没有评审记录"
            description="为当前版本指定一位评审人，结论与意见会随版本一起留档。"
            icon="file"
            compact
          />
          <ul v-else class="cr-list">
            <li
              v-for="row in historyRows"
              :key="row.id"
              class="cr-row"
              :class="{ 'cr-row--stale': row.status === 'INVALIDATED' }"
            >
              <div class="cr-row__main">
                <span class="cr-row__version">v{{ row.caseVersion }}</span>
                <span class="cr-row__conclusion">{{ conclusionLabel(row.conclusion) }}</span>
                <span class="cr-row__who">{{ reviewerName(row) }}</span>
                <span class="cr-row__time">{{ formatDateTime(row.decidedAt || row.createdAt) }}</span>
                <span :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
              </div>
              <p v-if="row.comment" class="cr-row__comment">{{ row.comment }}</p>
              <p v-if="row.status === 'INVALIDATED'" class="cr-row__stale">
                已过期 · {{ row.invalidReason || `用例已升到 v${caseVersion}` }}
              </p>
            </li>
          </ul>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.cr-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 2px 8px;
}
.cr-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border, #e5e6eb);
  padding-bottom: 10px;
}
.cr-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.cr-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}
.cr-pin {
  margin-left: 6px;
  padding: 0 6px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
  background: var(--bg-table-head, #fafbfc);
  color: var(--text-body, #4e5969);
}
.cr-banner {
  border-radius: var(--r-ctl, 6px);
  padding: 8px 10px;
  font-size: 12px;
  line-height: 18px;
}
.cr-banner--warn {
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
  border-left: 3px solid var(--color-warning, #c77700);
}
.cr-banner--inline {
  background: var(--bg-hover, #f7f9fc);
  color: var(--text-body, #4e5969);
  border-left: 3px solid var(--color-primary, #2b6fe5);
}
.cr-banner__hint {
  display: block;
  margin-top: 2px;
  opacity: 0.85;
}
.cr-open,
.cr-start,
.cr-history {
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-card, 8px);
  padding: 12px;
  background: var(--bg-card, #fff);
}
.cr-open__head,
.cr-start__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.cr-open__who {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cr-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-primary-light, #eaf1fe);
  color: var(--color-primary, #2b6fe5);
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.cr-open__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.cr-meta {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}
.cr-decide {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cr-decide__row {
  display: inline-flex;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
  overflow: hidden;
  width: fit-content;
}
.cr-seg {
  border: none;
  background: var(--bg-card, #fff);
  color: var(--text-body, #4e5969);
  font-size: 12px;
  padding: 5px 14px;
  cursor: pointer;
  border-right: 1px solid var(--border, #e5e6eb);
  transition: background var(--t-color, 120ms) ease, color var(--t-color, 120ms) ease;
}
.cr-seg:last-child {
  border-right: none;
}
.cr-seg:hover:not(:disabled) {
  background: var(--bg-hover, #f7f9fc);
}
.cr-seg.is-active.is-pass {
  background: var(--color-success-bg, #e8f7ec);
  color: var(--success-ink, #157a38);
  font-weight: 600;
}
.cr-seg.is-active.is-need_change {
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
  font-weight: 600;
}
.cr-seg.is-active.is-reject {
  background: var(--color-danger-bg, #fdecec);
  color: var(--danger-ink, #b3262e);
  font-weight: 600;
}
.cr-seg:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.cr-decide__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.cr-need {
  font-size: 12px;
  color: var(--danger-ink, #b3262e);
}
.cr-hint {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  line-height: 18px;
}
.cr-hint--block {
  margin: 12px 0 0;
  padding: 8px 10px;
  background: var(--bg-hover, #f7f9fc);
  border-radius: var(--r-ctl, 6px);
}
.cr-start__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.cr-start__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.cr-start__select {
  flex: 1;
}
.cr-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-pill, 999px);
  font-size: 12px;
  line-height: 1;
}
.cr-chip--open {
  background: var(--color-primary-light, #eaf1fe);
  color: var(--color-primary, #2b6fe5);
}
.cr-chip--done {
  background: var(--color-success-bg, #e8f7ec);
  color: var(--success-ink, #157a38);
}
.cr-chip--stale {
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
}
.cr-history__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.cr-history__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.cr-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cr-row {
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
  padding: 8px 10px;
  background: var(--bg-card, #fff);
}
.cr-row--stale {
  border-style: dashed;
  border-left: 3px solid var(--color-warning, #c77700);
  min-height: 60px;
  background: #fffdf8;
}
.cr-row__main {
  display: grid;
  grid-template-columns: 48px 96px 1fr 150px 72px;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.cr-row__version {
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--text-title, #1f2329);
  font-weight: 600;
}
.cr-row__conclusion {
  color: var(--text-body, #4e5969);
}
.cr-row__who {
  color: var(--text-body, #4e5969);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cr-row__time {
  color: var(--text-muted-aa, #6b7280);
}
.cr-row__comment {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-body, #4e5969);
  white-space: pre-wrap;
}
.cr-row__stale {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--warning-ink, #9a5b00);
}
</style>
