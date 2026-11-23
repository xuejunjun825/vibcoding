<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { applyRequirementChange, getApprovalInstance, getRequirementChange } from '@/api/req'
import type { ChangeDetail } from '@/api/req'
import type { ApprovalInstanceDetail } from '@/api/types'
import type { ApiError } from '@/api/client'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import ChangeDiffTable from '@/components/req/ChangeDiffTable.vue'
import ChangeImpactCards from '@/components/req/ChangeImpactCards.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { changeErrorText, changeListPath, impactSummaryLines, typeLabel } from '@/views/req/change'
import { normalizeTimeline, requirementPath } from '@/views/req/platform'
import { formatDateTime } from '@/utils/format'

/**
 * 变更详情深链页（DESIGN §3.3）。`/requirements/:id/changes/:changeId`。
 *
 * 这一页唯一的职责是回答「到底生效没有」：状态横幅说清 APPROVED ≠ APPLIED，
 * 字段对照给出前后值，APPLY 只在真正可应用时**整颗渲染**（不置灰、不解释）。
 */
const route = useRoute()
const router = useRouter()

const detail = ref<ChangeDetail | null>(null)
const instance = ref<ApprovalInstanceDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const errorStatus = ref(0)
const applyOpen = ref(false)
const applying = ref(false)
const applyError = ref('')

const requirementId = computed(() => Number(route.params.id))
const changeId = computed(() => Number(route.params.changeId))

const status = computed(() => detail.value?.status ?? '')
/** APPROVED 只代表「审批通过」，需求字段未动；文案与视觉都必须与 APPLIED 区分 */
const banner = computed(() => {
  switch (status.value) {
    case 'PENDING':
      return {
        tone: 'warning',
        title: '等待审批',
        text: '变更单尚未审批通过，需求字段仍是申请前的值。',
      }
    case 'APPROVED':
      return {
        tone: 'primary',
        title: '审批已通过，尚未应用',
        text: '需求字段仍是申请前的值 —— 点右上「应用变更」才写入，并留下基线记录。',
      }
    case 'APPLIED':
      return {
        tone: 'success',
        title: '已应用',
        text: detail.value?.appliedAt
          ? `需求字段已按本单写入（${formatDateTime(detail.value.appliedAt)}）。`
          : '需求字段已按本单写入。',
      }
    case 'REJECTED':
      return { tone: 'danger', title: '已驳回', text: '需求字段未改动；如需继续，请重新发起变更。' }
    case 'WITHDRAWN':
      return { tone: 'neutral', title: '已撤回', text: '需求字段未改动。' }
    default:
      return { tone: 'neutral', title: '未知状态', text: '无法判断本单是否生效。' }
  }
})

const canApply = computed(() => status.value === 'APPROVED' && detail.value?.canApply === true)

const diffFields = computed(() => {
  const items = detail.value?.items ?? []
  if (items.length) return items.map((item) => item.field)
  const keys = new Set<string>([
    ...Object.keys(detail.value?.before ?? {}),
    ...Object.keys(detail.value?.after ?? {}),
  ])
  return [...keys]
})

const timeline = computed(() => normalizeTimeline(instance.value?.timeline))

const trailChips = computed(() => {
  const chips = ['CHANGE_REQUESTED']
  if (status.value === 'APPLIED') chips.push('REQUIREMENT_CHANGE_APPLIED')
  if ((detail.value?.impact?.postLock?.lockedReleaseIds?.length ?? 0) > 0) chips.push('CHANGE_AFTER_RELEASE_LOCK')
  return chips
})

