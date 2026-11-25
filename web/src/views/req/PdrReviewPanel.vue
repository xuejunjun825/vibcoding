<script setup lang="ts">
/**
 * 需求详情「评审」tab 主体（r49 PDR 评审，交互稿第 1 / 4 屏）。
 *
 * 数据来源只有一个：`GET /api/req/requirements/{id}` 里回传的 `pdrReview`
 * （`PdrViews.ReviewDetail{review, opinions}`）。本组件**不自己再拉评审单**，
 * 只在写操作成功后 emit `refresh` 让详情页重拉——保证详情页其它 tab 看到的是同一份数据。
 *
 * 契约缺口（已用前端兜住，见交付汇报）：
 * 1. 详情不带 `Tally`：进度「2/4」与「谁还没评」由 `pdrProgress()` 用 reviewerIds × opinions 推；
 * 2. 附件只有 `bizType/bizId` 两级，挂在**评审单**上（`bizId = review.id`），
 *    `OpinionView` 没有 attachmentId —— 所以附件区在评审单级，意见行不显示「本条附件」；
 * 3. 没有 `GET /pdr-reviews/{id}`：本页所有写操作都用详情回传的 `review.id`。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  closePdrReview,
  concludePdrReview,
  submitPdrOpinions,
} from '@/api/pdr'
import {
  deleteAttachment,
  downloadAttachment,
  listAttachments,
  uploadAttachment,
} from '@/api/req'
import { PDR_ATTACHMENT_BIZ_TYPE } from '@/api/types.pdr'
import type { AttachmentRow, RequirementDetail } from '@/api/types'
import type { PdrDecision, PdrOpinion, PdrToTaskResult } from '@/api/types.pdr'
import { PDR_CATEGORY_OPTIONS } from '@/api/pdrCategories'
import type { PdrCategory } from '@/api/pdrCategories'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, fromNow } from '@/utils/format'
import { usePlatformDict } from '@/views/req/platform'
import StartPdrReviewDialog from '@/views/req/dialogs/StartPdrReviewDialog.vue'
import OpinionToTaskDialog from '@/views/req/dialogs/OpinionToTaskDialog.vue'
import {
  PDR_DECISION_CODES,
  PDR_DECISION_HINTS,
  groupOpinionsByReviewer,
  hasMyOpinion,
  pdrErrorInfo,
  pdrErrorNeedsRefresh,
  pdrModeLabel,
  pdrProgress,
  reviewWritable,
} from '@/views/req/pdr'


const props = withDefaults(
  defineProps<{
    detail: RequirementDetail
    /** 详情页整体 loading：切换 tab 时避免闪空态 */
    loading?: boolean
  }>(),
  { loading: false },
)

const emit = defineEmits<{ (event: 'refresh'): void }>()

const auth = useAuthStore()
const perm = usePermission()
const dict = usePlatformDict()

const me = computed(() => auth.user)
const review = computed(() => props.detail.pdrReview?.review ?? null)
const opinions = computed<PdrOpinion[]>(() => props.detail.pdrReview?.opinions ?? [])
const writable = computed(() => reviewWritable(review.value))
const progress = computed(() => pdrProgress(props.detail.pdrReview))
const groups = computed(() => groupOpinionsByReviewer(opinions.value))

const isReviewer = computed(() =>
  me.value ? (review.value?.reviewerIds ?? []).includes(me.value.id) : false,
)
const isInitiator = computed(() => me.value != null && review.value?.initiatorId === me.value.id)
// 「发起评审」＝ 页面码 pdr.review × 动作码 req.action.pdr_review_start 双条件。
// 只判动作码不够：评审中心页面对没有 pdr.review 页面码的人不可见，那时给出入口只会 403。
const canStart = computed(
  () => perm.canMenu('pdr.review') && perm.canAction('req.action.pdr_review_start'),
)
const canOpinion = computed(() => perm.canAction('req.action.pdr_review_opinion'))
const canTask = computed(() => perm.canAction('req.action.pdr_review_task'))
const canConclude = computed(() => perm.canAction('req.action.pdr_review_conclude'))
/** 转任务按钮：评审人 / 发起人（产品经理）/ 有汇总权的人可见，最终仍由后端 403 把关 */
const showTaskAction = computed(() => canTask.value && (isReviewer.value || isInitiator.value || canConclude.value))
const canUpload = computed(() => writable.value && (isReviewer.value || isInitiator.value))
const canWriteOpinion = computed(() => writable.value && isReviewer.value && canOpinion.value)
const myOpinionSubmitted = computed(() => hasMyOpinion(props.detail.pdrReview, me.value?.id))

