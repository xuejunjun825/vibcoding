<script setup lang="ts">
/**
 * CCode 分支策略（DESIGN C1/C2 · AC-09/AC-10）。
 *
 * 平台只登记期望；实际配置由 GitLab 执行。核验状态完全由服务端派生：
 * 没有核验记录 / 不可达 = UNVERIFIED（黄），永不为绿；集成未启用时点[核验]不发起请求。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import { listDomains } from '@/api/admin'
import type { DomainRow } from '@/api/admin'
import { listConfigs } from '@/api/integration'
import type { IntegrationConfigView } from '@/api/integration'
import {
  POLICY_REASON_LABEL,
  checkPolicy,
  deletePolicy,
  fetchChecks,
  fetchPolicies,
  latestCheckOf,
  savePolicy,
} from '@/api/code'
import type { PolicyBody, PolicyCheckView, PolicyRow } from '@/api/code'
import PolicyCheckPill from '@/components/code/PolicyCheckPill.vue'
import PolicyDiffTable from '@/components/code/PolicyDiffTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime } from '@/utils/format'
import '@/views/deliver/shared.css'

type StatusFilter = '' | 'MATCH' | 'DRIFT' | 'UNVERIFIED' | 'DISABLED'

const MERGE_LABEL: Record<string, string> = {
  MERGE: '合并提交',
  SQUASH: '压缩合并',
  FF_ONLY: '仅快进',
}

const ROLE_OPTIONS = ['Maintainer', 'Developer', 'Reporter', 'Owner']
const MERGE_OPTIONS = ['MERGE', 'SQUASH', 'FF_ONLY'] as const
const PAGE_SIZE = 20

const router = useRouter()
const { can } = usePermission()
const canEdit = computed(() => can('CCODE_POLICY_EDIT'))

const rows = ref<PolicyRow[]>([])
const domains = ref<DomainRow[]>([])
const gitlabConfig = ref<IntegrationConfigView | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const filters = reactive({ domainId: null as number | null, project: '', status: '' as StatusFilter })
const page = ref(1)
const checkingId = ref<number | null>(null)

/* ---------------- 读取 ---------------- */

async function loadGitlabConfig(): Promise<void> {
  try {
    const configs = await listConfigs('GITLAB')
    gitlabConfig.value = configs.find((item) => item.enabled) ?? configs[0] ?? null
  } catch {
    // 读不到配置不阻断列表；点核验时再按 409 原因给出说明
    gitlabConfig.value = null
  }
}

async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true
  error.value = null
  try {
    rows.value = await fetchPolicies({
      domainId: filters.domainId,
      project: filters.project.trim() || undefined,
    })
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message || '分支策略加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadDomains(): Promise<void> {
  try {
    domains.value = await listDomains()
  } catch {
    domains.value = []
  }
}

onMounted(() => {
  void loadDomains()
  void loadGitlabConfig()
  void load()
})

/* ---------------- 统计与筛选（全部在前端，口径来自服务端派生结果） ---------------- */

const counts = computed(() => {
  let match = 0
  let drift = 0
  for (const row of rows.value) {
    const result = latestCheckOf(row).result
    if (result === 'MATCH') match += 1
    else if (result === 'DRIFT') drift += 1
  }
  return {
    total: rows.value.length,
    match,
    drift,
    verified: match + drift,
    unverified: rows.value.length - match - drift,
  }
})

const filtered = computed(() => {
  return rows.value.filter((row) => {
    if (filters.status === 'DISABLED') return !row.enabled
    if (!filters.status) return true
    return latestCheckOf(row).result === filters.status
  })
})

const paged = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

/** 停用规则整行降一档灰度，但仍展示历史核验态 */
function rowClass({ row }: { row: PolicyRow }): string {
  return row.enabled ? '' : 'is-disabled-row'
}

function resetPage(): void {
  page.value = 1
}

