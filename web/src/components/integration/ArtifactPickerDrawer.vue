<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  searchArtifacts,
  type ArtifactItem,
  type ArtifactManualRef,
  type ArtifactSearchResult,
} from '@/api/integration'

/**
 * 制品检索抽屉（DESIGN I6 / §6.5，AC-7）。
 *
 * - 检索是交互读：用户输入才发请求（首屏零外呼），300ms 防抖，最多 50 条；
 * - Nexus 不可用时**不关闭抽屉、不清空输入**，顶部降级条说明原因并可重试；
 * - 手工登记与 Nexus 检索是两个 tab：手工登记明确标注“准入第 9 项跳过”。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    orderId: number
  }>(),
  { orderId: 0 },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'select', item: ArtifactItem): void
  (event: 'manual', item: ArtifactManualRef): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

type Tab = 'nexus' | 'manual'
const tab = ref<Tab>('nexus')

const keyword = ref('')
const repository = ref('')
const searching = ref(false)
const searchError = ref('')
const result = ref<ArtifactSearchResult | null>(null)
const selected = ref<ArtifactItem | null>(null)

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function scheduleSearch(): void {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (keyword.value.trim().length < 2) {
    result.value = null
    searchError.value = ''
    return
  }
  debounceTimer = setTimeout(() => {
    void runSearch()
  }, 300)
}

async function runSearch(): Promise<void> {
  const query = keyword.value.trim()
  if (query.length < 2) return
  searching.value = true
  searchError.value = ''
  try {
    result.value = await searchArtifacts(query, repository.value.trim() || undefined, 50)
    selected.value = null
  } catch (err) {
    // 降级：保留上一次结果，页面继续可用
    searchError.value = err instanceof ApiError ? err.message : '检索失败，请稍后重试'
  } finally {
    searching.value = false
  }
}

watch(
  () => props.modelValue,
  (value) => {
    if (!value) return
    selected.value = null
    searchError.value = ''
    result.value = null
    keyword.value = ''
    repository.value = ''
    tab.value = 'nexus'
  },
)

const items = computed<ArtifactItem[]>(() => result.value?.items ?? [])
const degraded = computed(() => Boolean(result.value?.degraded))

function pick(item: ArtifactItem): void {
  selected.value = item
}

function confirmSelect(): void {
  if (!selected.value) return
  emit('select', selected.value)
  open.value = false
}

function shortSha(sha?: string | null): string {
  return sha ? sha.slice(0, 12) : '—'
}

function formatSize(bytes?: number | null): string {
  if (bytes == null) return '—'
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${bytes} B`
}

/* ---------------- 手工登记 ---------------- */
const manual = ref<ArtifactManualRef>({
  repo: '',
  groupId: '',
  artifactName: '',
  version: '',
  packaging: 'jar',
  sha256: '',
})
const manualError = ref('')

function submitManual(): void {
  manualError.value = ''
  if (!manual.value.repo.trim() || !manual.value.artifactName.trim() || !manual.value.version.trim()) {
    manualError.value = '仓库、制品名与版本必填'
    return
  }
  if (!/^[a-fA-F0-9]{64}$/.test(manual.value.sha256.trim())) {
    manualError.value = 'sha256 必须是 64 位十六进制字符串'
    return
  }
  emit('manual', {
    repo: manual.value.repo.trim(),
    groupId: manual.value.groupId.trim(),
    artifactName: manual.value.artifactName.trim(),
    version: manual.value.version.trim(),
    packaging: manual.value.packaging?.trim() || undefined,
    sha256: manual.value.sha256.trim().toLowerCase(),
  })
  ElMessage.success('已登记手工制品；准入第 9 项将记为跳过')
  open.value = false
}
</script>