const actionError = ref('')
const busy = ref(false)

/**
 * 统一的写失败处理：行内常驻提示（不弹 toast，用户能看到完整原因）；
 * 409（状态被别人推进）/ 403（可见性变化）顺带让详情页重拉，避免界面停在旧状态。
 */
function handleWriteError(error: unknown): void {
  const info = pdrErrorInfo(error)
  actionError.value = info.invalidCategory
    ? `${info.text}（可选：${PDR_CATEGORY_OPTIONS.map((item) => item.label).join('、')}）`
    : info.text
  if (pdrErrorNeedsRefresh(error)) emit('refresh')
}

/* ---------------- 发起评审 ---------------- */
const startOpen = ref(false)

function openStart(): void {
  actionError.value = ''
  startOpen.value = true
}

function onStarted(): void {
  emit('refresh')
}

/* ---------------- 汇总 / 关闭 ---------------- */
async function conclude(): Promise<void> {
  if (!review.value) return
  const allMode = review.value.mode === 'MODE_ALL'
  try {
    await ElMessageBox.confirm(
      allMode
        ? `全员通过模式：${progress.value.submitted}/${progress.value.total} 位评审人已提交，未提交的人会被计为待评，后端可能拒绝汇总。确认汇总结论？`
        : '或签模式：任一评审人通过即通过。确认汇总结论？',
      '汇总结论',
      { type: 'warning', confirmButtonText: '确认汇总', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    await concludePdrReview(review.value.id)
    ElMessage.success('已汇总结论')
    emit('refresh')
  } catch (error) {
    handleWriteError(error)
  } finally {
    busy.value = false
  }
}

async function closeReview(): Promise<void> {
  if (!review.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('关闭后不能再提交意见（已提交的意见仍可转任务）。可填写关闭原因。', '关闭评审', {
      confirmButtonText: '关闭评审',
      cancelButtonText: '取消',
      inputPlaceholder: '关闭原因（可选）',
      inputValidator: (value) => (value ?? '').length <= 200 || '不超过 200 字',
    })
    reason = (result as { value?: string }).value ?? ''
  } catch {
    return
  }
  busy.value = true
  try {
    await closePdrReview(review.value.id, reason || undefined)
    ElMessage.success('评审已关闭')
    emit('refresh')
  } catch (error) {
    handleWriteError(error)
  } finally {
    busy.value = false
  }
}

/* ---------------- 我的意见（一次多条） ---------------- */
interface OpinionDraft {
  key: number
  category: PdrCategory | ''
  decision: PdrDecision
  comment: string
}

let draftSeq = 0
const drafts = ref<OpinionDraft[]>([])

function newDraft(): OpinionDraft {
  draftSeq += 1
  return { key: draftSeq, category: '', decision: 'PASS', comment: '' }
}

function addDraft(): void {
  if (drafts.value.length >= 20) {
    ElMessage.warning('一次最多提交 20 条意见')
    return
  }
  drafts.value.push(newDraft())
}

function removeDraft(key: number): void {
  drafts.value = drafts.value.filter((draft) => draft.key !== key)
  if (drafts.value.length === 0) drafts.value.push(newDraft())
}

watch(
  canWriteOpinion,
  (visible) => {
    if (visible && drafts.value.length === 0) drafts.value = [newDraft()]
  },
  { immediate: true },
)

async function submitOpinions(): Promise<void> {
  if (!review.value) return
  actionError.value = ''
  const rows = drafts.value.filter((draft) => draft.category || draft.comment.trim())
  if (rows.length === 0) {
    actionError.value = '请至少填写一条意见'
    return
  }
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index]
    if (!row.category) {
      actionError.value = `第 ${index + 1} 条意见还没选分类`
      return
    }
    if (row.comment.trim().length === 0) {
      actionError.value = `第 ${index + 1} 条意见缺少正文`
      return
    }
  }
  busy.value = true
  try {
    await submitPdrOpinions(review.value.id, {
      opinions: rows.map((row) => ({
        decision: row.decision,
        category: row.category as PdrCategory,
        comment: row.comment.trim(),
      })),
    })
    ElMessage.success(`已提交 ${rows.length} 条意见`)
    drafts.value = [newDraft()]
    emit('refresh')
  } catch (error) {
    handleWriteError(error)
  } finally {
    busy.value = false
  }
}

