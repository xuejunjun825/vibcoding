<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '@/api/client'

/**
 * 导入向导（DESIGN §5 / 3 步：模板 → 上传 → 校验结果）。
 * 校验结果逐行给出错误，允许改完重传；失败行数不为 0 时不允许“静默成功”。
 */
interface ImportError {
  row: number
  message: string
}

interface ImportResult {
  ok: number
  failed: number
  errors: ImportError[]
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    /** 模板下载地址（后端导出的示例文件） */
    templateUrl?: string
    templateName?: string
    accept?: string
    maxSizeMb?: number
    /** 由页面注入的校验/导入实现，返回逐行结果 */
    validate: (file: File) => Promise<ImportResult>
  }>(),
  {
    title: '批量导入',
    templateUrl: '',
    templateName: 'import-template.csv',
    accept: '.csv',
    maxSizeMb: 5,
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'imported', result: ImportResult): void
}>()

const step = ref(0)
const file = ref<File | null>(null)
const dragging = ref(false)
const validating = ref(false)
const result = ref<ImportResult | null>(null)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

const steps = ['下载模板', '上传文件', '校验结果']

const fileLabel = computed(() => {
  if (!file.value) return ''
  const size = file.value.size / 1024
  return `${file.value.name} · ${size < 1024 ? `${size.toFixed(0)} KB` : `${(size / 1024).toFixed(1)} MB`}`
})

const canValidate = computed(() => Boolean(file.value) && !validating.value)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    step.value = 0
    file.value = null
    result.value = null
    error.value = ''
    validating.value = false
  },
)

function close(): void {
  emit('update:modelValue', false)
}

function pickFile(): void {
  fileInput.value?.click()
}

function acceptFile(candidate: File | null | undefined): void {
  error.value = ''
  result.value = null
  if (!candidate) return
  if (candidate.size > props.maxSizeMb * 1024 * 1024) {
    error.value = `文件超过 ${props.maxSizeMb} MB，请拆分后再导入`
    file.value = null
    return
  }
  const extension = candidate.name.toLowerCase().slice(candidate.name.lastIndexOf('.'))
  const allowed = props.accept.split(',').map((item) => item.trim().toLowerCase())
  if (allowed.length && !allowed.includes(extension)) {
    error.value = `仅支持 ${props.accept} 格式`
    file.value = null
    return
  }
  file.value = candidate
}

function onInput(event: Event): void {
  const target = event.target as HTMLInputElement
  acceptFile(target.files?.[0])
  target.value = ''
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  acceptFile(event.dataTransfer?.files?.[0])
}

async function runValidate(): Promise<void> {
  if (!file.value) return
  validating.value = true
  error.value = ''
  try {
    const outcome = await props.validate(file.value)
    result.value = outcome
    step.value = 2
    emit('imported', outcome)
  } catch (caught) {
    error.value =
      caught instanceof ApiError ? caught.message : '校验失败，请检查文件后重试'
  } finally {
    validating.value = false
  }
}

