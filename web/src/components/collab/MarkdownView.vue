<script setup lang="ts">
import { computed } from 'vue'
import { extractHeadings, renderMarkdown } from '@/utils/markdown'

/**
 * 安全 Markdown 渲染（DESIGN §6 `MarkdownView`）。
 *
 * 只吃 `utils/markdown.ts` 产出的 HTML：那里先转义再拼装，**不解析原始 HTML**，
 * 所以这里可以直接 `v-html`，也不会出现 `<script>`/`onerror` 注入面。
 * 站内 Wiki 链接（`[[12|标题]]`）不整页刷新，交给父级走 router。
 */
const props = withDefaults(
  defineProps<{
    source: string
    /** 只读态：不再拦截内链点击（用于打印/对比视图） */
    plain?: boolean
  }>(),
  { plain: false },
)

const emit = defineEmits<{
  (e: 'open-page', pageId: number): void
}>()

const html = computed(() => renderMarkdown(props.source ?? ''))
const headings = computed(() => extractHeadings(props.source ?? ''))

function onClick(event: MouseEvent): void {
  if (props.plain) return
  const target = event.target as HTMLElement | null
  const anchor = target?.closest?.('a') as HTMLAnchorElement | null
  if (!anchor) return
  const pageId = anchor.dataset.wikiPage
  if (pageId) {
    event.preventDefault()
    const id = Number(pageId)
    if (Number.isFinite(id)) emit('open-page', id)
    return
  }
  const href = anchor.getAttribute('href') ?? ''
  if (/^https?:\/\//i.test(href)) {
    event.preventDefault()
    window.open(href, '_blank', 'noopener,noreferrer')
  }
}

defineExpose({ headings })
</script>

<template>
  <article class="md-view" @click="onClick">
    <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 已转义，不含原始 HTML -->
    <div class="md-view__body" v-html="html" />
    <p v-if="!source?.trim()" class="md-view__empty">这一页还是空的，点「编辑」开始写。</p>
  </article>
</template>

<style scoped>
.md-view {
  color: var(--text-body);
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}
.md-view__body :deep(h1) {
  font-size: 22px;
  line-height: 1.4;
  font-weight: 600;
  color: var(--text-title);
  margin: 0 0 16px;
}
.md-view__body :deep(h2) {
  font-size: 17px;
  line-height: 1.5;
  font-weight: 600;
  color: var(--text-title);
  margin: 28px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--divider);
}
.md-view__body :deep(h3) {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
  margin: 20px 0 8px;
}
.md-view__body :deep(p) {
  margin: 0 0 12px;
}
.md-view__body :deep(ul),
.md-view__body :deep(ol) {
  margin: 0 0 12px;
  padding-left: 22px;
}
.md-view__body :deep(li) {
  margin: 2px 0;
}
.md-view__body :deep(a) {
  color: var(--color-primary);
  text-decoration: none;
  border-bottom: 1px solid rgba(43, 111, 229, 0.32);
  cursor: pointer;
}
.md-view__body :deep(a:hover) {
  color: var(--color-primary-hover);
  border-bottom-color: var(--color-primary-hover);
}
.md-view__body :deep(code) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  padding: 1px 5px;
  border-radius: var(--r-cell);
  background: var(--bg-table-head, #f7f8fa);
  color: var(--text-title);
}
.md-view__body :deep(pre) {
  margin: 0 0 14px;
  padding: 12px 14px;
  border-radius: var(--r-card);
  background: var(--bg-table-head, #f7f8fa);
  border: 1px solid var(--divider);
  overflow-x: auto;
}
.md-view__body :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 12.5px;
  line-height: 1.6;
}
.md-view__body :deep(blockquote) {
  margin: 0 0 14px;
  padding: 8px 14px;
  border-left: 3px solid var(--border-strong);
  background: var(--bg-table-head, #f7f8fa);
  color: var(--text-secondary);
}
.md-view__body :deep(hr) {
  border: 0;
  border-top: 1px solid var(--divider);
  margin: 20px 0;
}
.md-view__body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0 0 16px;
  font-size: 13px;
}
.md-view__body :deep(th),
.md-view__body :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
  vertical-align: top;
}
.md-view__body :deep(th) {
  background: var(--bg-table-head);
  font-weight: 500;
  color: var(--text-title);
}
.md-view__body :deep(img) {
  max-width: 100%;
  border-radius: var(--r-card);
}
.md-view__empty {
  margin: 0;
  color: var(--text-weak);
}
</style>
