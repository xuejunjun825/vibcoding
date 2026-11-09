<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  createPage,
  createSpace,
  deletePage,
  diffVersions,
  fetchPage,
  fetchSpaces,
  fetchTree,
  fetchTrash,
  fetchVersions,
  linkPage,
  movePage,
  purgePage,
  restorePage,
  rollbackPage,
  savePage,
  searchWiki,
  unlinkPage,
  updateSpace,
  type WikiDiff,
  type WikiPageDetail,
  type WikiSearchResult,
  type WikiSpace,
  type WikiTreeNode,
  type WikiTrashItem,
  type WikiVersion,
  type WikiVisibility,
} from '@/api/collab'
import MarkdownEditor from '@/components/collab/MarkdownEditor.vue'
import MarkdownView from '@/components/collab/MarkdownView.vue'
import WikiDiffDrawer from '@/components/collab/WikiDiffDrawer.vue'
import WikiTree from '@/components/collab/WikiTree.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { useWikiPrefs } from '@/composables/useCollabPrefs'
import { useDictStore } from '@/stores/dict'
import { extractHeadings } from '@/utils/markdown'
import '@/views/deliver/shared.css'

/**
 * CTeam Wiki（DESIGN W1–W5 · AC-05/06/07）。
 *
 * 可见性、差异、检索计数全部来自服务端：页面不自己过滤空间、不自己算 diff、
 * 也不把「搜到的条数」当成权限之外的数字——服务端的 total 已经只计可见命中。
 * 布局按 DESIGN：220（树）+ 16 + 700（纸）+ 16 + 240（目录/属性/关联）。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const prefs = useWikiPrefs()
const dict = useDictStore()

const canEditAny = computed(() => can('WIKI_EDIT'))
const canAdmin = computed(() => can('WIKI_ADMIN'))
const canEditPage = computed(() => (page.value ? page.value.page.canEdit !== false : canEditAny.value))

const spaces = ref<WikiSpace[]>([])
const spaceId = ref<number | null>(null)
const spacesLoading = ref(false)
const tree = ref<WikiTreeNode[]>([])
const treeLoading = ref(false)
const treeError = ref('')
const trunk = ref<WikiTreeNode[]>([])

const page = ref<WikiPageDetail | null>(null)
const pageLoading = ref(false)
const pageError = ref<{ message: string; code?: string; status?: number } | null>(null)

const mode = ref<'tree' | 'trash'>('tree')
const trash = ref<WikiTrashItem[]>([])
const trashLoading = ref(false)

const searching = ref(false)
const searchQuery = ref('')
const searchLoading = ref(false)
const searchResult = ref<WikiSearchResult | null>(null)
const searchPage = ref(1)
const searchError = ref('')

const editing = ref(false)
const draftTitle = ref('')
const draftBody = ref('')
const dirty = ref(false)
const saving = ref(false)
const editNotice = ref('')
const conflict = ref<{ message: string; code?: string } | null>(null)

const expandedIds = ref<number[]>(prefs.expanded.value.map((item) => Number(item)).filter((id) => Number.isFinite(id)))

const versionsOpen = ref(false)
const versions = ref<WikiVersion[]>([])
const versionsLoading = ref(false)
const versionsError = ref('')

const diffOpen = ref(false)
const diffFrom = ref<number | null>(null)
const diffTo = ref<number | null>(null)
const diff = ref<WikiDiff | null>(null)
const diffLoading = ref(false)
const diffError = ref('')

const pageDialog = ref({ open: false, title: '', parentId: null as number | null, submitting: false, error: '' })
const spaceDialog = ref({
  open: false,
  editingId: null as number | null,
  name: '',
  visibility: 'DOMAIN' as WikiVisibility,
  description: '',
  submitting: false,
  error: '',
})
const moveDialog = ref({ open: false, targetId: null as number | null, submitting: false, error: '' })
const linkDialog = ref({ open: false, bizType: 'REQUIREMENT', bizId: null as number | null, submitting: false, error: '' })

const VISIBILITIES: { value: WikiVisibility; label: string; hint: string }[] = [
  { value: 'INHERIT', label: '继承域范围', hint: '按页面所属需求的域/厂商可见性判定' },
  { value: 'DOMAIN', label: '仅本域', hint: '同业务域成员可见，默认值' },
  { value: 'VENDOR', label: '仅同厂商', hint: '仅同厂商用户可见（供应商隔离）' },
]

const BIZ_TYPES = ['REQUIREMENT', 'TASK', 'RELEASE', 'CASE']

const currentSpace = computed(() => spaces.value.find((item) => item.id === spaceId.value) ?? null)
const headings = computed(() => (page.value ? extractHeadings(page.value.page.contentMd ?? '') : []))
const breadcrumb = computed(() => page.value?.breadcrumb ?? [])
const totalPages = computed(() => countNodes(tree.value))
const editable = computed(() => canEditPage.value && !page.value?.page.deletedAt)

function countNodes(nodes: WikiTreeNode[]): number {
  return nodes.reduce((sum, node) => sum + 1 + countNodes(node.children ?? []), 0)
}

/** 后端可能给平铺列表；统一成嵌套，WikiTree 的展开/缩进只认一种形状 */
function normalizeTree(nodes: WikiTreeNode[]): WikiTreeNode[] {
  const map = new Map<number, WikiTreeNode>()
  for (const node of nodes) map.set(node.id, { ...node, children: [] })
  const roots: WikiTreeNode[] = []
  for (const node of nodes) {
    const target = map.get(node.id)
    if (!target) continue
    const parent = node.parentId !== null && node.parentId !== undefined ? map.get(node.parentId) : undefined
    if (parent) parent.children = [...(parent.children ?? []), target]
    else roots.push(target)
  }
  return roots
}

function flatten(nodes: WikiTreeNode[], depth = 0): { node: WikiTreeNode; depth: number }[] {
  const out: { node: WikiTreeNode; depth: number }[] = []
  for (const node of nodes) {
    out.push({ node, depth })
    if (node.children?.length) out.push(...flatten(node.children, depth + 1))
  }
  return out
}

