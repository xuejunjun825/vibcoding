<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import StatusPill from '@/components/StatusPill.vue'
import { confirmMerge, syncMr, type MrCard } from '@/api/integration'
import { formatDateTime } from '@/utils/format'

/**
 * 需求详情「集成」tab 的代码卡（DESIGN I4 / AC-4）。
 *
 * 首屏只读父级一次载荷里的 MrCard，不在这里发任何请求；「刷新」「保存」「确认推进」
 * 都是用户显式动作。merged 推进默认人工确认（mrAutoAdvance 默认 false 是业务红线）。
 */
const props = withDefaults(
  defineProps<{
    requirementId: number
    card: MrCard | null
    loading?: boolean
    editable?: boolean
    fallbackUrl?: string
  }>(),
  { loading: false, editable: true, fallbackUrl: '' },
)

const emit = defineEmits<{
  (event: 'updated', card: MrCard): void
}>()

const editing = ref(false)
const draftUrl = ref('')
const saving = ref(false)
const refreshing = ref(false)
const confirming = ref(false)
const errorText = ref('')
const orderNotice = ref(false)

watch(
  () => props.card,
  (card) => {
    if (!editing.value) draftUrl.value = card?.mrUrl || props.fallbackUrl || ''
  },
  { immediate: true },
)

const mrUrl = computed(() => props.card?.mrUrl || props.fallbackUrl || '')
const hasMr = computed(() => Boolean(mrUrl.value))
const merged = computed(() => props.card?.state === 'MERGED')

const stateText = computed(() => {
  // 聚合端点恒返回对象（未绑定 MR 时是空卡且 stale=true），所以"过期"只在真有链接时提示，
  // 否则空态会被误标成"状态可能过期"。
  if (!props.card || !hasMr.value) return ''
  if (props.card.stale) return '状态可能过期'
  return ''
})

function startEdit(): void {
  draftUrl.value = mrUrl.value
  editing.value = true
  errorText.value = ''
}

function cancelEdit(): void {
  editing.value = false
  errorText.value = ''
  draftUrl.value = mrUrl.value
}

