<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { submitEstimate, tagsOf } from '@/api/req'
import {
  followRequirement,
  listRequirementFollowers,
  unfollowRequirement,
} from '@/api/poolOps'
import { ApiError } from '@/api/client'
import type {
  ApprovalInstanceDetail,
  ApprovalTimelineEvent,
  EstimateBlock,
  RequirementDetail,
  RequirementFollower,
  RequirementOrgCount,
} from '@/api/types'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import FieldFlag from '@/views/req/FieldFlag.vue'
import StatusPill from '@/components/StatusPill.vue'
import TagChips from '@/components/TagChips.vue'
import { usePermission } from '@/composables/usePermission'
import { useDictStore } from '@/stores/dict'
import { formatDate, formatDateTime, formatDays } from '@/utils/format'
import { changePermissionsOf } from '@/views/req/change'
import { ruleOf } from '@/views/req/editability'
import {
  ESTIMATE_CTA_LABEL,
  ESTIMATE_EVENT_LABEL,
  ESTIMATE_META,
  ESTIMATE_STALE_REASON,
  ESTIMATE_SUPERSEDED_REASON,
  estimateDeviationLabel,
  estimateGateTip,
  estimateStatusOf,
} from '@/views/req/estimate'
import { messageOf, requirementPath, typeLabel, usePlatformDict } from '@/views/req/platform'
import { pdrModeLabel, pdrProgress } from '@/views/req/pdr'

/**
 * P2 · 概览 tab：左字段网格，右审批进度小卡 + 最近 5 条动态。
 *
 * r26：顶部整宽「工时评估」卡片（六态 + 提交弹窗）。卡片门提示与动作行提示
 * 共用 `estimateGateTip()`，禁在模板里拼字符串（AC-13 逐字一致）。
 *
 * r43：字段行末尾挂 `FieldFlag`，可编辑性只读服务端三数组（`editability.ts`）。
 * **不再有本地「父需求」推断**——父需求人天为什么只读由矩阵的 `PARENT_ROLLUP` 说，
 * 子需求条数从此不参与任何字段判定（AC-3）。锁定只是标记，字段值永远可见。
 */
const props = defineProps<{
  detail: RequirementDetail
  approval: ApprovalInstanceDetail | null
  approvalLoading: boolean
  timeline: ApprovalTimelineEvent[]
  /** 工时评估块；BUS 或老后端为 null（NONE 是完整对象，不是空态） */
  estimate: EstimateBlock | null
}>()

const emit = defineEmits<{
  (event: 'open-approval'): void
  (event: 'submitted'): void
  /** r43 · 字段级「发起变更」：字段名交给详情页预填抽屉（AC-11） */
  (event: 'requestChange', field: string): void
  /** r47 · 跟单撞上 409 时请父级重取详情（状态可能已被他人关闭/合并） */
  (event: 'refresh'): void
}>()

const { domainName, vendorName, userName } = usePlatformDict()
const { can, canAction } = usePermission()
const dict = useDictStore()

/** 字段级 CTA 的权限 = 服务端 `permissions.changeRequest`（未下发按允许，见 change.ts 注释） */
const canChange = computed(() => changePermissionsOf(props.detail).changeRequest)

// —— r47 「我也需要」跟单卡 ——
/**
 * 跟单人数与「我是否跟单」以服务端字段为初值；本地状态只在 follow/unfollow
 * 响应上更新，随后用 followers 接口对账。父级 refreshAll 换 detail 时再同步。
 */
const FOLLOW_TERMINAL_STATUS = ['CLOSED', 'ACCEPTED_CLOSED', 'DONE']
const DRAWER_SIZE = 10
const followCount = ref(props.detail.followCount ?? 0)
const followedByMe = ref(props.detail.followedByMe ?? false)
const followNote = ref('')
const followBusy = ref(false)
const followers = ref<RequirementFollower[]>([])
const followerTotal = ref(0)
const followerOrgs = ref<RequirementOrgCount[]>([])
const followLoading = ref(false)
const followDrawer = ref(false)
const drawerPage = ref(1)
const drawerTotal = ref(0)
const drawerItems = ref<RequirementFollower[]>([])
const drawerLoading = ref(false)