const flatTree = computed(() => flatten(trunk.value))

/* ------------------------------------------------------------------ 加载 */

async function loadSpaces(): Promise<void> {
  spacesLoading.value = true
  try {
    spaces.value = await fetchSpaces()
    const remembered = prefs.spaceId.value
    const exists = spaces.value.some((item) => item.id === remembered)
    spaceId.value = exists ? remembered : (spaces.value[0]?.id ?? null)
    if (spaceId.value !== null) await loadTree()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    pageError.value = { message: apiError?.message ?? '空间列表加载失败', code: apiError?.code, status: apiError?.status }
  } finally {
    spacesLoading.value = false
  }
}

async function loadTree(): Promise<void> {
  if (spaceId.value === null) {
    tree.value = []
    trunk.value = []
    return
  }
  treeLoading.value = true
  treeError.value = ''
  try {
    const raw = await fetchTree(spaceId.value)
    tree.value = raw
    trunk.value = normalizeTree(raw)
    const remembered = prefs.pageId.value
    const exists = remembered !== null && raw.some((node) => node.id === remembered)
    const initial = exists ? remembered : (raw[0]?.id ?? null)
    if (!page.value || !raw.some((node) => node.id === page.value?.page.id)) {
      if (initial !== null) await openPage(initial)
    }
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    treeError.value = apiError?.message ?? '页面树加载失败'
  } finally {
    treeLoading.value = false
  }
}

async function openPage(pageId: number, pushQuery = true): Promise<void> {
  pageLoading.value = true
  pageError.value = null
  conflict.value = null
  editing.value = false
  dirty.value = false
  try {
    const detail = await fetchPage(pageId)
    page.value = detail
    prefs.remember(spaceId.value, pageId)
    // 面包屑整条展开，避免「进去了但树上看不到自己在哪」
    const ids = [...detail.breadcrumb.map((item) => item.id), pageId]
    const merged = [...new Set([...expandedIds.value, ...ids])]
    expandedIds.value = merged
    prefs.expandTo(merged.map(String))
    if (pushQuery) void router.replace({ name: 'wiki', query: { ...route.query, page: String(pageId) } })
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    pageError.value = {
      message: apiError?.message ?? '页面加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
  } finally {
    pageLoading.value = false
  }
}

onMounted(async () => {
  const fromQuery = Number(route.query.page)
  if (Number.isFinite(fromQuery) && fromQuery > 0) prefs.pageId.value = fromQuery
  await loadSpaces()
})

watch(spaceId, async (next, prev) => {
  if (next === prev) return
  prefs.remember(next, null)
  page.value = null
  searching.value = false
  mode.value = 'tree'
  await loadTree()
})

function onToggleExpanded(id: number): void {
  expandedIds.value = expandedIds.value.includes(id)
    ? expandedIds.value.filter((item) => item !== id)
    : [...expandedIds.value, id]
  prefs.expandTo(expandedIds.value.map(String))
}

/* ------------------------------------------------------------------ 检索 */

async function runSearch(target = 1): Promise<void> {
  const q = searchQuery.value.trim()
  if (!q) {
    ElMessage.warning('输入关键词再搜索')
    return
  }
  searching.value = true
  searchLoading.value = true
  searchError.value = ''
  searchPage.value = target
  try {
    searchResult.value = await searchWiki(q, target, 20)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    searchResult.value = null
    searchError.value =
      apiError?.code === 'WIKI_SEARCH_EMPTY' ? '关键词为空或只有停用词，换个词再试' : (apiError?.message ?? '搜索失败')
  } finally {
    searchLoading.value = false
  }
}

function closeSearch(): void {
  searching.value = false
  searchResult.value = null
}

/* ------------------------------------------------------------------ 编辑 */

function beginEdit(): void {
  if (!page.value || !editable.value) return
  draftTitle.value = page.value.page.title
  draftBody.value = page.value.page.contentMd ?? ''
  dirty.value = false
  editNotice.value = ''
  editing.value = true
}

async function save(): Promise<void> {
  if (!page.value) return
  if (!draftTitle.value.trim()) {
    editNotice.value = '标题不能为空'
    return
  }
  saving.value = true
  editNotice.value = ''
  conflict.value = null
  try {
    const result = await savePage(page.value.page.id, {
      title: draftTitle.value.trim(),
      contentMd: draftBody.value,
      baseVersion: page.value.page.currentVersion,
    })
    editing.value = false
    dirty.value = false
    if (result.noChange) {
      editNotice.value = '内容没有变化，未生成新版本'
    } else {
      editNotice.value = `已保存为 v${result.versionNo}`
    }
    await openPage(page.value.page.id, false)
    await loadTree()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    if (apiError?.code === 'WIKI_EDIT_CONFLICT') {
      conflict.value = {
        message: '这一页在你编辑期间被别人保存过，你的改动还没写入。',
        code: apiError.code,
      }
      return
    }
    if (apiError?.code === 'WIKI_DEPTH_EXCEEDED') {
      editNotice.value = 'Wiki 最多 4 级，当前层级已到上限'
      return
    }
    editNotice.value = apiError?.message ?? '保存失败'
  } finally {
    saving.value = false
  }
}

async function reloadAfterConflict(): Promise<void> {
  conflict.value = null
  editing.value = false
  dirty.value = false
  if (page.value) await openPage(page.value.page.id, false)
}

async function saveAsNew(): Promise<void> {
  if (!page.value || spaceId.value === null) return
  conflict.value = null
  try {
    const created = await createPage({
      spaceId: spaceId.value,
      parentId: page.value.page.id,
      title: `${draftTitle.value.trim()}（副本）`,
      contentMd: draftBody.value,
    })
    editing.value = false
    dirty.value = false
    await loadTree()
    await openPage(created.page.id)
    ElMessage.success('已另存为新页面')
  } catch (err) {
    editNotice.value = err instanceof ApiError ? err.message : '另存失败'
  }
}

/* ------------------------------------------------------------------ 版本 */

async function openVersions(): Promise<void> {
  if (!page.value) return
  versionsOpen.value = true
  versionsLoading.value = true
  versionsError.value = ''
  try {
    versions.value = await fetchVersions(page.value.page.id)
  } catch (err) {
    versionsError.value = err instanceof ApiError ? err.message : '版本列表加载失败'
  } finally {
    versionsLoading.value = false
  }
}

async function openDiff(from: number, to: number): Promise<void> {
  if (!page.value) return
  diffFrom.value = from
  diffTo.value = to
  diffOpen.value = true
  diffLoading.value = true
  diffError.value = ''
  diff.value = null
  try {
    diff.value = await diffVersions(page.value.page.id, from, to)
  } catch (err) {
    diffError.value = err instanceof ApiError ? err.message : '差异加载失败'
  } finally {
    diffLoading.value = false
  }
}

async function onDiffChange(payload: { from: number; to: number }): Promise<void> {
  await openDiff(payload.from, payload.to)
}

async function rollback(versionNo: number): Promise<void> {
  if (!page.value) return
  try {
    await ElMessageBox.confirm(
      `回滚会用 v${versionNo} 的内容生成一个新版本（历史版本不会被改写），继续？`,
      '回滚页面',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const result = await rollbackPage(page.value.page.id, versionNo)
    diffOpen.value = false
    versionsOpen.value = false
    await openPage(page.value.page.id, false)
    await openVersions()
    ElMessage.success(`已回滚，生成 v${result.versionNo}`)
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '回滚失败')
  }
}

/* ------------------------------------------------------------------ 页面动作 */

function openCreatePage(parentId: number | null = null): void {
  pageDialog.value = { open: true, title: '', parentId, submitting: false, error: '' }
}

async function submitCreatePage(): Promise<void> {
  if (spaceId.value === null) return
  if (!pageDialog.value.title.trim()) {
    pageDialog.value.error = '标题不能为空'
    return
  }
  pageDialog.value.submitting = true
  pageDialog.value.error = ''
  try {
    const created = await createPage({
      spaceId: spaceId.value,
      parentId: pageDialog.value.parentId,
      title: pageDialog.value.title.trim(),
      contentMd: '',
    })
    const parent = pageDialog.value.parentId
    pageDialog.value.open = false
    if (parent !== null) {
      expandedIds.value = [...new Set([...expandedIds.value, parent])]
      prefs.expandTo(expandedIds.value.map(String))
    }
    await loadTree()
    await openPage(created.page.id)
    beginEdit()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    pageDialog.value.error =
      apiError?.code === 'WIKI_DEPTH_EXCEEDED' ? '最多 4 级，不能在更深的位置新建' : (apiError?.message ?? '新建失败')
  } finally {
    pageDialog.value.submitting = false
  }
}

async function removePage(): Promise<void> {
  if (!page.value) return
  const target = page.value.page
  try {
    await ElMessageBox.confirm(
      `删除「${target.title}」及其子页？页面进入回收站，30 天内可恢复。`,
      '删除页面',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deletePage(target.id)
    page.value = null
    await loadTree()
    if (spaceId.value !== null) trash.value = await fetchTrash(spaceId.value)
    ElMessage.success('已移入回收站')
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '删除失败')
  }
}

/** 父页面来自树（WikiPageMeta 不带 parentId），避免用面包屑猜层级 */
function parentIdOf(pageId: number): number | null {
  const found = flatTree.value.find((entry) => entry.node.id === pageId)
  return found?.node.parentId ?? null
}

function openMove(): void {
  if (!page.value) return
  moveDialog.value = { open: true, targetId: parentIdOf(page.value.page.id), submitting: false, error: '' }
}

async function submitMove(): Promise<void> {
  if (!page.value) return
  moveDialog.value.submitting = true
  moveDialog.value.error = ''
  try {
    await movePage(page.value.page.id, moveDialog.value.targetId)
    moveDialog.value.open = false
    await loadTree()
    await openPage(page.value.page.id, false)
    ElMessage.success('已移动')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    moveDialog.value.error =
      apiError?.code === 'WIKI_CYCLE' ? '不能移动到自己的子页下面' : (apiError?.message ?? '移动失败')
  } finally {
    moveDialog.value.submitting = false
  }
}

async function rename(node: WikiTreeNode): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('新的页面标题', '重命名', {
      inputValue: node.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (input: string) => (input.trim() ? true : '标题不能为空'),
    })
    if (!page.value || page.value.page.id !== node.id) await openPage(node.id)
    if (page.value) {
      draftTitle.value = value.trim()
      await save()
    }
    await loadTree()
  } catch {
    /* 取消 */
  }
}