async function save(): Promise<void> {
  const url = draftUrl.value.trim()
  if (!url) {
    errorText.value = '请粘贴 GitLab MR 链接，如 https://gitlab.example.com/group/repo/-/merge_requests/128'
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    const card = await syncMr(props.requirementId, url)
    emit('updated', card)
    editing.value = false
    ElMessage.success('已同步 MR 状态')
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '同步失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

async function refresh(): Promise<void> {
  if (!mrUrl.value) return
  refreshing.value = true
  errorText.value = ''
  const before = props.card?.lastEventAt ? Date.parse(props.card.lastEventAt) : 0
  try {
    const card = await syncMr(props.requirementId, mrUrl.value)
    const after = card.lastEventAt ? Date.parse(card.lastEventAt) : 0
    // 服务端有单调水位：刷新拿到更早的水位说明这次事件被判 OUT_OF_ORDER、没有覆盖现状
    orderNotice.value = before > 0 && after > 0 && after < before
    emit('updated', card)
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '刷新失败，请稍后重试'
  } finally {
    refreshing.value = false
  }
}

async function advance(): Promise<void> {
  confirming.value = true
  errorText.value = ''
  try {
    const result = await confirmMerge(props.requirementId)
    ElMessage.success(result.advanced ? '已推进到「已合入」' : '当前状态不允许推进，已记录')
    if (props.card) emit('updated', { ...props.card, state: 'MERGED' })
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '推进失败，请稍后重试'
  } finally {
    confirming.value = false
  }
}

function dismissNotice(): void {
  orderNotice.value = false
}

function shortSha(value: string): string {
  return value.length > 40 ? `${value.slice(0, 40)}…` : value
}
</script>

<template>
  <section class="code-card" aria-labelledby="code-card-title">
    <header class="code-card__head">
      <h3 id="code-card-title" class="code-card__title">代码</h3>
      <span v-if="stateText" class="code-card__stale">{{ stateText }}</span>
      <span class="code-card__spacer" />
      <el-button
        v-if="hasMr && !editing"
        size="small"
        :loading="refreshing"
        @click="refresh"
      >
        刷新
      </el-button>
      <el-button v-if="editable && !editing" size="small" @click="startEdit">
        {{ hasMr ? '修改 MR' : '关联 MR' }}
      </el-button>
    </header>

    <div v-if="editing" class="code-card__editor">
      <el-input
        v-model="draftUrl"
        size="small"
        placeholder="https://gitlab.example.com/group/repo/-/merge_requests/128"
        @keyup.enter="save"
      />
      <div class="code-card__editor-actions">
        <el-button size="small" :disabled="saving" @click="cancelEdit">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
      <p v-if="errorText" class="code-card__error" role="alert">{{ errorText }}</p>
    </div>

    <template v-else-if="hasMr && card">
      <div class="code-card__main">
        <a
          v-if="card.mrUrl"
          class="code-card__link"
          :href="card.mrUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ card.title || '（无标题 MR）' }}
        </a>
        <span v-else class="code-card__link code-card__link--plain">{{ card.title || '（无标题 MR）' }}</span>
        <StatusPill v-if="card.state" kind="mr" :value="card.state" />
        <span v-else class="code-card__unstructured">未结构化（只存了链接）</span>
      </div>

      <div class="code-card__meta">
        <span class="code-card__meta-item">
          <span class="code-card__meta-label">批准</span>
          <span class="mono">{{ card.approvalsCount ?? 0 }}</span>
        </span>
        <span class="code-card__meta-item">
          <span class="code-card__meta-label">分支</span>
          <span class="mono code-card__branch">{{ card.sourceBranch || '—' }} → {{ card.targetBranch || '—' }}</span>
        </span>
        <span class="code-card__meta-item">
          <span class="code-card__meta-label">变更</span>
          <span class="code-card__diff">+{{ card.additions ?? 0 }} −{{ card.deletions ?? 0 }}</span>
        </span>
        <span class="code-card__meta-item">
          <span class="code-card__meta-label">最后同步</span>
          <span>{{ card.lastEventAt ? formatDateTime(card.lastEventAt) : '—' }}</span>
        </span>
      </div>

      <div v-if="mrUrl" class="code-card__url mono" :title="mrUrl">{{ shortSha(mrUrl) }}</div>

      <div v-if="merged" class="code-card__merged" role="status">
        <span>MR 已合入，需求仍停在编码阶段——推进动作需要人工确认。</span>
        <el-button size="small" type="warning" :loading="confirming" @click="advance">
          确认推进
        </el-button>
        <el-button size="small" text @click="dismissNotice">暂不</el-button>
      </div>

      <p v-if="orderNotice" class="code-card__notice">
        已忽略更早的事件（OUT_OF_ORDER）：MR 状态只允许向前推进，较旧的 webhook 不会把已合入打回。
      </p>
      <p v-if="errorText" class="code-card__error" role="alert">{{ errorText }}</p>
    </template>

    <div v-else-if="loading" class="code-card__empty">正在读取代码信息…</div>
    <div v-else class="code-card__empty">
      尚未关联 MR。粘贴 GitLab MR 链接后，这里会显示标题、状态、分支与变更行数。
      <p class="code-card__empty-hint">未关联不影响准入工作，但「MR 已填」一项会记录为失败。</p>
    </div>
  </section>
</template>

<style scoped>
.code-card {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-surface);
  padding: 14px 16px;
}
.code-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
.code-card__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.code-card__stale {
  color: var(--warning-ink);
  font-size: 11px;
}
.code-card__unstructured {
  flex: none;
  color: var(--text-weak);
  font-size: 11px;
}
.code-card__spacer {
  flex: 1;
}
.code-card__editor {
  margin-top: 10px;
}
.code-card__editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.code-card__main {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  min-width: 0;
}
.code-card__link {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-title);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}
.code-card__link:hover {
  color: var(--color-primary, #2f6fed);
  text-decoration: underline;
}
.code-card__link--plain {
  cursor: default;
}
.code-card__link--plain:hover {
  color: var(--text-title);
  text-decoration: none;
}
.code-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-body);
}
.code-card__meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.code-card__meta-label {
  color: var(--text-weak);
}
.code-card__branch {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.code-card__diff {
  font-variant-numeric: tabular-nums;
  color: var(--text-body);
}
.code-card__url {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-weak);
  overflow-wrap: anywhere;
}
.code-card__merged {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 8px 10px;
  border: 1px solid #f0dcae;
  border-left: 3px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.code-card__merged > span {
  flex: 1;
  min-width: 0;
}
.code-card__notice {
  margin: 8px 0 0;
  color: var(--text-weak);
  font-size: 11px;
}
.code-card__error {
  margin: 8px 0 0;
  color: var(--danger-ink);
  font-size: 12px;
}
.code-card__empty {
  margin-top: 10px;
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 20px;
}
.code-card__empty-hint {
  margin: 4px 0 0;
  color: var(--text-weak);
  font-size: 11px;
}
</style>
