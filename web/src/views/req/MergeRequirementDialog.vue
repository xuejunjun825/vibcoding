<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { searchRequirements } from '@/api/req'
import { listSimilarForRequirement, mergeRequirement } from '@/api/poolOps'
import { ApiError } from '@/api/client'
import type {
  RequirementDetail,
  RequirementMergeResult,
  RequirementRow,
  SimilarRequirementItem,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import { messageOf, typeLabel } from '@/views/req/platform'

/**
 * r47 · 合并重复需求弹层。
 *
 * 目标有两种选法：搜编号/标题，或直接从「相似需求」里点一条。
 * 提交只认 id（后端 `POST /requirements/{id}/merge {targetId, reason}`），
 * 页面不猜 code→id 的映射；搜不到就不许提交。
 *
 * 错误码逐个给中文：合并是**不可逆**的写操作，不能让用户看着 400 猜原因。
 */
const props = defineProps<{
  modelValue: boolean
  requirement: RequirementDetail | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'merged', result: RequirementMergeResult): void
}>()

interface MergeTarget {
  id: number
  code: string
  title: string
  status: string
  type: string
}

const keyword = ref('')
const reason = ref('')
const selected = ref<MergeTarget | null>(null)
const candidates = ref<RequirementRow[]>([])
const similar = ref<SimilarRequirementItem[]>([])
const searching = ref(false)
const submitting = ref(false)
const error = ref('')

const SEARCH_DEBOUNCE_MS = 300
let searchTimer: ReturnType<typeof setTimeout> | null = null
let searchSeq = 0

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const canSubmit = computed(
  () =>
    selected.value != null &&
    selected.value.id !== props.requirement?.id &&
    reason.value.trim().length >= 2 &&
    !submitting.value,
)

const submitHint = computed(() => {
  if (selected.value == null) return '先选择要合并到的目标需求'
  if (selected.value.id === props.requirement?.id) return '不能把需求合并到它自己'
  if (reason.value.trim().length < 2) return '填写合并原因（至少 2 个字）'
  return ''
})

function reset(): void {
  keyword.value = ''
  reason.value = ''
  selected.value = null
  candidates.value = []
  similar.value = []
  error.value = ''
  searching.value = false
}

const MERGE_ERROR_TEXT: Record<string, string> = {
  ALREADY_MERGED: '该需求已经并入其它需求，不能重复合并',
  TARGET_MERGED: '目标需求本身已被合并，请改选它并入的主需求',
  MERGE_CYCLE: '不能合并到自身或其子需求（会形成循环）',
  REQUIREMENT_CLOSED: '需求已关闭，不能再合并',
  VALIDATION_FAILED: '合并信息不完整或目标不存在，请检查编号后重试',
  FORBIDDEN: '没有合并重复需求的权限',
}

function errorTextOf(cause: unknown): string {
  if (cause instanceof ApiError && cause.code && MERGE_ERROR_TEXT[cause.code]) {
    return MERGE_ERROR_TEXT[cause.code]
  }
  return messageOf(cause)
}

async function loadSimilar(): Promise<void> {
  const source = props.requirement
  if (!source) return
  try {
    const page = await listSimilarForRequirement(source.id, 5)
    similar.value = (page.items ?? []).filter((item) => item.id !== source.id)
  } catch {
    similar.value = []
  }
}

function pick(row: MergeTarget): void {
  if (props.requirement && row.id === props.requirement.id) return
  selected.value = { id: row.id, code: row.code, title: row.title, status: row.status, type: row.type }
  error.value = ''
}

async function runSearch(): Promise<void> {
  const q = keyword.value.trim()
  if (q.length === 0) {
    candidates.value = []
    return
  }
  const seq = ++searchSeq
  searching.value = true
  try {
    const rows = await searchRequirements(q)
    if (seq !== searchSeq) return
    candidates.value = (rows ?? []).filter((row) => row.id !== props.requirement?.id)
    // 编号精确命中时直接选中，省一次点击
    const exact = candidates.value.find((row) => row.code?.toLowerCase() === q.toLowerCase())
    if (exact) pick(exact)
  } catch {
    if (seq !== searchSeq) return
    candidates.value = []
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

function scheduleSearch(): void {
  if (searchTimer != null) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    searchTimer = null
    void runSearch()
  }, SEARCH_DEBOUNCE_MS)
}