/* ------------------------------------------------------------------ 回收站 */

async function openTrash(): Promise<void> {
  mode.value = 'trash'
  if (spaceId.value === null) return
  trashLoading.value = true
  try {
    trash.value = await fetchTrash(spaceId.value)
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '回收站加载失败')
  } finally {
    trashLoading.value = false
  }
}

async function onRestore(id: number): Promise<void> {
  try {
    await restorePage(id)
    ElMessage.success('已恢复')
    await openTrash()
    await loadTree()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    ElMessage.error(
      apiError?.code === 'WIKI_RESTORE_EXPIRED' ? '超过 30 天恢复窗口，无法恢复' : (apiError?.message ?? '恢复失败'),
    )
  }
}

async function onPurge(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm('彻底删除后不可恢复，继续？', '彻底删除', {
      confirmButtonText: '彻底删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await purgePage(id)
    await openTrash()
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '删除失败')
  }
}

function backToTree(): void {
  mode.value = 'tree'
}

/* ------------------------------------------------------------------ 空间 */

function openSpaceDialog(space: WikiSpace | null): void {
  spaceDialog.value = {
    open: true,
    editingId: space?.id ?? null,
    name: space?.name ?? '',
    visibility: (space?.visibility as WikiVisibility) ?? 'DOMAIN',
    description: space?.description ?? '',
    submitting: false,
    error: '',
  }
}