function domainName(id: number): string {
  return domains.value.find((item) => item.id === id)?.name ?? `域 #${id}`
}

/* ---------------- 错误处理（409 不弹 toast，全部内联/对话框） ---------------- */

function applyFailedCheck(row: PolicyRow, err: unknown): void {
  const apiError = err instanceof ApiError ? err : null
  const code = apiError?.code ?? ''
  if (code === 'INTEGRATION_DISABLED') {
    row.latestCheck = { result: 'UNVERIFIED', reason: 'INTEGRATION_DISABLED' }
    integrationDialog.visible = true
    integrationDialog.reason = 'INTEGRATION_DISABLED'
    return
  }
  if (code === 'INTEGRATION_UNREACHABLE') {
    row.latestCheck = { result: 'UNVERIFIED', reason: 'INTEGRATION_UNREACHABLE' }
    rowNotice.value = { kind: 'warning', text: 'GitLab 不可达：本次核验未完成，状态保持「未核验」。' }
    return
  }
  if (apiError?.status === 403) {
    row.latestCheck = { result: 'UNVERIFIED', reason: 'PERMISSION_INSUFFICIENT' }
    rowNotice.value = { kind: 'warning', text: '当前账号没有该规则的核验权限（需 CCODE_POLICY_EDIT）。' }
    return
  }
  rowNotice.value = { kind: 'danger', text: apiError?.message || '核验未完成，请稍后重试。' }
}

const rowNotice = ref<{ kind: 'warning' | 'danger'; text: string } | null>(null)

/* ---------------- 核验（集成未启用时不发请求） ---------------- */

const integrationDialog = reactive({ visible: false, reason: '' as string })

async function onCheck(row: PolicyRow): Promise<void> {
  if (!canEdit.value || checkingId.value !== null) return
  rowNotice.value = null
  if (!gitlabConfig.value) await loadGitlabConfig()
  if (!gitlabConfig.value?.enabled) {
    integrationDialog.reason = 'INTEGRATION_DISABLED'
    integrationDialog.visible = true
    return
  }
  checkingId.value = row.id
  try {
    const check = await checkPolicy(row.id)
    row.latestCheck = check
    if (check.result === 'MATCH') ElMessage.success('核验完成：实际配置与期望一致')
    else if (check.result === 'DRIFT') ElMessage.warning('核验完成：存在差异，展开行查看逐项结果')
    else ElMessage.warning('核验完成，但状态仍是未核验')
    await load(true)
  } catch (err) {
    applyFailedCheck(row, err)
  } finally {
    checkingId.value = null
  }
}

/* ---------------- 展开行 / 核验历史 ---------------- */

const history = reactive({
  visible: false,
  loading: false,
  row: null as PolicyRow | null,
  items: [] as PolicyCheckView[],
  error: '',
})

async function openHistory(row: PolicyRow): Promise<void> {
  history.visible = true
  history.row = row
  history.items = []
  history.error = ''
  history.loading = true
  try {
    history.items = await fetchChecks(row.id, 5)
  } catch (err) {
    history.error = err instanceof ApiError ? err.message : '核验历史加载失败'
  } finally {
    history.loading = false
  }
}

/* ---------------- 编辑抽屉 ---------------- */

function emptyForm(): PolicyBody {
  return {
    domainId: domains.value[0]?.id ?? 0,
    gitlabProject: '',
    branchPattern: '',
    patternType: 'GLOB',
    protected: true,
    mergeMethod: 'MERGE',
    approvalsRequired: 1,
    pushRoles: ['Maintainer'],
    mergeRoles: ['Maintainer'],
    enabled: true,
  }
}

const drawer = reactive({
  visible: false,
  saving: false,
  editingId: null as number | null,
  version: 0,
  form: emptyForm(),
  testBranch: '',
  errors: { gitlabProject: '', branchPattern: '', approvals: '', form: '' },
})

