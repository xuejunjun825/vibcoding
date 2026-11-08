<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  disableWorkflow,
  listWorkflows,
  publishWorkflow,
  saveWorkflow,
} from '@/api/admin'
import type { WorkflowDefinitionRow } from '@/api/admin'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 审批流程定义（DESIGN P12 / AC-6）。
 * 表单式节点列表：开始 → 节点… → 结束，节点顺序即执行顺序。
 * 发布前服务端做可达性与审批人解析校验（400 + detail.nodeKey），
 * 已发布版本不可编辑，修改必须新建一条定义产生新版本。
 */
type NodeType = 'APPROVAL' | 'CONDITION' | 'CC'

interface ApproverSpec {
  type: 'ROLE' | 'USER' | 'SUPERVISOR'
  value: string
  scope: string
}

interface EditorNode {
  key: string
  type: NodeType
  name: string
  mode: 'ANY' | 'ALL'
  approvers: ApproverSpec[]
  whenText: string
}

const rows = ref<WorkflowDefinitionRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    rows.value = (await listWorkflows()) ?? []
  } catch (err) {
    error.value = {
      message: (err as Error).message || '流程定义加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/* ---------------- 编辑器 ---------------- */

const editorVisible = ref(false)
const saving = ref(false)
const publishing = ref(false)
const serverError = ref<{ message: string; nodeKey?: string } | null>(null)
const form = reactive<{ id?: number; code: string; name: string; bizType: string }>({
  id: undefined,
  code: '',
  name: '',
  bizType: 'REQUIREMENT',
})
const nodes = ref<EditorNode[]>([])
let seq = 0

function blankNode(type: NodeType): EditorNode {
  seq += 1
  return {
    key: `n${seq}`,
    type,
    name: type === 'APPROVAL' ? '审批节点' : type === 'CONDITION' ? '条件分支' : '抄送',
    mode: 'ANY',
    approvers:
      type === 'CC'
        ? [{ type: 'ROLE', value: 'DOMAIN_OWNER', scope: '' }]
        : [{ type: 'ROLE', value: 'SUB_BUSINESS_LEADER', scope: '' }],
    whenText: '{"priority":["P0"]}',
  }
}

function openCreate(): void {
  form.id = undefined
  form.code = ''
  form.name = ''
  form.bizType = 'REQUIREMENT'
  nodes.value = [blankNode('APPROVAL'), blankNode('CC')]
  serverError.value = null
  editorVisible.value = true
}

function addNode(type: NodeType): void {
  nodes.value.push(blankNode(type))
}

function moveNode(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= nodes.value.length) return
  const list = nodes.value
  const [item] = list.splice(index, 1)
  list.splice(target, 0, item)
}

function removeNode(index: number): void {
  nodes.value.splice(index, 1)
}

function addApprover(node: EditorNode): void {
  node.approvers.push({ type: 'ROLE', value: '', scope: '' })
}

function nodeTypeLabel(type: NodeType): string {
  return type === 'APPROVAL' ? '审批' : type === 'CONDITION' ? '条件' : '抄送'
}

function removeApprover(node: EditorNode, index: number): void {
  node.approvers.splice(index, 1)
}

const approverWarnings = computed<string[]>(() =>
  nodes.value
    .map((node, index) =>
      node.type === 'APPROVAL' && node.approvers.filter((item) => item.value || item.type === 'SUPERVISOR').length === 0
        ? `节点 ${index + 1}（${node.name || '未命名'}）未解析出审批人，流程不能发布`
        : '',
    )
    .filter(Boolean),
)

function buildDefinition(): { schemaVersion: number; start: string; nodes: unknown[] } {
  const chain = nodes.value.map((node, index) => {
    const nextKey = nodes.value[index + 1]?.key ?? 'end'
    if (node.type === 'CONDITION') {
      let when: unknown = {}
      try {
        when = JSON.parse(node.whenText || '{}')
      } catch {
        throw new Error(`节点 ${index + 1} 的条件不是合法 JSON`)
      }
      return {
        key: node.key,
        type: 'CONDITION',
        name: node.name,
        branches: [{ when, next: nextKey }],
        defaultNext: nextKey,
      }
    }
    if (node.type === 'CC') {
      return {
        key: node.key,
        type: 'CC',
        name: node.name,
        receivers: node.approvers
          .filter((item) => item.value || item.type === 'SUPERVISOR')
          .map((item) => ({
            type: item.type,
            ...(item.type === 'SUPERVISOR' ? { scope: item.scope || 'INITIATOR_ORG' } : { value: item.value }),
          })),
        next: nextKey,
      }
    }
    return {
      key: node.key,
      type: 'APPROVAL',
      name: node.name,
      mode: node.mode,
      approvers: node.approvers
        .filter((item) => item.value || item.type === 'SUPERVISOR')
        .map((item) => ({
          type: item.type,
          ...(item.type === 'SUPERVISOR' ? { scope: item.scope || 'INITIATOR_ORG' } : { value: item.value }),
        })),
      next: nextKey,
    }
  })
  return { schemaVersion: 1, start: nodes.value[0]?.key ?? 'end', nodes: chain }
}

async function submit(): Promise<number | undefined> {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('请填写流程编码与名称')
    return undefined
  }
  let definition: ReturnType<typeof buildDefinition>
  try {
    definition = buildDefinition()
  } catch (err) {
    ElMessage.error((err as Error).message)
    return undefined
  }
  saving.value = true
  serverError.value = null
  try {
    const saved = await saveWorkflow({
      id: form.id,
      code: form.code.trim(),
      name: form.name.trim(),
      bizType: form.bizType,
      definition,
    })
    form.id = saved.id
    ElMessage.success('草稿已保存（未发布不生效）')
    await load()
    return saved.id
  } catch (err) {
    const detail = (err as { detail?: { nodeKey?: string } }).detail
    serverError.value = {
      message: (err as Error).message || '保存失败',
      nodeKey: detail?.nodeKey,
    }
    return undefined
  } finally {
    saving.value = false
  }
}