/* ---------------- 意见 → 任务 ---------------- */
const taskOpen = ref(false)
const taskOpinion = ref<PdrOpinion | null>(null)

function openTask(opinion: PdrOpinion): void {
  actionError.value = ''
  taskOpinion.value = opinion
  taskOpen.value = true
}

function onTasked(_result: PdrToTaskResult): void {
  emit('refresh')
}

/* ---------------- 附件（评审单级） ---------------- */
const attachments = ref<AttachmentRow[]>([])
const attachmentsLoading = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const ACCEPT = '.pdf,.docx,.xlsx,.png,.jpg,.jpeg,.zip,.csv,.md'
const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

function formatSize(size?: number | null): string {
  if (!size) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

async function loadAttachments(): Promise<void> {
  if (!review.value) {
    attachments.value = []
    return
  }
  attachmentsLoading.value = true
  try {
    // 只读列表：无权限时静默降级为空（评审单本身可见，附件不可见不该整页红）
    attachments.value = await listAttachments(PDR_ATTACHMENT_BIZ_TYPE, review.value.id)
  } catch {
    attachments.value = []
  } finally {
    attachmentsLoading.value = false
  }
}

watch(
  () => review.value?.id,
  () => {
    void loadAttachments()
  },
  { immediate: true },
)

function pickFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !review.value) return
  if (file.size > MAX_ATTACHMENT_BYTES) {
    actionError.value = '单个附件不能超过 20MB'
    return
  }
  uploading.value = true
  actionError.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('bizType', PDR_ATTACHMENT_BIZ_TYPE)
    form.append('bizId', String(review.value.id))
    await uploadAttachment(form)
    ElMessage.success('附件已上传')
    await loadAttachments()
  } catch (error) {
    handleWriteError(error)
  } finally {
    uploading.value = false
  }
}

async function removeAttachment(row: AttachmentRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除附件「${row.fileName}」？`, '删除附件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteAttachment(row.id)
    ElMessage.success('附件已删除')
    await loadAttachments()
  } catch (error) {
    handleWriteError(error)
  }
}

function downloadAttachmentFile(row: AttachmentRow): void {
  void downloadAttachment(row.id, row.fileName)
}

/* ---------------- 展示辅助 ---------------- */
/** 评审人姓名：意见里带 reviewerName；还没评的人只能查字典（查不到显示 #id） */
function reviewerName(id: number): string {
  const fromOpinion = opinions.value.find((item) => item.reviewerId === id)?.reviewerName
  if (fromOpinion) return fromOpinion
  const name = dict.userName(id)
  return name.startsWith('用户 #') ? `评审人 #${id}` : name
}

function reviewerDecision(id: number): PdrDecision | null {
  return groups.value.find((group) => group.reviewerId === id)?.decision ?? null
}

function pendingReviewer(id: number): boolean {
  return progress.value.pending.includes(id)
}

/** 评审单头的一句话摘要：模式 + 截止 + 发起人 */
const headMeta = computed(() => {
  const current = review.value
  if (!current) return ''
  const parts = [pdrModeLabel(current.mode)]
  if (current.dueAt) parts.push(`截止 ${formatDateTime(current.dueAt)}`)
  if (current.fallback) parts.push('评审人由「提报人 + 域负责人」回退产生')
  return parts.join(' · ')
})

