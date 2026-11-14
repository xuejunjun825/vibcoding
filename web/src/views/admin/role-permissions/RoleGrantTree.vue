<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { AccessLevel, ModuleGrant, PageGrant } from '@/api/types'
import { draftKey } from './useRoleGrants'

/**
 * 左树（DESIGN §3.2，320 宽）。
 *
 * 树只读 `draft` 并向外抛事件，不直接改状态：模块批量置档与页面单选是两种粒度，
 * 放在父层（`useRoleGrants`）才能保证计数、脏标记与草稿一致。
 * tri-state 的语义（I1）：模块框 = 该模块所有页面 MENU 置 ALLOW / DENY（不动动作）；
 * 页面框 = 该页 MENU 置 ALLOW / DENY；半选来自 RESTRICTED 或模块内页面不一致。
 * checkbox 与行点击分离：点框改档，点行其余位置 = 选中并在右栏打开。
 */
const props = defineProps<{
  modules: ModuleGrant[]
  draft: Record<string, AccessLevel>
  selected: string
  onlyGranted: boolean
}>()

const emit = defineEmits<{
  'update:selected': [pageCode: string]
  'update:onlyGranted': [boolean]
  'set-page': [pageCode: string, access: AccessLevel]
  'set-module': [moduleCode: string, access: AccessLevel]
}>()

type Tri = 'ALLOW' | 'DENY' | 'MIXED'

const query = ref('')
const collapsed = ref<Set<string>>(new Set())
const listRef = ref<HTMLDivElement | null>(null)

const keyword = computed(() => query.value.trim().toLowerCase())

function accessOf(code: string): AccessLevel {
  return props.draft[draftKey('MENU', code)] ?? 'DENY'
}

function moduleTri(module: ModuleGrant): Tri {
  const states = module.pages.map((page) => accessOf(page.code))
  if (states.length === 0) return 'DENY'
  if (states.every((state) => state === 'ALLOW')) return 'ALLOW'
  if (states.every((state) => state === 'DENY')) return 'DENY'
  return 'MIXED'
}

function pageTri(page: PageGrant): Tri {
  const access = accessOf(page.code)
  return access === 'RESTRICTED' ? 'MIXED' : access
}

function grantedActions(page: PageGrant): number {
  return page.actions.filter((action) => (props.draft[draftKey('ACTION', action.code)] ?? 'DENY') !== 'DENY')
    .length
}

function moduleGranted(module: ModuleGrant): number {
  return module.pages.filter((page) => accessOf(page.code) !== 'DENY').length
}

/** 搜索命中：页面名/码、动作名/码、模块名；命中动作时该页保留并显示命中数（I3） */
function actionHits(page: PageGrant): number {
  const q = keyword.value
  if (!q) return 0
  return page.actions.filter(
    (action) => action.code.toLowerCase().includes(q) || action.name.toLowerCase().includes(q),
  ).length
}

function pageMatches(page: PageGrant, moduleNameMatches: boolean): boolean {
  const q = keyword.value
  if (!q || moduleNameMatches) return true
  return (
    page.name.toLowerCase().includes(q) ||
    page.code.toLowerCase().includes(q) ||
    actionHits(page) > 0
  )
}

function visiblePages(module: ModuleGrant): PageGrant[] {
  const moduleNameMatches = keyword.value
    ? module.name.toLowerCase().includes(keyword.value) || module.code.toLowerCase().includes(keyword.value)
    : false
  return module.pages.filter((page) => {
    if (!pageMatches(page, moduleNameMatches)) return false
    if (!props.onlyGranted) return true
    // 仅看已授权：隐藏「页面 DENY 且本页无任何动作非 DENY」的页面
    return accessOf(page.code) !== 'DENY' || grantedActions(page) > 0
  })
}

const rows = computed(() =>
  props.modules
    .map((module) => ({ module, pages: visiblePages(module) }))
    .filter((row) => row.pages.length > 0),
)

/** 未过滤时的同构数据：模块计数按全量口径（I2），不受搜索/过滤影响 */
const allModules = computed(() => props.modules)

function isOpen(moduleCode: string): boolean {
  // 搜索命中时父级自动展开（I3）；清空搜索后回到用户自己的展开态
  if (keyword.value) return true
  return !collapsed.value.has(moduleCode)
}

function toggle(moduleCode: string): void {
  const next = new Set(collapsed.value)
  if (next.has(moduleCode)) next.delete(moduleCode)
  else next.add(moduleCode)
  collapsed.value = next
}

function onModuleBox(module: ModuleGrant): void {
  emit('set-module', module.code, moduleTri(module) === 'ALLOW' ? 'DENY' : 'ALLOW')
}

function onPageBox(page: PageGrant): void {
  emit('set-page', page.code, accessOf(page.code) === 'ALLOW' ? 'DENY' : 'ALLOW')
}

function select(page: PageGrant): void {
  emit('update:selected', page.code)
}

/** 琥珀点：本页要么动作已授而页面未开放，要么页面开着但绑定功能码被拒（两种都给 title） */
function pageDot(page: PageGrant): string {
  const granted = grantedActions(page)
  if (accessOf(page.code) === 'DENY' && granted > 0) return `${granted} 个动作已授但页面未开放`
  if (accessOf(page.code) !== 'DENY') {
    const denied = page.functionCodes.filter(
      (fn) => (props.draft[draftKey('FUNCTION', fn.code)] ?? 'DENY') === 'DENY',
    )
    if (denied.length > 0) return `绑定功能码 ${denied.map((fn) => fn.code).join('、')} 被拒，按钮仍可能不可点`
  }
  return ''
}