function openCreate(): void {
  if (!canEdit.value) return
  drawer.editingId = null
  drawer.version = 0
  drawer.form = emptyForm()
  drawer.testBranch = ''
  clearErrors()
  drawer.visible = true
}

function openEdit(row: PolicyRow): void {
  if (!canEdit.value) return
  drawer.editingId = row.id
  drawer.version = row.version
  drawer.form = {
    domainId: row.domainId,
    gitlabProject: row.gitlabProject,
    branchPattern: row.branchPattern,
    patternType: row.patternType ?? 'GLOB',
    protected: Boolean(row.protected),
    mergeMethod: row.mergeMethod ?? 'MERGE',
    approvalsRequired: row.approvalsRequired ?? 0,
    pushRoles: [...(row.pushRoles ?? [])],
    mergeRoles: [...(row.mergeRoles ?? [])],
    enabled: Boolean(row.enabled),
  }
  drawer.testBranch = ''
  clearErrors()
  drawer.visible = true
}

function clearErrors(): void {
  drawer.errors.gitlabProject = ''
  drawer.errors.branchPattern = ''
  drawer.errors.approvals = ''
  drawer.errors.form = ''
}

function validate(): boolean {
  clearErrors()
  if (!drawer.form.domainId) drawer.errors.gitlabProject = '请选择业务域'
  if (!drawer.form.gitlabProject.trim()) drawer.errors.gitlabProject = '填写 GitLab 项目，例如 group/repo'
  if (!drawer.form.branchPattern.trim()) drawer.errors.branchPattern = '填写分支模式'
  else if (drawer.form.patternType === 'REGEX' && !regexOf(drawer.form.branchPattern)) {
    drawer.errors.branchPattern = '正则不合法：请检查括号与转义'
  }
  if (drawer.form.approvalsRequired > 0 && !drawer.form.protected) {
    drawer.errors.approvals = '审批人数 > 0 时必须开启保护分支'
    drawer.errors.form = '审批人数 > 0 时必须开启保护分支（保护分支是审批生效的前提）'
  }
  return !drawer.errors.gitlabProject && !drawer.errors.branchPattern && !drawer.errors.approvals
}

async function submit(): Promise<void> {
  if (!validate()) return
  drawer.saving = true
  try {
    const payload: PolicyBody = {
      ...drawer.form,
      gitlabProject: drawer.form.gitlabProject.trim(),
      branchPattern: drawer.form.branchPattern.trim(),
      version: drawer.editingId ? drawer.version : undefined,
      id: drawer.editingId ?? undefined,
    }
    const saved = await savePolicy(payload)
    drawer.visible = false
    ElMessage.success(drawer.editingId ? '规则已更新' : '规则已创建')
    const index = rows.value.findIndex((item) => item.id === saved?.id)
    if (index >= 0 && saved) rows.value[index] = { ...rows.value[index], ...saved }
    await load(true)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    const code = apiError?.code ?? ''
    if (code === 'POLICY_DUPLICATE') {
      drawer.errors.branchPattern = '该项目下已有相同分支模式'
      drawer.errors.form = '该项目下已存在同一条规则（项目 + 模式唯一）。'
    } else if (code === 'POLICY_CONTRADICTION') {
      drawer.errors.form = apiError?.message || '规则与平台既有策略矛盾，请调整后重试。'
    } else if (code === 'POLICY_PATTERN_INVALID') {
      drawer.errors.branchPattern = apiError?.message || '分支模式不合法'
      drawer.errors.form = apiError?.message || '分支模式不合法'
    } else if (code === 'POLICY_VERSION_CONFLICT' || apiError?.status === 409) {
      drawer.errors.form = '规则已被他人修改，请关闭抽屉重新打开后再保存。'
    } else {
      drawer.errors.form = apiError?.message || '保存失败，请稍后重试（输入已保留）'
    }
  } finally {
    drawer.saving = false
  }
}