const statusLockedHint = computed(() => {
  if (!review.value) return ''
  if (review.value.status === 'APPROVED') return '评审已通过，意见区已关闭；已提交的意见仍可转任务。'
  if (review.value.status === 'REJECTED') return '评审已驳回，意见区已关闭；已提交的意见仍可转任务。'
  if (review.value.status === 'CANCELLED') return '评审已取消，仅保留记录。'
  return ''
})

const taskLink = (): string => `/requirements/${props.detail.id}?tab=tasks`
</script>

<template>
  <section class="pdr">
    <div v-if="actionError" class="pdr__banner" role="alert">
      <span>{{ actionError }}</span>
      <el-button link type="primary" size="small" @click="actionError = ''">知道了</el-button>
    </div>

    <!-- 没有评审单：发起评审 -->
    <div v-if="!review" class="surface surface--pad">
      <EmptyState
        variant="empty"
        title="还没有 PDR 评审"
        description="PDR 需求在排期前要先过一轮产品需求评审：选评审人、定截止时间，评审人逐条提意见，汇总通过后再指派厂商评估。"
        icon="file"
        :action-text="canStart ? '发起评审' : ''"
        @action="openStart"
      />
      <p v-if="!canStart" class="pdr__hint pdr__hint--center">
        需要权限：页面 pdr.review + 动作 req.action.pdr_review_start（找产品经理开通）
      </p>
    </div>

    <!-- 有评审单 -->
    <div v-else class="pdr__body">
      <header class="surface surface--pad pdr__head">
        <div class="pdr__head-main">
          <div class="pdr__title-row">
            <span class="t-block-title">产品需求评审</span>
            <StatusPill kind="pdrReview" :value="review.status" size="sm" />
            <span class="pdr__progress t-code">
              {{ progress.submitted }}/{{ progress.total }} 已评
            </span>
          </div>
          <p class="pdr__meta">{{ headMeta }}</p>
        </div>
        <div class="pdr__head-actions">
          <el-button
            v-if="canConclude && writable"
            size="small"
            :loading="busy"
            data-test="pdr-conclude"
            @click="conclude"
          >
            汇总结论
          </el-button>
          <el-button
            v-if="canConclude && writable"
            size="small"
            :loading="busy"
            data-test="pdr-close"
            @click="closeReview"
          >
            关闭评审
          </el-button>
        </div>
      </header>

      <p v-if="statusLockedHint" class="pdr__hint">{{ statusLockedHint }}</p>

      <!-- 评审人 -->
      <section class="surface surface--pad">
        <div class="pdr__section-head">
          <span class="t-block-title">评审人</span>
          <span class="t-aux">{{ progress.submitted }}/{{ progress.total }} 已提交 · {{ pdrModeLabel(review.mode) }}</span>
        </div>
        <ul class="pdr__reviewers">
          <li
            v-for="id in review.reviewerIds"
            :key="id"
            class="pdr__reviewer"
            :class="{ 'is-pending': pendingReviewer(id) }"
          >
            <span class="pdr__reviewer-name">{{ reviewerName(id) }}</span>
            <span v-if="id === review.initiatorId" class="pdr__tag">发起人</span>
            <StatusPill
              v-if="reviewerDecision(id)"
              kind="caseReviewConclusion"
              :value="reviewerDecision(id) as string"
              size="sm"
            />
            <span v-else class="pdr__tag pdr__tag--muted">待评</span>
            <span v-if="isReviewer && id === me?.id" class="pdr__tag pdr__tag--muted">我</span>
          </li>
          <li v-if="review.reviewerIds.length === 0" class="pdr__reviewer is-pending">
            评审人待后端回填（发起时项目组为空）
          </li>
        </ul>
        <p v-if="!isReviewer && writable" class="pdr__hint">
          你不在本次评审人里，可以查看意见但不能提交意见。
        </p>
      </section>

      <!-- 我的意见：一次多条 -->
      <section v-if="canWriteOpinion" class="surface surface--pad" data-test="pdr-opinion-form">
        <div class="pdr__section-head">
          <span class="t-block-title">我的意见{{ drafts.length > 1 ? `（${drafts.length} 条）` : '' }}</span>
          <span class="t-aux">
            {{ myOpinionSubmitted ? '已提交过，可继续补充' : '同一次可以提交多条，分类必选' }}
          </span>
        </div>
        <div v-for="(draft, index) in drafts" :key="draft.key" class="pdr__draft">
          <div class="pdr__draft-head">
            <span class="t-aux">第 {{ index + 1 }} 条</span>
            <el-button
              v-if="drafts.length > 1"
              link
              size="small"
              @click="removeDraft(draft.key)"
            >
              删除
            </el-button>
          </div>
          <div class="pdr__draft-row">
            <el-select v-model="draft.category" placeholder="分类（必选）" size="small" style="width: 170px">
              <el-option
                v-for="option in PDR_CATEGORY_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-select v-model="draft.decision" size="small" style="width: 130px">
              <el-option
                v-for="code in PDR_DECISION_CODES"
                :key="code"
                :label="PDR_DECISION_HINTS[code]"
                :value="code"
              />
            </el-select>
            <el-input
              v-model="draft.comment"
              type="textarea"
              :rows="2"
              :maxlength="1000"
              show-word-limit
              placeholder="意见正文：说清问题 + 期望的修改"
              class="pdr__draft-comment"
            />
          </div>
        </div>
        <div class="pdr__draft-actions">
          <el-button link type="primary" size="small" @click="addDraft">+ 再加一条</el-button>
          <span class="pdr__spacer" />
          <el-button type="primary" size="small" :loading="busy" @click="submitOpinions">
            提交意见
          </el-button>
        </div>
      </section>

      <!-- 意见列表：按人分组 -->
      <section class="surface surface--pad">
        <div class="pdr__section-head">
          <span class="t-block-title">评审意见</span>
          <span class="t-aux">{{ opinions.length }} 条 · 附件 {{ attachments.length }} 个</span>
        </div>

        <div v-if="opinions.length === 0" class="pdr__empty">
          <EmptyState variant="empty" title="还没有评审意见" description="评审人提交意见后会按人分组显示在这里。" icon="inbox" compact />
        </div>

        <div v-for="group in groups" :key="group.reviewerId" class="pdr__group">
          <div class="pdr__group-head">
            <span class="pdr__group-name">{{ group.reviewerName }}</span>
            <StatusPill
              v-if="group.decision"
              kind="caseReviewConclusion"
              :value="group.decision"
              size="sm"
            />
            <span class="t-aux">{{ group.opinions.length }} 条</span>
          </div>
          <article v-for="opinion in group.opinions" :key="opinion.id" class="pdr__opinion">
            <div class="pdr__opinion-head">
              <span class="pdr__chip">{{ opinion.categoryLabel || opinion.category }}</span>
              <StatusPill kind="pdrOpinion" :value="opinion.status" size="sm" />
              <span class="t-aux">#{{ opinion.seq }} · {{ fromNow(opinion.createdAt) }}</span>
              <span class="pdr__spacer" />
              <a
                v-if="opinion.status === 'TASKED' && opinion.taskId"
                class="pdr__task-chip"
                :href="taskLink()"
              >
                任务 #{{ opinion.taskId }}
              </a>
              <el-button
                v-else-if="showTaskAction"
                link
                type="primary"
                size="small"
                @click="openTask(opinion)"
              >
                转为任务
              </el-button>
            </div>
            <p class="pdr__opinion-body">{{ opinion.comment }}</p>
          </article>
        </div>
      </section>

      <!-- 附件（评审单级） -->
      <section class="surface surface--pad">
        <div class="pdr__section-head">
          <span class="t-block-title">评审材料</span>
          <span class="t-aux">
            PDF / DOCX / XLSX / PNG / JPG / ZIP / CSV / MD，单个 ≤ 20MB
          </span>
        </div>
        <div class="pdr__file-actions">
          <input ref="fileInput" type="file" class="pdr__file-input" :accept="ACCEPT" @change="onFileChange" />
          <el-button v-if="canUpload" size="small" :loading="uploading" @click="pickFile">上传附件</el-button>
          <span v-if="!canUpload && writable" class="pdr__hint">只有评审人 / 发起人可以上传附件。</span>
          <span v-if="!writable" class="pdr__hint">评审已结束，附件只读。</span>
        </div>
        <ul v-if="attachments.length" class="pdr__files">
          <li v-for="row in attachments" :key="row.id" class="pdr__file">
            <button type="button" class="pdr__file-name" @click="downloadAttachmentFile(row)">
              {{ row.fileName }}
            </button>
            <span class="t-aux">{{ formatSize(row.size) }}</span>
            <span class="pdr__spacer" />
            <el-button
              v-if="canUpload"
              link
              type="danger"
              size="small"
              @click="removeAttachment(row)"
            >
              删除
            </el-button>
          </li>
        </ul>
        <p v-else-if="!attachmentsLoading" class="pdr__hint">还没有评审材料。</p>
      </section>
    </div>

    <StartPdrReviewDialog
      v-model="startOpen"
      :requirement-id="props.detail.id"
      :requirement-code="props.detail.code"
      :project-id="props.detail.projectId ?? props.detail.pdrReview?.review?.projectId ?? null"
      @started="onStarted"
    />

    <OpinionToTaskDialog
      v-model="taskOpen"
      :review-id="review?.id ?? 0"
      :requirement-id="props.detail.id"
      :requirement-code="props.detail.code"
      :opinion="taskOpinion"
      :current-user-id="me?.id ?? null"
      @tasked="onTasked"
    />
  </section>