// 进入页面即把默认选中的页面滚到可见处（DESIGN §3.2「树滚动到该项并高亮」）
watch(
  () => props.selected,
  (code) => {
    if (!code) return
    void nextTick(() => {
      const el = listRef.value?.querySelector<HTMLElement>(`[data-page="${CSS.escape(code)}"]`)
      el?.scrollIntoView({ block: 'nearest' })
    })
  },
  { immediate: true },
)
</script>

<template>
  <div class="tree">
    <div class="tree__head">
      <input
        v-model="query"
        class="tree__search"
        type="search"
        placeholder="页面 / 页面码 / 动作"
        aria-label="搜索页面、页面码或动作"
      />
    </div>

    <div ref="listRef" class="tree__list" role="tree" aria-label="页面授权树">
      <div v-for="row in rows" :key="row.module.code" class="tree__group" role="group">
        <div
          class="node node--module"
          role="treeitem"
          tabindex="0"
          :aria-expanded="isOpen(row.module.code) ? 'true' : 'false'"
          @click="toggle(row.module.code)"
          @keydown.enter.prevent="toggle(row.module.code)"
          @keydown.space.prevent="toggle(row.module.code)"
        >
          <span class="node__twist" :class="{ 'is-open': isOpen(row.module.code) }" aria-hidden="true">▸</span>
          <input
            class="node__box"
            type="checkbox"
            :checked="moduleTri(row.module) === 'ALLOW'"
            :indeterminate="moduleTri(row.module) === 'MIXED'"
            :aria-label="`整个模块「${row.module.name}」授权`"
            @click.stop="onModuleBox(row.module)"
          />
          <span class="node__name">{{ row.module.name }}</span>
          <span class="node__count">
            {{ moduleGranted(row.module) }}/{{ row.module.pages.length }}
          </span>
        </div>

        <template v-if="isOpen(row.module.code)">
          <div
            v-for="page in row.pages"
            :key="page.code"
            class="node node--page"
            :class="{ 'is-selected': page.code === selected }"
            :data-page="page.code"
            role="treeitem"
            tabindex="0"
            :aria-selected="page.code === selected ? 'true' : 'false'"
            @click="select(page)"
            @keydown.enter.prevent="select(page)"
          >
            <input
              class="node__box"
              type="checkbox"
              :checked="pageTri(page) === 'ALLOW'"
              :indeterminate="pageTri(page) === 'MIXED'"
              :aria-label="`页面「${page.name}」准入`"
              @click.stop="onPageBox(page)"
            />
            <span class="node__name">{{ page.name }}</span>
            <code class="node__code">{{ page.code }}</code>
            <span v-if="actionHits(page) > 0" class="node__hits">{{ actionHits(page) }} 个动作命中</span>
            <span v-if="pageDot(page)" class="node__dot" :title="pageDot(page)" aria-hidden="true" />
          </div>
        </template>
      </div>

      <p v-if="rows.length === 0" class="tree__empty">
        {{ onlyGranted ? '当前角色还没有已授权的页面' : '没有匹配的页面' }}
      </p>
    </div>

    <div class="tree__foot">
      <label class="tree__only">
        <input
          type="checkbox"
          :checked="onlyGranted"
          @change="emit('update:onlyGranted', ($event.target as HTMLInputElement).checked)"
        />
        仅看已授权
      </label>
      <span class="tree__total">{{ allModules.length }} 模块 · {{ allModules.reduce((n, m) => n + m.pages.length, 0) }} 页</span>
    </div>
  </div>
</template>

<style scoped>
.tree {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.tree__head {
  padding: 10px 12px 8px;
  border-bottom: 1px solid var(--divider);
}
.tree__search {
  width: 100%;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-card);
  color: var(--text-title);
  font-size: 13px;
  outline: none;
  transition: border-color 120ms var(--ease);
}
.tree__search:focus-visible {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(43, 111, 229, 0.12);
}
.tree__search::placeholder {
  color: var(--text-secondary);
}
.tree__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 8px 8px;
}
.tree__group + .tree__group {
  margin-top: 2px;
}
.node {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  border-radius: 4px;
  color: var(--text-title);
  cursor: pointer;
  transition: background 120ms var(--ease);
}
.node:hover {
  background: var(--bg-hover);
}
.node:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.node--module {
  height: 36px;
  padding: 0 8px;
  font-size: 13px;
  font-weight: 500;
}
.node--page {
  height: 32px;
  padding: 0 8px 0 22px;
  font-size: 13px;
}
.node--page.is-selected {
  background: var(--bg-selected);
  box-shadow: inset 2px 0 0 var(--color-primary);
}
.node__twist {
  width: 12px;
  color: var(--text-secondary);
  font-size: 10px;
  text-align: center;
  transition: transform 160ms var(--ease);
}
.node__twist.is-open {
  transform: rotate(90deg);
}
.node__box {
  flex: none;
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: var(--color-primary);
  cursor: pointer;
}
.node__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node__count {
  margin-left: auto;
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.node__code {
  flex: none;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 112px;
}
.node__hits {
  flex: none;
  color: var(--perm-half, #c77700);
  font-size: 11px;
}
.node__dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--perm-half, #c77700);
}
.tree__empty {
  margin: 12px 4px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.tree__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--divider);
  color: var(--text-muted-aa);
  font-size: 12px;
}
.tree__only {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.tree__total {
  font-variant-numeric: tabular-nums;
}
</style>