watch(keyword, scheduleSearch)
watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      reset()
      void loadSimilar()
    } else if (searchTimer != null) {
      clearTimeout(searchTimer)
      searchTimer = null
    }
  },
)

async function submit(): Promise<void> {
  const target = selected.value
  if (!canSubmit.value || !target || !props.requirement) return
  submitting.value = true
  error.value = ''
  try {
    const result = await mergeRequirement(props.requirement.id, {
      targetId: target.id,
      reason: reason.value.trim(),
    })
    ElMessage.success(
      `已并入 ${target.code}：迁入 ${result.movedFollows} 人跟单、${result.movedChildren} 个子需求`,
    )
    emit('merged', result)
    open.value = false
  } catch (cause) {
    error.value = errorTextOf(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="合并重复需求" width="560px" append-to-body>
    <div class="merge">
      <p class="merge__lead">
        把「{{ requirement?.code }} · {{ requirement?.title }}」并入主需求：当前需求将关闭，
        跟单人与子需求迁入目标，操作不可撤销。
      </p>

      <label class="merge__label">合并到</label>
      <el-input
        v-model="keyword"
        placeholder="输入目标需求编号或标题搜索"
        clearable
      />
      <p v-if="searching" class="merge__muted">正在搜索…</p>
      <ul v-else-if="candidates.length" class="merge__list">
        <li v-for="row in candidates" :key="row.id">
          <button
            type="button"
            class="merge__item"
            :class="{ 'is-selected': selected?.id === row.id }"
            @click="pick(row)"
          >
            <span class="merge__code">{{ row.code }}</span>
            <span class="merge__title">{{ row.title }}</span>
            <StatusPill kind="requirement" :value="row.status" size="sm" />
            <span class="merge__type">{{ typeLabel(row.type) }}</span>
          </button>
        </li>
      </ul>

      <div v-if="selected" class="merge__selected">
        <span class="merge__label">目标</span>
        <span class="merge__code">{{ selected.code }}</span>
        <span class="merge__title">{{ selected.title }}</span>
        <button type="button" class="merge__clear" @click="selected = null">重选</button>
      </div>

      <template v-if="similar.length && !selected">
        <span class="merge__label">相似需求</span>
        <ul class="merge__list">
          <li v-for="item in similar" :key="item.id">
            <button type="button" class="merge__item" @click="pick(item)">
              <span class="merge__code">{{ item.code }}</span>
              <span class="merge__title">{{ item.title }}</span>
              <StatusPill kind="requirement" :value="item.status" size="sm" />
              <span class="merge__follow">{{ item.followCount }} 人需要</span>
            </button>
          </li>
        </ul>
      </template>

      <label class="merge__label">合并原因</label>
      <el-input
        v-model="reason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="例如：与 REQ-… 描述同一诉求，保留主需求继续跟进"
      />

      <p v-if="error" class="merge__error">{{ error }}</p>
      <p v-else-if="submitHint" class="merge__muted">{{ submitHint }}</p>
    </div>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button
        type="danger"
        :disabled="!canSubmit"
        :loading="submitting"
        :title="submitHint"
        @click="submit"
      >
        确认合并
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.merge {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.merge__lead {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-body);
}
.merge__label {
  margin-top: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-title);
}
.merge__muted {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.merge__list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 190px;
  overflow-y: auto;
}
.merge__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
}
.merge__item:hover {
  background: var(--bg-hover);
}
.merge__item.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.merge__code {
  flex: 0 0 auto;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.merge__title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-title);
}
.merge__type,
.merge__follow {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.merge__selected {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 6px 8px;
  border: 1px solid var(--color-primary);
  border-radius: var(--r-cell);
  background: var(--color-primary-light);
}
.merge__clear {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}
.merge__error {
  margin: 4px 0 0;
  padding: 6px 10px;
  border-left: 2px solid var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
</style>