const applySummary = computed(() => {
  const impact = detail.value?.impact
  if (!impact) return ['影响快照缺失，应用后仍会留下审计记录']
  const lines = impactSummaryLines({
    children: impact.children?.count ?? null,
    daysDelta: impact.days?.delta ?? null,
    becomesStale: impact.days?.becomesStale ?? false,
    releaseLocked: impact.postLock?.value ?? null,
    affectedOrderCount: impact.admission?.passedOrderCodes?.length ?? null,
  })
  return [lines.line1, lines.line2].filter(Boolean) as string[]
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  errorStatus.value = 0
  try {
    detail.value = await getRequirementChange(changeId.value)
    if (detail.value.instanceId) {
      try {
        instance.value = await getApprovalInstance(detail.value.instanceId)
      } catch {
        // 审批实例读不到不该挡住单据本身：时间线给空态说明
        instance.value = null
      }
    } else {
      instance.value = null
    }
  } catch (error) {
    const apiError = error as ApiError
    detail.value = null
    loadError.value = apiError?.message ?? '变更单加载失败'
    errorStatus.value = apiError?.status ?? 0
  } finally {
    loading.value = false
  }
}

function retry(): void {
  void load()
}

async function doApply(): Promise<void> {
  if (applying.value) return
  applying.value = true
  applyError.value = ''
  try {
    detail.value = await applyRequirementChange(changeId.value)
    applyOpen.value = false
    ElMessage.success('变更已应用，需求字段已更新并留下基线记录')
  } catch (error) {
    // 409 就地渲染：CHANGE_CONFLICT 必须让人看到「一条都没写」这一点
    applyError.value = changeErrorText(error).text
    applyOpen.value = false
  } finally {
    applying.value = false
  }
}

async function copyChip(code: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success(`已复制 ${code}`)
  } catch {
    ElMessage.warning('复制失败，请手动选中')
  }
}

function restartChange(): void {
  void router.push(changeListPath(requirementId.value))
}

watch(
  () => route.params.changeId,
  () => {
    void load()
  },
)

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="change-detail" data-test="change-detail">
    <ErrorState
      v-if="loadError && !detail"
      :title="errorStatus === 403 ? '无权查看该变更单' : '变更单不存在或已删除'"
      :message="loadError"
      :code="String(errorStatus || '')"
      :retryable="errorStatus !== 404 && errorStatus !== 403"
      @retry="retry"
    />

    <template v-else-if="detail">
      <nav class="change-detail__crumbs" aria-label="面包屑">
        <router-link to="/requirements">需求池</router-link>
        <span class="change-detail__sep">/</span>
        <router-link :to="requirementPath(detail.requirement.id)">{{ detail.requirement.code }}</router-link>
        <span class="change-detail__sep">/</span>
        <router-link :to="changeListPath(detail.requirement.id)">变更</router-link>
        <span class="change-detail__sep">/</span>
        <span class="change-detail__current">#{{ detail.id }}</span>
      </nav>

      <header class="change-detail__head">
        <div class="change-detail__head-main">
          <div class="change-detail__title-row">
            <h1 class="change-detail__title">变更 #{{ detail.id }}</h1>
            <StatusPill kind="reqChange" :value="detail.status" />
            <span class="change-detail__type">{{ typeLabel(detail.changeType) }}</span>
          </div>
          <p class="change-detail__req">{{ detail.requirement.code }} {{ detail.requirement.title }}</p>
        </div>
        <div class="change-detail__actions">
          <!-- 只有 APPROVED 且服务端 canApply 才「整颗渲染」：不置灰、不解释，避免误读为待点未点 -->
          <el-button
            v-if="canApply"
            type="primary"
            size="small"
            data-test="change-apply"
            @click="applyOpen = true"
          >
            应用变更
          </el-button>
        </div>
      </header>

      <section class="change-banner" :class="`change-banner--${banner.tone}`" data-test="change-banner">
        <strong class="change-banner__title">{{ banner.title }}</strong>
        <span class="change-banner__text">{{ banner.text }}</span>
      </section>

      <p v-if="applyError" class="change-alert" role="alert">
        {{ applyError }}
        <button type="button" class="change-alert__link" @click="restartChange">重新发起变更</button>
      </p>

      <section class="surface change-card">
        <h2 class="change-card__title">申请原因</h2>
        <p class="change-card__reason">{{ detail.reason }}</p>
      </section>

      <section class="surface change-card">
        <h2 class="change-card__title">字段变更</h2>
        <p class="change-card__sub">
          提交前的值与申请后的值（{{ diffFields.length }} 项字段）。审批通过后仍需应用才写入需求。
        </p>
        <ChangeDiffTable :before="detail.before" :after="detail.after" :fields="diffFields" />
      </section>

      <section class="change-card change-card--plain">
        <h2 class="change-card__title">影响快照</h2>
        <p class="change-card__sub">申请提交时冻结的影响，应用后不重算（留痕口径）。</p>
        <ChangeImpactCards :impact="detail.impact" mode="readonly" />
      </section>

      <section class="surface change-card">
        <h2 class="change-card__title">审批进度</h2>
        <ApprovalTimeline v-if="timeline.length" :events="timeline" dense />
        <p v-else class="change-card__empty">暂无审批记录（审批实例可能已被清理）。</p>
      </section>

      <section class="surface change-card">
        <h2 class="change-card__title">留痕</h2>
        <div class="change-trail">
          <button
            v-for="chip in trailChips"
            :key="chip"
            type="button"
            class="change-trail__chip mono"
            :title="`点击复制 ${chip}`"
            @click="copyChip(chip)"
          >
            {{ chip }}
          </button>
          <span class="change-trail__meta">
            申请人 {{ detail.createdBy ? `#${detail.createdBy}` : '—' }} ·
            提交 {{ formatDateTime(detail.createdAt) }}
            <template v-if="detail.appliedAt"> · 应用 {{ formatDateTime(detail.appliedAt) }}</template>
            <template v-if="detail.appliedBy"> · 应用人 #{{ detail.appliedBy }}</template>
          </span>
        </div>
      </section>

      <ConfirmDialog
        v-model="applyOpen"
        tone="primary"
        title="应用变更"
        description="应用会在一个事务内写入需求字段、字段修改明细与审计；版本成员与锁定按影响快照处理。"
        :impact="applySummary"
        :reason-required="false"
        confirm-text="应用变更"
        :loading="applying"
        @confirm="doApply"
      />
    </template>

    <div v-else-if="loading" class="change-detail__loading">加载中…</div>
  </div>
