<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  addMilestone,
  addRequirementsToRelease,
  getRelease,
  listRequirements,
  removeRequirementFromRelease,
  requestReleaseApproval,
} from '@/api/req'
import type { ReleaseDetail, ReleaseRow, RequirementRow } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDate, formatDays } from '@/utils/format'
import { childInfo, messageOf, requirementPath, typeLabel, usePlatformDict } from '@/views/req/platform'

/**
 * P4 版本工作台：左工作项、右统计/里程碑/风险。
 * 锁定（LOCKED）/已发布（PUBLISHED）后需求范围只读，移出与纳入均禁用并给出原因。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const dict = usePlatformDict()

const detail = ref<ReleaseDetail | null>(null)
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const submitting = ref(false)
const releaseId = computed(() => Number(route.params.id))

const release = computed<ReleaseRow | null>(() => detail.value?.release ?? null)
const requirements = computed<RequirementRow[]>(() => {
  const raw = detail.value as (ReleaseDetail & { groups?: Array<{ requirements?: RequirementRow[]; items?: RequirementRow[] }> }) | null
  if (!raw) return []
  if (Array.isArray(raw.requirements) && raw.requirements.length > 0) return raw.requirements
  if (Array.isArray(raw.groups)) {
    return raw.groups.flatMap((group) => group.requirements ?? group.items ?? [])
  }
  return raw.requirements ?? []
})
const milestones = computed<Array<{ id: number; name: string; plannedDate?: string; status?: string }>>(() => {
  const raw = detail.value as (ReleaseDetail & { milestones?: Array<{ id: number; name: string; plannedDate?: string; status?: string }> }) | null
  return raw?.milestones ?? []
})

const scopeLocked = computed(() => release.value != null && ['LOCKED', 'PUBLISHED', 'CANCELLED'].includes(release.value.status))
const lockReason = computed(() => {
  if (!scopeLocked.value) return ''
  return release.value?.status === 'LOCKED' ? '版本已锁定，需求范围不可变更' : '版本已发布或取消，需求范围不可变更'
})
const canPlan = computed(() => can('release:plan'))
const canEditScope = computed(() => canPlan.value && !scopeLocked.value)

const stats = computed(() => {
  const fallback = { total: requirements.value.length, done: 0, estimateDays: 0, actualDays: 0 }
  const source = detail.value?.stats ?? fallback
  return {
    total: source.total ?? fallback.total,
    done: source.done ?? fallback.done,
    estimateDays: source.estimateDays ?? 0,
    actualDays: source.actualDays ?? 0,
  }
})
const donePercent = computed(() => (stats.value.total > 0 ? Math.round((stats.value.done / stats.value.total) * 100) : 0))
const riskItems = computed<RequirementRow[]>(() => detail.value?.riskItems ?? [])

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    dict.ensure()
    detail.value = await getRelease(releaseId.value)
  } catch (err) {
    error.value = messageOf(err)
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(releaseId, load)

const addOpen = ref(false)
const addIds = ref<number[]>([])
const addOptions = ref<RequirementRow[]>([])
const addSearching = ref(false)
const addSaving = ref(false)
const addError = ref('')

const existingIds = computed(() => new Set(requirements.value.map((row) => row.id)))

async function searchCandidates(keyword: string): Promise<void> {
  addSearching.value = true
  try {
    const page = await listRequirements({
      keyword: keyword || undefined,
      releaseId: undefined,
      page: 1,
      size: 20,
      view: 'flat',
    })
    addOptions.value = (page.items ?? []).filter((row) => !existingIds.value.has(row.id))
  } catch (err) {
    addError.value = messageOf(err)
    addOptions.value = []
  } finally {
    addSearching.value = false
  }
}

function openAdd(): void {
  addOpen.value = true
  addIds.value = []
  addOptions.value = []
  addError.value = ''
  void searchCandidates('')
}

async function submitAdd(): Promise<void> {
  if (addIds.value.length === 0 || addSaving.value) return
  addSaving.value = true
  addError.value = ''
  try {
    await addRequirementsToRelease(releaseId.value, addIds.value)
    ElMessage.success(`已纳入 ${addIds.value.length} 条需求`)
    addOpen.value = false
    await load()
  } catch (err) {
    addError.value = messageOf(err)
  } finally {
    addSaving.value = false
  }
}

const removeTarget = ref<RequirementRow | null>(null)
const removing = ref(false)
const removeOpen = ref(false)

function askRemove(row: RequirementRow): void {
  if (!canEditScope.value) return
  removeTarget.value = row
  removeOpen.value = true
}

async function confirmRemove(): Promise<void> {
  const target = removeTarget.value
  if (!target) return
  removing.value = true
  try {
    await removeRequirementFromRelease(releaseId.value, target.id)
    ElMessage.success(`已移出 ${target.code}`)
    removeOpen.value = false
    await load()
  } catch (err) {
    removeOpen.value = false
    actionError.value = messageOf(err)
  } finally {
    removing.value = false
  }
}

const milestoneOpen = ref(false)
const milestoneName = ref('')
const milestoneDate = ref('')
const milestoneSaving = ref(false)
const milestoneError = ref('')

async function submitMilestone(): Promise<void> {
  if (milestoneName.value.trim().length < 2 || !milestoneDate.value) {
    milestoneError.value = '请填写里程碑名称（≥2 字）与计划日期'
    return
  }
  milestoneSaving.value = true
  milestoneError.value = ''
  try {
    await addMilestone(releaseId.value, { name: milestoneName.value.trim(), plannedDate: milestoneDate.value })
    ElMessage.success('里程碑已添加')
    milestoneOpen.value = false
    milestoneName.value = ''
    milestoneDate.value = ''
    await load()
  } catch (err) {
    milestoneError.value = messageOf(err)
  } finally {
    milestoneSaving.value = false
  }
}

const approvalOpen = ref(false)
const approvalSaving = ref(false)

async function submitApproval(): Promise<void> {
  approvalSaving.value = true
  actionError.value = ''
  try {
    const result = await requestReleaseApproval(releaseId.value)
    approvalSaving.value = false
    approvalOpen.value = false
    ElMessage.success(
      result?.instanceId ? `发布审批已发起（实例 #${result.instanceId}）` : '发布审批已发起',
    )
    await load()
  } catch (err) {
    approvalSaving.value = false
    approvalOpen.value = false
    actionError.value = messageOf(err)
  }
}

function openRequirement(row: RequirementRow): void {
  void router.push(requirementPath(row.id))
}

function statusOfMilestone(status?: string): string {
  if (status === 'DONE') return '已完成'
  if (status === 'MISSED') return '已延期'
  return '未到期'
}
</script>

<template>
  <div class="release-detail">
    <ErrorState v-if="error" title="版本加载失败" :message="error" @retry="load" />

    <template v-else>
      <header class="page-head">
        <div class="page-head__left">
          <div class="head-stack">
            <div class="crumbs">
            <button type="button" class="link" @click="router.push({ name: 'releases' })">版本计划</button>
            <span class="crumbs__sep">/</span>
            <span>{{ release ? release.code : '…' }}</span>
          </div>
          <div class="title-row">
            <h1 class="page-head__title">{{ release?.name ?? '版本详情' }}</h1>
            <StatusPill v-if="release" kind="release" :value="release.status" />
            <span v-if="release?.gateMode" class="mode-tag" :class="{ 'is-loose': release.gateMode === 'LOOSE' }">
              {{ release.gateMode === 'LOOSE' ? '宽松门禁' : '严格门禁' }}
            </span>
            <span v-if="scopeLocked" class="lock-tag">🔒 {{ lockReason }}</span>
          </div>
          <p v-if="release" class="meta-line">
            <span>{{ dict.domainName(release.domainId) }}</span>
            <span>{{ formatDate(release.startDate) }} → {{ formatDate(release.endDate) }}</span>
            <span v-if="release.plannedReleaseDate">计划发布 {{ formatDate(release.plannedReleaseDate) }}</span>
          </p>
          </div>
        </div>
        <div class="page-head__actions">
          <el-button
            size="small"
            :disabled="!canEditScope"
            :title="canEditScope ? '' : scopeLocked ? lockReason : '需要权限：release:plan'"
            @click="openAdd"
          >
            纳入需求
          </el-button>
          <el-button
            size="small"
            :disabled="!canPlan"
            :title="canPlan ? '' : '需要权限：release:plan'"
            @click="(milestoneOpen = true), (milestoneError = '')"
          >
            添加里程碑
          </el-button>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            :disabled="!canPlan || release?.status !== 'PLAN'"
            :title="
              release?.status !== 'PLAN'
                ? '仅规划中的版本可发起发布审批'
                : canPlan
                  ? ''
                  : '需要权限：release:plan'
            "
            @click="approvalOpen = true"
          >
            提交发布审批
          </el-button>
        </div>
      </header>

      <div v-if="actionError" class="action-banner" role="alert">
        <span>{{ actionError }}</span>
        <button type="button" class="link" @click="actionError = ''">关闭</button>
      </div>

      <div class="stats">
        <div class="stat">
          <span class="stat__label">需求总数</span>
          <span class="stat__value">{{ stats.total }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">已完成</span>
          <span class="stat__value">{{ stats.done }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">预估人天</span>
          <span class="stat__value">{{ formatDays(stats.estimateDays) }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">实际人天</span>
          <span class="stat__value">{{ formatDays(stats.actualDays) }}</span>
        </div>
        <div class="stat stat--wide">
          <span class="stat__label">完成度 {{ donePercent }}%</span>
          <div class="bar" role="progressbar" :aria-valuenow="donePercent" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${donePercent}%` }" />
          </div>
        </div>
      </div>

      <div class="columns">
        <section class="surface column-main">
          <header class="section-head">
            <h2 class="section-title">工作项</h2>
            <span class="count-badge">{{ requirements.length }}</span>
          </header>

          <div v-if="loading" class="skeleton" style="height: 220px" />
          <EmptyState
            v-else-if="requirements.length === 0"
            title="版本里还没有需求"
            description="把需求纳入版本后，测试门禁与发布准入都会基于这里的范围计算。"
            action-text="纳入需求"
            icon="inbox"
            @action="openAdd"
          />
          <el-table v-else :data="requirements" size="small" row-key="id" @row-click="openRequirement">
            <el-table-column label="编号" width="128">
              <template #default="{ row }">
                <span class="mono code-link">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title }}</template>
            </el-table-column>
            <el-table-column label="类型" width="84">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <StatusPill kind="requirement" :value="row.status" size="sm" />
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="76">
              <template #default="{ row }"><PriorityDot :value="row.priority" /></template>
            </el-table-column>
            <el-table-column label="子需求" width="64">
              <template #default="{ row }">
                <span v-if="childInfo(row).childCount > 0" class="muted">
                  {{ childInfo(row).doneCount }}/{{ childInfo(row).childCount }}
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="预估/实际" width="92">
              <template #default="{ row }">
                <span class="num">{{ row.estimateDays ?? '—' }} / {{ row.actualDays ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64" align="right">
              <template #default="{ row }">
                <el-button
                  class="row-action"
                  link
                  type="danger"
                  size="small"
                  :disabled="!canEditScope"
                  :title="canEditScope ? '移出本版本' : scopeLocked ? lockReason : '需要权限：release:plan'"
                  @click.stop="askRemove(row)"
                >
                  移出
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <aside class="column-side">
          <section class="surface surface--pad side-block">
            <h2 class="section-title">里程碑</h2>
            <ul v-if="milestones.length > 0" class="milestones">
              <li v-for="item in milestones" :key="item.id">
                <span class="dot" :class="{ 'is-done': item.status === 'DONE' }" />
                <div>
                  <p class="milestone__name">{{ item.name }}</p>
                  <p class="milestone__meta">{{ formatDate(item.plannedDate) }} · {{ statusOfMilestone(item.status) }}</p>
                </div>
              </li>
            </ul>
            <p v-else class="muted small">还没有里程碑；把关键节点写进来，团队就知道何时该冻结范围。</p>
          </section>

          <section class="surface surface--pad side-block">
            <h2 class="section-title">风险项 <span class="count-badge count-badge--danger">{{ riskItems.length }}</span></h2>
            <ul v-if="riskItems.length > 0" class="risks">
              <li v-for="item in riskItems" :key="item.id">
                <button type="button" class="link" @click="openRequirement(item)">
                  {{ item.code }} · {{ item.title }}
                </button>
                <StatusPill kind="requirement" :value="item.status" size="sm" />
              </li>
            </ul>
            <p v-else class="muted small">没有识别到风险项。</p>
          </section>
        </aside>
      </div>
    </template>

    <el-dialog v-model="addOpen" title="纳入需求" width="560px" append-to-body>
      <p class="dialog-hint">只列出尚未纳入本版本的需求；已锁定的版本将拒绝变更。</p>
      <el-select
        v-model="addIds"
        multiple
        filterable
        remote
        reserve-keyword
        :remote-method="searchCandidates"
        :loading="addSearching"
        placeholder="输入编号或标题搜索"
        style="width: 100%"
      >
        <el-option
          v-for="row in addOptions"
          :key="row.id"
          :label="`${row.code} · ${row.title}`"
          :value="row.id"
        />
      </el-select>
      <p v-if="addError" class="dialog-error">{{ addError }}</p>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="addSaving" :disabled="addIds.length === 0" @click="submitAdd">
          纳入 {{ addIds.length || '' }} 条
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="milestoneOpen" title="添加里程碑" width="440px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="milestoneName" maxlength="40" placeholder="如：范围冻结 / 提测 / 发布" />
        </el-form-item>
        <el-form-item label="计划日期" required>
          <el-date-picker v-model="milestoneDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
      </el-form>
      <p v-if="milestoneError" class="dialog-error">{{ milestoneError }}</p>
      <template #footer>
        <el-button @click="milestoneOpen = false">取消</el-button>
        <el-button type="primary" :loading="milestoneSaving" @click="submitMilestone">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="approvalOpen" title="提交发布审批" width="440px" append-to-body>
      <p class="dialog-hint">
        提交后将按版本的审批流推进；审批通过前版本仍可调整需求范围，审批通过后自动锁定。
      </p>
      <ul class="dialog-impact">
        <li>审批人：由该业务域的发布审批流决定</li>
        <li>影响范围：本版本 {{ stats.total }} 条需求与后续发布单</li>
        <li>撤回：审批中可由发起人撤回，已通过后不可撤回</li>
      </ul>
      <template #footer>
        <el-button @click="approvalOpen = false">取消</el-button>
        <el-button type="primary" :loading="approvalSaving" @click="submitApproval">提交审批</el-button>
      </template>
    </el-dialog>

    <ConfirmDialog
      v-model="removeOpen"
      title="移出需求"
      :description="removeTarget ? `把 ${removeTarget.code} · ${removeTarget.title} 移出本版本？` : ''"
      :impact="['该需求将回到未排期状态，不再参与本版本的测试门禁与发布准入']"
      :reason-required="false"
      confirm-text="移出"
      tone="danger"
      :loading="removing"
      @confirm="confirmRemove"
      @cancel="removeOpen = false"
    />
  </div>
</template>

<style scoped>
.release-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 头部左侧是“面包屑/标题/元信息”的纵向堆栈；全局 .page-head__left 是横排 */
.head-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
  max-width: 100%;
}
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
  margin-bottom: 4px;
  white-space: nowrap;
}
.crumbs__sep {
  color: var(--text-weak);
}
.link {
  border: none;
  background: none;
  padding: 0;
  font-size: inherit;
  color: var(--color-primary);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.mode-tag {
  font-size: 11px;
  color: var(--info-ink);
  background: var(--color-info-bg);
  border-radius: var(--r-pill);
  padding: 1px 8px;
}
.mode-tag.is-loose {
  color: var(--warning-ink);
  background: var(--color-warning-bg);
}
.lock-tag {
  font-size: 12px;
  color: var(--warning-ink);
  background: var(--color-warning-bg);
  border-radius: var(--r-ctl);
  padding: 2px 8px;
}
.meta-line {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.action-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 13px;
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr)) 1.4fr;
  gap: 8px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.stat__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.stat__value {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.stat--wide {
  justify-content: center;
}
.bar {
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--divider);
  overflow: hidden;
}
.bar span {
  display: block;
  height: 100%;
  background: var(--color-primary);
  transition: width var(--t-expand) var(--ease);
}
.columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}
.column-main {
  min-width: 0;
  overflow: hidden;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--divider);
}
.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.column-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.side-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.milestones,
.risks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.milestones li {
  display: flex;
  gap: 8px;
}
.dot {
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  flex: none;
}
.dot.is-done {
  background: var(--color-primary);
  border-color: var(--color-primary);
}
.milestone__name {
  margin: 0;
  font-size: 13px;
  color: var(--text-body);
}
.milestone__meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.risks li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}
.muted {
  color: var(--text-muted-aa);
}
.small {
  font-size: 12px;
  margin: 0;
}
.mono {
  font-family: var(--font-mono);
}
.num {
  font-variant-numeric: tabular-nums;
}
.code-link {
  color: var(--color-primary);
  white-space: nowrap;
}
.row-action {
  opacity: 0;
  transition: opacity var(--t-color) var(--ease);
}
:deep(.el-table__row:hover) .row-action {
  opacity: 1;
}
.dialog-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.dialog-impact {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-body);
  line-height: 1.9;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
@media (max-width: 1180px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
