<script setup lang="ts">
/**
 * 质量门详情（DESIGN Q9 / AC-5/AC-6/AC-7）。
 *
 * 5 条规则卡 + 快照时间线 + 豁免。预评估只在内存返回（snapshotId=0，不固化）；
 * 正式快照由发布单提交/内部端点固化，这里只能回放（只读）。
 * 页面不计算任何规则结果，规则状态、实际值、阈值全部取自服务端。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { gateApi, waiverApi } from '@/api/quality'
import { listDeployOrders } from '@/api/req'
import { ApiError } from '@/api/client'
import type {
  GateDetail,
  GateReplay,
  GateRule,
  GateRuleConfig,
  GateRuleResult,
  GateSnapshot,
  WaiverRow,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import ErrorState from '@/components/ErrorState.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import QualityGatePanel from '@/components/quality/QualityGatePanel.vue'
import WaiverBadge from '@/components/quality/WaiverBadge.vue'
import WaiverDrawer from '@/components/quality/WaiverDrawer.vue'
import { formatDateTime } from '@/utils/format'
import '@/views/deliver/shared.css'

interface PreviewState {
  rules: GateRuleResult[]
  result: string
  computedAt?: string
}

const route = useRoute()
const router = useRouter()
const releaseId = computed(() => Number(route.params.releaseId))

const detail = ref<GateDetail | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const actionError = ref<{ message: string; code?: string } | null>(null)
const preview = ref<PreviewState | null>(null)
const evaluating = ref(false)

const configOpen = ref(false)
const configRules = ref<GateRuleConfig[]>([])
const savingConfig = ref(false)
const configError = ref<{ message: string; code?: string } | null>(null)

const waiverOpen = ref(false)
const waiverPreset = ref<string | null>(null)
const waiverError = ref<{ message: string; code?: string } | null>(null)

const replayOpen = ref(false)
const replayData = ref<GateReplay | null>(null)
const replayLoading = ref(false)

const revokeOpen = ref(false)
const revokeTarget = ref<WaiverRow | null>(null)
const revokeReason = ref('')
const revoking = ref(false)

const rules = computed<GateRule[]>(() => detail.value?.rules ?? [])
const activeWaivers = computed(() => (detail.value?.waivers ?? []).filter((row) => row.status === 'ACTIVE' || row.status === 'APPROVED'))
const pendingWaivers = computed(() => (detail.value?.waivers ?? []).filter((row) => row.status === 'PENDING'))
const displayRules = computed<GateRuleResult[]>(() => {
  if (preview.value) return preview.value.rules
  return rules.value.map((rule) => ({
    code: rule.code,
    status: rule.status ?? 'SKIPPED',
    required: rule.enabled,
    actual: rule.actual ?? '—',
    threshold: rule.thresholdText ?? (rule.threshold != null ? String(rule.threshold) : '—'),
    fixHint: rule.fixHint ?? '',
    name: rule.name,
  }))
})
const displayResult = computed(() => (preview.value ? preview.value.result : detail.value?.status ?? 'NOT_EVALUATED'))
const displayEvaluatedAt = computed(() =>
  preview.value ? preview.value.computedAt : detail.value?.evaluatedAt,
)
const failedCount = computed(
  () => displayRules.value.filter((rule) => rule.status === 'FAIL').length,
)
const waivedCount = computed(
  () => displayRules.value.filter((rule) => rule.status === 'WAIVED').length,
)
const conclusionTitle = computed(() => {
  if (displayResult.value === 'PASS') return '通过'
  if (displayResult.value === 'PASS_WITH_WAIVER') return '带豁免通过'
  if (displayResult.value === 'FAIL') return `未通过 · ${failedCount.value} 条规则未通过`
  return '未评估'
})
const conclusionTone = computed(() => {
  if (displayResult.value === 'PASS') return 'success'
  if (displayResult.value === 'PASS_WITH_WAIVER' || displayResult.value === 'NOT_EVALUATED') return 'warning'
  if (displayResult.value === 'FAIL') return 'danger'
  return 'neutral'
})
const conclusionHint = computed(() => {
  if (preview.value) return '预评估结果，仅用于判断当前差距，未固化'
  if (!detail.value?.evaluatedAt) return '尚未生成正式快照；提交发布单时会固化'
  return `评估于 ${formatDateTime(detail.value.evaluatedAt)}${waivedCount.value ? ` · ${waivedCount.value} 条规则带豁免` : ''}`
})

function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'RULE_NOT_WAIVABLE':
        return 'QG-04（P0/P1 未关闭）是红线规则，不可豁免，也不能被禁用。'
      case 'WAIVER_EXPIRY_INVALID':
        return '豁免有效期必须在 1–30 天之间。'
      case 'WAIVER_RULE_SINGLE_ONLY':
        return '一条豁免只能覆盖一条规则。'
      case 'SCOPE_DENIED':
        return '你没有权限审批自己申请的豁免。'
      default:
        return err.message || err.code || '请求失败'
    }
  }
  return err instanceof Error ? err.message : '请求失败'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  preview.value = null
  try {
    detail.value = await gateApi.detail(releaseId.value)
  } catch (err) {
    error.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    loading.value = false
  }
}

async function evaluate(): Promise<void> {
  if (evaluating.value) return
  evaluating.value = true
  actionError.value = null
  try {
    let deployOrderId = 0
    try {
      const orders = await listDeployOrders({ releaseId: releaseId.value, page: 1, size: 20 })
      deployOrderId = orders.items?.[0]?.id ?? 0
    } catch {
      deployOrderId = 0
    }
    const snapshot = await gateApi.evaluate(releaseId.value, { deployOrderId, requirementIds: [] })
    preview.value = {
      rules: snapshot.rules,
      result: snapshot.result,
      computedAt: snapshot.computedAt ?? undefined,
    }
    ElMessage.success('已完成预评估（未固化）')
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? (err.code ?? undefined) : undefined }
  } finally {
    evaluating.value = false
  }
}

function openConfig(): void {
  configRules.value = rules.value.map((rule) => ({
    code: rule.code,
    enabled: rule.status !== 'SKIPPED',
    threshold: rule.thresholdText ? Number(rule.thresholdText) : undefined,
  }))
  configError.value = null
  configOpen.value = true
}

function ruleName(code: string): string {
  return rules.value.find((rule) => rule.code === code)?.name || code
}

async function saveConfig(): Promise<void> {
  if (savingConfig.value) return
  savingConfig.value = true
  configError.value = null
  try {
    detail.value = await gateApi.configure(
      releaseId.value,
      configRules.value.map((rule) => ({
        code: rule.code,
        enabled: rule.enabled,
        threshold: rule.threshold != null && !Number.isNaN(rule.threshold) ? rule.threshold : undefined,
      })),
    )
    ElMessage.success('规则配置已保存；下次评估生效')
    configOpen.value = false
    preview.value = null
  } catch (err) {
    configError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    savingConfig.value = false
  }
}

function openWaiver(ruleCode?: string): void {
  waiverPreset.value = ruleCode ?? null
  waiverError.value = null
  waiverOpen.value = true
}

function onWaiverSubmitted(): void {
  waiverOpen.value = false
  ElMessage.success('豁免申请已提交，待审批')
  void load()
}

async function openReplay(snapshot: GateSnapshot): Promise<void> {
  replayOpen.value = true
  replayLoading.value = true
  replayData.value = null
  actionError.value = null
  try {
    replayData.value = await gateApi.replay(snapshot.snapshotId)
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? (err.code ?? undefined) : undefined }
    replayOpen.value = false
  } finally {
    replayLoading.value = false
  }
}

function ruleSummary(snapshot: GateSnapshot): string {
  const failed = snapshot.rules.filter((rule) => rule.status === 'FAIL').length
  const waived = snapshot.rules.filter((rule) => rule.status === 'WAIVED').length
  if (!failed && !waived) return '5/5 通过'
  const parts: string[] = []
  if (failed) parts.push(`${failed} 未通过`)
  if (waived) parts.push(`${waived} 豁免`)
  return parts.join(' · ')
}

function openRevoke(row: WaiverRow): void {
  revokeTarget.value = row
  revokeReason.value = ''
  revokeOpen.value = true
}

async function submitRevoke(): Promise<void> {
  if (!revokeTarget.value || !revokeReason.value.trim() || revoking.value) return
  revoking.value = true
  actionError.value = null
  try {
    await waiverApi.revoke(revokeTarget.value.id, revokeReason.value.trim())
    ElMessage.success('豁免已撤销；已生成的历史快照保持不变')
    revokeOpen.value = false
    await load()
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? (err.code ?? undefined) : undefined }
  } finally {
    revoking.value = false
  }
}

watch(releaseId, () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="dl-page gate-detail">
    <ErrorState
      v-if="error && !detail"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />

    <template v-else>
      <header class="dl-head">
        <div class="dl-head__title gate-title">
          <button type="button" class="dl-head__back" aria-label="返回质量门总览" @click="router.push({ name: 'quality-gates' })">←</button>
          <div>
            <h1>{{ detail?.releaseName || `release #${releaseId}` }}</h1>
            <p class="gate-title__sub">
              质量门 · 规则集 v{{ detail?.rulesetVersion ?? '—' }}
              <span v-if="detail?.evaluatedAt" class="gate-title__dot">·</span>
              <span v-if="detail?.evaluatedAt">最近评估 {{ formatDateTime(detail.evaluatedAt) }}</span>
            </p>
          </div>
        </div>
        <div class="dl-head__actions">
          <el-button :disabled="evaluating" @click="evaluate">预评估</el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button @click="openConfig">规则配置</el-button>
          <el-button type="primary" @click="openWaiver()">申请豁免</el-button>
        </div>
      </header>

      <ErrorState
        v-if="actionError"
        :message="actionError.message"
        :code="actionError.code"
        compact
        class="gate-action-error"
      />

      <ConclusionBar :title="conclusionTitle" :tone="conclusionTone" :hint="conclusionHint" kicker="门禁结论">
        <div class="gate-conclusion__chips">
          <span v-if="preview" class="gate-preview-chip">预览（未固化）</span>
          <DigestChip
            v-else-if="detail?.snapshots?.length"
            :hash="detail.snapshots[detail.snapshots.length - 1]?.hash"
            label="最近快照"
          />
          <span v-if="pendingWaivers.length" class="gate-pending-chip">
            待审批豁免 {{ pendingWaivers.length }}
          </span>
        </div>
      </ConclusionBar>

      <div class="gate-layout">
        <div class="gate-main">
          <QualityGatePanel
            mode="gate"
            title="5 条门禁规则"
            :rules="displayRules"
            :result="displayResult"
            :evaluated-at="displayEvaluatedAt"
            :snapshot-id="preview ? 0 : detail?.snapshots?.[detail.snapshots.length - 1]?.snapshotId"
            :hash="preview ? undefined : detail?.snapshots?.[detail.snapshots.length - 1]?.hash"
            :preview="!!preview"
            :waivers="activeWaivers"
            @waiver="openWaiver"
          />
        </div>

        <aside class="gate-side">
          <section class="surface surface--pad gate-side__block">
            <h2 class="t-block-title">快照时间线</h2>
            <p class="t-aux gate-side__hint">快照不可编辑、不可删除；回放只读，不改变任何数据。</p>
            <ul v-if="detail?.snapshots?.length" class="gate-timeline">
              <li v-for="snapshot in [...(detail?.snapshots ?? [])].reverse()" :key="snapshot.snapshotId">
                <div class="gate-timeline__head">
                  <StatusPill kind="gate" :value="snapshot.result" size="sm" />
                  <span class="t-aux">{{ formatDateTime(snapshot.computedAt) }}</span>
                </div>
                <div class="gate-timeline__meta">
                  <span class="gate-timeline__id">#{{ snapshot.snapshotId }}</span>
                  <span class="gate-timeline__rules">{{ ruleSummary(snapshot) }}</span>
                </div>
                <div class="gate-timeline__foot">
                  <DigestChip :hash="snapshot.hash" label="快照" />
                  <el-button link type="primary" size="small" @click="openReplay(snapshot)">回放</el-button>
                </div>
              </li>
            </ul>
            <p v-else class="gate-side__empty">还没有正式快照。发布单提交或内部端点求值时会固化。</p>
          </section>

          <section class="surface surface--pad gate-side__block">
            <div class="gate-side__head">
              <h2 class="t-block-title">豁免记录</h2>
              <el-button link type="primary" size="small" @click="openWaiver()">申请</el-button>
            </div>
            <ul v-if="detail?.waivers?.length" class="gate-waivers">
              <li v-for="row in detail.waivers" :key="row.id">
                <div class="gate-waivers__head">
                  <span class="t-code">{{ row.ruleCode }}</span>
                  <WaiverBadge :status="row.status" :expiry-at="row.expiryAt" compact />
                </div>
                <p class="gate-waivers__reason">{{ row.reason }}</p>
                <div class="gate-waivers__meta">
                  <span>{{ row.expiryAt ? `到期 ${formatDateTime(row.expiryAt)}` : '长期' }}</span>
                  <el-button
                    v-if="row.status === 'ACTIVE' || row.status === 'APPROVED'"
                    link
                    type="danger"
                    size="small"
                    @click="openRevoke(row)"
                  >
                    撤销
                  </el-button>
                </div>
              </li>
            </ul>
            <p v-else class="gate-side__empty">没有豁免记录。QG-04 永远不可豁免。</p>
          </section>
        </aside>
      </div>
    </template>

    <el-dialog v-model="configOpen" title="质量门规则配置" width="600px" :close-on-click-modal="false">
      <div v-if="configError" class="gate-dialog-error" role="alert">
        <span v-if="configError.code" class="t-code">{{ configError.code }}</span>
        <span>{{ configError.message }}</span>
      </div>
      <p class="t-aux gate-config-hint">
        QG-04（P0/P1 未关闭）是红线规则，不可关闭、不可豁免；其它规则可启用/停用，QG-03 可调整通过率阈值。
      </p>
      <ul class="gate-config">
        <li v-for="rule in configRules" :key="rule.code" class="gate-config__row">
          <div class="gate-config__label">
            <span class="t-code">{{ rule.code }}</span>
            <span>{{ ruleName(rule.code) }}</span>
            <span v-if="rule.code === 'QG-04'" class="gate-config__locked">不可关闭</span>
          </div>
          <div class="gate-config__controls">
            <el-input-number
              v-if="rule.code === 'QG-03'"
              v-model="rule.threshold"
              :min="0"
              :max="100"
              :step="1"
              controls-position="right"
              size="small"
              style="width: 120px"
            />
            <span v-if="rule.code === 'QG-03'" class="t-aux">%</span>
            <el-switch v-model="rule.enabled" :disabled="rule.code === 'QG-04'" />
          </div>
        </li>
      </ul>
      <template #footer>
        <el-button :disabled="savingConfig" @click="configOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存配置</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="replayOpen" title="快照回放" size="560px" :with-header="true">
      <div v-if="replayLoading" class="gate-replay__loading">正在回放快照…</div>
      <template v-else-if="replayData">
        <div class="gate-replay__head">
          <StatusPill kind="gate" :value="replayData.result" />
          <span class="t-aux">快照 #{{ replayData.snapshotId }} · {{ formatDateTime(replayData.computedAt) }}</span>
        </div>
        <div class="gate-replay__digest">
          <DigestChip :hash="replayData.hash ?? undefined" label="快照 hash" />
        </div>
        <div v-if="replayData.diff?.changedRules?.length" class="gate-replay__diff">
          <span class="t-aux">与当前数据相比，以下规则结果已变化：</span>
          <span v-for="rule in replayData.diff.changedRules" :key="rule.code" class="gate-replay__diff-chip">{{ rule.code }}</span>
        </div>
        <ul class="gate-replay__rules">
          <li v-for="rule in replayData.rules" :key="rule.code">
            <div class="gate-replay__rule-head">
              <span class="t-code">{{ rule.code }}</span>
              <StatusPill kind="rule" :value="rule.status" size="sm" />
            </div>
            <p class="gate-replay__rule-line">
              <span class="t-aux">实测</span>{{ rule.actual }}
              <span class="t-aux gate-replay__sep">阈值</span>{{ rule.threshold }}
            </p>
          </li>
        </ul>
        <p class="t-aux gate-replay__note">回放只读取快照 JSON，不重新求值，也不会写库。</p>
      </template>
    </el-drawer>

    <WaiverDrawer
      v-model="waiverOpen"
      mode="apply"
      :release-id="releaseId"
      :release-name="detail?.releaseName ?? undefined"
      :rules="displayRules"
      :preset-rule="waiverPreset ?? undefined"
      @submitted="onWaiverSubmitted"
    />

    <el-dialog v-model="revokeOpen" title="撤销豁免" width="460px" :close-on-click-modal="false">
      <p class="t-aux gate-config-hint">
        撤销后该豁免立即失效；已固化的历史快照保持不变，后续评估将按无豁免计算。
      </p>
      <el-input v-model="revokeReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="撤销原因（必填）" />
      <template #footer>
        <el-button :disabled="revoking" @click="revokeOpen = false">取消</el-button>
        <el-button type="danger" :loading="revoking" :disabled="!revokeReason.trim()" @click="submitRevoke">确认撤销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.gate-title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.gate-title h1 {
  margin: 0;
}
.gate-title__sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-title__dot {
  margin: 0 4px;
  color: var(--text-weak);
}
.gate-action-error {
  margin: 12px 0;
}
.gate-conclusion__chips {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gate-preview-chip {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
}
.gate-pending-chip {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
  font-size: 11px;
}
.gate-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  margin-top: 12px;
  align-items: start;
}
.gate-side {
  display: grid;
  gap: 12px;
}
.gate-side__block {
  min-width: 0;
}
.gate-side__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.gate-side__hint {
  margin: 2px 0 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate-side__empty {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-weak);
  line-height: 1.6;
}
.gate-timeline {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
  position: relative;
}
.gate-timeline li {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  transition: border-color var(--t-color) var(--ease);
}
.gate-timeline li:hover {
  border-color: var(--border-strong);
}
.gate-timeline__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.gate-timeline__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
}
.gate-timeline__id {
  font-variant-numeric: tabular-nums;
  color: var(--text-muted-aa);
}
.gate-timeline__rules {
  color: var(--text-body);
}
.gate-timeline__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}
.gate-waivers {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
}
.gate-waivers li {
  padding-bottom: 8px;
  border-bottom: 1px solid var(--divider);
}
.gate-waivers li:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.gate-waivers__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.gate-waivers__reason {
  margin: 4px 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-body);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gate-waivers__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-weak);
}
.gate-dialog-error {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 10px;
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  font-size: 12px;
  color: var(--text-body);
}
.gate-config-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted-aa);
}
.gate-config {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 2px;
}
.gate-config__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--divider);
}
.gate-config__row:last-child {
  border-bottom: none;
}
.gate-config__label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
}
.gate-config__locked {
  font-size: 11px;
  color: var(--danger-ink);
}
.gate-config__controls {
  display: flex;
  align-items: center;
  gap: 6px;
}
.gate-replay__loading {
  padding: 32px 0;
  text-align: center;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.gate-replay__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.gate-replay__digest {
  margin: 10px 0;
}
.gate-replay__diff {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  padding: 8px 10px;
  background: var(--color-warning-bg);
  border-radius: var(--r-ctl);
  font-size: 12px;
}
.gate-replay__diff-chip {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--bg-card);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--warning-ink);
}
.gate-replay__rules {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}
.gate-replay__rules li {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}
.gate-replay__rule-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.gate-replay__rule-line {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-body);
}
.gate-replay__sep {
  margin-left: 16px;
}
.gate-replay__note {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--text-weak);
}
@media (max-width: 1080px) {
  .gate-layout {
    grid-template-columns: 1fr;
  }
}
</style>