/** 终态（关闭/已验收关闭/已完成/已并入主需求）：按钮禁用并说明原因，不假装能跟单 */
const followBlocked = computed(() => {
  if (props.detail.mergedIntoId != null) return true
  return FOLLOW_TERMINAL_STATUS.includes(String(props.detail.status ?? '').toUpperCase())
})
const followBlockedReason = computed(() => {
  const target = props.detail.mergedIntoId
  if (target != null) return `该需求已并入 #${target}，请到主需求下跟单`
  const label = dict.label('requirement', props.detail.status) || '已关闭'
  return `需求已关闭（${label}），不能再跟单`
})
const canFollow = computed(() => canAction('req.action.follow'))
const followButtonHint = computed(() => {
  if (followBlocked.value) return followBlockedReason.value
  return followedByMe.value ? '取消跟单（跟单人数 -1）' : '标记我也需要，跟单人数 +1'
})

function orgLabel(org: RequirementOrgCount): string {
  return org.orgName || (org.orgId != null ? `组织 #${org.orgId}` : '未归属组织')
}

async function loadFollowers(): Promise<void> {
  const requirementId = props.detail.id
  followLoading.value = true
  try {
    const page = await listRequirementFollowers(requirementId, 1, 5)
    if (requirementId !== props.detail.id) return
    followers.value = page.items ?? []
    followerTotal.value = page.total ?? 0
    followerOrgs.value = page.orgs ?? []
    followedByMe.value = page.mine
    if (props.detail.followCount == null) followCount.value = page.total ?? 0
  } catch {
    // 跟单人明细是附加信息：读不到不阻断详情，也不清掉人数
    if (requirementId === props.detail.id) followers.value = []
  } finally {
    if (requirementId === props.detail.id) followLoading.value = false
  }
}

async function toggleFollow(): Promise<void> {
  if (followBlocked.value || followBusy.value) return
  followBusy.value = true
  try {
    if (followedByMe.value) {
      await unfollowRequirement(props.detail.id)
      followedByMe.value = false
      followCount.value = Math.max(0, followCount.value - 1)
      ElMessage.success('已取消跟单')
    } else {
      const result = await followRequirement(props.detail.id, followNote.value)
      followedByMe.value = result.followedByMe
      followCount.value = result.followCount
      followNote.value = ''
      ElMessage.success(result.created ? '已加入跟单' : '你已经在跟单列表中')
    }
    await loadFollowers()
  } catch (error) {
    if (error instanceof ApiError && error.code === 'REQUIREMENT_CLOSED') {
      // 本地 status 可能已过期：409 说明服务端已终态，不拿旧状态拼文案
      ElMessage.warning('该需求已关闭（或已并入主需求），无法再跟单')
      emit('refresh')
    } else if (error instanceof ApiError && error.isForbidden) {
      ElMessage.warning('没有跟单权限，请联系需求管理员授权')
    } else if (error instanceof ApiError && error.status === 404 && followedByMe.value) {
      // 并发下已被取消：以服务端为准，静默对账
      followedByMe.value = false
      await loadFollowers()
    } else {
      ElMessage.error(messageOf(error))
    }
  } finally {
    followBusy.value = false
  }
}

async function loadDrawer(): Promise<void> {
  drawerLoading.value = true
  try {
    const page = await listRequirementFollowers(props.detail.id, drawerPage.value, DRAWER_SIZE)
    drawerItems.value = page.items ?? []
    drawerTotal.value = page.total ?? 0
  } catch {
    drawerItems.value = []
  } finally {
    drawerLoading.value = false
  }
}

function openFollowDrawer(): void {
  followDrawer.value = true
  drawerPage.value = 1
  void loadDrawer()
}

function onDrawerPage(page: number): void {
  drawerPage.value = page
  void loadDrawer()
}

/** 父级刷新（合并/关闭后）会换 detail：人数与「我的状态」以新 detail 为准 */
watch(
  () => [props.detail.followCount, props.detail.followedByMe],
  () => {
    followCount.value = props.detail.followCount ?? followCount.value
    followedByMe.value = props.detail.followedByMe ?? followedByMe.value
  },
)