</template>

<style scoped>
.change-detail {
  display: flex;
  flex-direction: column;
  gap: var(--change-row-gap);
  padding: var(--change-gutter);
  max-width: 1080px;
}

.change-detail__crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-detail__crumbs a {
  color: var(--text-muted-aa);
  text-decoration: none;
}

.change-detail__crumbs a:hover {
  color: var(--color-primary);
}

.change-detail__current {
  color: var(--text-title);
}

.change-detail__sep {
  color: var(--text-weak);
}

.change-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.change-detail__title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.change-detail__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-title);
}

.change-detail__type {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
}

.change-detail__req {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted-aa);
}

.change-detail__actions {
  display: flex;
  gap: 8px;
}

.change-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: var(--change-banner-pad);
  border-radius: var(--r-cell);
  border: 1px solid transparent;
}

.change-banner__title {
  font-size: 14px;
  font-weight: 600;
}

.change-banner__text {
  font-size: 13px;
}

.change-banner--warning {
  background: var(--color-warning-bg);
  border-color: var(--color-warning);
  color: var(--warning-ink);
}

.change-banner--primary {
  background: var(--bg-selected);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.change-banner--success {
  background: var(--color-success-bg);
  border-color: var(--color-success);
  color: var(--success-ink);
}

.change-banner--danger {
  background: var(--color-danger-bg);
  border-color: var(--color-danger);
  color: var(--danger-ink);
}

.change-banner--neutral {
  background: var(--bg-hover);
  border-color: var(--border);
  color: var(--text-muted-aa);
}

.change-alert {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--r-cell);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 13px;
}

.change-alert__link {
  margin-left: 8px;
  border: 0;
  padding: 0;
  background: none;
  color: var(--color-primary);
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
}

.change-card {
  padding: 16px;
}

.change-card--plain {
  padding: 0;
}

.change-card__title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}

.change-card__sub {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-card__reason {
  margin: 0;
  font-size: 13px;
  line-height: 22px;
  color: var(--text-body);
  white-space: pre-wrap;
  word-break: break-word;
}

.change-card__empty {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-trail {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.change-trail__chip {
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  padding: 2px 8px;
  font-size: 11px;
  color: var(--text-muted-aa);
  cursor: pointer;
}

.change-trail__chip:hover {
  border-color: var(--border-strong);
  color: var(--text-title);
}

.change-trail__meta {
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-detail__loading {
  padding: 40px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted-aa);
}

@media (max-width: 1280px) {
  .change-detail {
    max-width: 960px;
  }
}
</style>
