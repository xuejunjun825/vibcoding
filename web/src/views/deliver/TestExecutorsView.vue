<script setup lang="ts">
/**
 * CTest 执行器台账（DESIGN E1 · AC-19）。
 *
 * 健康五态完全由服务端 `ExecutorHealth` 派生（≤24h / 24–72h / >72h），
 * 页面只渲染 pill 与最近上报时间，不自己算阈值；停用即时生效并明确告知 422。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import { listApiClients } from '@/api/admin'
import type { ApiClientRow } from '@/api/types'
import { fetchExecutorReports, fetchExecutors, saveExecutor } from '@/api/deliver'
import type { ExecutorSaveBody, TestExecutorRow } from '@/api/deliver'
import ExecutorHealthPill from '@/components/deliver/ExecutorHealthPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, formatNumber } from '@/utils/format'
import '@/views/deliver/shared.css'

const { can } = usePermission()
const canManage = computed(() => can('TEST_EXECUTOR_MANAGE'))

const rows = ref<TestExecutorRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
/** 近 7 天上报计数：undefined = 未取到（显示 —，不补 0） */
const reportTotals = reactive<Record<number, number | null>>({})
const clients = ref<ApiClientRow[]>([])

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const payload = await fetchExecutors()
    rows.value = payload.items
    await loadReportTotals()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message || '执行器加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 近 7 天计数逐行取（本服务本地快照，不触发外部外呼）；失败保持 — 而不是 0 */
async function loadReportTotals(): Promise<void> {
  await Promise.all(
    rows.value.map(async (row) => {
      try {
        const buckets = await fetchExecutorReports(row.id, 7)
        reportTotals[row.id] = buckets.reduce((sum, item) => sum + (Number(item.count) || 0), 0)
      } catch {
        reportTotals[row.id] = null
      }
    }),
  )
}

async function loadClients(): Promise<void> {
  try {
    // 凭证名只是台账的一列辅助信息：/admin/api-clients 需要 org:manage，qa1（仅
    // TEST_EXECUTOR_VIEW）必然 403。silentForbidden 让 403 只降级为「AppKey #id」，
    // 不再由 client.ts 全局跳 /403 把整页踢走（D-01）；凭证下拉本身只对 canManage 可见。
    const page = await listApiClients({ page: 1, size: 200 }, { silentForbidden: true })
    clients.value = page.items ?? []
  } catch {
    clients.value = []
  }
}

onMounted(() => {
  void load()
  void loadClients()
})

function clientLabel(id?: number | null): string {
  if (!id) return '未绑定'
  const client = clients.value.find((item) => item.id === id)
  return client ? `${client.name} · ${client.appKey}` : `AppKey #${id}`
}

const counts = computed(() => ({
  total: rows.value.length,
  online: rows.value.filter((row) => row.health === 'ONLINE').length,
  enabled: rows.value.filter((row) => row.enabled).length,
}))

/* ---------------- 新增 / 编辑（抽屉 560） ---------------- */

const formDrawer = ref({
  visible: false,
  saving: false,
  editingId: null as number | null,
  errors: { code: '', name: '', baseUrl: '', form: '' },
  form: {
    code: '',
    name: '',
    baseUrl: '',
    apiClientId: null as number | null,
    enabled: true,
  },
})

function clearErrors(): void {
  formDrawer.value.errors.code = ''
  formDrawer.value.errors.name = ''
  formDrawer.value.errors.baseUrl = ''
  formDrawer.value.errors.form = ''
}

function openCreate(): void {
  if (!canManage.value) return
  formDrawer.value.editingId = null
  formDrawer.value.form = { code: '', name: '', baseUrl: '', apiClientId: null, enabled: true }
  clearErrors()
  formDrawer.value.visible = true
}

function openEdit(row: TestExecutorRow): void {
  if (!canManage.value) return
  formDrawer.value.editingId = row.id
  formDrawer.value.form = {
    code: row.code,
    name: row.name,
    baseUrl: row.baseUrl ?? '',
    apiClientId: row.apiClientId ?? null,
    enabled: row.enabled,
  }
  clearErrors()
  formDrawer.value.visible = true
}

