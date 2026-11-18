<script setup lang="ts">
/**
 * 发布等级流程绑定（r30 / DESIGN §4.5）：6 个槽 = 2 类单据 × 3 个等级。
 *
 * - 权限复用 `workflow:manage`（不新增权限码，rbac 计数不变）；本页没有独立导航项与页面码，
 *   由流程定义页入口进入，a3 审计的 pageCode 集合因此不变；
 * - 「恢复默认」= DELETE 绑定行，服务端解析回落默认定义；不复制默认 id，避免两份默认配置；
 * - 绑定只影响后续发起的实例，在途实例仍按自己的 definitionVersion 走完。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listFlowBindings, listWorkflows, resetFlowBinding, saveFlowBinding } from '@/api/admin'
import type { WorkflowDefinitionRow } from '@/api/admin'
import { ApiError } from '@/api/client'
import type { FlowBindingSlot } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { messageOf } from '@/views/req/platform'
import '@/views/admin/admin-shared.css'

const { canAction } = usePermission()
const canManage = computed(() => canAction('workflow:manage'))
const manageHint = computed(() => (canManage.value ? '' : '需要权限：workflow:manage'))

const GROUPS = [
  { bizType: 'RELEASE', label: '版本发布', desc: '版本「提交发布审批」时按发布等级取链' },
  { bizType: 'REQ_DEPLOY', label: '发布单', desc: '发布单「提交发布申请」时按发布等级取链' },
] as const

const LEVELS = [
  { level: 'NORMAL', label: '常规' },
  { level: 'EMERGENCY', label: '紧急' },
  { level: 'ROLLBACK', label: '回滚' },
] as const

const MODE_LABEL: Record<string, string> = { ALL: '会签', ANY: '或签', SEQUENTIAL: '逐级' }

const slots = ref<FlowBindingSlot[]>([])
const loading = ref(false)
const pageError = ref<{ message: string; code?: string; status?: number } | null>(null)

function fallbackSlot(bizType: string, level: string, label: string): FlowBindingSlot {
  return {
    bizType,
    releaseLevel: level,
    releaseLevelLabel: label,
    definitionId: null,
    definitionName: null,
    definitionVersion: null,
    status: 'MISSING',
    isDefault: true,
    nodes: [],
  }
}

function slotOf(bizType: string, level: string, label: string): FlowBindingSlot {
  return (
    slots.value.find((item) => item.bizType === bizType && item.releaseLevel === level) ??
    fallbackSlot(bizType, level, label)
  )
}

const groups = computed(() =>
  GROUPS.map((group) => ({
    ...group,
    items: LEVELS.map((level) => slotOf(group.bizType, level.level, level.label)),
  })),
)

const customCount = computed(() => slots.value.filter((slot) => !slot.isDefault).length)

async function load(): Promise<void> {
  loading.value = true
  pageError.value = null
  try {
    slots.value = (await listFlowBindings()) ?? []
  } catch (err) {
    pageError.value = {
      message: messageOf(err),
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function applySlot(next: FlowBindingSlot): void {
  const index = slots.value.findIndex(
    (item) => item.bizType === next.bizType && item.releaseLevel === next.releaseLevel,
  )
  if (index >= 0) {
    const copy = slots.value.slice()
    copy[index] = next
    slots.value = copy
  } else {
    slots.value = [...slots.value, next]
  }
}

/* ---------------- 更换定义 ---------------- */

const dialogOpen = ref(false)
const dialogSlot = ref<FlowBindingSlot | null>(null)
const dialogDefinitions = ref<WorkflowDefinitionRow[]>([])
const dialogDefinitionId = ref<number | null>(null)
const dialogLoading = ref(false)
const dialogSaving = ref(false)
const dialogError = ref('')
const dialogFieldError = ref('')

const dialogChain = computed(() => {
  const hit = dialogDefinitions.value.find((item) => item.id === dialogDefinitionId.value)
  return hit ?? null
})

async function openDialog(slot: FlowBindingSlot): Promise<void> {
  if (!canManage.value) return
  dialogSlot.value = slot
  dialogOpen.value = true
  dialogLoading.value = true
  dialogError.value = ''
  dialogFieldError.value = ''
  dialogDefinitions.value = []
  dialogDefinitionId.value = slot.definitionId ?? null
  try {
    const rows = (await listWorkflows(slot.bizType)) ?? []
    dialogDefinitions.value = rows.filter((row) => row.status === 'PUBLISHED')
    if (!dialogDefinitions.value.some((row) => row.id === dialogDefinitionId.value)) {
      dialogDefinitionId.value = dialogDefinitions.value.find((row) => row.isDefault)?.id ?? null
    }
  } catch (err) {
    dialogError.value = messageOf(err)
  } finally {
    dialogLoading.value = false
  }
}

