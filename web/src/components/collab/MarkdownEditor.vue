<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import MarkdownView from './MarkdownView.vue'

/**
 * Wiki 编辑器（DESIGN §6 `MarkdownEditor`）。
 *
 * 工具栏 40 + 「编辑 700 / 预览 476」并置；textarea 是纯文本域——不做富文本、
 * 不注入 HTML，预览走 `MarkdownView`（同样不解析原始 HTML），所见即所存。
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    disabled?: boolean
    saving?: boolean
    /** <1280 时退化为单栏切换 */
    previewEnabled?: boolean
  }>(),
  { disabled: false, saving: false, previewEnabled: true },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'save'): void
  (e: 'change-dirty', dirty: boolean): void
}>()

const textarea = ref<HTMLTextAreaElement | null>(null)
const showPreview = ref(true)

const value = computed({
  get: () => props.modelValue,
  set: (next: string) => emit('update:modelValue', next),
})

const stats = computed(() => {
  const text = props.modelValue ?? ''
  const chars = text.replace(/\s/g, '').length
  const lines = text ? text.split('\n').length : 0
  return { chars, lines }
})

function applyEdit(next: string, caretStart: number, caretEnd = caretStart): void {
  value.value = next
  emit('change-dirty', true)
  void nextTick(() => {
    const el = textarea.value
    if (!el) return
    el.focus()
    el.setSelectionRange(caretStart, caretEnd)
  })
}

/** 包裹选中文本；无选区时插入占位并选中占位，方便直接替换 */
function wrap(before: string, after = before, placeholder = '文字'): void {
  const el = textarea.value
  const text = props.modelValue ?? ''
  const start = el?.selectionStart ?? text.length
  const end = el?.selectionEnd ?? text.length
  const selected = text.slice(start, end)
  const body = selected || placeholder
  const next = `${text.slice(0, start)}${before}${body}${after}${text.slice(end)}`
  applyEdit(next, start + before.length, start + before.length + body.length)
}

/** 行首前缀（列表/引用/标题） */
function prefixLine(prefix: string): void {
  const el = textarea.value
  const text = props.modelValue ?? ''
  const start = el?.selectionStart ?? text.length
  const lineStart = text.lastIndexOf('\n', start - 1) + 1
  const next = `${text.slice(0, lineStart)}${prefix}${text.slice(lineStart)}`
  applyEdit(next, start + prefix.length)
}

function insertBlock(block: string, placeholderFrom = 0, placeholderLen = 0): void {
  const el = textarea.value
  const text = props.modelValue ?? ''
  const start = el?.selectionStart ?? text.length
  const end = el?.selectionEnd ?? text.length
  const needsLead = start > 0 && !text.slice(0, start).endsWith('\n')
  const head = needsLead ? '\n' : ''
  const next = `${text.slice(0, start)}${head}${block}${text.slice(end)}`
  const caret = start + head.length + placeholderFrom
  applyEdit(next, caret, caret + placeholderLen)
}

function insertLink(): void {
  const el = textarea.value
  const text = props.modelValue ?? ''
  const start = el?.selectionStart ?? text.length
  const end = el?.selectionEnd ?? text.length
  const label = text.slice(start, end) || '链接文字'
  const snippet = `[${label}](https://)`
  const next = `${text.slice(0, start)}${snippet}${text.slice(end)}`
  applyEdit(next, start + label.length + 3, start + snippet.length - 1)
}

function insertWikiLink(): void {
  const el = textarea.value
  const text = props.modelValue ?? ''
  const start = el?.selectionStart ?? text.length
  const end = el?.selectionEnd ?? text.length
  const label = text.slice(start, end) || '页面标题'
  const snippet = `[[页面ID|${label}]]`
  const next = `${text.slice(0, start)}${snippet}${text.slice(end)}`
  applyEdit(next, start + 2, start + 2 + 4)
}

function insertTable(): void {
  insertBlock('| 列 1 | 列 2 |\n| --- | --- |\n| 值 | 值 |\n', 2, 3)
}

function onKeydown(event: KeyboardEvent): void {
  if (props.disabled) return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    emit('save')
    return
  }
  if (event.key === 'Tab') {
    event.preventDefault()
    const el = textarea.value
    if (!el) return
    const text = props.modelValue ?? ''
    const start = el.selectionStart
    const end = el.selectionEnd
    const next = `${text.slice(0, start)}  ${text.slice(end)}`
    applyEdit(next, start + 2, end + 2)
  }
}

function onInput(event: Event): void {
  const el = event.target as HTMLTextAreaElement
  value.value = el.value
  emit('change-dirty', true)
}