function validate(): boolean {
  clearErrors()
  if (!formDrawer.value.editingId) {
    if (!formDrawer.value.form.code.trim()) formDrawer.value.errors.code = '填写执行器代码（租户内唯一）'
    else if (!/^[A-Za-z0-9_-]{2,32}$/.test(formDrawer.value.form.code.trim())) {
      formDrawer.value.errors.code = '只允许字母、数字、下划线与连字符，2–32 位'
    }
  }
  if (!formDrawer.value.form.name.trim()) formDrawer.value.errors.name = '填写执行器名称'
  const baseUrl = formDrawer.value.form.baseUrl.trim()
  if (baseUrl && !/^https?:\/\//.test(baseUrl)) formDrawer.value.errors.baseUrl = '地址需以 http:// 或 https:// 开头'
  return !formDrawer.value.errors.code && !formDrawer.value.errors.name && !formDrawer.value.errors.baseUrl
}

async function submit(): Promise<void> {
  if (!validate()) return
  formDrawer.value.saving = true
  try {
    const body: ExecutorSaveBody = {
      id: formDrawer.value.editingId ?? undefined,
      name: formDrawer.value.form.name.trim(),
      baseUrl: formDrawer.value.form.baseUrl.trim() || undefined,
      apiClientId: formDrawer.value.form.apiClientId,
      enabled: formDrawer.value.form.enabled,
    }
    if (!formDrawer.value.editingId) body.code = formDrawer.value.form.code.trim()
    await saveExecutor(body)
    formDrawer.value.visible = false
    ElMessage.success(formDrawer.value.editingId ? '执行器已更新' : '执行器已创建')
    await load()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    const code = apiError?.code ?? ''
    if (code === 'EXECUTOR_CODE_DUPLICATE' || apiError?.status === 409) {
      formDrawer.value.errors.code = '该代码已被占用（租户内唯一）'
      formDrawer.value.errors.form = '执行器代码在租户内唯一，换个代码再试。'
    } else {
      formDrawer.value.errors.form = apiError?.message || '保存失败，输入已保留'
    }
  } finally {
    formDrawer.value.saving = false
  }
}

/* ---------------- 启用 / 停用 ---------------- */

const togglingId = ref<number | null>(null)

async function toggle(row: TestExecutorRow): Promise<void> {
  if (!canManage.value) return
  const next = !row.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        `停用后该执行器上报将返回 422 EXECUTOR_DISABLED（不会静默丢弃）。确认停用「${row.name}」？`,
        '停用执行器',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  togglingId.value = row.id
  try {
    await saveExecutor({
      id: row.id,
      name: row.name,
      baseUrl: row.baseUrl ?? undefined,
      apiClientId: row.apiClientId ?? null,
      enabled: next,
    })
    ElMessage.success(next ? '执行器已启用' : '执行器已停用')
    await load()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    ElMessage.warning(apiError?.message || '状态未保存')
  } finally {
    togglingId.value = null
  }
}

/* ---------------- 接入说明抽屉 ---------------- */

const guide = ref({ visible: false, row: null as TestExecutorRow | null })

function openGuide(row?: TestExecutorRow): void {
  guide.value.row = row ?? rows.value[0] ?? null
  guide.value.visible = true
}

const guideSnippet = computed(() => {
  const code = guide.value.row?.code ?? 'executor-code'
  return [
    `POST /api/deliver/openapi/executions`,
    `X-Api-Key: <AppKey>`,
    `Content-Type: application/json`,
    ``,
    `{`,
    `  "executorCode": "${code}",`,
    `  "runId": "ci-20261109-001",`,
    `  "caseCode": "TC-0001",`,
    `  "result": "PASS"`,
    `}`,
  ].join('\n')
})

const copied = ref(false)

async function copySnippet(): Promise<void> {
  try {
    await navigator.clipboard.writeText(guideSnippet.value)
    copied.value = true
    ElMessage.success('已复制接入示例')
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    ElMessage.warning('浏览器未允许剪贴板，请手动选中复制')
  }
}
</script>

<template>
  <div class="dl-page exec-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">执行器</h1>
        <span class="dl-meta">{{ counts.total }} 台 · 在线 {{ counts.online }} · 启用 {{ counts.enabled }}</span>
      </div>
      <div class="dl-head__actions">
        <el-button size="small" :disabled="loading" @click="load()">刷新</el-button>
        <el-button size="small" @click="openGuide()">接入说明</el-button>
        <el-button v-if="canManage" size="small" type="primary" @click="openCreate">新增执行器</el-button>
      </div>
    </header>

    <div class="exec-legend" role="note" aria-label="健康状态图例">
      <span class="exec-legend__item"><i class="dot dot--online" />在线 ≤24h</span>
      <span class="exec-legend__item"><i class="dot dot--stale" />滞后 24–72h</span>
      <span class="exec-legend__item"><i class="dot dot--offline" />离线 &gt;72h</span>
      <span class="exec-legend__item"><i class="dot dot--never" />从未上报</span>
      <span class="exec-legend__item"><i class="dot dot--disabled" />已停用</span>
      <span class="exec-legend__hint">健康由服务端按最近上报时间派生，停用即时生效。</span>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load()" />

    <section v-else class="surface surface--flush">
      <el-table v-loading="loading" :data="rows" row-key="id" class="dl-table" size="small">
        <el-table-column label="名称" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="exec-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="code" width="120">
          <template #default="{ row }"><span class="exec-code">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="地址" width="240" show-overflow-tooltip>
          <template #default="{ row }"><span class="exec-url">{{ row.baseUrl || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="绑定 AppKey" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="row.apiClientId ? 'exec-client' : 'muted'">{{ clientLabel(row.apiClientId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近上报" width="160">
          <template #default="{ row }">
            <span class="muted">{{ formatDateTime(row.lastReportAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="健康" width="88">
          <template #default="{ row }">
            <ExecutorHealthPill :health="row.health" :last-report-at="formatDateTime(row.lastReportAt)" />
          </template>
        </el-table-column>
        <el-table-column label="7 天" width="88" align="right">
          <template #default="{ row }">
            <span v-if="reportTotals[row.id] === null" class="muted" title="未取到上报统计">—</span>
            <span v-else class="num">{{ formatNumber(reportTotals[row.id] ?? 0) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="64" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              :disabled="!canManage || togglingId === row.id"
              :aria-label="`启用执行器 ${row.name}`"
              @update:model-value="toggle(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <div class="exec-actions">
              <el-button link size="small" :disabled="!canManage" @click="openEdit(row)">编辑</el-button>
              <el-button link size="small" @click="openGuide(row)">示例</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !rows.length"
        title="还没有执行器"
        :description="canManage ? '登记第一台执行器后，CI 上报即可按 executorCode 归属到台账。' : '联系管理员登记执行器后再查看健康状态。'"
        icon="inbox"
        :action-text="canManage ? '新增执行器' : ''"
        @action="openCreate"
      />
    </section>

    <!-- 新增 / 编辑抽屉 560 -->
    <el-drawer v-model="formDrawer.visible" :size="560" :close-on-click-modal="false" destroy-on-close>
      <template #header>
        <div class="exec-drawer-head">
          <h2 class="exec-drawer-title">{{ formDrawer.editingId ? '编辑执行器' : '新增执行器' }}</h2>
          <span class="dl-hint">停用后上报返回 422 EXECUTOR_DISABLED；执行器代码创建后不可改。</span>
        </div>
      </template>

      <div class="exec-form">
        <div class="exec-field">
          <label class="exec-field__label" for="exec-code">执行器代码</label>
          <el-input
            id="exec-code"
            v-model="formDrawer.form.code"
            :disabled="Boolean(formDrawer.editingId)"
            size="small"
            placeholder="例如 ci-runner-01"
            class="exec-mono"
          />
          <p class="exec-field__hint">租户内唯一；创建后不可修改（CI 上报按它归属）。</p>
          <p v-if="formDrawer.errors.code" class="exec-field__error">{{ formDrawer.errors.code }}</p>
        </div>

        <div class="exec-field">
          <label class="exec-field__label" for="exec-name">名称</label>
          <el-input id="exec-name" v-model="formDrawer.form.name" size="small" placeholder="例如 主干流水线执行器" />
          <p v-if="formDrawer.errors.name" class="exec-field__error">{{ formDrawer.errors.name }}</p>
        </div>

        <div class="exec-field">
          <label class="exec-field__label" for="exec-url">地址</label>
          <el-input
            id="exec-url"
            v-model="formDrawer.form.baseUrl"
            size="small"
            placeholder="https://ci.example.com/jenkins"
            class="exec-mono"
          />
          <p class="exec-field__hint">台账展示用；上报仍走平台 OpenAPI。</p>
          <p v-if="formDrawer.errors.baseUrl" class="exec-field__error">{{ formDrawer.errors.baseUrl }}</p>
        </div>

        <div class="exec-field">
          <label class="exec-field__label" for="exec-client">绑定 AppKey（可空）</label>
          <el-select id="exec-client" v-model="formDrawer.form.apiClientId" clearable size="small" placeholder="不绑定">
            <el-option
              v-for="client in clients"
              :key="client.id"
              :label="`${client.name} · ${client.appKey}`"
              :value="client.id"
            />
          </el-select>
          <p class="exec-field__hint">绑定后只有该客户端能代表此执行器上报；未绑定按客户端自行声明。</p>
        </div>

        <div class="exec-field exec-field--inline">
          <label class="exec-field__label" for="exec-enabled">启用</label>
          <el-switch id="exec-enabled" v-model="formDrawer.form.enabled" size="small" />
        </div>

        <div v-if="formDrawer.errors.form" class="exec-form__error" role="alert">{{ formDrawer.errors.form }}</div>
      </div>

      <template #footer>
        <div class="exec-form__foot">
          <el-button size="small" @click="formDrawer.visible = false">取消</el-button>
          <el-button size="small" type="primary" :loading="formDrawer.saving" @click="submit">
            {{ formDrawer.editingId ? '保存' : '创建执行器' }}
          </el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 接入说明 560 -->
    <el-drawer v-model="guide.visible" :size="560" destroy-on-close>
      <template #header>
        <div class="exec-drawer-head">
          <h2 class="exec-drawer-title">接入说明</h2>
          <span class="dl-hint">
            {{ guide.row ? `${guide.row.name}（${guide.row.code}）` : '执行器通过 OpenAPI 上报执行结果' }}
          </span>
        </div>
      </template>
      <div class="exec-guide">
        <p class="exec-guide__text">
          CI 在每条用例执行完成后上报一次；<code>executorCode</code> 只用于归属台账，不参与幂等键——
          同一 <code>runId + caseCode</code> 重复上报仍然幂等。
        </p>
        <pre class="exec-guide__code">{{ guideSnippet }}</pre>
        <div class="exec-guide__actions">
          <el-button size="small" @click="copySnippet">{{ copied ? '已复制' : '复制示例' }}</el-button>
          <el-button size="small" @click="guide.visible = false">关闭</el-button>
        </div>
        <p class="exec-guide__hint">
          上报被拒时按错误码处理：422 <code>EXECUTOR_DISABLED</code> = 执行器已停用；401 表示 AppKey 无效。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.exec-page {
  gap: 12px;
}

.exec-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  padding: 7px 12px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
  background: var(--bg-table-head, #fafbfc);
  font-size: 12px;
  color: var(--text-body, #4e5969);
}

.exec-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.exec-legend__hint {
  margin-left: auto;
  color: var(--text-muted-aa, #6b7280);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.dot--online {
  background: var(--color-success, #1f9d45);
}

.dot--stale {
  background: var(--color-warning, #c77700);
}

.dot--offline {
  background: var(--color-danger, #d9363e);
}

.dot--never {
  background: var(--border-strong, #c9cdd4);
}

.dot--disabled {
  background: transparent;
  border: 1px solid var(--border-strong, #c9cdd4);
  box-sizing: border-box;
}

.exec-name {
  font-weight: 500;
  color: var(--text-title, #1f2329);
}

.exec-code,
.exec-url,
.exec-mono :deep(.el-input__inner) {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

.exec-client {
  font-size: 12px;
}

.muted {
  color: var(--text-muted-aa, #6b7280);
}

.num {
  font-variant-numeric: tabular-nums;
}

.exec-actions {
  display: flex;
  gap: 4px;
}

.exec-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.exec-drawer-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.exec-drawer-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}

.exec-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.exec-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.exec-field--inline {
  flex-direction: row;
  align-items: center;
  gap: 10px;
}

.exec-field__label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title, #1f2329);
}

.exec-field__hint {
  margin: 0;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-muted-aa, #6b7280);
}

.exec-field__error {
  margin: 0;
  font-size: 12px;
  color: var(--danger-ink, #b3262e);
}

.exec-form__error {
  padding: 8px 12px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
  font-size: 12px;
  line-height: 18px;
}

.exec-form__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.exec-guide {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.exec-guide__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body, #4e5969);
}

.exec-guide__code {
  margin: 0;
  padding: 12px;
  border-radius: var(--r-ctl, 6px);
  background: #f7f9fc;
  border: 1px solid var(--border, #e5e6eb);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-title, #1f2329);
  white-space: pre-wrap;
  word-break: break-all;
}

.exec-guide__actions {
  display: flex;
  gap: 8px;
}

.exec-guide__hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  line-height: 1.7;
}

.exec-guide code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  padding: 0 3px;
  background: var(--bg-hover, #f7f9fc);
  border-radius: 3px;
}
</style>
