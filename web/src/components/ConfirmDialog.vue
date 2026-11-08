<script setup lang="ts">
import { ref, watch } from 'vue'

/**
 * 危险操作确认（DESIGN §6.7）：
 * 标题 → 影响范围（1-3 条）→ 必填原因 → 取消 / 确认（红实心）。
 * 校验失败在弹窗内联提示，不弹 toast（toast 会随弹窗关闭而丢失上下文）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    description?: string
    /** 影响范围，1-3 条，逐条列出后果 */
    impact?: string[]
    reasonRequired?: boolean
    reasonLabel?: string
    reasonPlaceholder?: string
    confirmText?: string
    cancelText?: string
    tone?: 'danger' | 'primary'
    /** 需要额外勾选「已知悉」 */
    requireAck?: boolean
    ackLabel?: string
    confirmDisabled?: boolean
    loading?: boolean
  }>(),
  {
    description: '',
    impact: () => [],
    reasonRequired: true,
    reasonLabel: '原因',
    reasonPlaceholder: '请说明原因，将记录在审计日志中',
    confirmText: '确认',
    cancelText: '取消',
    tone: 'danger',
    requireAck: false,
    ackLabel: '',
    confirmDisabled: false,
    loading: false,
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'confirm', payload: { reason: string; acknowledged: boolean }): void
  (event: 'cancel'): void
}>()

const reason = ref('')
const acknowledged = ref(false)
const error = ref('')

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reason.value = ''
      acknowledged.value = false
      error.value = ''
    }
  },
)

function close(): void {
  emit('update:modelValue', false)
  emit('cancel')
}

function submit(): void {
  if (props.reasonRequired && reason.value.trim().length < 2) {
    error.value = '请填写至少 2 个字的原因'
    return
  }
  if (props.requireAck && !acknowledged.value) {
    error.value = '请先勾选确认项'
    return
  }
  error.value = ''
  emit('confirm', { reason: reason.value.trim(), acknowledged: acknowledged.value })
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.title"
    width="440px"
    align-center
    :close-on-click-modal="false"
    append-to-body
    class="confirm-dialog"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="error = ''"
  >
    <p v-if="props.description" class="cd__desc">{{ props.description }}</p>

    <ul v-if="props.impact.length" class="cd__impact">
      <li v-for="(line, index) in props.impact" :key="index">{{ line }}</li>
    </ul>

    <div v-if="props.reasonRequired" class="cd__field">
      <label class="cd__label" for="cd-reason">{{ props.reasonLabel }}<span class="cd__req">*</span></label>
      <el-input
        id="cd-reason"
        v-model="reason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        :placeholder="props.reasonPlaceholder"
      />
    </div>

    <el-checkbox v-if="props.requireAck" v-model="acknowledged" class="cd__ack">
      {{ props.ackLabel || '我已知悉上述影响' }}
    </el-checkbox>

    <p v-if="error" class="cd__error" role="alert">{{ error }}</p>

    <template #footer>
      <el-button :disabled="props.loading" @click="close">{{ props.cancelText }}</el-button>
      <el-button
        :type="props.tone === 'danger' ? 'danger' : 'primary'"
        :loading="props.loading"
        :disabled="props.confirmDisabled"
        @click="submit"
      >
        {{ props.confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cd__desc {
  font-size: 13px;
  line-height: 20px;
  color: var(--text-body);
}
.cd__impact {
  margin: 10px 0 0;
  padding: 8px 12px 8px 26px;
  background: var(--color-danger-bg);
  border-left: 2px solid var(--color-danger);
  border-radius: var(--r-ctl);
  font-size: 12px;
  line-height: 20px;
  color: var(--danger-ink);
}
.cd__impact li + li {
  margin-top: 2px;
}
.cd__field {
  margin-top: 14px;
}
.cd__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--text-body);
}
.cd__req {
  margin-left: 2px;
  color: var(--color-danger);
}
.cd__ack {
  margin-top: 12px;
}
.cd__error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
