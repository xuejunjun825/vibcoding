<script setup lang="ts">
import { computed } from 'vue'
import type { WikiDiff, WikiVersion } from '@/api/collab'

/**
 * 版本对比抽屉（DESIGN §6 `WikiDiffDrawer`）。
 *
 * diff 由服务端 `GET /wiki/pages/{id}/versions/diff` 给出——前端不做第二份 LCS，
 * 这样「页面里看到的差异」与「回滚的目标内容」永远是同一口径。
 * 720 宽、行号 40+40、+/− 前缀，长行换行而不是横向滚动。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    diff?: WikiDiff | null
    versions?: WikiVersion[]
    from?: number | null
    to?: number | null
    loading?: boolean
    error?: string
    rollbackable?: boolean
  }>(),
  {
    diff: null,
    versions: () => [],
    from: null,
    to: null,
    loading: false,
    error: '',
    rollbackable: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'change', payload: { from: number; to: number }): void
  (e: 'rollback', versionNo: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const versionOptions = computed(() =>
  [...props.versions]
    .sort((a, b) => b.versionNo - a.versionNo)
    .map((item) => ({
      value: item.versionNo,
      label: `v${item.versionNo}${item.current ? '（当前）' : ''}`,
    })),
)

const summary = computed(() => props.diff ?? null)

function changeFrom(value: number): void {
  emit('change', { from: value, to: props.to ?? value })
}

function changeTo(value: number): void {
  emit('change', { from: props.from ?? value, to: value })
}

function sign(kind: string): string {
  if (kind === 'ADD') return '+'
  if (kind === 'DEL') return '−'
  return ''
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :size="720"
    title="版本对比"
    direction="rtl"
    class="wdd"
    :destroy-on-close="false"
  >
    <template #header>
      <div class="wdd__head">
        <span class="wdd__title">版本对比</span>
        <span v-if="summary" class="wdd__stat">
          <span class="wdd__stat-add">+{{ summary.added }}</span>
          <span class="wdd__stat-del">−{{ summary.removed }}</span>
          <span v-if="summary.changed" class="wdd__stat-chg">{{ summary.changed }} 处变更</span>
        </span>
      </div>
    </template>

    <div class="wdd__body">
      <div class="wdd__picker">
        <label class="wdd__field">
          <span class="wdd__label">对比基准</span>
          <el-select
            :model-value="from ?? undefined"
            size="small"
            class="wdd__select"
            placeholder="选择版本"
            @update:model-value="(v: number) => changeFrom(v)"
          >
            <el-option v-for="opt in versionOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </label>
        <span class="wdd__arrow" aria-hidden="true">→</span>
        <label class="wdd__field">
          <span class="wdd__label">目标版本</span>
          <el-select
            :model-value="to ?? undefined"
            size="small"
            class="wdd__select"
            placeholder="选择版本"
            @update:model-value="(v: number) => changeTo(v)"
          >
            <el-option v-for="opt in versionOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </label>
        <span class="wdd__spacer" />
        <button
          v-if="rollbackable && from"
          type="button"
          class="wdd__rollback"
          @click="emit('rollback', from)"
        >
          回滚到 v{{ from }}
        </button>
      </div>

      <div v-if="error" class="wdd__error" role="alert">{{ error }}</div>

      <div v-if="loading" class="wdd__skeleton" aria-busy="true">
        <div v-for="n in 8" :key="n" class="wdd__skeleton-row" :style="{ width: `${58 + ((n * 13) % 38)}%` }" />
      </div>

      <div v-else-if="summary && summary.lines.length" class="wdd__diff" role="table" aria-label="版本差异">
        <div
          v-for="(line, index) in summary.lines"
          :key="index"
          class="wdd__line"
          :class="`wdd__line--${line.kind.toLowerCase()}`"
          role="row"
        >
          <span class="wdd__no">{{ line.leftNo ?? '' }}</span>
          <span class="wdd__no">{{ line.rightNo ?? '' }}</span>
          <span class="wdd__sign" aria-hidden="true">{{ sign(line.kind) }}</span>
          <span class="wdd__text" role="cell">{{ line.text || ' ' }}</span>
        </div>
      </div>

      <div v-else-if="!loading && !error" class="wdd__empty">
        <p>{{ summary ? '两个版本内容完全一致' : '选择两个版本查看差异' }}</p>
        <p v-if="!summary" class="wdd__empty-hint">同一页面的任意两次保存都可以对比，回滚会生成新版本而不是覆盖历史。</p>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.wdd__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.wdd__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
}
.wdd__stat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.wdd__stat-add {
  color: var(--success-ink);
}
.wdd__stat-del {
  color: var(--danger-ink);
}
.wdd__stat-chg {
  color: var(--text-muted-aa);
}
.wdd__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.wdd__picker {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--divider);
}
.wdd__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.wdd__label {
  font-size: 11px;
  color: var(--text-weak);
}
.wdd__select {
  width: 168px;
}
.wdd__arrow {
  padding-bottom: 6px;
  color: var(--text-weak);
}
.wdd__spacer {
  flex: 1 1 auto;
}
.wdd__rollback {
  height: 30px;
  padding: 0 12px;
  margin-bottom: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  font-size: 12px;
  color: var(--text-body);
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), color var(--t-color) var(--ease);
}
.wdd__rollback:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.wdd__error {
  flex: none;
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.wdd__diff {
  flex: 1 1 auto;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.7;
}
.wdd__line {
  display: flex;
  align-items: flex-start;
  min-height: 24px;
}
.wdd__line--add {
  background: #e8f7ec;
}
.wdd__line--del {
  background: #fdecec;
}
.wdd__no {
  flex: none;
  width: 40px;
  text-align: right;
  padding: 2px 6px 2px 0;
  color: var(--text-weak);
  font-size: 11px;
  user-select: none;
  border-right: 1px solid var(--divider);
}
.wdd__sign {
  flex: none;
  width: 16px;
  text-align: center;
  color: var(--text-muted-aa);
  user-select: none;
}
.wdd__line--add .wdd__sign {
  color: var(--success-ink);
}
.wdd__line--del .wdd__sign {
  color: var(--danger-ink);
}
.wdd__text {
  flex: 1 1 auto;
  min-width: 0;
  padding: 2px 10px 2px 2px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-body);
}
.wdd__empty {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-weak);
  font-size: 13px;
}
.wdd__empty-hint {
  font-size: 12px;
  color: var(--text-weak);
}
.wdd__skeleton {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 8px;
}
.wdd__skeleton-row {
  height: 12px;
  border-radius: 3px;
  background: var(--bg-hover);
  animation: wdd-pulse 1.2s ease-in-out infinite;
}
@keyframes wdd-pulse {
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}
@media (max-width: 900px) {
  .wdd__picker {
    flex-wrap: wrap;
  }
}
</style>