/** 同页切换需求：备注/抽屉等本地态必须清，否则会带到下一条需求 */
watch(
  () => props.detail.id,
  () => {
    followNote.value = ''
    followDrawer.value = false
    drawerPage.value = 1
    drawerItems.value = []
    followCount.value = props.detail.followCount ?? 0
    followedByMe.value = props.detail.followedByMe ?? false
    void loadFollowers()
  },
)

onMounted(() => {
  void loadFollowers()
})

/**
 * 业务域支持多值（r43 新增只读投影 `domainIds`）；旧响应回退单值。
 * 值永远渲染，锁定只在行尾加标记——「11 字段值全可见」是 anti-goal 红线（AC-9）。
 */
const domainsText = computed(() => {
  const ids = props.detail.domainIds
  if (Array.isArray(ids) && ids.length > 0) return ids.map((domainId) => domainName(domainId)).join('、')
  return props.detail.domainName ?? domainName(props.detail.domainId)
})
const pendingTasks = computed(() => (props.approval?.tasks ?? []).filter((task) => task.status === 'PENDING'))
/** 最近 5 条 = 时间线末尾（升序渲染），不能取头部，否则评估事件永远进不来 */
const recent = computed(() => props.timeline.slice(-5))
const currentNode = computed(() => props.approval?.instance.currentNode ?? '')
const approvalStatus = computed(() => props.approval?.instance.status ?? '')

// —— 工时评估卡片（r26） ——
const estimateStatus = computed(() => estimateStatusOf(props.estimate))
const estimateMeta = computed(() => ESTIMATE_META[estimateStatus.value])
const gateTip = computed(() => estimateGateTip(estimateStatus.value))
const currentDays = computed(() => props.detail.estimateDays ?? null)
/** PENDING/REJECTED 显示待审单据人天；APPROVED/STALE 显示已通过人天 */
const assessedDays = computed(() => props.estimate?.approvedDays ?? props.estimate?.proposedDays ?? null)
const deviation = computed(() => estimateDeviationLabel(currentDays.value, assessedDays.value))
const approverName = computed(() =>
  props.estimate?.approverId != null ? userName(props.estimate.approverId) : '—',
)
/** 无评估单时卡片仍渲染，只把「提交评估」当唯一 CTA */
const canSubmitEstimate = computed(() => can('req:deliver'))
const canResubmitEstimate = computed(() =>
  ['NONE', 'REJECTED', 'SUPERSEDED', 'STALE'].includes(estimateStatus.value),
)
const hasInstance = computed(() => props.estimate?.instanceId != null)

const metaRow = computed(() => {
  const block = props.estimate
  if (!block) return ''
  switch (estimateStatus.value) {
    case 'PENDING':
      return `提交人 ${userName(props.detail.ownerId)} · 提交于 ${formatDateTime(block.submittedAt)}`
    case 'APPROVED':
    case 'STALE':
      return block.decidedAt
        ? `审批人 ${approverName.value} · 通过于 ${formatDateTime(block.decidedAt)}`
        : `审批人 ${approverName.value}`
    case 'REJECTED':
      return block.decidedAt
        ? `驳回人 ${approverName.value} · ${formatDateTime(block.decidedAt)}`
        : `驳回人 ${approverName.value}`
    case 'SUPERSEDED':
      return block.decidedAt ? `作废于 ${formatDateTime(block.decidedAt)}` : ''
    default:
      return ''
  }
})

const reasonText = computed(() => {
  const block = props.estimate
  if (!block) return ''
  if (estimateStatus.value === 'REJECTED') return `驳回原因：${block.reason ?? '—'}`
  if (estimateStatus.value === 'SUPERSEDED') return ESTIMATE_SUPERSEDED_REASON
  if (estimateStatus.value === 'STALE') {
    if (block.approvedDays != null && currentDays.value != null) {
      return `人天已由 ${formatDays(block.approvedDays)} 调整为 ${formatDays(currentDays.value)}（${deviation.value}），超过 ±20%，评估已失效。`
    }
    return ESTIMATE_STALE_REASON
  }
  return ''
})

// —— 提交评估弹窗 ——
const dialogOpen = ref(false)
const formDays = ref<number | undefined>()
const formNote = ref('')
const formError = ref('')
const submitting = ref(false)
const flash = ref(false)

