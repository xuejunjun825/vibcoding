<script setup lang="ts">
/**
 * 测试数据说明卡（DESIGN T2 · AC-16/AC-17）。
 *
 * - 正文上限 2000 字，计数器常驻；超限直接拦在输入层（服务端也会 400 DATA_NOTE_TOO_LONG）。
 * - 计划 COMPLETED 后锁定：卡片顶部锁条 + 全字段只读，不允许「看起来能改」。
 * - 并发用 revision：别人先保存过就 409 DATA_NOTE_CONFLICT，提示重新载入而不是覆盖。
 * - 附件最多 5 个，走 /attachments（bizType=TEST_PLAN）；审计只记长度与附件数。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import { DATA_NOTE_MAX, fetchDataNote, saveDataNote } from '@/api/deliver'
import type { TestDataNote } from '@/api/deliver'
import { deleteAttachment, downloadAttachment, uploadAttachment } from '@/api/req'
import type { AttachmentRow } from '@/api/types'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, formatNumber } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'

const props = defineProps<{
  planId: number
  planStatus?: string | null
}>()

const MAX_ATTACHMENTS = 5

const { can } = usePermission()
const canEdit = computed(() => can('plan:manage') || can('test:execute'))

const note = ref<TestDataNote | null>(null)
const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const draft = ref('')
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const inlineError = ref('')
const conflict = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const locked = computed(() => Boolean(note.value?.locked) || props.planStatus === 'COMPLETED')
const readonly = computed(() => locked.value || !canEdit.value)

const used = computed(() => draft.value.length)
const overLimit = computed(() => used.value > DATA_NOTE_MAX)
const attachments = computed<AttachmentRow[]>(() => note.value?.attachments ?? [])
const canAddAttachment = computed(() => attachments.value.length < MAX_ATTACHMENTS && !readonly.value)

const displayText = computed(() => (note.value?.dataNote ?? '').trim())

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  conflict.value = false
  try {
    note.value = await fetchDataNote(props.planId)
    draft.value = note.value.dataNote ?? ''
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message || '测试数据说明加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
  } finally {
    loading.value = false
  }
}

function startEdit(): void {
  if (readonly.value) return
  draft.value = note.value?.dataNote ?? ''
  inlineError.value = ''
  editing.value = true
}

function cancelEdit(): void {
  draft.value = note.value?.dataNote ?? ''
  inlineError.value = ''
  editing.value = false
}

async function save(): Promise<void> {
  if (readonly.value) return
  if (overLimit.value) {
    inlineError.value = `超出 ${used.value - DATA_NOTE_MAX} 字，请先精简到 ${DATA_NOTE_MAX} 字以内。`
    return
  }
  saving.value = true
  inlineError.value = ''
  conflict.value = false
  try {
    const saved = await saveDataNote(props.planId, {
      dataNote: draft.value,
      revision: note.value?.revision ?? 0,
    })
    note.value = saved
    draft.value = saved.dataNote ?? ''
    editing.value = false
    ElMessage.success('测试数据说明已保存')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    const code = apiError?.code ?? ''
    if (code === 'DATA_NOTE_CONFLICT' || apiError?.status === 409) {
      conflict.value = true
      inlineError.value = '有人先保存过这页说明，你的草稿没有覆盖对方。载入最新内容后再改。'
      return
    }
    if (code === 'PLAN_DATA_LOCKED') {
      inlineError.value = '计划已完成，测试数据说明已锁定。'
      await load()
      return
    }
    if (code === 'DATA_NOTE_TOO_LONG') {
      inlineError.value = `测试数据说明最多 ${DATA_NOTE_MAX} 字。`
      return
    }
    inlineError.value = apiError?.message || '保存失败，草稿已保留'
  } finally {
    saving.value = false
  }
}

function pickFile(): void {
  if (!canAddAttachment.value) return
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploading.value = true
  inlineError.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('bizType', 'TEST_PLAN')
    form.append('bizId', String(props.planId))
    await uploadAttachment(form)
    note.value = await fetchDataNote(props.planId)
    ElMessage.success('附件已上传')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    inlineError.value = apiError?.message || '附件上传失败'
  } finally {
    uploading.value = false
  }
}

async function removeAttachment(row: AttachmentRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除附件「${row.fileName}」？删除后无法恢复。`, '删除附件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteAttachment(row.id)
    note.value = await fetchDataNote(props.planId)
    ElMessage.success('附件已删除')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    inlineError.value = apiError?.message || '附件删除失败'
  }
}

function download(row: AttachmentRow): void {
  void downloadAttachment(row.id, row.fileName)
}

watch(
  () => props.planId,
  () => {
    editing.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section class="dn-card">
    <header class="dn-head">
      <div>
        <h3 class="dn-title">测试数据说明</h3>
        <p class="dn-sub">执行前需要准备的数据、账号与清理方式；随计划留档，审计只记长度与附件数。</p>
      </div>
      <div class="dn-head__actions">
        <el-button v-if="!editing && !readonly" size="small" :disabled="loading" @click="startEdit">编辑</el-button>
      </div>
    </header>

    <div v-if="locked" class="dn-lock">
      <span class="dn-lock__icon" aria-hidden="true">🔒</span>
      <span>计划已完成，测试数据说明已锁定</span>
      <span class="dn-lock__hint">需要修改请先重新打开计划（会产生审计记录）。</span>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <div v-else-if="loading && !note" class="dn-skeleton" aria-hidden="true">
      <div class="dn-skeleton__line" />
      <div class="dn-skeleton__line dn-skeleton__line--short" />
    </div>

    <template v-else>
      <div v-if="conflict" class="dn-conflict" role="alert">
        {{ inlineError }}
        <el-button link type="primary" size="small" @click="load">载入最新内容</el-button>
      </div>
      <p v-else-if="inlineError" class="dn-error" role="alert">{{ inlineError }}</p>

      <div v-if="editing" class="dn-body">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="7"
          resize="vertical"
          placeholder="例如：预置 3 个测试账号（qa01/qa02/qa03，密码见 1Password「qa-fixtures」）；执行前清空 t_order 表中 tenant=demo 的数据…"
        />
        <div class="dn-count" :class="{ 'is-over': overLimit }">
          {{ formatNumber(used) }} / {{ DATA_NOTE_MAX }} 字
          <span v-if="overLimit" class="dn-count__over">超出 {{ used - DATA_NOTE_MAX }} 字</span>
        </div>
        <div class="dn-editor__actions">
          <el-button size="small" @click="cancelEdit">取消</el-button>
          <el-button size="small" type="primary" :loading="saving" :disabled="overLimit" @click="save">
            保存说明
          </el-button>
        </div>
      </div>

      <div v-else class="dn-read">
        <p v-if="displayText" class="dn-text">{{ displayText }}</p>
        <EmptyState
          v-else
          title="还没有测试数据说明"
          :description="readonly ? '这份计划还没有写测试数据说明。' : '写清执行前要准备什么、执行后怎么清理，下一个人就不用猜。'"
          icon="file"
          compact
          :action-text="readonly ? undefined : '补充说明'"
          @action="startEdit"
        />
      </div>

      <div class="dn-attachments">
        <div class="dn-attachments__head">
          <span class="dn-attachments__title">附件</span>
          <span class="dn-hint">{{ attachments.length }} / {{ MAX_ATTACHMENTS }} · 测试数据文件、账号表、清理脚本</span>
          <el-button v-if="canAddAttachment" link type="primary" size="small" :loading="uploading" @click="pickFile">
            上传附件
          </el-button>
          <input ref="fileInput" type="file" class="dn-file" @change="onFileChange" />
        </div>
        <ul v-if="attachments.length" class="dn-files">
          <li v-for="row in attachments" :key="row.id" class="dn-file-row">
            <button type="button" class="dn-file-row__name" @click="download(row)">
              {{ row.fileName }}
            </button>
            <span class="dn-file-row__meta">
              {{ row.size ? `${formatNumber(Math.round(row.size / 1024))} KB` : '—' }}
              <template v-if="row.createdAt"> · {{ formatDateTime(row.createdAt) }}</template>
            </span>
            <el-button v-if="!readonly" link type="danger" size="small" @click="removeAttachment(row)">删除</el-button>
          </li>
        </ul>
        <p v-else class="dn-hint">还没有附件。</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.dn-card {
  width: 100%;
  max-width: 704px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-card, 8px);
  background: var(--bg-card, #fff);
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dn-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dn-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.dn-sub {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa, #6b7280);
}
.dn-lock {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-info-bg, #f2f3f5);
  color: var(--info-ink, #4e5969);
  font-size: 12px;
  border-left: 3px solid var(--border-strong, #c9cdd4);
}
.dn-lock__hint {
  color: var(--text-muted-aa, #6b7280);
}
.dn-conflict {
  padding: 8px 10px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-warning-bg, #fff7e6);
  color: var(--warning-ink, #9a5b00);
  font-size: 12px;
  line-height: 18px;
  border-left: 3px solid var(--color-warning, #c77700);
}
.dn-error {
  margin: 0;
  padding: 8px 10px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-danger-bg, #fdecec);
  color: var(--danger-ink, #b3262e);
  font-size: 12px;
  line-height: 18px;
}
.dn-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: var(--text-body, #4e5969);
  white-space: pre-wrap;
  word-break: break-word;
}
.dn-count {
  text-align: right;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}
.dn-count.is-over {
  color: var(--danger-ink, #b3262e);
}
.dn-count__over {
  margin-left: 6px;
}
.dn-editor__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.dn-attachments {
  border-top: 1px dashed var(--border, #e5e6eb);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dn-attachments__head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dn-attachments__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}
.dn-hint {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  margin: 0;
}
.dn-file {
  display: none;
}
.dn-files {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dn-file-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px 48px;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border-radius: var(--r-ctl, 6px);
  transition: background var(--t-color, 120ms) ease;
}
.dn-file-row:hover {
  background: var(--bg-hover, #f7f9fc);
}
.dn-file-row__name {
  border: none;
  background: none;
  padding: 0;
  text-align: left;
  font-size: 13px;
  color: var(--color-primary, #2b6fe5);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dn-file-row__meta {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  text-align: right;
}
.dn-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dn-skeleton__line {
  height: 12px;
  border-radius: 4px;
  background: linear-gradient(90deg, #f2f3f5 25%, #e9ebef 37%, #f2f3f5 63%);
  background-size: 400% 100%;
  animation: dn-shimmer 1.2s ease infinite;
}
.dn-skeleton__line--short {
  width: 62%;
}
@keyframes dn-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
</style>