/* ---------------- 模式测试（纯前端，不请求 GitLab） ---------------- */

function regexOf(pattern: string): RegExp | null {
  try {
    return new RegExp(`^(?:${pattern})$`)
  } catch {
    return null
  }
}

function globRegexOf(glob: string): RegExp {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
  const body = escaped.replace(/\*/g, '.*').replace(/\?/g, '.')
  return new RegExp(`^(?:${body})$`)
}

const testResult = computed<{ ok: boolean; text: string } | null>(() => {
  const branch = drawer.testBranch.trim()
  if (!branch || !drawer.form.branchPattern.trim()) return null
  const pattern = drawer.form.branchPattern.trim()
  if (drawer.form.patternType === 'REGEX') {
    const regex = regexOf(pattern)
    if (!regex) return { ok: false, text: '正则不合法，无法测试' }
    return regex.test(branch)
      ? { ok: true, text: `「${branch}」匹配该模式` }
      : { ok: false, text: `「${branch}」不匹配该模式` }
  }
  return globRegexOf(pattern).test(branch)
    ? { ok: true, text: `「${branch}」匹配该模式` }
    : { ok: false, text: `「${branch}」不匹配该模式` }
})

/* ---------------- 启用开关 / 删除 ---------------- */

async function toggleEnabled(row: PolicyRow, raw: string | number | boolean): Promise<void> {
  const value = Boolean(raw)
  const previous = row.enabled
  row.enabled = value
  try {
    await savePolicy({
      id: row.id,
      domainId: row.domainId,
      gitlabProject: row.gitlabProject,
      branchPattern: row.branchPattern,
      patternType: row.patternType,
      protected: row.protected,
      mergeMethod: row.mergeMethod,
      approvalsRequired: row.approvalsRequired,
      pushRoles: row.pushRoles ?? [],
      mergeRoles: row.mergeRoles ?? [],
      enabled: value,
      version: row.version,
    })
    ElMessage.success(value ? '规则已启用' : '规则已停用')
  } catch (err) {
    row.enabled = previous
    const apiError = err instanceof ApiError ? err : null
    ElMessage.warning(apiError?.message || '启用状态未保存')
  }
}