/** 承接厂商：需求上带（详情 view.vendorId/vendorName）；评估提交前必须有主 */
const vendorText = computed(() =>
  props.detail.vendorId ? props.detail.vendorName ?? vendorName(props.detail.vendorId) : '未指派厂商',
)
const pdrReview = computed(() => props.detail.pdrReview?.review ?? null)
const pdrMeta = computed(() => {
  const review = pdrReview.value
  if (!review) return ''
  const progress = pdrProgress(props.detail.pdrReview)
  return `${pdrModeLabel(review.mode)} · ${progress.submitted}/${progress.total} 已评`
})

const dialogTitle = computed(() =>
  estimateStatus.value === 'NONE' ? '提交工时评估' : '重新提交工时评估',
)
/** 重提时提示上次单据人天，避免负责人凭记忆重填 */
const lastDaysHint = computed(() => {
  if (estimateStatus.value === 'NONE' || props.estimate?.proposedDays == null) return ''
  return `上次评估 ${formatDays(props.estimate.proposedDays)}`
})

function openEstimateDialog(): void {
  if (!canSubmitEstimate.value) return
  formDays.value = props.estimate?.proposedDays ?? props.detail.estimateDays ?? undefined
  formNote.value = ''
  formError.value = ''
  dialogOpen.value = true
}

function flashCard(): void {
  flash.value = true
  window.setTimeout(() => {
    flash.value = false
  }, 600)
}