async function saveDialog(): Promise<void> {
  const slot = dialogSlot.value
  if (!slot) return
  if (!dialogDefinitionId.value) {
    dialogFieldError.value = '请选择一个已发布的流程定义'
    return
  }
  dialogSaving.value = true
  dialogError.value = ''
  dialogFieldError.value = ''
  try {
    const next = await saveFlowBinding(slot.bizType, slot.releaseLevel, dialogDefinitionId.value)
    applySlot(next)
    dialogOpen.value = false
    ElMessage.success(`${slot.releaseLevelLabel || slot.releaseLevel} 已切换审批链`)
  } catch (err) {
    if (err instanceof ApiError && err.code === 'DEFINITION_NOT_PUBLISHED') {
      dialogFieldError.value = '该定义不是已发布状态，发布后才能绑定'
    } else if (err instanceof ApiError && err.code === 'BINDING_INVALID') {
      dialogFieldError.value = err.message || '绑定参数不合法（等级或单据类型）'
    } else {
      dialogError.value = messageOf(err)
    }
  } finally {
    dialogSaving.value = false
  }
}

/* ---------------- 恢复默认（删绑定行） ---------------- */

const resetTarget = ref<FlowBindingSlot | null>(null)
const resetOpen = ref(false)
const resetting = ref(false)
const resetError = ref('')

function askReset(slot: FlowBindingSlot): void {
  if (!canManage.value || slot.isDefault) return
  resetTarget.value = slot
  resetError.value = ''
  resetOpen.value = true
}

async function confirmReset(): Promise<void> {
  const slot = resetTarget.value
  if (!slot) return
  resetting.value = true
  try {
    const next = await resetFlowBinding(slot.bizType, slot.releaseLevel)
    applySlot(next)
    resetOpen.value = false
    ElMessage.success(`${slot.releaseLevelLabel || slot.releaseLevel} 已恢复默认链`)
  } catch (err) {
    resetError.value = messageOf(err)
  } finally {
    resetting.value = false
  }
}

const resetAllOpen = ref(false)
const resetAllSaving = ref(false)