async function remove(row: PolicyRow): Promise<void> {
  if (!canEdit.value) return
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.gitlabProject} · ${row.branchPattern}」后不再参与核验；历史核验记录保留。`,
      '删除分支策略',
      { confirmButtonText: '删除规则', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deletePolicy(row.id)
    ElMessage.success('规则已删除')
    await load(true)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    rowNotice.value = { kind: 'danger', text: apiError?.message || '删除失败' }
  }
}

function goIntegration(): void {
  integrationDialog.visible = false
  void router.push({ name: 'admin-integrations' })
}
</script>

<template>
  <div class="dl-page policy-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">分支策略</h1>
        <span class="policy-count">
          策略 {{ counts.total }} · 已核验 {{ counts.verified }}（一致 {{ counts.match }} / 差异 {{ counts.drift }}）·
          未核验 {{ counts.unverified }} 不计入
        </span>
      </div>
      <div class="dl-head__actions">
        <el-button size="small" :disabled="loading" @click="load()">刷新</el-button>
        <el-button v-if="canEdit" size="small" type="primary" @click="openCreate">新增规则</el-button>
      </div>
    </header>

    <p class="dl-hint policy-hint">
      平台只登记规则；push / merge / 建分支的拦截由 GitLab 执行。
      <button type="button" class="policy-link" @click="router.push({ name: 'admin-integrations' })">集成配置</button>
    </p>

    <div class="dl-filters">
      <el-select v-model="filters.domainId" placeholder="全部业务域" clearable size="small" @change="resetPage">
        <el-option v-for="item in domains" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input
        v-model="filters.project"
        placeholder="项目 group/repo"
        clearable
        size="small"
        @keyup.enter="resetPage(); load()"
        @clear="resetPage(); load()"
      />
      <el-select v-model="filters.status" placeholder="全部状态" size="small" @change="resetPage">
        <el-option label="全部状态" value="" />
        <el-option label="一致" value="MATCH" />
        <el-option label="有差异" value="DRIFT" />
        <el-option label="未核验" value="UNVERIFIED" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <div class="dl-filters__spacer" />
      <span class="dl-meta">共 {{ filtered.length }} 条 · 每页 {{ PAGE_SIZE }} 条</span>
    </div>

    <div v-if="rowNotice" class="policy-notice" :class="`policy-notice--${rowNotice.kind}`" role="alert">
      <span>{{ rowNotice.text }}</span>
      <button type="button" class="policy-notice__close" aria-label="关闭提示" @click="rowNotice = null">×</button>
    </div>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load()"
    />

    <section v-else class="surface surface--flush">
      <el-table
        v-loading="loading"
        :data="paged"
        row-key="id"
        class="dl-table"
        size="small"
        :row-class-name="rowClass"
      >
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <PolicyDiffTable :items="latestCheckOf(row).items" :result="latestCheckOf(row).result" />
          </template>
        </el-table-column>
        <el-table-column label="项目" width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="policy-project">
              <span class="policy-project__key">{{ row.gitlabProject }}</span>
              <span class="policy-project__domain">{{ domainName(row.domainId) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分支模式" width="300">
          <template #default="{ row }">
            <div class="policy-pattern">
              <span class="policy-pattern__type">{{ row.patternType === 'REGEX' ? 'REGEX' : 'GLOB' }}</span>
              <span class="policy-pattern__value">{{ row.branchPattern }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="保护" width="64" align="center">
          <template #default="{ row }">
            <span :class="row.protected ? 'policy-flag is-on' : 'policy-flag'" :title="row.protected ? '要求保护分支' : '不要求保护分支'">
              {{ row.protected ? '是' : '否' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="合并" width="100">
          <template #default="{ row }">{{ MERGE_LABEL[row.mergeMethod] ?? row.mergeMethod }}</template>
        </el-table-column>
        <el-table-column label="审批" width="64" align="right">
          <template #default="{ row }">{{ row.approvalsRequired }}</template>
        </el-table-column>
        <el-table-column label="启用" width="64" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              :disabled="!canEdit"
              :aria-label="`启用规则 ${row.branchPattern}`"
              @update:model-value="(value: string | number | boolean) => toggleEnabled(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近核验" width="180">
          <template #default="{ row }">
            <div class="policy-check-cell">
              <PolicyCheckPill
                :result="latestCheckOf(row).result"
                :reason="latestCheckOf(row).reason"
                :checked-at="formatDateTime(latestCheckOf(row).checkedAt)"
                :loading="checkingId === row.id"
              />
              <button type="button" class="policy-time" @click="openHistory(row)">
                {{ latestCheckOf(row).checkedAt ? formatDateTime(latestCheckOf(row).checkedAt) : '无记录' }}
              </button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <div class="policy-actions">
              <el-button
                link
                type="primary"
                size="small"
                :loading="checkingId === row.id"
                :disabled="!canEdit || checkingId !== null"
                @click="onCheck(row)"
              >
                核验
              </el-button>
              <el-button link size="small" :disabled="!canEdit" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" :disabled="!canEdit" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !filtered.length"
        :variant="rows.length ? 'filtered' : 'empty'"
        :title="rows.length ? '没有符合筛选的规则' : '还没有分支策略'"
        :description="
          rows.length
            ? '调整业务域 / 项目 / 状态后再试；停用规则不计入一致率。'
            : '登记期望的 GitLab 分支保护与合并规则后，可随时核验实际配置。'
        "
        icon="inbox"
        :action-text="rows.length || !canEdit ? '' : '新增规则'"
        @action="openCreate"
      />
    </section>

    <div v-if="filtered.length > PAGE_SIZE" class="policy-pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="PAGE_SIZE"
        :total="filtered.length"
        layout="prev, pager, next"
        small
        background
      />
    </div>

    <!-- 规则抽屉 640 -->
    <el-drawer v-model="drawer.visible" :size="640" :close-on-click-modal="false" destroy-on-close>
      <template #header>
        <div class="policy-drawer-head">
          <h2 class="policy-drawer-title">{{ drawer.editingId ? '编辑分支策略' : '新增分支策略' }}</h2>
          <span class="dl-hint">保存后按需核验；规则本身不拦截 GitLab 操作。</span>
        </div>
      </template>

      <div class="policy-form">
        <div class="policy-field">
          <label class="policy-field__label" for="policy-domain">业务域</label>
          <el-select id="policy-domain" v-model="drawer.form.domainId" placeholder="选择业务域" size="small">
            <el-option v-for="item in domains" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <p v-if="drawer.errors.gitlabProject" class="policy-field__error">{{ drawer.errors.gitlabProject }}</p>
        </div>

        <div class="policy-field">
          <label class="policy-field__label" for="policy-project">GitLab 项目</label>
          <el-input
            id="policy-project"
            v-model="drawer.form.gitlabProject"
            placeholder="group/repo（可手填，不要求已接入）"
            size="small"
            class="policy-mono"
          />
          <p v-if="drawer.errors.gitlabProject" class="policy-field__error">{{ drawer.errors.gitlabProject }}</p>
        </div>

        <div class="policy-field">
          <label class="policy-field__label" for="policy-pattern">分支模式</label>
          <div class="policy-pattern-row">
            <el-input
              id="policy-pattern"
              v-model="drawer.form.branchPattern"
              placeholder="release/* 或 ^release/[0-9]+\.[0-9]+$"
              size="small"
              class="policy-mono"
              :class="{ 'is-error': drawer.errors.branchPattern }"
            />
            <div class="segmented" role="group" aria-label="模式类型">
              <button
                type="button"
                :class="{ 'is-active': drawer.form.patternType === 'GLOB' }"
                @click="drawer.form.patternType = 'GLOB'"
              >
                glob
              </button>
              <button
                type="button"
                :class="{ 'is-active': drawer.form.patternType === 'REGEX' }"
                @click="drawer.form.patternType = 'REGEX'"
              >
                regex
              </button>
            </div>
          </div>
          <p v-if="drawer.errors.branchPattern" class="policy-field__error">{{ drawer.errors.branchPattern }}</p>
          <div class="policy-test">
            <el-input
              v-model="drawer.testBranch"
              size="small"
              placeholder="输入分支名测试（纯前端，不请求 GitLab）"
              class="policy-test__input"
            />
            <span v-if="testResult" class="policy-test__result" :class="testResult.ok ? 'is-ok' : 'is-bad'">
              {{ testResult.text }}
            </span>
          </div>
        </div>

        <div class="policy-field policy-field--inline">
          <label class="policy-field__label" for="policy-protected">保护分支</label>
          <el-switch id="policy-protected" v-model="drawer.form.protected" size="small" />
          <span class="dl-hint">关闭后审批人数必须为 0。</span>
        </div>

        <div class="policy-field">
          <label class="policy-field__label" for="policy-merge">合并方式</label>
          <div class="segmented" role="group" aria-label="合并方式">
            <button
              v-for="option in MERGE_OPTIONS"
              :key="option"
              type="button"
              :class="{ 'is-active': drawer.form.mergeMethod === option }"
              @click="drawer.form.mergeMethod = option"
            >
              {{ option }}
            </button>
          </div>
        </div>

        <div class="policy-field policy-field--inline">
          <label class="policy-field__label" for="policy-approvals">审批人数</label>
          <el-input-number id="policy-approvals" v-model="drawer.form.approvalsRequired" :min="0" :max="5" size="small" />
          <span class="dl-hint">0–5 人；&gt; 0 时必须保护分支。</span>
          <p v-if="drawer.errors.approvals" class="policy-field__error">{{ drawer.errors.approvals }}</p>
        </div>

        <div class="policy-field">
          <label class="policy-field__label">推送角色</label>
          <el-select v-model="drawer.form.pushRoles" multiple filterable allow-create size="small" placeholder="可推送的角色">
            <el-option v-for="role in ROLE_OPTIONS" :key="role" :label="role" :value="role" />
          </el-select>
        </div>

        <div class="policy-field">
          <label class="policy-field__label">合并角色</label>
          <el-select v-model="drawer.form.mergeRoles" multiple filterable allow-create size="small" placeholder="可合并的角色">
            <el-option v-for="role in ROLE_OPTIONS" :key="role" :label="role" :value="role" />
          </el-select>
        </div>

        <div class="policy-field policy-field--inline">
          <label class="policy-field__label" for="policy-enabled">启用</label>
          <el-switch id="policy-enabled" v-model="drawer.form.enabled" size="small" />
        </div>

        <div v-if="drawer.errors.form" class="policy-form__error" role="alert">
          {{ drawer.errors.form }}
        </div>
      </div>

      <template #footer>
        <div class="policy-form__foot">
          <el-button size="small" @click="drawer.visible = false">取消</el-button>
          <el-button size="small" type="primary" :loading="drawer.saving" @click="submit">
            {{ drawer.editingId ? '保存' : '创建规则' }}
          </el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 核验历史 560（只增不改） -->
    <el-drawer v-model="history.visible" :size="560" destroy-on-close>
      <template #header>
        <div class="policy-drawer-head">
          <h2 class="policy-drawer-title">核验历史</h2>
          <span class="dl-hint">
            {{ history.row?.gitlabProject }} · {{ history.row?.branchPattern }}（最近 5 次，倒序，只增不改）
          </span>
        </div>
      </template>
      <ErrorState v-if="history.error" compact :message="history.error" @retry="history.row && openHistory(history.row)" />
      <div v-else v-loading="history.loading" class="policy-history">
        <div v-for="item in history.items" :key="`${item.id ?? item.checkedAt}`" class="policy-history__row">
          <PolicyCheckPill :result="item.result" :reason="item.reason" />
          <div class="policy-history__body">
            <span class="policy-history__time">{{ formatDateTime(item.checkedAt) }}</span>
            <span class="policy-history__reason">
              {{ item.reason ? POLICY_REASON_LABEL[item.reason] ?? item.reason : item.result === 'MATCH' ? '逐项一致' : '有差异' }}
              <template v-if="item.checkedByName"> · {{ item.checkedByName }}</template>
            </span>
          </div>
        </div>
        <EmptyState
          v-if="!history.loading && !history.items.length"
          title="还没有核验记录"
          description="点规则行的「核验」后，这里会按时间倒序保留历史。"
          icon="file"
          compact
        />
      </div>
    </el-drawer>

    <!-- 集成未启用 480：不发起请求 -->
    <el-dialog v-model="integrationDialog.visible" title="GitLab 未启用" width="480px" :close-on-click-modal="false">
      <p class="policy-dialog__text">
        分支核验需要先启用 GitLab 集成；当前没有启用的 GitLab 配置，因此本次没有发起核验请求。
      </p>
      <p class="policy-dialog__hint">规则可以继续登记与编辑，核验状态保持「未核验」，不计入一致率。</p>
      <template #footer>
        <el-button size="small" @click="integrationDialog.visible = false">知道了</el-button>
        <el-button size="small" type="primary" @click="goIntegration">去集成配置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.policy-page {
  gap: 12px;
}

.policy-count {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.policy-hint {
  display: flex;
  align-items: center;
  gap: 4px;
}

.policy-link {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary, #2b6fe5);
  cursor: pointer;
}

.policy-link:hover {
  text-decoration: underline;
}

.policy-link:focus-visible {
  outline: 2px solid var(--color-primary, #2b6fe5);
  outline-offset: 2px;
  border-radius: 2px;
}

.policy-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border-radius: var(--r-ctl, 6px);
  font-size: 12px;
  line-height: 18px;
}

.policy-notice--warning {
  background: var(--color-warning-bg, #fff7e6);
  border: 1px solid #f2d59b;
  color: var(--warning-ink, #9a5b00);
}

.policy-notice--danger {
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
}

.policy-notice__close {
  margin-left: auto;
  border: 0;
  background: none;
  color: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}

.policy-project {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.policy-project__key {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  color: var(--text-title, #1f2329);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.policy-project__domain {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.policy-pattern {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.policy-pattern__type {
  flex: none;
  padding: 0 4px;
  height: 16px;
  line-height: 16px;
  border-radius: 3px;
  border: 1px solid var(--border, #e5e6eb);
  background: var(--bg-table-head, #fafbfc);
  font-size: 10px;
  letter-spacing: 0.4px;
  color: var(--text-muted-aa, #6b7280);
}

.policy-pattern__value {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.policy-flag {
  color: var(--text-muted-aa, #6b7280);
}

.policy-flag.is-on {
  color: var(--success-ink, #157a38);
  font-weight: 500;
}

.policy-check-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.policy-time {
  border: 0;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.policy-time:hover {
  color: var(--color-primary, #2b6fe5);
  text-decoration: underline;
}

.policy-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.policy-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

:deep(.el-table__row.is-disabled-row) td {
  color: var(--text-muted-aa, #6b7280);
}

.policy-pager {
  display: flex;
  justify-content: flex-end;
}

.policy-drawer-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.policy-drawer-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}

.policy-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.policy-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.policy-field--inline {
  flex-direction: row;
  align-items: center;
  gap: 10px;
}

.policy-field__label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title, #1f2329);
}

.policy-field__error {
  margin: 0;
  font-size: 12px;
  color: var(--danger-ink, #b3262e);
}

.policy-field :deep(.el-select) {
  width: 100%;
}

.policy-mono :deep(.el-input__inner) {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.policy-pattern-row {
  display: flex;
  gap: 8px;
}

.policy-pattern-row :deep(.el-input) {
  flex: 1;
}

.policy-pattern-row .is-error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--color-danger, #d9363e) inset;
}

.policy-test {
  display: flex;
  align-items: center;
  gap: 8px;
}

.policy-test__input {
  flex: 1;
}

.policy-test__result {
  font-size: 12px;
  white-space: nowrap;
}

.policy-test__result.is-ok {
  color: var(--success-ink, #157a38);
}

.policy-test__result.is-bad {
  color: var(--danger-ink, #b3262e);
}

.segmented {
  display: inline-flex;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
  overflow: hidden;
  flex: none;
}

.segmented button {
  border: 0;
  background: var(--bg-card, #fff);
  padding: 0 10px;
  height: 24px;
  font-size: 12px;
  color: var(--text-body, #4e5969);
  cursor: pointer;
}

.segmented button + button {
  border-left: 1px solid var(--border, #e5e6eb);
}

.segmented button:hover {
  background: var(--bg-hover, #f7f9fc);
}

.segmented button.is-active {
  background: var(--color-primary-light, #eaf1fe);
  color: var(--color-primary, #2b6fe5);
  font-weight: 500;
}

.policy-form__error {
  padding: 8px 12px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
  font-size: 12px;
  line-height: 18px;
}

.policy-form__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.policy-history {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.policy-history__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
}

.policy-history__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.policy-history__time {
  font-size: 12px;
  color: var(--text-title, #1f2329);
}

.policy-history__reason {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.policy-dialog__text {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body, #4e5969);
}

.policy-dialog__hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}
</style>
