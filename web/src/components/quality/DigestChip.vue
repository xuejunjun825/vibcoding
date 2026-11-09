<script setup lang="ts">
/**
 * 快照 hash 芯片（DESIGN §6）：只显示前 8 位，点击复制完整 64 位。
 * 复制失败必须可见（execCommand 兜底也失败时给出提示），不能静默。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = withDefaults(
  defineProps<{
    hash?: string | null
    label?: string
    /** 无 hash 时的占位文案（未评估 / 未固化） */
    emptyText?: string
  }>(),
  { hash: null, label: '快照', emptyText: '未固化' },
)

const short = computed(() => (props.hash ? props.hash.slice(0, 8) : ''))

function fallbackCopy(text: string): boolean {
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', 'readonly')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}

async function copy(): Promise<void> {
  if (!props.hash) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.hash)
    } else if (!fallbackCopy(props.hash)) {
      throw new Error('copy unavailable')
    }
    ElMessage.success('已复制哈希')
  } catch {
    ElMessage.warning('浏览器不允许读取剪贴板，请手动选择复制')
  }
}
</script>

<template>
  <el-tooltip
    v-if="hash"
    placement="top"
    :content="`${label}哈希（点击复制完整 64 位）：${hash}`"
    :show-after="200"
  >
    <button type="button" class="digest-chip" :aria-label="`复制${label}哈希`" @click="copy">
      <span class="digest-chip__label">{{ label }}</span>
      <span class="digest-chip__hash t-code">{{ short }}</span>
      <span class="digest-chip__more">…</span>
    </button>
  </el-tooltip>
  <span v-else class="digest-chip digest-chip--empty" :title="emptyText">{{ emptyText }}</span>
</template>

<style scoped>
.digest-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  max-width: 100%;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease);
}
.digest-chip:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.digest-chip:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.digest-chip__label {
  color: var(--text-muted-aa);
  flex: none;
}
.digest-chip__hash {
  flex: none;
  letter-spacing: 0.02em;
}
.digest-chip__more {
  flex: none;
  color: var(--text-weak);
  letter-spacing: 0.08em;
}
.digest-chip--empty {
  cursor: default;
  color: var(--text-weak);
  background: transparent;
  border-style: dashed;
}
</style>