</template>

<style scoped>
.pdr {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pdr__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pdr__banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}
.pdr__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.pdr__head-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.pdr__title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pdr__progress {
  color: var(--text-secondary);
}
.pdr__meta {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
}
.pdr__head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.pdr__section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.pdr__reviewers {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.pdr__reviewer {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--bg-card);
  font-size: 12px;
  color: var(--text-body);
}
.pdr__reviewer.is-pending {
  border-style: dashed;
  color: var(--text-secondary);
}
.pdr__reviewer-name {
  font-weight: 500;
}
.pdr__tag {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--color-info-bg);
  color: var(--info-ink);
  font-size: 11px;
}
.pdr__tag--muted {
  background: var(--bg-hover);
  color: var(--text-secondary);
}
.pdr__hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.pdr__hint--center {
  text-align: center;
}
.pdr__draft {
  padding: 8px 0;
  border-top: 1px dashed var(--divider);
}
.pdr__draft:first-of-type {
  border-top: none;
}
.pdr__draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.pdr__draft-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.pdr__draft-comment {
  flex: 1;
  min-width: 0;
}
.pdr__draft-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--divider);
}
.pdr__spacer {
  flex: 1;
}
.pdr__empty {
  padding: 8px 0;
}
.pdr__group + .pdr__group {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
}
.pdr__group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.pdr__group-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.pdr__opinion {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.pdr__opinion + .pdr__opinion {
  margin-top: 8px;
}
.pdr__opinion-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pdr__chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 10px;
  background: var(--color-info-bg);
  color: var(--info-ink);
  font-size: 12px;
}
.pdr__opinion-body {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-body);
  line-height: 1.7;
  white-space: pre-wrap;
}
.pdr__task-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 10px;
  background: var(--color-success-bg);
  color: var(--success-ink);
  font-size: 12px;
  text-decoration: none;
}
.pdr__task-chip:hover {
  text-decoration: underline;
}
.pdr__file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pdr__file-input {
  display: none;
}
.pdr__files {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}
.pdr__file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
}
.pdr__file-name {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-primary);
  font-size: 13px;
  cursor: pointer;
}
.pdr__file-name:hover {
  text-decoration: underline;
}
</style>
