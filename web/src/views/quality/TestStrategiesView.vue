<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { strategyApi, envApi } from '@/api/quality'
import { listReleases, listRequirements } from '@/api/req'
import { usePermission } from '@/composables/usePermission'
import type {
  EnvRow,
  ReleaseRow,
  RequirementRow,
  StrategyDetail,
  StrategyRiskRow,
  StrategyRoundPlan,
  StrategyRow,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDate } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 测试策略（DESIGN Q2 / AC-9）。
 *
 * 版本语义由服务端守：ACTIVE 版本不可原地修改（409），要改就「保存为新版本」生成 DRAFT；
 * 历史版本一律只读横幅 + 全控件 disabled，页面不做“看起来能改但保存才报错”。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()

const releaseId = computed(() => {
  const raw = route.query.releaseId
  return raw ? Number(raw) : null
})

const releases = ref<ReleaseRow[]>([])
const versions = ref<StrategyRow[]>([])
const detail = ref<StrategyDetail | null>(null)
const requirements = ref<RequirementRow[]>([])
const envOptions = ref<EnvRow[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const conflict = ref<{ code?: string; message: string } | null>(null)

const form = reactive({
  requirementIds: [] as number[],
  note: '',
  risk: [] as StrategyRiskRow[],
  envIds: [] as number[],
  roundPlan: [] as StrategyRoundPlan[],
  resourceNote: '',
})

const activeVersion = computed(() => versions.value.find((item) => item.status === 'ACTIVE') ?? null)
const selected = computed(() => detail.value ?? versions.value[0] ?? null)
const isHistorical = computed(() => {
  const status = String(selected.value?.status ?? '')
  return status === 'RETIRED' || (status === 'ACTIVE' && !!activeVersion.value && selected.value?.id !== activeVersion.value.id)
})
const readOnly = computed(() => isHistorical.value || !can('strategy:manage'))
const needsNewVersion = computed(() => String(selected.value?.status ?? '') === 'ACTIVE')
const editable = computed(() => !readOnly.value)

const versionLabel = (version: StrategyRow): string =>
  `v${version.versionNo}（${version.status === 'ACTIVE' ? '当前' : version.status === 'DRAFT' ? '草稿' : '已退役'}）`

function applyDetail(data: StrategyDetail): void {
  detail.value = data
  form.requirementIds = [...(data.scope?.requirementIds ?? [])]
  form.note = data.scope?.note ?? ''
  form.risk = (data.risk ?? []).map((row) => ({ ...row }))
  form.envIds = [...(data.envIds ?? [])]
  form.roundPlan = (data.roundPlan ?? []).map((row) => ({ ...row }))
  form.resourceNote = data.resourceNote ?? ''
}

function ensureRiskKeys(): void {
  form.risk = form.risk.map((row, index) => ({ ...row, key: row.key || `R${index + 1}` }))
}

function addRisk(): void {
  form.risk.push({ key: `R${form.risk.length + 1}`, level: 'MEDIUM', description: '', mitigation: '' })
}

function removeRisk(index: number): void {
  form.risk.splice(index, 1)
  ensureRiskKeys()
}

function addRound(): void {
  const nextNo = form.roundPlan.length + 1
  form.roundPlan.push({ roundNo: nextNo, roundType: nextNo === 1 ? 'FEATURE' : 'REGRESSION', name: `R${nextNo}` })
}

function removeRound(index: number): void {
  form.roundPlan.splice(index, 1)
}

function payload(): Record<string, unknown> {
  ensureRiskKeys()
  return {
    releaseId: releaseId.value ?? undefined,
    scope: {
      requirementIds: form.requirementIds,
      note: form.note || undefined,
    },
    risk: form.risk.filter((row) => row.description || row.mitigation),
    envIds: form.envIds,
    roundPlan: form.roundPlan,
    resourceNote: form.resourceNote || undefined,
  }
}

async function loadVersions(selectId?: number): Promise<void> {
  if (!releaseId.value) return
  const page = await strategyApi.page({ releaseId: releaseId.value, page: 1, size: 50 })
  versions.value = page.items ?? []
  const target = selectId
    ? versions.value.find((item) => item.id === selectId)
    : versions.value.find((item) => item.status === 'ACTIVE') ?? versions.value[0]
  if (target) {
    applyDetail(await strategyApi.detail(target.id))
  } else {
    detail.value = null
    form.requirementIds = []
    form.risk = []
    form.envIds = []
    form.roundPlan = []
    form.note = ''
    form.resourceNote = ''
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    if (!releases.value.length) {
      releases.value = (await listReleases({ page: 1, size: 100 })).items ?? []
    }
    if (!releaseId.value && releases.value.length) {
      void router.replace({ query: { ...route.query, releaseId: releases.value[0].id } })
      return
    }
    if (releaseId.value) {
      await Promise.all([loadVersions(), loadRequirements(), loadEnvs()])
    }
  } catch (err) {
    error.value = {
      message: (err as Error).message || '测试策略加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

async function loadRequirements(): Promise<void> {
  if (!releaseId.value) return
  try {
    const page = await listRequirements({ releaseId: releaseId.value, page: 1, size: 200 } as never)
    requirements.value = page.items ?? []
  } catch {
    requirements.value = []
  }
}

async function loadEnvs(): Promise<void> {
  try {
    const page = await envApi.page({ page: 1, size: 100 })
    envOptions.value = page.items ?? []
  } catch {
    envOptions.value = []
  }
}

async function save(): Promise<void> {
  if (!form.requirementIds.length) {
    ElMessage.warning('必测范围至少选择 1 条需求')
    return
  }
  saving.value = true
  conflict.value = null
  try {
    if (needsNewVersion.value || !selected.value) {
      try {
        await ElMessageBox.confirm(
          `将生成 v${(versions.value[0]?.versionNo ?? 0) + 1}，当前生效版本保持只读。`,
          '保存为新版本',
          { confirmButtonText: '生成新版本', cancelButtonText: '取消' },
        )
      } catch {
        return
      }
      const created = await strategyApi.create(payload() as never)
      ElMessage.success(`已生成 v${created.versionNo}`)
      await loadVersions(created.id)
    } else {
      const updated = await strategyApi.update(selected.value.id, payload() as never)
      applyDetail(updated)
      ElMessage.success('策略已保存')
      await loadVersions(updated.id)
    }
  } catch (err) {
    const typed = err as { code?: string; message?: string }
    conflict.value = { code: typed.code, message: typed.message || '保存未完成' }
  } finally {
    saving.value = false
  }
}

async function activate(): Promise<void> {
  const target = selected.value
  if (!target) return
  try {
    await ElMessageBox.confirm(`激活 v${target.versionNo} 后，其他版本变为只读。`, '激活策略', {
      confirmButtonText: '激活',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await strategyApi.activate(target.id)
    ElMessage.success('策略已生效')
    await loadVersions(target.id)
  } catch (err) {
    const typed = err as { code?: string; message?: string }
    conflict.value = { code: typed.code, message: typed.message || '激活未完成' }
  }
}

function selectVersion(id: number): void {
  void reloadVersion(id)
}

async function reloadVersion(id: number): Promise<void> {
  loading.value = true
  try {
    applyDetail(await strategyApi.detail(id))
  } catch (err) {
    error.value = { message: (err as Error).message || '策略版本加载失败' }
  } finally {
    loading.value = false
  }
}

watch(releaseId, () => {
  void load()
})

onMounted(load)
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">测试策略</h1>
        <StatusPill v-if="selected" kind="strategy" :value="selected.status" />
      </div>
      <div class="dl-head__actions">
        <el-select
          :model-value="releaseId ?? undefined"
          placeholder="选择版本"
          style="width: 220px"
          @update:model-value="(value: number) => router.replace({ query: { releaseId: String(value) } })"
        >
          <el-option v-for="release in releases" :key="release.id" :label="release.name" :value="release.id" />
        </el-select>
        <el-select
          v-if="versions.length"
          :model-value="selected?.id"
          style="width: 180px"
          @update:model-value="selectVersion"
        >
          <el-option v-for="version in versions" :key="version.id" :label="versionLabel(version)" :value="version.id" />
        </el-select>
        <el-button
          type="primary"
          :disabled="readOnly || saving"
          :loading="saving"
          @click="save"
        >
          {{ needsNewVersion ? '保存为新版本' : '保存' }}
        </el-button>
        <el-button
          v-if="selected && selected.status === 'DRAFT'"
          :disabled="!can('strategy:manage') || saving"
          @click="activate"
        >
          激活此版本
        </el-button>
      </div>
    </header>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <template v-else>
      <div v-if="isHistorical" class="readonly-banner" role="status">
        只读 · v{{ selected?.versionNo }}（{{ formatDate(selected?.updatedAt ?? null) }}）——历史版本不可修改，改动会生成新版本
      </div>
      <div v-else-if="needsNewVersion" class="dl-summary-note">
        当前版本生效中：保存不会原地修改，会生成一个新的草稿版本。
      </div>

      <div v-if="conflict" class="dl-conflict" role="alert">
        <div class="conflict-head">
          <strong>操作未完成</strong>
          <span v-if="conflict.code" class="conflict-code">{{ conflict.code }}</span>
          <span class="conflict-spacer" />
          <button type="button" class="conflict-close" aria-label="关闭提示" @click="conflict = null">×</button>
        </div>
        <p class="conflict-msg">{{ conflict.message }}</p>
      </div>

      <div v-if="!releaseId" class="surface surface--pad">
        <EmptyState title="先选择一个版本" description="测试策略按 Release 版本维护，选择后编辑必测范围与轮次计划" icon="file" />
      </div>

      <div v-else class="strategy-grid" v-loading="loading">
        <!-- 左 2/3：范围 / 风险 / 环境 / 资源 -->
        <section class="surface surface--pad strategy-main">
          <div class="strategy-field">
            <label class="strategy-field__label" for="strategy-scope">
              必测需求范围 <span class="strategy-field__req">必填 · 至少 1 条</span>
            </label>
            <el-select
              id="strategy-scope"
              v-model="form.requirementIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              :disabled="!editable"
              placeholder="从当前版本的需求里选择必测项"
              style="width: 100%"
            >
              <el-option
                v-for="item in requirements"
                :key="item.id"
                :label="`${item.code} ${item.title}`"
                :value="item.id"
              />
            </el-select>
            <p class="strategy-field__hint">
              <template v-if="requirements.length">当前版本共 {{ requirements.length }} 条需求；未选中的不进入准入 QG-01 覆盖范围。</template>
              <template v-else>当前版本还没有需求，先在需求池提报。</template>
            </p>
          </div>

          <div class="strategy-field">
            <div class="strategy-field__row">
              <span class="strategy-field__label">风险清单</span>
              <el-button link type="primary" size="small" :disabled="!editable" @click="addRisk">+ 加一行</el-button>
            </div>
            <p v-if="!form.risk.length" class="strategy-field__hint">还没有风险项。至少记录一条高风险模块，否则策略看起来像“没有风险”。</p>
            <div v-for="(row, index) in form.risk" :key="index" class="risk-row">
              <el-input v-model="row.description" size="small" placeholder="风险描述" :disabled="!editable" />
              <el-select v-model="row.level" size="small" :disabled="!editable" class="risk-row__level">
                <el-option label="高" value="HIGH" />
                <el-option label="中" value="MEDIUM" />
                <el-option label="低" value="LOW" />
              </el-select>
              <el-input v-model="row.mitigation" size="small" placeholder="应对措施" :disabled="!editable" />
              <el-button link type="danger" size="small" :disabled="!editable" @click="removeRisk(index)">删除</el-button>
            </div>
          </div>

          <div class="strategy-field">
            <span class="strategy-field__label">执行环境</span>
            <el-select v-model="form.envIds" multiple :disabled="!editable" placeholder="选择可用的测试环境" style="width: 100%">
              <el-option
                v-for="env in envOptions"
                :key="env.id"
                :label="env.status === 'DISABLED' ? `${env.name}（已停用）` : env.name"
                :value="env.id"
                :disabled="env.status === 'DISABLED'"
              />
            </el-select>
          </div>

          <div class="strategy-field">
            <span class="strategy-field__label">资源说明</span>
            <el-input
              v-model="form.resourceNote"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              :disabled="!editable"
              placeholder="人力、设备、外部依赖等约束"
            />
          </div>

          <div class="strategy-field">
            <span class="strategy-field__label">范围备注</span>
            <el-input v-model="form.note" :disabled="!editable" maxlength="200" placeholder="可选" />
          </div>
        </section>

        <!-- 右 1/3：轮次计划 -->
        <aside class="surface surface--pad strategy-side">
          <header class="strategy-side__head">
            <span>轮次计划</span>
            <el-button link type="primary" size="small" :disabled="!editable" @click="addRound">+ 加轮次</el-button>
          </header>
          <p v-if="!form.roundPlan.length" class="strategy-field__hint">还没有轮次计划；执行时也可临时开轮次。</p>
          <article v-for="(round, index) in form.roundPlan" :key="index" class="round-plan">
            <div class="round-plan__head">
              <span class="round-plan__no">R{{ round.roundNo ?? index + 1 }}</span>
              <el-select v-model="round.roundType" size="small" :disabled="!editable" class="round-plan__type">
                <el-option label="新功能验证" value="FEATURE" />
                <el-option label="回归" value="REGRESSION" />
                <el-option label="冒烟" value="SMOKE" />
              </el-select>
              <span class="dl-filters__spacer" />
              <el-button link type="danger" size="small" :disabled="!editable" @click="removeRound(index)">移除</el-button>
            </div>
            <el-input v-model="round.name" size="small" :disabled="!editable" placeholder="目标（如：主流程回归）" />
            <div class="round-plan__dates">
              <el-input v-model="round.plannedStart" size="small" type="date" :disabled="!editable" />
              <el-input v-model="round.plannedEnd" size="small" type="date" :disabled="!editable" />
            </div>
          </article>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.readonly-banner {
  display: flex;
  align-items: center;
  height: 32px;
  margin-bottom: 12px;
  padding: 0 12px;
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 12px;
}

.dl-summary-note {
  margin-bottom: 12px;
}

.conflict-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conflict-code {
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: rgba(217, 54, 62, 0.08);
  color: var(--danger-ink);
  font-family: var(--font-mono);
  font-size: 11px;
}

.conflict-spacer {
  flex: 1;
}

.conflict-close {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--r-cell);
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 16px;
  cursor: pointer;
}

.conflict-msg {
  margin: 4px 0 0;
  font-size: 12px;
}

.strategy-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
  gap: 12px;
  align-items: start;
}

@media (max-width: 1180px) {
  .strategy-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.strategy-main,
.strategy-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.strategy-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.strategy-field__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.strategy-field__label {
  color: var(--text-title);
  font-size: 13px;
  font-weight: 600;
}

.strategy-field__req {
  margin-left: 6px;
  color: var(--text-muted-aa);
  font-size: 11px;
  font-weight: 400;
}

.strategy-field__hint {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 16px;
}

.risk-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) 90px minmax(0, 1fr) 48px;
  gap: 6px;
  align-items: center;
}

.risk-row__level {
  width: 90px;
}

.strategy-side__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--text-title);
  font-size: 13px;
  font-weight: 600;
}

.round-plan {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-table-head);
}

.round-plan__head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.round-plan__no {
  color: var(--text-title);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.round-plan__type {
  width: 130px;
}

.round-plan__dates {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
</style>
