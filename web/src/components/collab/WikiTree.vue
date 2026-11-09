<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { WikiTreeNode, WikiTrashItem } from '@/api/collab'

/**
 * Wiki 页面树（DESIGN §6 `WikiTree`）。
 *
 * - 行高 32、缩进 16/级、最多 4 级（第 4 级不再给「新建子页」入口）；
 * - 键盘可达：↑↓ 移动、→ 展开/进子级、← 收起/回父级、Enter 打开、Home/End 跳首尾；
 * - 回收站复用同一个组件（`mode="trash"`），只是换了数据源与行尾动作，
 *   这样「剩余天数」这类口径不会在第二个组件里再写一遍。
 */
const props = withDefaults(
  defineProps<{
    nodes?: WikiTreeNode[]
    trash?: WikiTrashItem[]
    selectedId?: number | null
    expanded?: number[]
    mode?: 'tree' | 'trash'
    loading?: boolean
    readonly?: boolean
    maxLevel?: number
  }>(),
  {
    nodes: () => [],
    trash: () => [],
    selectedId: null,
    expanded: () => [],
    mode: 'tree',
    loading: false,
    readonly: false,
    maxLevel: 4,
  },
)

const emit = defineEmits<{
  (e: 'select', id: number): void
  (e: 'toggle', id: number): void
  (e: 'action', payload: { action: 'child' | 'rename' | 'move' | 'delete'; id: number }): void
  (e: 'restore', id: number): void
  (e: 'purge', id: number): void
}>()

interface FlatNode {
  id: number
  title: string
  level: number
  parentId: number | null
  hasChildren: boolean
  expanded: boolean
  remainingDays?: number
}

const listRef = ref<HTMLElement | null>(null)
const activeId = ref<number | null>(null)

const flat = computed<FlatNode[]>(() => {
  if (props.mode === 'trash') {
    return props.trash.map((item) => ({
      id: item.id,
      title: item.title,
      level: 0,
      parentId: null,
      hasChildren: false,
      expanded: false,
      remainingDays: item.remainingDays,
    }))
  }
  const out: FlatNode[] = []
  const walk = (nodes: WikiTreeNode[], level: number, parentId: number | null) => {
    for (const node of nodes) {
      const hasChildren = Boolean(node.children?.length) || Boolean(node.hasChildren)
      const expanded = props.expanded.includes(node.id)
      out.push({
        id: node.id,
        title: node.title,
        level,
        parentId,
        hasChildren,
        expanded,
      })
      if (hasChildren && expanded && node.children?.length) walk(node.children, level + 1, node.id)
    }
  }
  walk(props.nodes, 0, null)
  return out
})

watch(
  () => props.selectedId,
  (id) => {
    if (id !== null && id !== undefined) activeId.value = id
    void nextTick(() => scrollActiveIntoView())
  },
  { immediate: true },
)

function scrollActiveIntoView(): void {
  const list = listRef.value
  if (!list) return
  const selector = activeId.value !== null ? `[data-wiki-id="${activeId.value}"]` : ''
  const el = selector ? (list.querySelector(selector) as HTMLElement | null) : null
  el?.scrollIntoView({ block: 'nearest' })
}

function indexOf(id: number | null): number {
  return flat.value.findIndex((node) => node.id === id)
}

function pick(id: number): void {
  activeId.value = id
  if (props.mode === 'tree') emit('select', id)
}

function move(delta: number): void {
  const list = flat.value
  if (!list.length) return
  const current = indexOf(activeId.value)
  const next = current < 0 ? 0 : Math.min(list.length - 1, Math.max(0, current + delta))
  if (list[next]) {
    activeId.value = list[next].id
    void nextTick(() => scrollActiveIntoView())
  }
}

function onKeydown(event: KeyboardEvent): void {
  const list = flat.value
  if (!list.length) return
  const current = indexOf(activeId.value)
  const node = current >= 0 ? list[current] : list[0]

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      event.preventDefault()
      move(-1)
      break
    case 'Home':
      event.preventDefault()
      activeId.value = list[0].id
      void nextTick(() => scrollActiveIntoView())
      break
    case 'End':
      event.preventDefault()
      activeId.value = list[list.length - 1].id
      void nextTick(() => scrollActiveIntoView())
      break
    case 'ArrowRight': {
      event.preventDefault()
      if (!node) return
      if (node.hasChildren && !node.expanded) {
        emit('toggle', node.id)
      } else if (node.hasChildren) {
        const child = list[current + 1]
        if (child) {
          activeId.value = child.id
          if (props.mode === 'tree') emit('select', child.id)
        }
      }
      break
    }
    case 'ArrowLeft': {
      event.preventDefault()
      if (!node) return
      if (node.hasChildren && node.expanded) {
        emit('toggle', node.id)
      } else if (node.parentId !== null) {
        activeId.value = node.parentId
        if (props.mode === 'tree') emit('select', node.parentId)
      }
      break
    }
    case 'Enter':
    case ' ':
      event.preventDefault()
      if (node) pick(node.id)
      break
    default:
      break
  }
}

function rowAction(action: 'child' | 'rename' | 'move' | 'delete', id: number): void {
  emit('action', { action, id })
}

function canAddChild(node: FlatNode): boolean {
  return !props.readonly && props.mode === 'tree' && node.level < props.maxLevel - 1
}
</script>