async function saveDraft(): Promise<void> {
  await submit()
}

async function saveAndPublish(): Promise<void> {
  if (approverWarnings.value.length) {
    ElMessage.warning(approverWarnings.value[0])
    return
  }
  const id = await submit()
  if (!id) return
  publishing.value = true
  try {
    const result = await publishWorkflow(id)
    ElMessage.success(`已发布 v${result.version}，已发布版本不可编辑`)
    editorVisible.value = false
    await load()
  } catch (err) {
    const detail = (err as { detail?: { nodeKey?: string } }).detail
    serverError.value = { message: (err as Error).message || '发布失败', nodeKey: detail?.nodeKey }
  } finally {
    publishing.value = false
  }
}

/* ---------------- 列表操作 ---------------- */

const publishTarget = ref<WorkflowDefinitionRow | null>(null)
const publishVisible = ref(false)

function askPublish(row: WorkflowDefinitionRow): void {
  publishTarget.value = row
  publishVisible.value = true
}

async function doPublish(): Promise<void> {
  if (!publishTarget.value) return
  try {
    const result = await publishWorkflow(publishTarget.value.id)
    publishVisible.value = false
    ElMessage.success(`已发布 v${result.version}`)
    await load()
  } catch (err) {
    publishVisible.value = false
    ElMessage.error((err as Error).message || '发布失败')
    await load()
  }
}

async function doDisable(row: WorkflowDefinitionRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `停用后该流程不再接收新单据，在途审批实例继续按已钉版本走完。确认停用「${row.name}」？`,
      '停用流程',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await disableWorkflow(row.id)
    ElMessage.success('流程已停用')
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '停用失败')
  }
}

function statusTone(status: string): string {
  if (status === 'PUBLISHED') return 'ink-success'
  if (status === 'DISABLED') return 'ink-muted'
  return 'ink-warning'
}