const TOOLS: { key: string; label: string; title: string; run: () => void }[] = [
  { key: 'h2', label: 'H2', title: '二级标题', run: () => prefixLine('## ') },
  { key: 'bold', label: 'B', title: '加粗（Ctrl+B）', run: () => wrap('**', '**', '加粗') },
  { key: 'italic', label: 'I', title: '斜体', run: () => wrap('*', '*', '斜体') },
  { key: 'code', label: '</>', title: '行内代码', run: () => wrap('`', '`', 'code') },
  { key: 'ul', label: '•', title: '无序列表', run: () => prefixLine('- ') },
  { key: 'ol', label: '1.', title: '有序列表', run: () => prefixLine('1. ') },
  { key: 'quote', label: '❝', title: '引用', run: () => prefixLine('> ') },
  { key: 'link', label: '🔗', title: '链接', run: insertLink },
  { key: 'wiki', label: '[[ ]]', title: '关联 Wiki 页面', run: insertWikiLink },
  { key: 'table', label: '▦', title: '插入表格', run: insertTable },
]

if (typeof window !== 'undefined') {
  const narrow = window.matchMedia?.('(max-width: 1279px)')
  if (narrow?.matches) showPreview.value = false
}
</script>

<template>
  <div class="mde" :class="{ 'mde--disabled': disabled }">
    <div class="mde__toolbar" role="toolbar" aria-label="Markdown 工具栏">
      <button
        v-for="tool in TOOLS"
        :key="tool.key"
        type="button"
        class="mde__tool"
        :title="tool.title"
        :aria-label="tool.title"
        :disabled="disabled"
        @click="tool.run"
      >
        {{ tool.label }}
      </button>
      <span class="mde__spacer" />
      <span class="mde__stats">{{ stats.chars }} 字 · {{ stats.lines }} 行</span>
      <button
        v-if="previewEnabled"
        type="button"
        class="mde__toggle"
        :class="{ 'is-on': showPreview }"
        :aria-pressed="showPreview"
        @click="showPreview = !showPreview"
      >
        {{ showPreview ? '隐藏预览' : '显示预览' }}
      </button>
      <button type="button" class="mde__save" :disabled="disabled || saving" @click="emit('save')">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>

    <div class="mde__body" :class="{ 'mde__body--single': !showPreview }">
      <textarea
        ref="textarea"
        class="mde__input"
        :value="modelValue"
        :disabled="disabled"
        spellcheck="false"
        aria-label="Markdown 正文"
        placeholder="支持 Markdown；`[[页面ID|标题]]` 可关联其它 Wiki 页面"
        @input="onInput"
        @keydown="onKeydown"
      />
      <div v-if="showPreview" class="mde__preview" aria-label="预览">
        <div class="mde__preview-head">预览</div>
        <div class="mde__preview-body">
          <MarkdownView :source="modelValue" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mde {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.mde--disabled {
  background: var(--bg-table-head);
}
.mde__toolbar {
  flex: none;
  height: 40px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 8px;
  border-bottom: 1px solid var(--divider);
  background: var(--bg-table-head);
}
.mde__tool {
  min-width: 26px;
  height: 26px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: var(--r-cell);
  background: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--t-color) var(--ease), color var(--t-color) var(--ease);
}
.mde__tool:hover:not(:disabled) {
  background: var(--bg-card);
  border-color: var(--border);
  color: var(--color-primary);
}
.mde__tool:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.mde__tool:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.mde__spacer {
  flex: 1 1 auto;
}
.mde__stats {
  font-size: 11px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
  margin-right: 8px;
}
.mde__toggle {
  height: 24px;
  padding: 0 8px;
  margin-right: 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.mde__toggle.is-on {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.mde__save {
  height: 24px;
  padding: 0 12px;
  border: 1px solid var(--color-primary);
  border-radius: var(--r-ctl);
  background: var(--color-primary);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: background var(--t-color) var(--ease);
}
.mde__save:hover:not(:disabled) {
  background: var(--color-primary-hover);
}
.mde__save:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.mde__body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 16px;
  padding: 0;
}
.mde__input {
  flex: 1 1 700px;
  min-width: 0;
  padding: 16px 18px;
  border: 0;
  outline: none;
  resize: none;
  background: var(--bg-card);
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.75;
  tab-size: 2;
}
.mde__input:focus {
  box-shadow: inset 0 0 0 1px var(--color-primary-light);
}
.mde__input:disabled {
  color: var(--text-muted-aa);
}
.mde__preview {
  flex: 0 0 476px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--divider);
  background: var(--bg-card);
}
.mde__preview-head {
  flex: none;
  height: 28px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-weak);
  border-bottom: 1px solid var(--divider);
}
.mde__preview-body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 16px;
}
.mde__body--single .mde__input {
  flex: 1 1 auto;
}
@media (max-width: 1279px) {
  .mde__preview {
    display: none;
  }
}
</style>