async function submitSpace(): Promise<void> {
  if (!spaceDialog.value.name.trim()) {
    spaceDialog.value.error = '空间名不能为空'
    return
  }
  spaceDialog.value.submitting = true
  spaceDialog.value.error = ''
  try {
    if (spaceDialog.value.editingId !== null) {
      const updated = await updateSpace(spaceDialog.value.editingId, {
        name: spaceDialog.value.name.trim(),
        visibility: spaceDialog.value.visibility,
        description: spaceDialog.value.description,
      })
      spaces.value = spaces.value.map((item) => (item.id === updated.id ? updated : item))
    } else {
      const created = await createSpace({
        name: spaceDialog.value.name.trim(),
        visibility: spaceDialog.value.visibility,
        description: spaceDialog.value.description,
      })
      spaces.value = [...spaces.value, created]
      spaceId.value = created.id
    }
    spaceDialog.value.open = false
    await loadSpaces()
  } catch (err) {
    spaceDialog.value.error = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    spaceDialog.value.submitting = false
  }
}

/* ------------------------------------------------------------------ 关联 */

function openLink(): void {
  linkDialog.value = { open: true, bizType: 'REQUIREMENT', bizId: null, submitting: false, error: '' }
}

async function submitLink(): Promise<void> {
  if (!page.value || !linkDialog.value.bizId) {
    linkDialog.value.error = '请填写对象 ID'
    return
  }
  linkDialog.value.submitting = true
  linkDialog.value.error = ''
  try {
    await linkPage(page.value.page.id, { bizType: linkDialog.value.bizType, bizId: linkDialog.value.bizId })
    linkDialog.value.open = false
    await openPage(page.value.page.id, false)
  } catch (err) {
    linkDialog.value.error = err instanceof ApiError ? err.message : '关联失败'
  } finally {
    linkDialog.value.submitting = false
  }
}

async function removeLink(bizType: string, bizId: number): Promise<void> {
  if (!page.value) return
  try {
    await unlinkPage(page.value.page.id, { bizType, bizId })
    await openPage(page.value.page.id, false)
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '解除关联失败')
  }
}

/* ------------------------------------------------------------------ 目录 */

