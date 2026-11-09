<script setup lang="ts">
/**
 * 需求详情「文档」tab（DESIGN R2 · AC-8）。
 *
 * 只展示与本需求双向关联的 Wiki 页；候选页来自 /wiki/search（服务端已按可见性过滤），
 * 页面不做第二套权限判断，也不在列表接口之外自己拼可见范围。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  fetchWikiLinks,
  linkWikiPage,
  searchWikiPages,
  unlinkWikiPage,
} from '@/api/req'
import type { WikiLinkRow, WikiSearchItem } from '@/api/req'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime } from '@/utils/format'
import '@/views/deliver/shared.css'

const props = withDefaults(
  defineProps<{
    requirementId: number
    /** 关联的业务对象类型：需求详情固定 REQUIREMENT（后端双向关联表用同一枚举） */
    bizType?: string
  }>(),
  { bizType: 'REQUIREMENT' },
)

const { can } = usePermission()
const canView = computed(() => can('WIKI_VIEW'))
const canEdit = computed(() => can('WIKI_EDIT'))

const links = ref<WikiLinkRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const forbidden = ref(false)

async function load(): Promise<void> {
  if (!canView.value) {
    forbidden.value = true
    links.value = []
    return
  }
  loading.value = true
  error.value = null
  try {
    links.value = await fetchWikiLinks(props.bizType, props.requirementId)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    if (apiError?.status === 403) {
      forbidden.value = true
      links.value = []
    } else {
      error.value = {
        message: apiError?.message || '关联文档加载失败',
        code: apiError?.code,
        status: apiError?.status,
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------------- 关联抽屉 560 ---------------- */

const drawer = reactive({
  visible: false,
  keyword: '',
  items: [] as WikiSearchItem[],
  total: 0,
  loading: false,
  degraded: false,
  error: '',
  linkingId: null as number | null,
  page: 1,
})

let searchTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput(): void {
  drawer.page = 1
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void runSearch()
  }, 260)
}

async function runSearch(): Promise<void> {
  if (!drawer.keyword.trim()) {
    drawer.items = []
    drawer.total = 0
    drawer.error = ''
    return
  }
  drawer.loading = true
  drawer.error = ''
  try {
    const result = await searchWikiPages(drawer.keyword.trim(), drawer.page, 20)
    drawer.items = result.items
    drawer.total = result.total
    drawer.degraded = Boolean(result.degraded)
  } catch (err) {
    drawer.error = err instanceof ApiError ? err.message : '搜索失败'
    drawer.items = []
    drawer.total = 0
  } finally {
    drawer.loading = false
  }
}

function openDrawer(): void {
  if (!canEdit.value) return
  drawer.visible = true
  drawer.keyword = ''
  drawer.items = []
  drawer.total = 0
  drawer.error = ''
  drawer.degraded = false
  drawer.page = 1
}

const linkedIds = computed(() => new Set(links.value.map((row) => row.pageId)))

async function link(item: WikiSearchItem): Promise<void> {
  if (drawer.linkingId !== null) return
  drawer.linkingId = item.pageId
  try {
    await linkWikiPage(item.pageId, props.bizType, props.requirementId)
    ElMessage.success('已关联文档')
    drawer.items = drawer.items.filter((row) => row.pageId !== item.pageId)
    await load()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    if (apiError?.status === 409) {
      ElMessage.warning('该文档已关联过，无需重复关联')
    } else {
      drawer.error = apiError?.message || '关联失败'
    }
  } finally {
    drawer.linkingId = null
  }
}

async function unlink(row: WikiLinkRow): Promise<void> {
  if (!canEdit.value) return
  try {
    await ElMessageBox.confirm(`解除与「${row.title}」的关联？文档本身不会被删除。`, '解除关联', {
      confirmButtonText: '解除关联',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await unlinkWikiPage(row.pageId, props.bizType, props.requirementId)
    ElMessage.success('已解除关联')
    await load()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = { message: apiError?.message || '解除关联失败', code: apiError?.code, status: apiError?.status }
  }
}

watch(
  () => props.requirementId,
  () => {
    forbidden.value = false
    void load()
  },
)
</script>

<template>
  <div class="doc-tab">
    <header class="doc-tab__head">
      <div>
        <h2 class="doc-tab__title">文档</h2>
        <p class="doc-tab__sub">需求与 Wiki 页双向关联；在 Wiki 页也能看到反向链接。</p>
      </div>
      <el-button v-if="canEdit" size="small" type="primary" :disabled="forbidden" @click="openDrawer">
        关联 Wiki 页面
      </el-button>
    </header>

    <ErrorState
      v-if="error"
      compact
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load()"
    />

    <EmptyState
      v-else-if="forbidden"
      variant="forbidden"
      title="没有查看 Wiki 的权限"
      description="需要 WIKI_VIEW 才能看到关联文档；请联系管理员开通。"
      icon="lock"
      compact
    />

    <section v-else class="doc-tab__surface">
      <div v-if="loading" class="doc-tab__loading" aria-busy="true">
        <span v-for="index in 3" :key="index" class="doc-tab__skeleton" />
      </div>

      <table v-else-if="links.length" class="doc-table">
        <thead>
          <tr>
            <th>标题</th>
            <th class="doc-table__space">空间</th>
            <th class="doc-table__time">更新时间</th>
            <th class="doc-table__ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in links" :key="row.pageId">
            <td class="doc-table__title">
              <router-link
                class="doc-table__link"
                :to="{ name: 'wiki', query: { page: String(row.pageId) } }"
                :title="row.title"
              >
                {{ row.title }}
              </router-link>
            </td>
            <td>{{ row.spaceName || '—' }}</td>
            <td class="doc-table__time">{{ formatDateTime(row.updatedAt) }}</td>
            <td class="doc-table__ops">
              <el-button v-if="canEdit" link type="danger" size="small" @click="unlink(row)">解挂</el-button>
              <span v-else class="doc-table__muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>

      <EmptyState
        v-else
        title="还没有关联文档"
        :description="canEdit ? '关联需求相关的设计、接口或测试说明，执行人不用再四处找文档。' : '还没有人关联 Wiki 文档。'"
        icon="file"
        :action-text="canEdit ? '关联 Wiki 页面' : ''"
        compact
        @action="openDrawer"
      />
    </section>

    <!-- 关联抽屉 560：搜索 + 结果列表，只列调用者可见的页 -->
    <el-drawer v-model="drawer.visible" :size="560" destroy-on-close>
      <template #header>
        <div class="doc-drawer__head">
          <h2 class="doc-drawer__title">关联 Wiki 页面</h2>
          <span class="dl-hint">只搜索你有权限查看的空间与页面（服务端先过滤再计数）。</span>
        </div>
      </template>

      <div class="doc-drawer">
        <el-input
          v-model="drawer.keyword"
          size="small"
          clearable
          placeholder="搜索标题或正文关键词"
          @input="onKeywordInput"
          @keyup.enter="runSearch"
        />

        <p v-if="drawer.degraded" class="doc-drawer__hint">
          全文索引不可用，当前为降级匹配（结果可能偏少）。
        </p>
        <p v-if="drawer.error" class="doc-drawer__error" role="alert">{{ drawer.error }}</p>

        <div v-if="drawer.loading" class="doc-drawer__loading" aria-busy="true">
          <span v-for="index in 3" :key="index" class="doc-tab__skeleton" />
        </div>

        <ul v-else-if="drawer.items.length" class="doc-results">
          <li v-for="item in drawer.items" :key="item.pageId" class="doc-results__row">
            <div class="doc-results__body">
              <span class="doc-results__title">{{ item.title }}</span>
              <span class="doc-results__meta">
                {{ item.spaceName || '未知空间' }} · {{ formatDateTime(item.updatedAt) }}
              </span>
              <p v-if="item.snippet" class="doc-results__snippet">{{ item.snippet }}</p>
            </div>
            <el-button
              v-if="linkedIds.has(item.pageId)"
              size="small"
              disabled
              title="已关联"
            >
              已关联
            </el-button>
            <el-button
              v-else
              size="small"
              type="primary"
              :loading="drawer.linkingId === item.pageId"
              @click="link(item)"
            >
              关联
            </el-button>
          </li>
        </ul>

        <EmptyState
          v-else-if="drawer.keyword.trim()"
          title="没有匹配的文档"
          description="换个关键词，或确认该文档所在空间对你可见。"
          icon="search"
          compact
        />
        <EmptyState
          v-else
          title="搜索后再选择"
          description="输入标题或正文关键词，结果只包含你有权限查看的页面。"
          icon="search"
          compact
        />

        <p v-if="drawer.total > drawer.items.length" class="doc-drawer__more">
          共 {{ drawer.total }} 条，当前显示前 {{ drawer.items.length }} 条；请用更精确的关键词缩小范围。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.doc-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.doc-tab__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.doc-tab__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}

.doc-tab__sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.doc-tab__surface {
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-card, 8px);
  background: var(--bg-card, #fff);
  padding: 4px 12px 12px;
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.doc-table th {
  padding: 8px;
  text-align: left;
  font-weight: 500;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  border-bottom: 1px solid var(--divider, #f2f3f5);
}

.doc-table td {
  padding: 9px 8px;
  border-bottom: 1px solid var(--divider, #f2f3f5);
  color: var(--text-body, #4e5969);
}

.doc-table tbody tr:hover td {
  background: var(--bg-hover, #f7f9fc);
}

.doc-table__space {
  width: 180px;
}

.doc-table__time {
  width: 160px;
  color: var(--text-muted-aa, #6b7280);
  font-variant-numeric: tabular-nums;
}

.doc-table__ops {
  width: 88px;
  text-align: right;
}

.doc-table__link {
  color: var(--color-primary, #2b6fe5);
  text-decoration: none;
  font-weight: 500;
  display: inline-block;
  max-width: 560px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.doc-table__link:hover {
  text-decoration: underline;
}

.doc-table__muted {
  color: var(--text-muted-aa, #6b7280);
}

.doc-tab__loading,
.doc-drawer__loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 0;
}

.doc-tab__skeleton {
  height: 34px;
  border-radius: var(--r-ctl, 6px);
  background: linear-gradient(90deg, #f4f6f9 25%, #eceff3 37%, #f4f6f9 63%);
  background-size: 400% 100%;
  animation: doc-skel 1.4s ease infinite;
}

@keyframes doc-skel {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .doc-tab__skeleton {
    animation: none;
  }
}

.doc-drawer__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.doc-drawer__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title, #1f2329);
}

.doc-drawer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.doc-drawer__hint,
.doc-drawer__more {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}

.doc-drawer__error {
  margin: 0;
  padding: 6px 10px;
  border-radius: var(--r-ctl, 6px);
  background: var(--color-danger-bg, #fdecec);
  border: 1px solid #f5c2c4;
  color: var(--danger-ink, #b3262e);
  font-size: 12px;
}

.doc-results {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.doc-results__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border, #e5e6eb);
  border-radius: var(--r-ctl, 6px);
}

.doc-results__row:hover {
  border-color: var(--border-strong, #c9cdd4);
  background: var(--bg-hover, #f7f9fc);
}

.doc-results__body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.doc-results__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title, #1f2329);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-results__meta {
  font-size: 11px;
  color: var(--text-muted-aa, #6b7280);
}

.doc-results__snippet {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body, #4e5969);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