async function confirmResetAll(): Promise<void> {
  resetAllSaving.value = true
  let done = 0
  try {
    for (const slot of slots.value.filter((item) => !item.isDefault)) {
      const next = await resetFlowBinding(slot.bizType, slot.releaseLevel)
      applySlot(next)
      done += 1
    }
    ElMessage.success(done > 0 ? `已恢复 ${done} 个槽的默认链` : '所有槽都已是默认链')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    resetAllSaving.value = false
    resetAllOpen.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="bindings">
    <header class="page-head">
      <div class="page-head__left">
        <div class="bindings__title">
          <h1 class="page-head__title">发布等级审批链</h1>
          <span class="bindings__count">已自定义 {{ customCount }} / 6</span>
        </div>
        <p class="page-head__sub">
          绑定只决定「发起时用哪条流程定义」，判定仍由审批引擎执行；改动不影响在途实例。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button
          size="small"
          :disabled="!canManage || customCount === 0"
          :title="canManage ? '' : manageHint"
          @click="resetAllOpen = true"
        >
          全部恢复默认
        </el-button>
      </div>
    </header>

    <ErrorState
      v-if="pageError"
      title="绑定配置加载失败"
      :message="pageError.message"
      :code="pageError.code"
      :status="pageError.status"
      @retry="load"
    />

    <section v-else v-loading="loading" class="bindings__body">
      <p v-if="!canManage" class="bindings__notice" role="status">
        当前账号只读：需要权限 workflow:manage 才能更换定义或恢复默认。
      </p>

      <div v-for="group in groups" :key="group.bizType" class="bind-group">
        <header class="bind-group__head">
          <h2 class="bind-group__title">{{ group.label }}</h2>
          <span class="mono bind-group__code">{{ group.bizType }}</span>
          <span class="bind-group__desc">{{ group.desc }}</span>
        </header>

        <div class="bind-group__grid">
          <article
            v-for="slot in group.items"
            :key="`${slot.bizType}-${slot.releaseLevel}`"
            class="bind-card"
            :class="{ 'is-default': slot.isDefault }"
          >
            <header class="bind-card__head">
              <div class="bind-card__level">
                <span class="bind-card__level-name">{{ slot.releaseLevelLabel || slot.releaseLevel }}</span>
                <span class="mono bind-card__level-code">{{ slot.releaseLevel }}</span>
              </div>
              <span class="bind-card__seal" :class="{ 'is-default': slot.isDefault }">
                {{ slot.isDefault ? '默认' : '自定义' }}
              </span>
            </header>

            <p class="bind-card__def" :title="slot.definitionName || '未绑定'">
              {{ slot.definitionName || '按默认定义解析' }}
              <span v-if="slot.definitionVersion" class="bind-card__ver">v{{ slot.definitionVersion }}</span>
            </p>
            <p class="bind-card__meta">
              <template v-if="slot.definitionId">定义 #{{ slot.definitionId }}</template>
              <template v-else>未配置绑定</template>
              · {{ slot.isDefault ? '删行即回落' : '显式绑定' }}
            </p>

            <ol v-if="(slot.nodes ?? []).length > 0" class="bind-card__nodes">
              <li v-for="(node, i) in slot.nodes ?? []" :key="node.key" class="bind-node">
                <span class="bind-node__no mono">{{ i + 1 }}</span>
                <span class="bind-node__name">{{ node.name }}</span>
                <span v-if="node.mode" class="bind-node__mode">{{ MODE_LABEL[node.mode] ?? node.mode }}</span>
              </li>
            </ol>
            <p v-else class="bind-card__nodes-empty">该定义还没有审批节点</p>

            <footer class="bind-card__foot">
              <el-button
                size="small"
                :disabled="!canManage"
                :title="canManage ? '' : manageHint"
                @click="openDialog(slot)"
              >
                更换定义
              </el-button>
              <el-button
                v-if="!slot.isDefault"
                size="small"
                :disabled="!canManage"
                :title="canManage ? '' : manageHint"
                @click="askReset(slot)"
              >
                恢复默认
              </el-button>
            </footer>
          </article>
        </div>
      </div>
    </section>

    <el-dialog v-model="dialogOpen" title="更换审批链定义" width="560px" append-to-body>
      <p class="bindings__dialog-hint">
        {{ dialogSlot ? `${dialogSlot.bizType} · ${dialogSlot.releaseLevelLabel || dialogSlot.releaseLevel}` : '' }}
        —— 只列出同单据类型、已发布的定义；保存后仅影响之后发起的实例。
      </p>

      <div v-if="dialogLoading" class="skeleton" style="height: 64px" />
      <el-select
        v-else
        v-model="dialogDefinitionId"
        class="bindings__select"
        placeholder="选择已发布的流程定义"
        :disabled="dialogDefinitions.length === 0"
        filterable
      >
        <el-option
          v-for="row in dialogDefinitions"
          :key="row.id"
          :value="row.id"
          :label="`${row.name} · v${row.version}${row.isDefault ? '（默认）' : ''}`"
        />
      </el-select>
      <p v-if="!dialogLoading && dialogDefinitions.length === 0" class="bindings__dialog-hint">
        该单据类型还没有已发布的流程定义：先到「流程定义」发布一条，再回来绑定。
      </p>
      <p v-if="dialogChain" class="bindings__dialog-chain">
        节点链：{{ dialogChain.name }} · v{{ dialogChain.version }}
      </p>
      <p v-if="dialogFieldError" class="bindings__field-error" role="alert">{{ dialogFieldError }}</p>
      <p v-if="dialogError" class="bindings__field-error" role="alert">{{ dialogError }}</p>

      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="dialogSaving"
          :disabled="dialogLoading || !dialogDefinitionId"
          @click="saveDialog"
        >
          保存绑定
        </el-button>
      </template>
    </el-dialog>

    <ConfirmDialog
      v-model="resetOpen"
      title="恢复默认审批链"
      :description="
        resetTarget
          ? `把 ${resetTarget.bizType} · ${resetTarget.releaseLevelLabel || resetTarget.releaseLevel} 恢复为默认链？`
          : ''
      "
      :impact="['会删除该槽的绑定行，之后发起的实例按默认定义解析', '在途实例不受影响']"
      :reason-required="false"
      confirm-text="恢复默认"
      :loading="resetting"
      @confirm="confirmReset"
      @cancel="resetOpen = false"
    />
    <p v-if="resetError" class="bindings__field-error" role="alert">{{ resetError }}</p>

    <ConfirmDialog
      v-model="resetAllOpen"
      title="全部恢复默认"
      :description="`把 ${customCount} 个已自定义的槽全部恢复默认链？`"
      :impact="['只删除自定义绑定，默认链本身不会被改动', '在途实例不受影响']"
      :reason-required="false"
      confirm-text="全部恢复"
      :loading="resetAllSaving"
      @confirm="confirmResetAll"
      @cancel="resetAllOpen = false"
    />
  </div>
</template>

<style scoped>
.bindings {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bindings__title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bindings__count {
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 11px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.bindings__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}
.bindings__notice {
  margin: 0;
  padding: 6px 10px;
  border: 1px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 18px;
}
.bind-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bind-group__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.bind-group__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.bind-group__code {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.bind-group__desc {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.bind-group__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.bind-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  min-height: 156px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
/* 默认槽只加 1px 主色边 + 印章：不换底、不阴影，避免“默认=告警” */
.bind-card.is-default {
  border-color: var(--color-primary);
}
.bind-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.bind-card__level {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.bind-card__level-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.bind-card__level-code {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.bind-card__seal {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  font-size: 11px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.bind-card__seal.is-default {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.bind-card__def {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  line-height: 18px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bind-card__ver {
  flex: none;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.bind-card__meta {
  margin: 0;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-muted-aa);
}
.bind-card__nodes {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 2px 0 0;
  padding: 0;
  list-style: none;
}
.bind-node {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 3px 6px;
  border: 1px solid var(--divider);
  border-radius: var(--r-cell);
  background: var(--bg-hover);
}
.bind-node__no {
  flex: none;
  width: 16px;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.bind-node__name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bind-node__mode {
  flex: none;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.bind-card__nodes-empty {
  flex: 1;
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.bind-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--divider);
}
.bindings__dialog-hint {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.bindings__select {
  width: 100%;
}
.bindings__dialog-chain {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-body);
}
.bindings__field-error {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--danger-ink);
}
@media (max-width: 1100px) {
  .bind-group__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .bind-group__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