<template>
  <div class="wt" :class="{ 'wt--loading': loading }">
    <div
      ref="listRef"
      class="wt__list"
      role="tree"
      :aria-label="mode === 'trash' ? '回收站页面' : 'Wiki 页面树'"
      :aria-multiselectable="false"
      tabindex="0"
      @keydown="onKeydown"
    >
      <div
        v-for="node in flat"
        :key="node.id"
        class="wt__row"
        :class="{
          'wt__row--active': selectedId === node.id,
          'wt__row--focus': activeId === node.id,
          'wt__row--muted': mode === 'trash',
        }"
        role="treeitem"
        :data-wiki-id="node.id"
        :aria-level="node.level + 1"
        :aria-selected="selectedId === node.id"
        :aria-expanded="node.hasChildren ? node.expanded : undefined"
        :tabindex="activeId === node.id ? 0 : -1"
        @click="pick(node.id)"
        @focus="activeId = node.id"
      >
        <span class="wt__indent" :style="{ width: `${node.level * 16}px` }" />
        <button
          v-if="mode === 'tree' && node.hasChildren"
          type="button"
          class="wt__chev"
          :aria-label="node.expanded ? `收起 ${node.title}` : `展开 ${node.title}`"
          tabindex="-1"
          @click.stop="emit('toggle', node.id)"
        >
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path
              :d="node.expanded ? 'M4.4 6 8 10l3.6-4' : 'M6 4.4 10 8l-4 3.6'"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
        <span v-else class="wt__chev wt__chev--ph" aria-hidden="true" />

        <svg class="wt__icon" viewBox="0 0 16 16" aria-hidden="true">
          <path
            d="M3.6 2.6h5.2l3.6 3.4v7.4H3.6V2.6ZM8.8 2.6v3.4h3.6M5.6 9h4.8M5.6 11h3.2"
            fill="none"
            stroke="currentColor"
            stroke-width="1.2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>

        <span class="wt__title" :title="node.title">{{ node.title }}</span>

        <span v-if="mode === 'trash'" class="wt__days" :class="{ 'wt__days--warn': (node.remainingDays ?? 0) <= 7 }">
          {{ node.remainingDays ?? 0 }} 天后清除
        </span>

        <template v-if="mode === 'trash'">
          <span class="wt__actions">
            <button type="button" class="wt__link" @click.stop="emit('restore', node.id)">恢复</button>
            <button type="button" class="wt__link wt__link--danger" @click.stop="emit('purge', node.id)">彻底删除</button>
          </span>
        </template>
        <template v-else-if="!readonly">
          <span class="wt__actions">
            <button
              v-if="canAddChild(node)"
              type="button"
              class="wt__icon-btn"
              title="新建子页"
              aria-label="新建子页"
              @click.stop="rowAction('child', node.id)"
            >
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path d="M8 3.4v9.2M3.4 8h9.2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
            </button>
            <el-dropdown trigger="click" placement="bottom-end" @command="(cmd: 'rename' | 'move' | 'delete') => rowAction(cmd, node.id)">
              <button type="button" class="wt__icon-btn" title="更多" aria-label="更多操作" @click.stop>
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path
                    d="M3.4 8h.01M8 8h.01M12.6 8h.01"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="move">移动到…</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </span>
        </template>
      </div>

      <div v-if="!flat.length && !loading" class="wt__empty">
        <slot name="empty">
          <p>{{ mode === 'trash' ? '回收站是空的' : '这个空间还没有页面' }}</p>
        </slot>
      </div>
    </div>
    <div v-if="loading" class="wt__loading" aria-live="polite">加载中…</div>
  </div>
</template>

<style scoped>
.wt {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.wt__list {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 4px 8px 12px;
  outline: none;
}
.wt__list:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus-ring, var(--color-primary));
  border-radius: var(--r-card);
}
.wt__row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 6px 0 4px;
  border-radius: var(--r-cell);
  cursor: pointer;
  transition: background var(--t-color) var(--ease), color var(--t-color) var(--ease);
}
.wt__row:hover {
  background: var(--bg-hover);
}
.wt__row:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.wt__row--active {
  background: var(--bg-selected);
  box-shadow: inset 2px 0 0 var(--color-primary);
}
.wt__row--active .wt__title {
  color: var(--color-primary);
  font-weight: 500;
}
.wt__row--focus:not(.wt__row--active) {
  box-shadow: inset 0 0 0 1px var(--border-strong);
}
.wt__row--muted .wt__title {
  color: var(--text-body);
}
.wt__indent {
  flex: none;
}
.wt__chev {
  flex: none;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-muted-aa);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 2px;
}
.wt__chev svg {
  width: 16px;
  height: 16px;
}
.wt__chev--ph {
  cursor: default;
}
.wt__chev:hover {
  color: var(--text-title);
}
.wt__icon {
  flex: none;
  width: 14px;
  height: 14px;
  color: var(--text-muted-aa);
}
.wt__row--active .wt__icon {
  color: var(--color-primary);
}
.wt__title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wt__days {
  flex: none;
  font-size: 11px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}
.wt__days--warn {
  color: var(--warning-ink);
}
.wt__actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--t-color) var(--ease);
}
.wt__row:hover .wt__actions,
.wt__row:focus-within .wt__actions,
.wt__row--active .wt__actions {
  opacity: 1;
}
.wt__icon-btn {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: var(--r-cell);
  background: none;
  color: var(--text-muted-aa);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.wt__icon-btn svg {
  width: 14px;
  height: 14px;
}
.wt__icon-btn:hover {
  background: var(--bg-selected);
  color: var(--color-primary);
}
.wt__link {
  border: 0;
  background: none;
  padding: 0 4px;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.wt__link:hover {
  text-decoration: underline;
}
.wt__link--danger {
  color: var(--danger-ink);
}
.wt__empty {
  padding: 24px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--text-weak);
}
.wt__loading {
  padding: 8px;
  font-size: 12px;
  color: var(--text-weak);
}
.wt--loading .wt__list {
  opacity: 0.6;
}
</style>
