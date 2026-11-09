<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { promoteArtifact, type ArtifactPromoteResult, type ArtifactView } from '@/api/integration'

/**
 * 制品晋级弹窗（DESIGN I6 / AC-7.3）。
 *
 * 晋级的判定在服务端同一个事务里做（sha256 三方一致），前端只负责展示「期望 / 实际」。
 * CHECKSUM_MISMATCH 是常驻红条 + 按钮禁用：用户必须先重新绑定，不能靠重试蒙过去。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    artifact: ArtifactView | null
    targetRepo?: string
  }>(),
  { targetRepo: '' },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'promoted', result: ArtifactPromoteResult): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const confirming = ref(false)
const acknowledged = ref(false)
const errorCode = ref('')
const errorText = ref('')
const expectedSha = ref('')
const actualSha = ref('')

watch(
  () => props.modelValue,
  (value) => {
    if (!value) return
    acknowledged.value = false
    errorCode.value = ''
    errorText.value = ''
    expectedSha.value = props.artifact?.sha256 ?? ''
    actualSha.value = props.artifact?.sha256 ?? ''
  },
)

const checksumMismatch = computed(() => errorCode.value === 'CHECKSUM_MISMATCH')
const canConfirm = computed(
  () => Boolean(props.artifact) && acknowledged.value && !confirming.value && !checksumMismatch.value,
)

function extractSha(source: unknown): string {
  if (!source || typeof source !== 'object') return ''
  const record = source as Record<string, unknown>
  for (const key of ['actualSha256', 'actual', 'expectedSha256', 'expected', 'sha256']) {
    const value = record[key]
    if (typeof value === 'string' && value.length) return value
  }
  return ''
}

async function confirm(): Promise<void> {
  if (!props.artifact) return
  confirming.value = true
  errorCode.value = ''
  errorText.value = ''
  try {
    const result = await promoteArtifact(props.artifact.id, {
      targetRepo: props.targetRepo || undefined,
    })
    ElMessage.success('已晋级；新制品已写入目标仓库坐标')
    emit('promoted', result)
    open.value = false
  } catch (err) {
    if (err instanceof ApiError) {
      errorCode.value = err.code
      errorText.value = err.message || '晋级失败'
      if (err.code === 'CHECKSUM_MISMATCH') {
        const detail = err.detail
        const mismatch = extractSha(detail)
        if (mismatch) actualSha.value = mismatch
        if (detail && typeof detail === 'object') {
          const record = detail as Record<string, unknown>
          const expected = record.expectedSha256 ?? record.expected
          if (typeof expected === 'string' && expected.length) expectedSha.value = expected
        }
      }
    } else {
      errorText.value = '晋级失败，请稍后重试'
    }
  } finally {
    confirming.value = false
  }
}

/** sha256 只展示前 12 位，完整值放 title / 展开行，避免长串撑破 520 宽的弹窗 */
function shortSha(sha?: string | null): string {
  return sha ? sha.slice(0, 12) : '—'
}
</script>

<template>
  <el-dialog
    v-model="open"
    title="晋级制品"
    :width="520"
    append-to-body
    destroy-on-close
    class="promote-dialog"
  >
    <template v-if="artifact">
      <dl class="promote__meta">
        <div class="promote__row">
          <dt>制品</dt>
          <dd class="mono">{{ artifact.artifactName }}:{{ artifact.version }}</dd>
        </div>
        <div class="promote__row">
          <dt>源仓库</dt>
          <dd class="mono">{{ artifact.repo }}</dd>
        </div>
        <div class="promote__row">
          <dt>目标仓库</dt>
          <dd class="mono">
            <template v-if="targetRepo">{{ targetRepo }}</template>
            <template v-else><span class="promote__missing">按服务端配置的目标仓库（未在页面指定）</span></template>
          </dd>
        </div>
      </dl>

      <div class="promote__sha" :class="{ 'is-mismatch': checksumMismatch }">
        <div class="promote__sha-row">
          <span class="promote__sha-label">期望 sha256</span>
          <span class="mono" :title="expectedSha">{{ shortSha(expectedSha) }}</span>
        </div>
        <div class="promote__sha-row">
          <span class="promote__sha-label">实际 sha256</span>
          <span v-if="confirming" class="promote__checking">校验中…</span>
          <span v-else class="mono" :title="actualSha">{{ shortSha(actualSha) }}</span>
        </div>
        <p v-if="checksumMismatch" class="promote__mismatch" role="alert">
          校验值不一致（CHECKSUM_MISMATCH）：目标仓库的实际制品与登记的期望值不同，晋级已阻止。
          请重新选择制品后再晋级。
        </p>
      </div>

      <p v-if="errorText && !checksumMismatch" class="promote__error" role="alert">{{ errorText }}</p>

      <p class="promote__audit">
        晋级会在目标仓库生成一个新坐标的制品记录，并保留 promotedFromId 审计链；源记录不会被改写。
      </p>

      <el-checkbox v-model="acknowledged" class="promote__ack">
        我确认按上述目标仓库晋级，并已知晓该操作会新增审计记录
      </el-checkbox>
    </template>
    <p v-else class="promote__error">没有可晋级的制品。</p>

    <template #footer>
      <div class="promote__footer">
        <el-button size="small" @click="open = false">取消</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="confirming"
          :disabled="!canConfirm"
          @click="confirm"
        >
          {{ confirming ? '校验中…' : '确认晋级' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.promote__meta {
  margin: 0 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--divider);
  border-radius: var(--r-card);
  background: var(--bg-sunken);
}
.promote__row {
  display: flex;
  gap: 12px;
  line-height: 22px;
  font-size: 12px;
}
.promote__row dt {
  width: 64px;
  flex: none;
  color: var(--text-weak);
}
.promote__row dd {
  margin: 0;
  min-width: 0;
  color: var(--text-body);
  overflow-wrap: anywhere;
}
.promote__missing {
  color: var(--warning-ink);
}
.promote__sha {
  padding: 10px 12px;
  border: 1px solid var(--divider);
  border-radius: var(--r-card);
}
.promote__sha.is-mismatch {
  border-color: #f7d4d5;
  background: var(--color-danger-bg);
}
.promote__sha-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 26px;
  font-size: 12px;
}
.promote__sha-label {
  color: var(--text-weak);
}
.promote__checking {
  color: var(--text-muted-aa);
}
.promote__mismatch {
  margin: 6px 0 0;
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 18px;
}
.promote__error {
  margin: 10px 0 0;
  color: var(--danger-ink);
  font-size: 12px;
}
.promote__audit {
  margin: 12px 0 8px;
  color: var(--text-weak);
  font-size: 12px;
  line-height: 18px;
}
.promote__ack :deep(.el-checkbox__label) {
  font-size: 12px;
  color: var(--text-body);
  white-space: normal;
}
.promote__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