function restart(): void {
  step.value = 1
  result.value = null
  error.value = ''
  file.value = null
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.title"
    width="560px"
    align-center
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <ol class="iw__steps">
      <li
        v-for="(label, index) in steps"
        :key="label"
        class="iw__step"
        :class="{ 'iw__step--active': index === step, 'iw__step--done': index < step }"
      >
        <span class="iw__step-no">{{ index + 1 }}</span>
        <span class="iw__step-label">{{ label }}</span>
      </li>
    </ol>

    <section v-if="step === 0" class="iw__panel">
      <p class="iw__hint">
        模板首行为表头，请勿修改列名；每行一条数据，单次最多 500 行。
      </p>
      <a
        v-if="props.templateUrl"
        class="iw__download"
        :href="props.templateUrl"
        :download="props.templateName"
      >
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path d="M8 2.5v7.2M5 7l3 3 3-3M3 13h10" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        下载导入模板
      </a>
      <span v-else class="iw__download iw__download--disabled" title="模板地址未配置">
        下载导入模板
      </span>
    </section>

    <section v-else-if="step === 1" class="iw__panel">
      <div
        class="iw__drop"
        :class="{ 'iw__drop--over': dragging, 'iw__drop--filled': Boolean(file) }"
        role="button"
        tabindex="0"
        @click="pickFile"
        @keydown.enter="pickFile"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 16V5m0 0-4 4m4-4 4 4M4 19h16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <p class="iw__drop-title">{{ file ? fileLabel : '把文件拖到这里，或点击选择' }}</p>
        <p class="iw__drop-hint">支持 {{ props.accept }}，最大 {{ props.maxSizeMb }} MB</p>
        <input
          ref="fileInput"
          type="file"
          class="iw__file"
          :accept="props.accept"
          @change="onInput"
        />
      </div>
      <p v-if="error" class="iw__error" role="alert">{{ error }}</p>
    </section>

    <section v-else class="iw__panel">
      <div class="iw__summary">
        <span class="iw__summary-ok">成功 {{ result?.ok ?? 0 }} 行</span>
        <span
          class="iw__summary-fail"
          :class="{ 'iw__summary-fail--zero': !result?.failed }"
        >
          失败 {{ result?.failed ?? 0 }} 行
        </span>
      </div>
      <div v-if="result?.errors?.length" class="iw__errors">
        <div class="iw__errors-head">
          <span>行号</span>
          <span>原因</span>
        </div>
        <div v-for="item in result.errors" :key="`${item.row}-${item.message}`" class="iw__errors-row">
          <span class="iw__errors-row-no">{{ item.row }}</span>
          <span>{{ item.message }}</span>
        </div>
      </div>
      <p v-else class="iw__hint">全部行校验通过，已写入系统。</p>
    </section>

    <template #footer>
      <el-button v-if="step === 2" @click="restart">重新选择文件</el-button>
      <el-button v-if="step === 0" type="primary" @click="step = 1">下一步</el-button>
      <el-button v-else-if="step === 1" type="primary" :disabled="!canValidate" :loading="validating" @click="runValidate">
        {{ validating ? '校验中…' : '开始校验' }}
      </el-button>
      <el-button v-else type="primary" @click="close">完成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.iw__steps {
  display: flex;
  gap: 20px;
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
}
.iw__step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-weak);
}
.iw__step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.iw__step--active {
  color: var(--text-title);
  font-weight: 500;
}
.iw__step--active .iw__step-no {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}
.iw__step--done .iw__step-no {
  border-color: var(--color-success);
  color: var(--color-success);
}
.iw__panel {
  min-height: 148px;
}
.iw__hint {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-muted-aa);
}
.iw__download {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--color-primary);
  text-decoration: none;
}
.iw__download:hover {
  color: var(--color-primary-hover);
}
.iw__download svg {
  width: 14px;
  height: 14px;
}
.iw__download--disabled {
  color: var(--text-weak);
  cursor: not-allowed;
}
.iw__drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 28px 16px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-card);
  background: var(--bg-hover);
  cursor: pointer;
  transition:
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease);
}
.iw__drop:hover,
.iw__drop--over {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.iw__drop--filled {
  border-style: solid;
  border-color: var(--color-primary);
}
.iw__drop svg {
  width: 24px;
  height: 24px;
  color: var(--text-muted-aa);
}
.iw__drop-title {
  font-size: 13px;
  color: var(--text-title);
}
.iw__drop-hint {
  font-size: 12px;
  color: var(--text-weak);
}
.iw__file {
  display: none;
}
.iw__error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--danger-ink);
}
.iw__summary {
  display: flex;
  gap: 16px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.iw__summary-ok {
  color: var(--success-ink);
  font-weight: 500;
}
.iw__summary-fail {
  color: var(--danger-ink);
  font-weight: 500;
}
.iw__summary-fail--zero {
  color: var(--text-muted-aa);
  font-weight: 400;
}
.iw__errors {
  margin-top: 12px;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
}
.iw__errors-head,
.iw__errors-row {
  display: grid;
  grid-template-columns: 56px 1fr;
  gap: 8px;
  padding: 6px 10px;
  font-size: 12px;
}
.iw__errors-head {
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  position: sticky;
  top: 0;
}
.iw__errors-row {
  border-top: 1px solid var(--divider);
  color: var(--text-body);
  line-height: 18px;
}
.iw__errors-row-no {
  font-family: var(--font-mono);
  color: var(--text-weak);
}
</style>