<template>
  <el-drawer
    v-model="open"
    :size="720"
    title="选择制品"
    append-to-body
    destroy-on-close
    class="artifact-drawer"
  >
    <template #default>
    <div class="artifact-drawer__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        class="artifact-drawer__tab"
        :class="{ 'is-active': tab === 'nexus' }"
        :aria-selected="tab === 'nexus'"
        @click="tab = 'nexus'"
      >
        Nexus 检索
      </button>
      <button
        type="button"
        role="tab"
        class="artifact-drawer__tab"
        :class="{ 'is-active': tab === 'manual' }"
        :aria-selected="tab === 'manual'"
        @click="tab = 'manual'"
      >
        手工登记
      </button>
    </div>

    <template v-if="tab === 'nexus'">
      <div class="artifact-drawer__search">
        <el-input
          v-model="keyword"
          size="small"
          placeholder="输入制品名（≥2 字符），如 order-service"
          clearable
          @update:model-value="scheduleSearch"
          @keyup.enter="runSearch"
        />
        <el-input
          v-model="repository"
          size="small"
          class="artifact-drawer__repo"
          placeholder="仓库（可选）"
          clearable
        />
        <el-button size="small" :loading="searching" @click="runSearch">搜索</el-button>
      </div>

      <div v-if="degraded" class="artifact-drawer__degraded" role="status">
        <span>Nexus 暂不可用，已降级：{{ result?.reason || '检索结果可能不完整' }}</span>
        <el-button link type="primary" size="small" @click="runSearch">重试</el-button>
      </div>
      <div v-if="searchError" class="artifact-drawer__error" role="alert">
        <span>{{ searchError }}</span>
        <el-button link type="primary" size="small" @click="runSearch">重试</el-button>
      </div>

      <div v-if="searching && !items.length" class="artifact-drawer__skeleton">
        <span v-for="index in 6" :key="index" class="artifact-drawer__bar" />
      </div>

      <table v-else-if="items.length" class="artifact-table">
        <thead>
          <tr>
            <th class="artifact-table__pick" />
            <th>名称</th>
            <th>版本</th>
            <th>打包</th>
            <th class="artifact-table__num">大小</th>
            <th>sha256</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="`${item.repository}:${item.groupId}:${item.artifactName}:${item.version}`"
            class="artifact-table__row"
            :class="{ 'is-selected': selected?.artifactName === item.artifactName && selected?.version === item.version }"
            @click="pick(item)"
          >
            <td class="artifact-table__pick">
              <input
                type="radio"
                :checked="selected?.artifactName === item.artifactName && selected?.version === item.version"
                :aria-label="`选择 ${item.artifactName} ${item.version}`"
                @change="pick(item)"
              />
            </td>
            <td>
              <span class="artifact-table__name">{{ item.artifactName }}</span>
              <span class="artifact-table__group mono">{{ item.groupId }}</span>
            </td>
            <td class="mono">{{ item.version }}</td>
            <td class="artifact-table__muted">{{ item.packaging || '—' }}</td>
            <td class="artifact-table__num">{{ formatSize(item.sizeBytes) }}</td>
            <td class="mono artifact-table__sha" :title="item.sha256 ?? undefined">{{ shortSha(item.sha256) }}</td>
          </tr>
        </tbody>
      </table>

      <div v-else-if="keyword.trim().length >= 2 && !searching" class="artifact-drawer__empty">
        没有匹配的制品；换个关键词，或切到「手工登记」。
      </div>
      <div v-else class="artifact-drawer__empty">
        输入制品名开始检索。Nexus 不可用时列表为空并给出原因，不会让页面白屏。
      </div>
    </template>

    <template v-else>
      <p class="artifact-drawer__hint">
        手工登记只记录你填写的坐标与 sha256，服务端不会去 Nexus 校验；准入第 9 项记为 SKIP，不等于通过。
      </p>
      <el-form label-position="top" size="small" class="artifact-form">
        <div class="artifact-form__row">
          <el-form-item label="仓库" required>
            <el-input v-model="manual.repo" placeholder="如 nexus-releases" />
          </el-form-item>
          <el-form-item label="groupId" required>
            <el-input v-model="manual.groupId" placeholder="com.example" />
          </el-form-item>
        </div>
        <div class="artifact-form__row">
          <el-form-item label="制品名" required>
            <el-input v-model="manual.artifactName" placeholder="order-service" />
          </el-form-item>
          <el-form-item label="版本" required>
            <el-input v-model="manual.version" placeholder="1.4.0" />
          </el-form-item>
          <el-form-item label="打包">
            <el-input v-model="manual.packaging" placeholder="jar" />
          </el-form-item>
        </div>
        <el-form-item label="sha256（64 位十六进制）" required>
          <el-input v-model="manual.sha256" maxlength="64" placeholder="e3b0c442…" />
        </el-form-item>
      </el-form>
      <p v-if="manualError" class="artifact-drawer__error-text">{{ manualError }}</p>
    </template>
    </template>

    <template #footer>
      <div class="artifact-drawer__footer">
        <span class="artifact-drawer__summary">
          <template v-if="tab === 'manual'">手工登记会以 MANUAL 来源绑定到当前发布单</template>
          <template v-else-if="selected">
            已选 <b class="mono">{{ selected.artifactName }}:{{ selected.version }}</b>
            · sha256 {{ shortSha(selected.sha256) }}
          </template>
          <template v-else>尚未选择制品（最多返回 50 条）</template>
        </span>
        <div class="artifact-drawer__actions">
          <el-button size="small" @click="open = false">取消</el-button>
          <el-button v-if="tab === 'manual'" size="small" type="primary" @click="submitManual">
            登记并绑定
          </el-button>
          <el-button v-else size="small" type="primary" :disabled="!selected" @click="confirmSelect">
            确认选择
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.artifact-drawer__tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--divider);
  margin-bottom: 12px;
}
.artifact-drawer__tab {
  height: 34px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-muted-aa);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
}
.artifact-drawer__tab:hover {
  color: var(--text-title);
}
.artifact-drawer__tab.is-active {
  color: var(--color-primary, #2f6fed);
  border-bottom-color: currentColor;
  font-weight: 500;
}
.artifact-drawer__tab:focus-visible {
  outline: 2px solid var(--focus-ring, #2f6fed);
  outline-offset: -2px;
}
.artifact-drawer__search {
  display: flex;
  align-items: center;
  gap: 8px;
}
.artifact-drawer__repo {
  width: 180px;
}
.artifact-drawer__degraded,
.artifact-drawer__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid #f0dcae;
  border-left: 3px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.artifact-drawer__error {
  border-color: #f7d4d5;
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.artifact-drawer__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 14px;
}
.artifact-drawer__bar {
  height: 32px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  animation: artifact-shimmer 1.2s var(--ease) infinite;
}
.artifact-table {
  width: 100%;
  margin-top: 12px;
  border-collapse: collapse;
  font-size: 12px;
}
.artifact-table th {
  height: 32px;
  padding: 0 8px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}
.artifact-table td {
  height: 40px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--divider);
  color: var(--text-body);
}
.artifact-table__row {
  cursor: pointer;
}
.artifact-table__row:hover {
  background: var(--bg-hover);
}
.artifact-table__row.is-selected {
  background: var(--color-info-bg);
}
.artifact-table__pick {
  width: 32px;
  text-align: center;
}
.artifact-table__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.artifact-table__name {
  display: block;
  color: var(--text-title);
}
.artifact-table__group {
  display: block;
  font-size: 10px;
  color: var(--text-weak);
}
.artifact-table__muted {
  color: var(--text-weak);
}
.artifact-table__sha {
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.artifact-drawer__empty {
  padding: 40px 12px;
  text-align: center;
  color: var(--text-weak);
  font-size: 12px;
}
.artifact-drawer__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.artifact-drawer__summary {
  font-size: 12px;
  color: var(--text-muted-aa);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-drawer__actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.artifact-drawer__hint {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-left: 2px solid var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 18px;
}
.artifact-form__row {
  display: flex;
  gap: 12px;
}
.artifact-form__row > * {
  flex: 1;
  min-width: 0;
}
.artifact-drawer__error-text {
  margin: 4px 0 0;
  color: var(--danger-ink);
  font-size: 12px;
}
@keyframes artifact-shimmer {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 0.8;
  }
}
</style>
