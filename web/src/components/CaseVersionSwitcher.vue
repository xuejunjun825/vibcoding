<script setup lang="ts">
import { computed } from 'vue'
import { formatDate } from '@/utils/format'

/**
 * 用例版本切换器（DESIGN §6.3 / AC-16）：
 * 选中历史版本 → 顶部出现只读 banner，页面其余输入全部禁用。
 * 执行记录永远显示“当时快照”的版本号，所以这里只负责选择，不猜内容。
 */
interface VersionOption {
  versionNo: number
  createdAt?: string
  createdBy?: string
  current?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue: number
    versions: VersionOption[]
    /** 用例当前最新版本；缺省取 versions 里 current=true 的那条 */
    currentVersion?: number | null
    disabled?: boolean
    /** 查看历史时是否在控件上方渲染只读 banner */
    showBanner?: boolean
  }>(),
  { currentVersion: null, disabled: false, showBanner: true },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: number): void
  (event: 'change', value: number): void
}>()

const latest = computed(() => {
  if (props.currentVersion !== null) return props.currentVersion
  const flagged = props.versions.find((item) => item.current)
  if (flagged) return flagged.versionNo
  return props.versions.reduce((max, item) => Math.max(max, item.versionNo), 0)
})

const selected = computed(() => props.versions.find((item) => item.versionNo === props.modelValue))
const isHistory = computed(() => props.modelValue !== latest.value)

const bannerText = computed(() => {
  const created = selected.value?.createdAt
  const who = selected.value?.createdBy ? ` · ${selected.value.createdBy}` : ''
  const when = created ? `（${formatDate(created)}${who}）` : ''
  return `只读 · 正在查看 v${props.modelValue}${when}`
})

const options = computed(() =>
  [...props.versions]
    .sort((a, b) => b.versionNo - a.versionNo)
    .map((item) => ({
      value: item.versionNo,
      label: item.versionNo === latest.value ? `v${item.versionNo}（当前）` : `v${item.versionNo}`,
    })),
)

function onChange(value: number): void {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <div class="ver">
    <div class="ver__control">
      <span class="ver__label">版本</span>
      <el-select
        :model-value="props.modelValue"
        size="small"
        class="ver__select"
        :disabled="props.disabled"
        @update:model-value="onChange"
      >
        <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <span v-if="isHistory" class="ver__flag">历史版本</span>
    </div>

    <div v-if="props.showBanner && isHistory" class="ver__banner" role="status">
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <rect x="3.2" y="7" width="9.6" height="6.6" rx="1.4" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M5.6 7V5.2a2.4 2.4 0 0 1 4.8 0V7" fill="none" stroke="currentColor" stroke-width="1.3" />
      </svg>
      <span class="ver__banner-text">{{ bannerText }}</span>
      <button v-if="latest !== props.modelValue" type="button" class="ver__back" @click="onChange(latest)">
        回到当前版本
      </button>
    </div>
  </div>
</template>

<style scoped>
.ver {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ver__control {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ver__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.ver__select {
  width: 148px;
}
.ver__flag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  font-weight: 500;
}
.ver__banner {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 12px;
  background: var(--color-info-bg);
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  color: var(--text-body);
  font-size: 12px;
}
.ver__banner svg {
  width: 14px;
  height: 14px;
  flex: none;
  color: var(--text-muted-aa);
}
.ver__banner-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ver__back {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.ver__back:hover {
  color: var(--color-primary-hover);
}
</style>
