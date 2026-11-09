<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'

/**
 * 凭据字段（DESIGN I1 / §6.2，AC-1.2 / AC-10.1）。
 *
 * 这一块是"凭据只写不读"的唯一 UI 出口，三条硬规则都写在这里：
 * 1. **已配置时只显示末四位**：`•••• 1234`（单色 mono chip），明文/密文永不进 DOM，
 *    也不提供复制按钮 —— 复制就等于读；
 * 2. **空串不改**：更换时留空提交"保持原值"，父组件据此不发送 credential；
 * 3. **清除要二次确认**：必须手工输入配置名称（服务端 `confirmName` 的比对值就是 `name`），
 *    输错就禁用确认按钮，绝不"点一下就清空"。
 *
 * 组件不认识系统、也不发请求：它只把"用户想写什么"抛给父组件的 dirty 草稿。
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 服务端是否已有该凭据（hasCredential / hasSecret） */
    configured: boolean
    /** 末四位（服务端只回这个） */
    last4?: string | null
    label?: string
    /** 凭据类型文案：密码 / Private Token / Token */
    kindLabel?: string
    /** 清除确认时要用户键入的值（= 配置名称，与服务端 confirmName 一致） */
    confirmName?: string
    placeholder?: string
    hint?: string
    disabled?: boolean
    readonly?: boolean
    inputId?: string
  }>(),
  {
    last4: null,
    label: '凭据',
    kindLabel: '密码',
    confirmName: '',
    placeholder: '保存后不可查看',
    hint: '',
    disabled: false,
    readonly: false,
    inputId: '',
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'clear'): void
}>()

const value = computed<string>({
  get: () => props.modelValue,
  set: (next) => emit('update:modelValue', next),
})

/** 已配置但用户点了「更换」：展开密码输入，取消后可退回只读行 */
const uid = useId()
const hintId = computed(() => `cred-hint-${uid}`)
const clearInputId = computed(() => `cred-clear-${uid}`)

const replacing = ref(false)
const clearOpen = ref(false)
const clearInput = ref('')
const clearError = ref('')

const canConfirmClear = computed(
  () => !!props.confirmName && clearInput.value.trim() === props.confirmName,
)

const masked = computed(() => (props.last4 ? `•••• ${props.last4}` : '•••• ????'))

const hintText = computed(
  () => props.hint || (props.configured ? '留空保持不变；任何情况下都不会回显凭据内容' : '保存后不可查看'),
)

/** 清除成功后父组件把 hasX 置 false，这里收起更换态，回到"填写新凭据" */
watch(
  () => props.configured,
  (configured) => {
    if (!configured) {
      replacing.value = false
      emit('update:modelValue', '')
    }
  },
)

function startReplace(): void {
  replacing.value = true
}

function cancelReplace(): void {
  replacing.value = false
  emit('update:modelValue', '')
}

function openClear(): void {
  clearInput.value = ''
  clearError.value = ''
  clearOpen.value = true
  void nextTick(() => {
    // 焦点落在输入框：确认动作必须"动手打字"，不是顺手回车
    document.getElementById(clearInputId.value)?.focus()
  })
}

function confirmClear(): void {
  if (!canConfirmClear.value) {
    clearError.value = `名称不匹配，请输入「${props.confirmName}」`
    return
  }
  clearOpen.value = false
  replacing.value = false
  emit('update:modelValue', '')
  emit('clear')
}
</script>

<template>
  <div class="cred">
    <div class="cred__head">
      <span class="cred__label">{{ label }}</span>
      <span class="cred__kind">{{ kindLabel }}</span>
    </div>

    <!-- 已配置：只读行（末四位 chip + 更换 / 清除） -->
    <div v-if="configured && !replacing" class="cred__readonly">
      <span class="cred__state">
        <svg class="cred__lock" viewBox="0 0 12 12" aria-hidden="true">
          <path
            d="M3.4 5.3V3.9a2.6 2.6 0 0 1 5.2 0v1.4M2.6 5.3h6.8V10H2.6V5.3Z"
            fill="none"
            stroke="currentColor"
            stroke-width="1"
          />
        </svg>
        已配置
      </span>
      <span class="cred__last4 t-code" :aria-label="`末四位 ${last4 || '未知'}`">{{ masked }}</span>
      <span class="cred__note">保存后不可查看</span>
      <span class="cred__spacer" />
      <template v-if="!readonly">
        <el-button link type="primary" size="small" @click="startReplace">更换</el-button>
        <el-button link type="danger" size="small" @click="openClear">清除</el-button>
      </template>
    </div>

    <!-- 未配置 / 更换：密码输入（autocomplete=new-password，防浏览器回填已保存口令） -->
    <div v-else class="cred__edit">
      <el-input
        :id="inputId || undefined"
        v-model="value"
        type="password"
        show-password
        autocomplete="new-password"
        :disabled="disabled || readonly"
        :placeholder="placeholder"
        :aria-label="label"
        :aria-describedby="hintId"
      />
      <p :id="hintId" class="cred__hint">{{ hintText }}</p>
      <el-button v-if="configured && !readonly" link size="small" @click="cancelReplace">
        取消更换
      </el-button>
    </div>

    <!-- 清除确认：必须键入配置名称（服务端 confirmName 同值） -->
    <el-dialog
      v-model="clearOpen"
      :title="`清除${label}`"
      width="420px"
      append-to-body
      :close-on-click-modal="false"
      @closed="clearInput = ''"
    >
      <div class="cred__dialog">
        <p class="cred__dialog-desc">
          清除后该配置将不再携带{{ label }}，依赖它的调用会立即失败。此操作不可撤销，需重新填写并保存。
        </p>
        <label class="cred__dialog-label" :for="clearInputId">
          输入配置名称 <span class="t-code">{{ confirmName }}</span> 以确认
        </label>
        <el-input
          :id="clearInputId"
          v-model="clearInput"
          autocomplete="off"
          placeholder="逐字输入上面的名称"
          @keyup.enter="confirmClear"
        />
        <p v-if="clearError" class="cred__dialog-err" role="alert">{{ clearError }}</p>
      </div>
      <template #footer>
        <el-button @click="clearOpen = false">取消</el-button>
        <el-button type="danger" :disabled="!canConfirmClear" @click="confirmClear">
          清除{{ label }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cred {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.cred__head {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.cred__label {
  color: var(--text-body);
  font-size: 12px;
}

.cred__kind {
  color: var(--text-weak);
  font-size: 11px;
}

.cred__readonly {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-page);
}

.cred__state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-body);
  font-size: 12px;
}

.cred__lock {
  width: 12px;
  height: 12px;
  color: var(--text-secondary);
}

.cred__last4 {
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 11px;
  line-height: 18px;
  letter-spacing: 0.04em;
}

.cred__note {
  color: var(--text-weak);
  font-size: 11px;
}

.cred__spacer {
  flex: 1;
}

.cred__edit {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cred__hint {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 1.5;
}

.cred__dialog {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cred__dialog-desc {
  margin: 0;
  color: var(--text-body);
  font-size: 13px;
  line-height: 1.6;
}

.cred__dialog-label {
  color: var(--text-body);
  font-size: 12px;
}

.cred__dialog-err {
  margin: 0;
  color: var(--danger-ink);
  font-size: 12px;
}
</style>