async function submitForm(): Promise<void> {
  if (!canSubmitEstimate.value) return
  // 契约顺序：先指派厂商 → 再有厂商的工时评估（后端 409 VENDOR_NOT_ASSIGNED）
  if (!props.detail.vendorId) {
    formError.value = '请先指派厂商，再由厂商提交工时评估'
    return
  }
  const days = Number(formDays.value ?? 0)
  if (!Number.isFinite(days) || days <= 0) {
    formError.value = '评估人天必须大于 0'
    return
  }
  // 与后端同口径：只接受 0.5 人天的整数倍（400 INVALID_DAY_PRECISION）
  if (Math.round(days * 2) !== days * 2) {
    formError.value = '评估人天必须按 0.5 人天为步长填写'
    return
  }
  if (formNote.value.length > 500) {
    formError.value = '说明最多 500 字'
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    await submitEstimate(props.detail.id, {
      estimateDays: days,
      note: formNote.value.trim() || undefined,
    })
    ElMessage.success('工时评估已提交，等待审批')
    dialogOpen.value = false
    flashCard()
    emit('submitted')
  } catch (error) {
    const status = (error as { status?: number }).status
    const code = (error as { code?: string }).code
    // 已通过且人天未变：不关窗，行内说明并刷新卡片（AC-4 / §3.6）
    if (status === 409 && code === 'ESTIMATE_ALREADY_APPROVED') {
      formError.value = messageOf(error) || '该需求已通过工时评估且人天未变，无需重新提交'
      emit('submitted')
      return
    }
    if (status === 403) {
      formError.value = '无权提交工时评估'
      return
    }
    formError.value = messageOf(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="overview">
    <section
      v-if="detail.type !== 'BUS'"
      class="surface surface--pad estimate-card"
      :class="{ 'is-flash': flash }"
      data-test="estimate-card"
      :data-status="estimateStatus"
      aria-live="polite"
    >
      <header class="estimate-card__head">
        <div class="estimate-card__title-row">
          <h3 class="estimate-card__title">工时评估</h3>
          <span class="estimate-pill" :class="`estimate-pill--${estimateMeta.tone}`">
            {{ estimateMeta.label }}
          </span>
        </div>
        <div class="estimate-card__cta">
          <el-button
            v-if="canResubmitEstimate"
            size="small"
            data-test="estimate-submit"
            :disabled="!canSubmitEstimate || submitting"
            :title="canSubmitEstimate ? '' : '需要权限：req:deliver'"
            @click="openEstimateDialog"
          >
            {{ estimateStatus === 'NONE' ? ESTIMATE_CTA_LABEL.submit : ESTIMATE_CTA_LABEL.resubmit }}
          </el-button>
          <el-button v-if="hasInstance" size="small" @click="emit('open-approval')">
            {{ ESTIMATE_CTA_LABEL.view }}
          </el-button>
        </div>
      </header>

      <dl class="estimate-metrics">
        <div>
          <dt>当前人天</dt>
          <dd class="num">{{ formatDays(currentDays) }}</dd>
        </div>
        <div>
          <dt>评估人天</dt>
          <dd class="num">{{ formatDays(assessedDays) }}</dd>
        </div>
        <div>
          <dt>偏差</dt>
          <dd class="num">{{ deviation }}</dd>
        </div>
        <div>
          <dt>审批人</dt>
          <dd>{{ approverName }}</dd>
        </div>
      </dl>

      <p v-if="metaRow" class="estimate-card__meta">{{ metaRow }}</p>
      <p v-if="estimate?.note" class="estimate-card__note">
        评估说明：{{ estimate.note }}
      </p>
      <p
        v-if="reasonText"
        class="estimate-reason"
        :class="estimateStatus === 'REJECTED' ? 'estimate-reason--danger' : 'estimate-reason--warning'"
      >
        {{ reasonText }}
      </p>
      <p v-if="gateTip" class="estimate-gate" data-test="estimate-gate-tip">
        <svg class="estimate-gate__lock" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
          <path
            d="M3.5 5V3.75a2.5 2.5 0 0 1 5 0V5"
            fill="none"
            stroke="currentColor"
            stroke-width="1.2"
            stroke-linecap="round"
          />
          <rect x="2.25" y="5" width="7.5" height="5.25" rx="1.2" fill="none" stroke="currentColor" stroke-width="1.2" />
        </svg>
        <span class="estimate-gate__text">{{ gateTip }}</span>
      </p>
    </section>

    <div class="overview__grid">
      <section class="surface surface--pad overview__fields">
        <h3 class="overview__title">基本信息</h3>
        <dl class="grid">
          <div>
            <dt>需求类型</dt>
            <dd>
              {{ typeLabel(detail.type) }}
              <FieldFlag
                :rule="ruleOf(detail, 'type')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'type')"
              />
            </dd>
          </div>
          <div>
            <dt>业务域</dt>
            <dd>
              {{ domainsText }}
              <FieldFlag
                :rule="ruleOf(detail, 'domainIds')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'domainIds')"
              />
            </dd>
          </div>
          <div>
            <dt>承接厂商</dt>
            <dd>
              {{ detail.vendorName ?? vendorName(detail.vendorId) }}
              <span v-if="!detail.vendorId" class="overview__muted">未指派</span>
            </dd>
          </div>
          <div v-if="detail.type === 'PDR'">
            <dt>评审状态</dt>
            <dd>
              <template v-if="pdrReview">
                <StatusPill kind="pdrReview" :value="pdrReview.status" size="sm" />
                <span class="overview__muted">{{ pdrMeta }}</span>
              </template>
              <span v-else class="overview__muted">未发起</span>
            </dd>
          </div>
          <div>
            <dt>负责人</dt>
            <dd>
              {{ detail.ownerName ?? userName(detail.ownerId) }}
              <FieldFlag
                :rule="ruleOf(detail, 'ownerUserId')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'ownerUserId')"
              />
            </dd>
          </div>
          <div>
            <dt>期望交付</dt>
            <dd>
              {{ formatDate(detail.expectedDeliveryDate) }}
              <FieldFlag
                :rule="ruleOf(detail, 'expectedDeliveryDate')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'expectedDeliveryDate')"
              />
            </dd>
          </div>
          <div>
            <dt>所属版本</dt>
            <dd>
              {{ detail.releaseName ?? (detail.releaseId ? `版本 #${detail.releaseId}` : '未排期') }}
              <FieldFlag
                :rule="ruleOf(detail, 'releaseId')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'releaseId')"
              />
            </dd>
          </div>
          <div>
            <dt>预估人天</dt>
            <dd class="num">
              {{ formatDays(detail.estimateDays) }}
              <FieldFlag
                :rule="ruleOf(detail, 'estimateDays')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'estimateDays')"
              />
            </dd>
          </div>
          <div>
            <dt>实际人天</dt>
            <dd class="num">
              {{ formatDays(detail.actualDays) }}
              <span class="rollup-mark" data-test="actual-days-rollup">汇总</span>
            </dd>
          </div>
          <div data-test="overview-parent">
            <dt>父需求</dt>
            <dd>
              <router-link
                v-if="detail.parent"
                :to="requirementPath(detail.parent.id)"
                class="parent-link"
                :title="`${detail.parent.code} ${detail.parent.title}`"
              >
                <span class="code-mono">{{ detail.parent.code }}</span>
                <span class="parent-title">{{ detail.parent.title }}</span>
              </router-link>
              <span v-else class="parent-none">顶层需求</span>
              <FieldFlag
                :rule="ruleOf(detail, 'parentId')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'parentId')"
              />
            </dd>
          </div>
          <div data-test="overview-sonar">
            <dt>Sonar 项目键</dt>
            <dd>
              <span
                v-if="detail.sonarProjectKey"
                class="code-mono"
                :title="detail.sonarProjectKey"
              >{{ detail.sonarProjectKey }}</span>
              <span v-else class="parent-none">—</span>
              <FieldFlag
                :rule="ruleOf(detail, 'sonarProjectKey')"
                :change-disabled="!canChange"
                @request-change="emit('requestChange', 'sonarProjectKey')"
              />
            </dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(detail.createdAt) }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd>{{ formatDateTime(detail.updatedAt) }}</dd>
          </div>
        </dl>
        <div v-if="tagsOf(detail).length" class="overview__tags">
          <span class="overview__tags-label">标签</span>
          <TagChips :tags="tagsOf(detail)" />
        </div>
        <p v-if="detail.description" class="overview__desc">{{ detail.description }}</p>
      </section>

      <aside class="overview__side">
        <!-- r47 「我也需要」：人数/组织聚合/前 5 位跟单人；终态禁用并说明原因 -->
        <section class="surface surface--pad follow" data-test="follow-card">
          <header class="overview__card-head">
            <h3 class="overview__title">需求热度</h3>
            <span class="follow__count">{{ followCount }} 人需要</span>
          </header>
          <div class="follow__actions">
            <el-button
              v-if="canFollow"
              class="btn--disabled-reason"
              size="small"
              :type="followedByMe ? 'default' : 'primary'"
              :loading="followBusy"
              :disabled="followBlocked || followBusy"
              :title="followButtonHint"
              @click="toggleFollow"
            >
              {{ followedByMe ? '取消跟单' : '我也需要' }}
            </el-button>
            <span v-if="followBlocked" class="follow__hint">{{ followBlockedReason }}</span>
          </div>
          <el-input
            v-if="canFollow && !followedByMe && !followBlocked"
            v-model="followNote"
            class="follow__note"
            size="small"
            maxlength="80"
            placeholder="备注（可选）：为什么需要"
          />
          <div class="follow__orgs" v-if="followerOrgs.length">
            <span v-for="org in followerOrgs.slice(0, 5)" :key="`${org.orgId ?? org.orgName}`" class="follow__org">
              {{ orgLabel(org) }} {{ org.count }}
            </span>
          </div>
          <div class="follow__list">
            <div v-if="followLoading" class="overview__muted">正在加载跟单人…</div>
            <template v-else-if="followers.length">
              <ul class="follow__items">
                <li v-for="follower in followers" :key="follower.userId" class="follow__item">
                  <span class="follow__name">{{ follower.userName || `用户 #${follower.userId}` }}</span>
                  <span class="follow__org-name">{{ follower.orgName || '未归属组织' }}</span>
                </li>
              </ul>
              <button
                v-if="followerTotal > followers.length"
                type="button"
                class="overview__link"
                @click="openFollowDrawer"
              >
                查看全部 {{ followerTotal }} 人 →
              </button>
            </template>
            <p v-else class="overview__muted">还没有人跟单，点「我也需要」让团队看到热度。</p>
          </div>
        </section>

        <section class="surface surface--pad">
          <header class="overview__card-head">
            <h3 class="overview__title">审批进度</h3>
            <button type="button" class="overview__link" @click="emit('open-approval')">查看审批 →</button>
          </header>
          <div v-if="approvalLoading" class="skeleton skeleton__row" />
          <template v-else-if="approval">
            <div class="overview__approval">
              <StatusPill kind="approval" :value="approvalStatus" />
              <span class="overview__node">当前节点：{{ currentNode || '—' }}</span>
              <span class="overview__node">第 {{ approval.instance.definitionVersion }} 版流程</span>
            </div>
            <ul v-if="pendingTasks.length" class="overview__tasks">
              <li v-for="task in pendingTasks" :key="task.id">
                <span>{{ task.nodeName }}</span>
                <span class="overview__assignee">{{ task.assignee?.name ?? '待指派' }}</span>
              </li>
            </ul>
            <p v-else class="overview__muted">没有待处理的审批任务</p>
          </template>
          <p v-else class="overview__muted">尚未发起审批；提交后这里显示当前节点与待办人。</p>
        </section>

        <section class="surface surface--pad">
          <h3 class="overview__title">最近动态</h3>
          <ApprovalTimeline
            v-if="recent.length"
            :events="recent"
            :labels="ESTIMATE_EVENT_LABEL"
            dense
            :active="approvalStatus === 'PENDING'"
            pending-label="等待后续审批节点"
          />
          <p v-else class="overview__muted">还没有流转记录。</p>
        </section>
      </aside>
    </div>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="520px" append-to-body>
      <el-form label-position="top" size="small" @submit.prevent>
        <!-- r49：承接厂商由需求带出，只读；没指派就不让提交（后端也会 409 VENDOR_NOT_ASSIGNED） -->
        <el-form-item label="承接厂商">
          <el-input :model-value="vendorText" readonly disabled data-test="estimate-vendor">
            <template #suffix>
              <span v-if="!detail.vendorId" class="t-aux">系统带出，不可改</span>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="评估人天" required>
          <el-input-number
            v-model="formDays"
            :min="0.5"
            :step="0.5"
            :precision="1"
            controls-position="right"
            style="width: 160px"
            aria-required="true"
          />
          <span class="estimate-dialog__suffix">人天，按 0.5 步长</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="formNote"
            type="textarea"
            :rows="3"
            :maxlength="500"
            show-word-limit
            placeholder="补充评估依据（可选）"
          />
        </el-form-item>
      </el-form>
      <p v-if="lastDaysHint" class="estimate-dialog__hint">{{ lastDaysHint }}</p>
      <p v-if="!detail.vendorId" class="estimate-dialog__hint estimate-dialog__hint--warn" data-test="estimate-vendor-hint">
        请先指派厂商：工时评估由承接厂商给出，未指派时不能提交。
      </p>
      <p v-if="formError" class="dialog-error">{{ formError }}</p>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="submitting || !detail.vendorId"
          @click="submitForm"
        >
          {{ ESTIMATE_CTA_LABEL.submit }}
        </el-button>
      </template>
    </el-dialog>

    <!-- r47 跟单明细抽屉：前 5 条之外的完整名单，按页取，不在前端拼全量 -->
    <el-drawer v-model="followDrawer" title="跟单明细" size="420px" append-to-body>
      <div class="follow-drawer">
        <p class="follow-drawer__summary">
          共 {{ drawerTotal }} 人跟单<span v-if="followerOrgs.length"> · {{ followerOrgs.length }} 个组织</span>
        </p>
        <div v-if="followerOrgs.length" class="follow__orgs follow-drawer__orgs">
          <span v-for="org in followerOrgs" :key="`drawer-${org.orgId ?? org.orgName}`" class="follow__org">
            {{ orgLabel(org) }} {{ org.count }}
          </span>
        </div>
        <div v-if="drawerLoading" class="overview__muted">正在加载…</div>
        <template v-else>
          <ul v-if="drawerItems.length" class="follow__items">
            <li v-for="follower in drawerItems" :key="follower.userId" class="follow__item">
              <span class="follow__name">{{ follower.userName || `用户 #${follower.userId}` }}</span>
              <span class="follow__org-name">{{ follower.orgName || '未归属组织' }}</span>
              <span v-if="follower.note" class="follow-drawer__note">{{ follower.note }}</span>
            </li>
          </ul>
          <p v-else class="overview__muted">暂无跟单</p>
        </template>
        <div v-if="drawerTotal > DRAWER_SIZE" class="follow-drawer__pager">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="drawerTotal"
            :page-size="DRAWER_SIZE"
            :current-page="drawerPage"
            @current-change="onDrawerPage"
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.overview__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}
.overview__side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.overview__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
/* ---- 工时评估卡片（r26） ---- */
.estimate-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.estimate-card.is-flash {
  animation: estimate-flash 600ms var(--ease);
}
@keyframes estimate-flash {
  from {
    background: var(--flash-ok);
  }
  to {
    background: var(--bg-card);
  }
}
.estimate-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.estimate-card__title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.estimate-card__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.estimate-card__cta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.estimate-pill {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.estimate-pill--success {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.estimate-pill--warning {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.estimate-pill--danger {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.estimate-pill--info {
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.estimate-pill--muted {
  background: var(--bg-hover);
  color: var(--text-muted-aa);
}
.estimate-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin: 0;
  padding: 8px 12px;
  background: var(--bg-hover);
  border-radius: var(--r-ctl);
}
.estimate-metrics dt {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.estimate-metrics dd {
  margin: 2px 0 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.estimate-metrics dd.num {
  font-size: 18px;
  font-weight: 600;
}
.estimate-card__meta {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.estimate-card__note {
  margin: 0;
  font-size: 12px;
  color: var(--text-body);
  line-height: 1.6;
  white-space: pre-wrap;
}
.estimate-reason {
  margin: 0;
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  font-size: 12px;
  line-height: 1.6;
}
.estimate-reason--danger {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.estimate-reason--warning {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.estimate-gate {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.estimate-gate__lock {
  width: 12px;
  height: 12px;
  flex: 0 0 12px;
  margin-top: 2px;
  color: currentColor;
}
.estimate-gate__text {
  min-width: 0;
}
.estimate-dialog__suffix {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.estimate-dialog__hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.estimate-dialog__hint--warn {
  padding: 6px 8px;
  border: 1px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
.overview__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.overview__card-head .overview__title {
  margin: 0;
}
.overview__link {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.overview__link:hover {
  text-decoration: underline;
}
.overview__link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px 20px;
  margin: 0;
}
.grid div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.grid dt {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.grid dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-title);
}
.num {
  font-variant-numeric: tabular-nums;
}
/**
 * 「实际人天」不是矩阵字段（永远由交付/子需求汇总出来），所以只留一个静态来源说明，
 * 不带锁图标、不依赖子需求条数（r43：本地父需求推断已删）。
 */
.rollup-mark {
  margin-left: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
/* 父需求 / Sonar 两行是 r43 补齐的只读展示（DESIGN §3.2） */
.parent-link {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
  color: var(--color-primary);
  text-decoration: none;
}
.parent-link:hover .parent-title {
  text-decoration: underline;
  text-underline-offset: 2px;
}
.parent-link:focus-visible {
  outline: none;
  border-radius: var(--r-cell);
  box-shadow: var(--focus-ring);
}
.code-mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.parent-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.parent-none {
  font-size: 13px;
  color: var(--text-muted-aa);
}
.overview__tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
}
.overview__tags-label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.overview__desc {
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body);
  white-space: pre-wrap;
}
.overview__approval {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.overview__node {
  font-size: 12px;
  color: var(--text-body);
}
.overview__tasks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.overview__tasks li {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-body);
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-page);
}
.overview__assignee {
  color: var(--text-title);
}
.overview__muted {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
/* r47 跟单卡：白底方角、1px 边框；组织聚合用 20px 浅底 pill */
.follow__count {
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.follow__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.follow__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.5;
}
.follow__note {
  margin-top: 8px;
}
.follow__orgs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.follow__org {
  padding: 0 6px;
  height: 20px;
  line-height: 18px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  font-size: 12px;
  color: var(--text-body);
  white-space: nowrap;
}
.follow__list {
  margin-top: 8px;
}
.follow__items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.follow__item {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: var(--text-body);
}
.follow__name {
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.follow__org-name {
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.follow-drawer__summary {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.follow-drawer__note {
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.follow-drawer__orgs {
  margin: 0 0 10px;
}
.follow-drawer__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
@media (max-width: 1100px) {
  .overview__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (prefers-reduced-motion: reduce) {
  .estimate-card.is-flash {
    animation: none;
  }
}
</style>