function statusLabel(status: string): string {
  return status === 'PUBLISHED' ? '已发布' : status === 'DISABLED' ? '已停用' : '草稿'
}

onMounted(load)
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <span class="adm-hint">发布即固化版本；修改已发布流程需新建定义产生 v+1，在途实例仍走旧版本。</span>
      <div class="adm-toolbar__spacer" />
      <el-button link :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建流程</el-button>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <section v-else class="surface">
      <EmptyState
        v-if="!loading && !rows.length"
        title="还没有审批流程"
        description="需求提报、需求拆解、发布单都需要一条已发布的流程定义"
        icon="file"
        action-text="新建流程"
        @action="openCreate"
      />
      <el-table v-else v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="编码" width="180">
          <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column prop="bizType" label="单据类型" width="150" />
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span :class="statusTone(row.status)">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ row.updatedAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="askPublish(row)">发布</el-button>
              <el-button v-if="row.status === 'PUBLISHED'" link @click="doDisable(row)">停用</el-button>
              <el-tooltip
                v-if="row.status === 'PUBLISHED'"
                content="已发布版本只读；修改请新建定义"
                placement="top"
              >
                <span class="ink-muted" style="font-size: 12px">只读</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="editorVisible" title="新建审批流程" size="720px" :close-on-click-modal="false">
      <div class="adm-form">
        <el-form label-position="top">
          <div class="adm-form-grid adm-form-grid--3">
            <el-form-item label="编码" required>
              <el-input v-model="form.code" maxlength="40" placeholder="req_default" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="form.name" maxlength="60" placeholder="需求提报审批" />
            </el-form-item>
            <el-form-item label="单据类型">
              <el-select v-model="form.bizType" style="width: 100%">
                <el-option label="需求提报 REQUIREMENT" value="REQUIREMENT" />
                <el-option label="需求拆解 DECOMPOSE" value="DECOMPOSE" />
                <el-option label="发布单 DEPLOY" value="DEPLOY" />
              </el-select>
            </el-form-item>
          </div>
        </el-form>

        <div class="adm-head">
          <div>
            <div class="adm-head__title">节点链</div>
            <p class="adm-head__desc">按顺序执行：上一个节点通过后进入下一个；条件分支命中时跳入其分支，否则走默认分支（即下一节点）。</p>
          </div>
        </div>

        <div class="adm-nodes">
          <div v-for="(node, index) in nodes" :key="node.key" class="adm-node">
            <div class="adm-node__head">
              <span class="adm-node__key">{{ index + 1 }} · {{ node.key }}</span>
              <span class="adm-node__type">{{ nodeTypeLabel(node.type) }}</span>
              <el-input v-model="node.name" size="small" maxlength="40" style="width: 200px" />
              <div class="adm-node__spacer" />
              <el-button link size="small" :disabled="index === 0" @click="moveNode(index, -1)">上移</el-button>
              <el-button link size="small" :disabled="index === nodes.length - 1" @click="moveNode(index, 1)">下移</el-button>
              <el-button link size="small" type="danger" @click="removeNode(index)">删除</el-button>
            </div>

            <template v-if="node.type === 'APPROVAL'">
              <el-radio-group v-model="node.mode" size="small">
                <el-radio value="ANY">或签（一人通过）</el-radio>
                <el-radio value="ALL">会签（全部通过）</el-radio>
              </el-radio-group>
              <div class="adm-approvers">
                <div v-for="(approver, approverIndex) in node.approvers" :key="approverIndex" class="adm-approver">
                  <el-select v-model="approver.type" size="small" style="width: 130px">
                    <el-option label="角色" value="ROLE" />
                    <el-option label="指定用户" value="USER" />
                    <el-option label="上级主管" value="SUPERVISOR" />
                  </el-select>
                  <el-input
                    v-if="approver.type !== 'SUPERVISOR'"
                    v-model="approver.value"
                    size="small"
                    style="width: 200px"
                    :placeholder="approver.type === 'ROLE' ? '角色代码，如 PROJECT' : '用户 ID'"
                  />
                  <el-select v-else v-model="approver.scope" size="small" style="width: 200px">
                    <el-option label="发起人组织内" value="INITIATOR_ORG" />
                    <el-option label="发起人域内" value="DOMAIN" />
                    <el-option label="租户内" value="TENANT" />
                  </el-select>
                  <el-button link size="small" type="danger" @click="removeApprover(node, approverIndex)">移除</el-button>
                </div>
                <el-button link type="primary" size="small" @click="addApprover(node)">+ 审批人</el-button>
              </div>
            </template>

            <template v-else-if="node.type === 'CONDITION'">
              <p class="adm-hint">条件命中时进入下一节点；未命中同样走下一节点。条件用 JSON 表达，例如 {"priority":["P0"],"estimateDays":{"gte":5}}。</p>
              <el-input v-model="node.whenText" type="textarea" :rows="2" placeholder='{"priority":["P0"]}' />
            </template>

            <template v-else>
              <p class="adm-hint">抄送只通知不阻断，审批结束后抄送给下列角色。</p>
              <div class="adm-approvers">
                <div v-for="(approver, approverIndex) in node.approvers" :key="approverIndex" class="adm-approver">
                  <el-select v-model="approver.type" size="small" style="width: 130px">
                    <el-option label="角色" value="ROLE" />
                    <el-option label="指定用户" value="USER" />
                    <el-option label="上级主管" value="SUPERVISOR" />
                  </el-select>
                  <el-input
                    v-if="approver.type !== 'SUPERVISOR'"
                    v-model="approver.value"
                    size="small"
                    style="width: 200px"
                    placeholder="角色代码或用户 ID"
                  />
                  <el-select v-else v-model="approver.scope" size="small" style="width: 200px">
                    <el-option label="发起人组织内" value="INITIATOR_ORG" />
                    <el-option label="发起人域内" value="DOMAIN" />
                  </el-select>
                  <el-button link size="small" type="danger" @click="removeApprover(node, approverIndex)">移除</el-button>
                </div>
                <el-button link type="primary" size="small" @click="addApprover(node)">+ 抄送人</el-button>
              </div>
            </template>

            <div v-if="approverWarnings[index]" class="adm-node__error">{{ approverWarnings[index] }}</div>
          </div>
        </div>

        <div class="adm-node__actions">
          <el-button size="small" @click="addNode('APPROVAL')">+ 审批节点</el-button>
          <el-button size="small" @click="addNode('CONDITION')">+ 条件分支</el-button>
          <el-button size="small" @click="addNode('CC')">+ 抄送节点</el-button>
        </div>

        <div v-if="serverError" class="adm-node__error" style="margin-top: 12px">
          {{ serverError.message }}
          <span v-if="serverError.nodeKey">（节点 {{ serverError.nodeKey }}）</span>
        </div>
      </div>

      <template #footer>
        <div class="adm-drawer-footer">
          <span class="adm-hint">发布前服务端会再次校验节点可达与审批人可解析。</span>
          <div>
            <el-button @click="editorVisible = false">取消</el-button>
            <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>
            <el-button
              type="primary"
              :loading="publishing"
              :disabled="approverWarnings.length > 0"
              @click="saveAndPublish"
            >
              保存并发布
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <ConfirmDialog
      v-model="publishVisible"
      title="发布流程定义"
      description="发布会固化当前版本，已发布版本不可编辑；在途单据按发起时钉住的版本继续。"
      :require-reason="false"
      confirm-text="发布"
      @confirm="doPublish"
      @cancel="publishVisible = false"
    />
  </div>
</template>

<style scoped>
.adm-pages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.adm-nodes {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.adm-node__type {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
  font-size: 12px;
  color: var(--text-body);
}

.adm-approvers {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.adm-approver {
  display: flex;
  align-items: center;
  gap: 8px;
}

.adm-node__actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.adm-drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
</style>