function jumpToHeading(id: string): void {
  const el = document.getElementById(id)
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function goBack(): void {
  if (editing.value && dirty.value) {
    void ElMessageBox.confirm('有未保存的修改，确定离开编辑？', '未保存', {
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
      .then(() => {
        editing.value = false
        dirty.value = false
      })
      .catch(() => undefined)
    return
  }
  editing.value = false
  dirty.value = false
}

watch(editing, (on) => {
  if (!on) return
  window.addEventListener('beforeunload', beforeUnload)
})
function beforeUnload(event: BeforeUnloadEvent): void {
  if (!editing.value || !dirty.value) return
  event.preventDefault()
}
</script>

<template>
  <div class="dl-page wiki-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">Wiki</h1>
        <span class="dl-meta">
          {{ currentSpace?.name ?? '未选择空间' }} ·
          {{ currentSpace ? dict.label('wikiVisibility', currentSpace.visibility) : '—' }} ·
          共 {{ totalPages }} 页
        </span>
      </div>
      <div class="dl-head__actions">
        <el-input
          v-model="searchQuery"
          size="small"
          placeholder="搜索页面标题与正文"
          clearable
          style="width: 220px"
          @keyup.enter="runSearch(1)"
          @clear="closeSearch"
        >
          <template #prefix>
            <span class="wiki-search-icon" aria-hidden="true">⌕</span>
          </template>
        </el-input>
        <el-button size="small" @click="runSearch(1)">搜索</el-button>
        <el-button v-if="canAdmin" size="small" @click="openSpaceDialog(currentSpace)">空间设置</el-button>
        <el-button v-if="canEditAny" size="small" type="primary" :disabled="spaceId === null" @click="openCreatePage(null)">
          新建页面
        </el-button>
      </div>
    </header>

    <p class="dl-hint">
      草稿即版本：每次保存产出一个不可变版本，对比与回滚都基于版本号；搜索只统计你有权看到的页面。
    </p>

    <ErrorState
      v-if="pageError && !page"
      :message="pageError.message"
      :code="pageError.code"
      :status="pageError.status"
      @retry="loadSpaces()"
    />

    <div v-else class="wiki-layout">
      <aside class="wiki-col wiki-col--tree" aria-label="页面树">
        <div class="wiki-tree-head">
          <el-select
            v-model="spaceId"
            size="small"
            placeholder="选择空间"
            :loading="spacesLoading"
            class="wiki-space-select"
          >
            <el-option v-for="item in spaces" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
          <button
            v-if="canAdmin"
            type="button"
            class="wiki-icon-btn"
            title="新建空间"
            aria-label="新建空间"
            @click="openSpaceDialog(null)"
          >
            +
          </button>
        </div>

        <div class="wiki-tree-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            class="wiki-tree-tab"
            :class="{ 'is-active': mode === 'tree' }"
            :aria-selected="mode === 'tree'"
            @click="backToTree"
          >
            页面
          </button>
          <button
            type="button"
            role="tab"
            class="wiki-tree-tab"
            :class="{ 'is-active': mode === 'trash' }"
            :aria-selected="mode === 'trash'"
            @click="openTrash"
          >
            回收站
            <span v-if="trash.length" class="wiki-tree-tab__count">{{ trash.length }}</span>
          </button>
        </div>

        <div v-if="treeError" class="wiki-tree-error">
          <span>{{ treeError }}</span>
          <button type="button" class="wiki-link" @click="loadTree()">重试</button>
        </div>

        <WikiTree
          v-else-if="mode === 'trash'"
          mode="trash"
          :trash="trash"
          :loading="trashLoading"
          :selected-id="page?.page.id ?? null"
          @restore="onRestore"
          @purge="onPurge"
        />
        <WikiTree
          v-else
          :nodes="trunk"
          :selected-id="page?.page.id ?? null"
          :expanded="expandedIds"
          :loading="treeLoading"
          :readonly="!canEditAny"
          @select="openPage"
          @toggle="onToggleExpanded"
          @action="(payload) => {
            if (payload.action === 'child') openCreatePage(payload.id)
            else if (payload.action === 'rename') {
              const found = flatTree.find((item) => item.node.id === payload.id)
              if (found) rename(found.node)
            } else if (payload.action === 'move') {
              if (page?.page.id !== payload.id) openPage(payload.id)
              openMove()
            } else if (payload.action === 'delete') {
              if (page?.page.id === payload.id) removePage()
              else openPage(payload.id).then(() => removePage())
            }
          }"
        >
          <template #empty>
            <EmptyState
              v-if="spaceId !== null"
              title="这个空间还没有页面"
              description="新建第一页，或用 `[[页面ID|标题]]` 把已有页面串起来。"
              icon="file"
              compact
              :action-text="canEditAny ? '新建页面' : ''"
              @action="openCreatePage(null)"
            />
          </template>
        </WikiTree>
      </aside>

      <main class="wiki-col wiki-col--paper">
        <!-- 搜索态 -->
        <section v-if="searching" class="wiki-paper wiki-paper--search" aria-label="搜索结果">
          <div class="wiki-search-head">
            <span>「{{ searchQuery }}」的搜索结果</span>
            <span v-if="searchResult" class="wiki-search-total">
              共 {{ searchResult.total }} 条（仅统计可见结果）
            </span>
            <button type="button" class="wiki-link" @click="closeSearch">返回页面</button>
          </div>
          <p v-if="searchResult?.degraded" class="wiki-search-degraded">
            全文索引不可用，已降级为标题/正文 LIKE 匹配；结果可能少于预期。
          </p>
          <div v-if="searchError" class="wiki-inline-error" role="alert">{{ searchError }}</div>
          <div v-else-if="searchLoading" class="wiki-search-skeleton" aria-busy="true">
            <div v-for="n in 4" :key="n" class="wiki-skeleton-row" />
          </div>
          <div v-else-if="searchResult?.items.length" class="wiki-search-list">
            <button
              v-for="hit in searchResult.items"
              :key="hit.pageId"
              type="button"
              class="wiki-search-row"
              @click="closeSearch(); openPage(hit.pageId)"
            >
              <span class="wiki-search-title">{{ hit.title }}</span>
              <span class="wiki-search-snippet">{{ hit.snippet }}</span>
              <span class="wiki-search-meta">{{ hit.spaceName }} · {{ (hit.updatedAt ?? '').replace('T', ' ').slice(0, 16) }}</span>
            </button>
          </div>
          <EmptyState v-else title="没有匹配的页面" description="换个关键词，或确认该页面对你可见。" icon="search" compact />
          <div v-if="searchResult && searchResult.total > searchResult.size" class="wiki-search-pager">
            <el-pagination
              layout="prev, pager, next"
              :current-page="searchPage"
              :page-size="searchResult.size"
              :total="searchResult.total"
              background
              @current-change="(p: number) => runSearch(p)"
            />
          </div>
        </section>

        <!-- 阅读 / 编辑 -->
        <section v-else class="wiki-paper">
          <div v-if="page?.page.deletedAt" class="wiki-deleted" role="status">
            <span>
              这一页在回收站，{{ page.page.remainingDays ?? 0 }} 天后彻底清除。
            </span>
            <button type="button" class="wiki-link" @click="onRestore(page.page.id)">恢复</button>
          </div>

          <nav v-if="breadcrumb.length > 1" class="wiki-crumb" aria-label="面包屑">
            <template v-for="(item, index) in breadcrumb" :key="item.id">
              <button type="button" class="wiki-crumb__item" @click="openPage(item.id)">{{ item.title }}</button>
              <span v-if="index < breadcrumb.length - 1" class="wiki-crumb__sep" aria-hidden="true">/</span>
            </template>
          </nav>

          <div v-if="conflict" class="wiki-conflict" role="alert">
            <div class="wiki-conflict__body">
              <strong>{{ conflict.message }}</strong>
              <span>你的编辑内容还在下面，可以另存为新页面，或放弃后重新加载最新版本。</span>
            </div>
            <div class="wiki-conflict__actions">
              <button type="button" class="wiki-btn" @click="saveAsNew">另存为新页面</button>
              <button type="button" class="wiki-btn wiki-btn--ghost" @click="reloadAfterConflict">放弃并重新加载</button>
            </div>
          </div>

          <ErrorState
            v-if="pageError"
            compact
            :message="pageError.message"
            :code="pageError.code"
            :status="pageError.status"
            @retry="page?.page.id ? openPage(page.page.id, false) : loadTree()"
          />

          <div v-else-if="pageLoading" class="wiki-paper-skeleton" aria-busy="true">
            <div class="wiki-skeleton-title" />
            <div v-for="n in 6" :key="n" class="wiki-skeleton-row" />
          </div>

          <template v-else-if="page">
            <header class="wiki-paper-head">
              <div class="wiki-paper-title">
                <h2 v-if="!editing">{{ page.page.title }}</h2>
                <el-input
                  v-else
                  v-model="draftTitle"
                  size="large"
                  maxlength="120"
                  placeholder="页面标题"
                  aria-label="页面标题"
                />
                <p class="wiki-paper-sub">
                  v{{ page.page.currentVersion }} · {{ page.page.updatedByName ?? '—' }} ·
                  {{ (page.page.updatedAt ?? '').replace('T', ' ').slice(0, 16) }}
                  <span v-if="editing && dirty" class="wiki-dirty">未保存</span>
                </p>
              </div>
              <div class="wiki-paper-actions">
                <template v-if="editing">
                  <button type="button" class="wiki-btn wiki-btn--primary" :disabled="saving" @click="save">
                    {{ saving ? '保存中…' : '保存' }}
                  </button>
                  <button type="button" class="wiki-btn wiki-btn--ghost" :disabled="saving" @click="goBack">取消</button>
                </template>
                <template v-else>
                  <button v-if="editable" type="button" class="wiki-btn wiki-btn--primary" @click="beginEdit">编辑</button>
                  <button type="button" class="wiki-btn" @click="openVersions">版本</button>
                  <el-dropdown trigger="click">
                    <button type="button" class="wiki-btn wiki-btn--ghost" aria-label="更多操作">更多</button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item :disabled="!editable" @click="openCreatePage(page.page.id)">新建子页</el-dropdown-item>
                        <el-dropdown-item :disabled="!editable" @click="openMove">移动到…</el-dropdown-item>
                        <el-dropdown-item :disabled="!editable" @click="openLink">添加关联</el-dropdown-item>
                        <el-dropdown-item divided :disabled="!editable" @click="removePage">删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
              </div>
            </header>

            <p v-if="editNotice" class="wiki-edit-notice" role="status">{{ editNotice }}</p>

            <div v-if="!editing" class="wiki-read">
              <MarkdownView :source="page.page.contentMd" @open-page="openPage" />
            </div>
            <MarkdownEditor
              v-else
              v-model="draftBody"
              class="wiki-editor"
              :saving="saving"
              :disabled="saving"
              @save="save"
              @change-dirty="dirty = true"
            />
          </template>

          <EmptyState
            v-else
            :title="spaceId === null ? '还没有 Wiki 空间' : '选择左侧页面开始阅读'"
            :description="spaceId === null ? '空间决定可见范围（继承域 / 仅本域 / 仅同厂商）。' : '或者新建一页，把需求、版本的背景知识沉淀下来。'"
            icon="file"
            :action-text="spaceId === null && canAdmin ? '新建空间' : ''"
            @action="openSpaceDialog(null)"
          />
        </section>
      </main>

      <aside class="wiki-col wiki-col--side" aria-label="页内目录与属性">
        <section class="wiki-side-card">
          <h3 class="wiki-side-title">本页目录</h3>
          <nav v-if="headings.length" class="wiki-toc" aria-label="页内目录">
            <button
              v-for="heading in headings"
              :key="heading.id"
              type="button"
              class="wiki-toc__item"
              :class="`wiki-toc__item--h${heading.level}`"
              @click="jumpToHeading(heading.id)"
            >
              {{ heading.text }}
            </button>
          </nav>
          <p v-else class="wiki-side-empty">正文里用 ## 添加小标题，这里会自动生成目录。</p>
        </section>

        <section class="wiki-side-card">
          <h3 class="wiki-side-title">属性</h3>
          <dl class="wiki-props">
            <dt>所属空间</dt>
            <dd>{{ currentSpace?.name ?? '—' }}</dd>
            <dt>可见范围</dt>
            <dd>{{ currentSpace ? dict.label('wikiVisibility', currentSpace.visibility) : '—' }}</dd>
            <dt>当前版本</dt>
            <dd>{{ page?.page.currentVersion ?? '—' }}</dd>
            <dt>页面层级</dt>
            <dd>{{ breadcrumb.length ? `${breadcrumb.length} / 4 级` : '—' }}</dd>
          </dl>
        </section>

        <section class="wiki-side-card">
          <div class="wiki-side-head">
            <h3 class="wiki-side-title">关联对象</h3>
            <button v-if="editable" type="button" class="wiki-link" @click="openLink">+ 关联</button>
          </div>
          <ul v-if="page?.links.length" class="wiki-links">
            <li v-for="link in page.links" :key="`${link.bizType}-${link.bizId}`" class="wiki-links__row">
              <span class="wiki-links__type">{{ link.bizType }}</span>
              <span class="wiki-links__title">{{ link.title ?? `#${link.bizId}` }}</span>
              <button v-if="editable" type="button" class="wiki-link wiki-link--danger" @click="removeLink(link.bizType, link.bizId)">
                解除
              </button>
            </li>
          </ul>
          <p v-else class="wiki-side-empty">还没有关联；关联后需求/缺陷详情会显示这一页。</p>
        </section>
      </aside>
    </div>

    <!-- 版本抽屉 -->
    <el-drawer v-model="versionsOpen" :size="560" title="版本历史" direction="rtl">
      <div class="wiki-versions">
        <div v-if="versionsError" class="wiki-inline-error" role="alert">{{ versionsError }}</div>
        <div v-if="versionsLoading" class="wiki-search-skeleton" aria-busy="true">
          <div v-for="n in 5" :key="n" class="wiki-skeleton-row" />
        </div>
        <div v-else class="wiki-version-list">
          <div v-for="version in versions" :key="version.versionNo" class="wiki-version-row">
            <div class="wiki-version-main">
              <span class="wiki-version-no">v{{ version.versionNo }}</span>
              <span v-if="version.current" class="wiki-version-current">当前</span>
              <span class="wiki-version-time">{{ (version.savedAt ?? '').replace('T', ' ').slice(0, 16) }}</span>
              <span class="wiki-version-author">{{ version.savedByName ?? '—' }}</span>
            </div>
            <p class="wiki-version-note">{{ version.changeNote || '（无备注）' }}</p>
            <div class="wiki-version-actions">
              <button type="button" class="wiki-link" @click="openDiff(version.versionNo, page?.page.currentVersion ?? version.versionNo)">
                与当前对比
              </button>
              <button
                v-if="!version.current && editable"
                type="button"
                class="wiki-link"
                @click="rollback(version.versionNo)"
              >
                回滚到此版本
              </button>
            </div>
          </div>
          <p v-if="!versions.length" class="wiki-side-empty">还没有版本记录。</p>
        </div>
      </div>
    </el-drawer>

    <WikiDiffDrawer
      v-model="diffOpen"
      :diff="diff"
      :versions="versions"
      :from="diffFrom"
      :to="diffTo"
      :loading="diffLoading"
      :error="diffError"
      :rollbackable="editable"
      @change="onDiffChange"
      @rollback="rollback"
    />

    <!-- 新建页面 -->
    <el-dialog v-model="pageDialog.open" title="新建页面" width="480px">
      <div class="wiki-dialog">
        <label class="wiki-field">
          <span>标题</span>
          <el-input v-model="pageDialog.title" maxlength="120" placeholder="例如：支付链路背景与决策记录" @keyup.enter="submitCreatePage" />
        </label>
        <label class="wiki-field">
          <span>父页面</span>
          <el-select v-model="pageDialog.parentId" placeholder="根目录" clearable class="wiki-field__control">
            <el-option v-for="item in flatTree" :key="item.node.id" :value="item.node.id" :label="`${'　'.repeat(item.depth)}${item.node.title}`" />
          </el-select>
        </label>
        <p v-if="pageDialog.error" class="wiki-inline-error" role="alert">{{ pageDialog.error }}</p>
      </div>
      <template #footer>
        <button type="button" class="wiki-btn wiki-btn--ghost" @click="pageDialog.open = false">取消</button>
        <button type="button" class="wiki-btn wiki-btn--primary" :disabled="pageDialog.submitting" @click="submitCreatePage">
          {{ pageDialog.submitting ? '创建中…' : '创建' }}
        </button>
      </template>
    </el-dialog>

    <!-- 空间 -->
    <el-dialog v-model="spaceDialog.open" :title="spaceDialog.editingId ? '空间设置' : '新建空间'" width="520px">
      <div class="wiki-dialog">
        <label class="wiki-field">
          <span>空间名</span>
          <el-input v-model="spaceDialog.name" maxlength="60" placeholder="例如：研发知识库" />
        </label>
        <label class="wiki-field">
          <span>可见范围</span>
          <el-select v-model="spaceDialog.visibility" class="wiki-field__control">
            <el-option v-for="item in VISIBILITIES" :key="item.value" :value="item.value" :label="item.label">
              <span>{{ item.label }}</span>
              <span class="wiki-option-hint">{{ item.hint }}</span>
            </el-option>
          </el-select>
        </label>
        <p class="wiki-field-hint">{{ VISIBILITIES.find((item) => item.value === spaceDialog.visibility)?.hint }}</p>
        <label class="wiki-field">
          <span>说明</span>
          <el-input v-model="spaceDialog.description" type="textarea" :rows="2" maxlength="200" />
        </label>
        <p v-if="spaceDialog.error" class="wiki-inline-error" role="alert">{{ spaceDialog.error }}</p>
      </div>
      <template #footer>
        <button type="button" class="wiki-btn wiki-btn--ghost" @click="spaceDialog.open = false">取消</button>
        <button type="button" class="wiki-btn wiki-btn--primary" :disabled="spaceDialog.submitting" @click="submitSpace">
          {{ spaceDialog.submitting ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>

    <!-- 移动 -->
    <el-dialog v-model="moveDialog.open" title="移动到" width="480px">
      <div class="wiki-dialog">
        <label class="wiki-field">
          <span>目标父页面</span>
          <el-select v-model="moveDialog.targetId" placeholder="根目录" clearable class="wiki-field__control">
            <el-option
              v-for="item in flatTree.filter((entry) => entry.node.id !== page?.page.id)"
              :key="item.node.id"
              :value="item.node.id"
              :label="`${'　'.repeat(item.depth)}${item.node.title}`"
            />
          </el-select>
        </label>
        <p class="wiki-field-hint">不能移动到自己的子页下面（服务端会以 409 WIKI_CYCLE 拒绝）。</p>
        <p v-if="moveDialog.error" class="wiki-inline-error" role="alert">{{ moveDialog.error }}</p>
      </div>
      <template #footer>
        <button type="button" class="wiki-btn wiki-btn--ghost" @click="moveDialog.open = false">取消</button>
        <button type="button" class="wiki-btn wiki-btn--primary" :disabled="moveDialog.submitting" @click="submitMove">
          {{ moveDialog.submitting ? '移动中…' : '移动' }}
        </button>
      </template>
    </el-dialog>

    <!-- 关联 -->
    <el-dialog v-model="linkDialog.open" title="添加关联" width="460px">
      <div class="wiki-dialog">
        <label class="wiki-field">
          <span>对象类型</span>
          <el-select v-model="linkDialog.bizType" class="wiki-field__control">
            <el-option v-for="type in BIZ_TYPES" :key="type" :value="type" :label="type" />
          </el-select>
        </label>
        <label class="wiki-field">
          <span>对象 ID</span>
          <el-input v-model.number="linkDialog.bizId" type="number" placeholder="例如 1024" />
        </label>
        <p v-if="linkDialog.error" class="wiki-inline-error" role="alert">{{ linkDialog.error }}</p>
      </div>
      <template #footer>
        <button type="button" class="wiki-btn wiki-btn--ghost" @click="linkDialog.open = false">取消</button>
        <button type="button" class="wiki-btn wiki-btn--primary" :disabled="linkDialog.submitting" @click="submitLink">
          {{ linkDialog.submitting ? '关联中…' : '关联' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wiki-page {
  --wiki-nav: 220px;
  --wiki-measure: 700px;
  --wiki-toc: 240px;
  --wiki-tree-row: 32px;
}
.wiki-layout {
  display: grid;
  grid-template-columns: var(--wiki-nav) minmax(0, var(--wiki-measure)) var(--wiki-toc);
  gap: 16px;
  align-items: start;
}
@media (max-width: 1279px) {
  .wiki-layout {
    grid-template-columns: var(--wiki-nav) minmax(0, 1fr);
  }
  .wiki-col--side {
    display: none;
  }
}
@media (max-width: 900px) {
  .wiki-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .wiki-col--tree {
    max-height: 240px;
  }
}
.wiki-col--tree {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  overflow: hidden;
}
.wiki-tree-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px;
  border-bottom: 1px solid var(--divider);
}
.wiki-space-select {
  flex: 1 1 auto;
  min-width: 0;
}
.wiki-icon-btn {
  flex: none;
  width: 24px;
  height: 24px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.wiki-icon-btn:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.wiki-tree-tabs {
  display: flex;
  gap: 4px;
  padding: 6px 8px 0;
  border-bottom: 1px solid var(--divider);
}
.wiki-tree-tab {
  position: relative;
  height: 28px;
  padding: 0 8px;
  border: 0;
  background: none;
  font-size: 12px;
  color: var(--text-muted-aa);
  cursor: pointer;
}
.wiki-tree-tab.is-active {
  color: var(--color-primary);
  font-weight: 500;
}
.wiki-tree-tab.is-active::after {
  content: '';
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: -1px;
  height: 2px;
  background: var(--color-primary);
}
.wiki-tree-tab__count {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: var(--r-pill);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 10px;
}
.wiki-tree-error {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  font-size: 12px;
  color: var(--danger-ink);
}
.wiki-col--paper {
  min-width: 0;
}
.wiki-paper {
  min-height: 480px;
  padding: 40px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}
.wiki-paper--search {
  padding: 20px 24px;
}
.wiki-crumb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  margin-bottom: 12px;
  font-size: 12px;
}
.wiki-crumb__item {
  border: 0;
  background: none;
  padding: 0;
  color: var(--text-muted-aa);
  cursor: pointer;
}
.wiki-crumb__item:hover {
  color: var(--color-primary);
}
.wiki-crumb__sep {
  color: var(--text-weak);
}
.wiki-paper-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--divider);
}
.wiki-paper-title {
  min-width: 0;
  flex: 1 1 auto;
}
.wiki-paper-title h2 {
  margin: 0 0 6px;
  font-size: 22px;
  line-height: 1.4;
  font-weight: 600;
  color: var(--text-title);
}
.wiki-paper-sub {
  margin: 0;
  font-size: 11px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}
.wiki-dirty {
  margin-left: 8px;
  color: var(--warning-ink);
}
.wiki-paper-actions {
  flex: none;
  display: flex;
  gap: 8px;
}
.wiki-btn {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  font-size: 12px;
  color: var(--text-body);
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.wiki-btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.wiki-btn--primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}
.wiki-btn--primary:hover:not(:disabled) {
  background: var(--color-primary-hover);
  color: #fff;
}
.wiki-btn--ghost {
  background: none;
}
.wiki-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.wiki-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.wiki-link {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.wiki-link:hover {
  text-decoration: underline;
}
.wiki-link--danger {
  color: var(--danger-ink);
}
.wiki-edit-notice {
  margin: 0 0 12px;
  padding: 6px 10px;
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  font-size: 12px;
  color: var(--text-secondary);
}
.wiki-deleted {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.wiki-conflict {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 12px;
  border: 1px solid #e9c989;
  border-left: 3px solid #d99a1a;
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  font-size: 12px;
  color: var(--warning-ink);
}
.wiki-conflict__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1 1 auto;
}
.wiki-conflict__actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.wiki-editor {
  min-height: 420px;
  height: calc(100vh - 420px);
}
.wiki-read {
  min-height: 240px;
}
.wiki-paper-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wiki-skeleton-title {
  width: 45%;
  height: 22px;
  border-radius: 4px;
  background: var(--bg-hover);
}
.wiki-skeleton-row {
  height: 12px;
  width: 88%;
  border-radius: 3px;
  background: var(--bg-hover);
  animation: wiki-pulse 1.2s ease-in-out infinite;
}
@keyframes wiki-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
.wiki-inline-error {
  margin: 0 0 8px;
  padding: 6px 10px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.wiki-search-icon {
  color: var(--text-weak);
  font-size: 13px;
}
.wiki-search-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-title);
}
.wiki-search-total {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.wiki-search-head .wiki-link {
  margin-left: auto;
}
.wiki-search-degraded {
  margin: 0 0 12px;
  padding: 6px 10px;
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
}
.wiki-search-list {
  display: flex;
  flex-direction: column;
}
.wiki-search-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-height: 64px;
  padding: 10px 8px;
  border: 0;
  border-bottom: 1px solid var(--divider);
  background: none;
  text-align: left;
  cursor: pointer;
}
.wiki-search-row:hover {
  background: var(--bg-hover);
}
.wiki-search-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-primary);
}
.wiki-search-snippet {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.wiki-search-meta {
  font-size: 11px;
  color: var(--text-weak);
}
.wiki-search-pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
.wiki-col--side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wiki-side-card {
  padding: 12px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}
.wiki-side-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.wiki-side-title {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-title);
}
.wiki-side-head .wiki-side-title {
  margin-bottom: 8px;
}
.wiki-side-empty {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-weak);
}
.wiki-toc {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.wiki-toc__item {
  border: 0;
  background: none;
  padding: 3px 0 3px 8px;
  text-align: left;
  font-size: 12px;
  color: var(--text-secondary);
  border-left: 2px solid transparent;
  cursor: pointer;
}
.wiki-toc__item:hover {
  color: var(--color-primary);
  border-left-color: var(--color-primary);
}
.wiki-toc__item--h1 {
  font-weight: 600;
  color: var(--text-title);
}
.wiki-toc__item--h3 {
  padding-left: 18px;
  font-size: 11px;
}
.wiki-props {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 6px 8px;
  margin: 0;
  font-size: 11px;
}
.wiki-props dt {
  color: var(--text-weak);
}
.wiki-props dd {
  margin: 0;
  color: var(--text-body);
  overflow: hidden;
  text-overflow: ellipsis;
}
.wiki-links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wiki-links__row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}
.wiki-links__type {
  flex: none;
  padding: 1px 4px;
  border-radius: var(--r-cell);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-family: var(--font-mono);
  font-size: 10px;
}
.wiki-links__title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-body);
}
.wiki-versions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wiki-version-list {
  display: flex;
  flex-direction: column;
}
.wiki-version-row {
  min-height: 56px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--divider);
}
.wiki-version-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
}
.wiki-version-no {
  font-family: var(--font-mono);
  font-weight: 600;
  color: var(--text-title);
}
.wiki-version-current {
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--color-primary-light);
  color: var(--color-active);
  font-size: 10px;
}
.wiki-version-time {
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.wiki-version-author {
  margin-left: auto;
  color: var(--text-weak);
}
.wiki-version-note {
  margin: 3px 0;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.wiki-version-actions {
  display: flex;
  gap: 12px;
}
.wiki-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wiki-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}
.wiki-field__control {
  width: 100%;
}
.wiki-field-hint {
  margin: 0;
  font-size: 11px;
  color: var(--text-weak);
}
.wiki-option-hint {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-weak);
}
</style>
