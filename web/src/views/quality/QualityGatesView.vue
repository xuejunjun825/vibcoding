<script setup lang="ts">
/**
 * 质量门总览（DESIGN Q8 / AC-5/AC-6）。
 *
 * 两个视图：门禁总览（按版本一行）与豁免待办（PENDING 豁免）。
 * 门禁结果只展示服务端固化/预评估的快照，页面不做任何规则求值。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { gateApi, waiverApi } from '@/api/quality'
import { listReleases } from '@/api/req'
import { ApiError } from '@/api/client'
import StatusPill from '@/components/StatusPill.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import WaiverBadge from '@/components/quality/WaiverBadge.vue'
import WaiverDrawer from '@/components/quality/WaiverDrawer.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDate, formatDateTime } from '@/utils/format'
import '@/views/deliver/shared.css'
import type { GateRow, ReleaseRow, WaiverRow } from '@/api/types'

interface GateListRow extends GateRow {
  hash?: string
  passedRules?: number
  totalRules?: number
  reportVersionNo?: number
}

interface WaiverListRow extends WaiverRow {
  releaseName?: string
  releaseCode?: string
  applicantName?: string
}

const router = useRouter()

const tab = ref<'gates' | 'waivers'>('gates')

const gates = ref<GateListRow[]>([])
const gatesTotal = ref(0)
const gatePage = ref(1)
const gateSize = ref(20)
const gateStatus = ref('')
const releaseFilter = ref<number | null>(null)
const gatesLoading = ref(false)
const gatesError = ref<{ message: string; code?: string; status?: number } | null>(null)

const waivers = ref<WaiverListRow[]>([])
const waiversTotal = ref(0)
const waiverPage = ref(1)
const waiverSize = ref(20)
const waiversLoading = ref(false)
const waiversError = ref<{ message: string; code?: string; status?: number } | null>(null)
const expandedWaiver = ref<number | null>(null)

const drawerOpen = ref(false)
const activeWaiver = ref<WaiverRow | null>(null)

const releases = ref<ReleaseRow[]>([])
const pendingCount = ref(0)

const gateEmpty = computed(() => !gatesLoading.value && !gates.value.length)
const waiverEmpty = computed(() => !waiversLoading.value && !waivers.value.length)

function messageOf(err: unknown): string {
  if (err instanceof ApiError) return err.message || err.code || '请求失败'
  return err instanceof Error ? err.message : '请求失败'
}

async function loadReleases(): Promise<void> {
  try {
    const data = await listReleases({ page: 1, size: 100 })
    releases.value = data.items ?? []
  } catch {
    releases.value = []
  }
}

async function loadGates(): Promise<void> {
  gatesLoading.value = true
  gatesError.value = null
  try {
    const data = await gateApi.page({
      releaseId: releaseFilter.value ?? undefined,
      status: gateStatus.value || undefined,
    })
    gates.value = (data.items ?? []) as GateListRow[]
    gatesTotal.value = data.total ?? 0
    if (data.items?.length) gatePage.value = 1
  } catch (err) {
    gatesError.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    gatesLoading.value = false
  }
}

async function loadWaivers(): Promise<void> {
  waiversLoading.value = true
  waiversError.value = null
  try {
    const data = await waiverApi.page({ status: 'PENDING', page: waiverPage.value, size: waiverSize.value } as any)
    waivers.value = (data.items ?? []) as WaiverListRow[]
    waiversTotal.value = data.total ?? 0
    pendingCount.value = data.total ?? 0
  } catch (err) {
    waiversError.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    waiversLoading.value = false
  }
}

function switchTab(value: 'gates' | 'waivers'): void {
  tab.value = value
  if (value === 'gates') {
    void loadGates()
  } else {
    waiverPage.value = 1
    void loadWaivers()
  }
}

function ruleSummary(row: GateListRow): string {
  if (row.passedRules != null && row.totalRules != null) return `${row.passedRules}/${row.totalRules} 通过`
  const failed = row.failedRules?.length ?? 0
  if (row.status === 'PASS' || row.status === 'PASS_WITH_WAIVER') return '5/5 通过'
  if (!row.gateId) return '未评估'
  return failed ? `${Math.max(0, 5 - failed)}/5 通过` : '—'
}

function failedCodes(row: GateListRow): string {
  const list = row.failedRules ?? []
  return list.length ? list.join(' · ') : ''
}

function openGate(row: GateListRow): void {
  void router.push({ name: 'quality-gate-detail', params: { releaseId: row.releaseId } })
}

function openApprove(row: WaiverListRow): void {
  activeWaiver.value = row
  drawerOpen.value = true
}

function changeWaiverPage(page: number): void {
  waiverPage.value = page
  void loadWaivers()
}

function onWaiverSubmitted(): void {
  drawerOpen.value = false
  ElMessage.success('豁免审批已提交')
  void loadWaivers()
  void loadGates()
}

onMounted(async () => {
  await Promise.all([loadReleases(), loadGates(), loadWaivers()])
})
</script>

<template>
  <div class="dl-page gate-list">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1>质量门</h1>
        <span class="count-badge">{{ gatesTotal }}</span>
      </div>
      <div class="dl-head__actions">
        <el-button @click="router.push({ name: 'quality-metrics' })">质量指标</el-button>
      </div>
    </header>

    <p class="dl-hint">
      质量门由 5 条规则组成，结果来自服务端快照；豁免按规则申请、审批后生效，<strong>P0/P1 未关闭（QG-04）不可豁免</strong>。
    </p>

    <section class="surface surface--flush">
      <div class="gate-tabs" role="tablist" aria-label="质量门视图">
        <button
          type="button"
          role="tab"
          class="chip-count"
          :class="{ 'is-active': tab === 'gates' }"
          :aria-selected="tab === 'gates'"
          @click="switchTab('gates')"
        >
          门禁总览
        </button>
        <button
          type="button"
          role="tab"
          class="chip-count"
          :class="{ 'is-active': tab === 'waivers' }"
          :aria-selected="tab === 'waivers'"
          @click="switchTab('waivers')"
        >
          豁免待办
          <span v-if="pendingCount" class="count-badge count-badge--danger gate-tabs__badge">{{ pendingCount }}</span>
        </button>
      </div>

      <template v-if="tab === 'gates'">
        <div class="filter-bar">
          <el-select
            v-model="releaseFilter"
            placeholder="全部版本"
            clearable
            filterable
            style="width: 220px"
            @change="loadGates"
          >
            <el-option
              v-for="release in releases"
              :key="release.id"
              :value="release.id"
              :label="`${release.code} · ${release.name}`"
            />
          </el-select>
          <el-select v-model="gateStatus" placeholder="全部结果" clearable style="width: 150px" @change="loadGates">
            <el-option value="PASS" label="通过" />
            <el-option value="PASS_WITH_WAIVER" label="带豁免通过" />
            <el-option value="FAIL" label="未通过" />
            <el-option value="NOT_EVALUATED" label="未评估" />
          </el-select>
          <span class="dl-filters__spacer" />
          <el-button :loading="gatesLoading" @click="loadGates">刷新</el-button>
        </div>

        <ErrorState
          v-if="gatesError"
          :message="gatesError.message"
          :code="gatesError.code"
          :status="gatesError.status"
          @retry="loadGates"
        />
        <EmptyState
          v-else-if="gateEmpty"
          icon="lock"
          title="还没有质量门记录"
          description="在版本详情进入「质量门」完成规则配置后执行预评估；发布单提交时会固化正式快照。"
        />
        <table v-else class="dl-table gate-table">
          <thead>
            <tr>
              <th>版本</th>
              <th>门禁结果</th>
              <th>规则</th>
              <th>报告</th>
              <th>评估时间</th>
              <th>快照</th>
              <th class="is-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in gates" :key="row.releaseId" class="gate-row" @click="openGate(row)">
              <td>
                <span class="gate-release">{{ row.releaseName || `release #${row.releaseId}` }}</span>
                <span v-if="row.activeWaiverCount" class="gate-waiver-count">豁免 {{ row.activeWaiverCount }}</span>
              </td>
              <td><StatusPill kind="gate" :value="row.status || 'NOT_EVALUATED'" size="sm" /></td>
              <td>
                <span class="gate-rules">{{ ruleSummary(row) }}</span>
                <span v-if="failedCodes(row)" class="gate-rules__fail" :title="failedCodes(row)">{{ failedCodes(row) }}</span>
              </td>
              <td>
                <span v-if="row.reportVersionId" class="t-code">报告 v{{ row.reportVersionNo ?? '—' }}</span>
                <span v-else class="t-aux">—</span>
              </td>
              <td>{{ row.evaluatedAt ? formatDateTime(row.evaluatedAt) : '未评估' }}</td>
              <td>
                <DigestChip v-if="row.hash" :hash="row.hash" label="快照" />
                <span v-else class="t-aux">—</span>
              </td>
              <td class="is-actions">
                <span class="row-actions">
                  <el-button link type="primary" @click.stop="openGate(row)">查看</el-button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="gatesTotal > gateSize" class="gate-pager">
          <el-pagination layout="prev, pager, next, total" :total="gatesTotal" :page-size="gateSize" :current-page="gatePage" background />
        </div>
      </template>

      <template v-else>
        <ErrorState
          v-if="waiversError"
          :message="waiversError.message"
          :code="waiversError.code"
          :status="waiversError.status"
          @retry="loadWaivers"
        />
        <EmptyState
          v-else-if="waiverEmpty"
          icon="inbox"
          title="没有待审批的豁免"
          description="规则级豁免由版本负责人在质量门详情申请；审批通过后按有效期自动失效。"
        />
        <table v-else class="dl-table waiver-table">
          <thead>
            <tr>
              <th>规则</th>
              <th>版本</th>
              <th>原因</th>
              <th>影响范围</th>
              <th>到期日</th>
              <th>申请人</th>
              <th>状态</th>
              <th class="is-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in waivers" :key="row.id">
              <tr class="waiver-row" :class="{ 'is-expanded': expandedWaiver === row.id }">
                <td>
                  <span class="t-code">{{ row.ruleCode }}</span>
                  <span class="waiver-rule-name">{{ row.ruleName }}</span>
                </td>
                <td>{{ row.releaseName || `release #${row.releaseId}` }}</td>
                <td>
                  <button type="button" class="waiver-reason" @click="expandedWaiver = expandedWaiver === row.id ? null : row.id">
                    {{ row.reason }}
                  </button>
                </td>
                <td class="waiver-scope">{{ row.impactScope }}</td>
                <td>{{ formatDate(row.expiryAt) }}</td>
                <td>{{ row.applicantName || '—' }}</td>
                <td><WaiverBadge :status="row.status" :expiry-at="row.expiryAt" compact /></td>
                <td class="is-actions">
                  <span class="row-actions">
                    <el-button link type="primary" @click="openApprove(row)">审批</el-button>
                  </span>
                </td>
              </tr>
              <tr v-if="expandedWaiver === row.id" class="waiver-expanded">
                <td colspan="8">
                  <div class="waiver-expanded__body">
                    <p><span class="t-aux">理由：</span>{{ row.reason }}</p>
                    <p><span class="t-aux">影响范围：</span>{{ row.impactScope }}</p>
                    <p class="t-aux">申请于 {{ formatDateTime(row.createdAt) }} · 豁免只覆盖单条规则，不改变其他规则的判定。</p>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div v-if="waiversTotal > waiverSize" class="gate-pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="waiversTotal"
            :page-size="waiverSize"
            :current-page="waiverPage"
            background
            @current-change="changeWaiverPage"
          />
        </div>
      </template>
    </section>

    <WaiverDrawer
      v-model="drawerOpen"
      mode="approve"
      :release-id="activeWaiver?.releaseId ?? 0"
      :release-name="activeWaiver ? releases.find((item) => item.id === activeWaiver?.releaseId)?.name : undefined"
      :waiver="activeWaiver"
      @submitted="onWaiverSubmitted"
    />
  </div>
</template>

<style scoped>
.gate-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 16px 0;
  border-bottom: 1px solid var(--divider);
}
.gate-tabs__badge {
  margin-left: 6px;
}
.gate-table tbody tr {
  cursor: pointer;
}
.gate-table tbody tr:hover,
.waiver-table tbody tr:hover {
  background: var(--bg-hover);
}
.gate-table .is-actions,
.waiver-table .is-actions {
  width: 64px;
  text-align: right;
}
.gate-release {
  font-weight: 500;
  color: var(--text-title);
}
.gate-waiver-count {
  margin-left: 8px;
  font-size: 11px;
  color: var(--warning-ink);
}
.gate-rules {
  font-variant-numeric: tabular-nums;
}
.gate-rules__fail {
  margin-left: 8px;
  font-size: 11px;
  color: var(--danger-ink);
}
.gate-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--divider);
}
.waiver-rule-name {
  margin-left: 6px;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.waiver-reason {
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
  max-width: 300px;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-body);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.waiver-reason:hover {
  color: var(--color-primary);
}
.waiver-scope {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted-aa);
}
.waiver-expanded td {
  background: var(--bg-hover);
}
.waiver-expanded__body {
  padding: 4px 8px 10px;
  font-size: 12px;
  line-height: 1.7;
}
.waiver-expanded__body p {
  margin: 0;
}
</style>
