<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createRole, deleteRole, listRoles, setRoleStatus, updateRole } from '@/api/admin'
import type { RoleRow } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import '@/views/admin/admin-shared.css'

/**
 * 角色与权限（DESIGN §0 + §3.1 / PRD AC-8、AC-9、AC-11）。
 *
 * 列表页只做「角色这一层」的增改停删与入口；三层授权在独立路由
 * `/admin/roles/:id/permissions` 上做——授权页要占满 1024 宽做左树 + 右动作表，
 * 塞进列表页的抽屉会把动作表挤到横向滚动。
 *
 * 两条硬规则：
 * 1. 内置角色的停用 / 删除按钮 disabled + tooltip，**不靠服务端 409 兜**：内置角色承载
 *    预设 168 行授权，演示重置靠它复原，误点一次就是「演示环境回不到基线」。
 * 2. 409 / 400 一律内联（对话框字段下方或表格上方），不用 toast：角色编码重复、
 *    角色仍挂着用户、内置不可删这三类错误，用户需要停下来看清是哪一条再决定下一步。
 */
const router = useRouter()

const rows = ref<RoleRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

/** 输入框与已生效筛选分开：DESIGN 要求回车触发，避免每敲一个字就重排整页 */
const keyword = ref('')
const query = ref('')
const activeOnly = ref(false)

/** 行内错误（409 / 400）：挂在表格上方，用户改完或点「知道了」即清 */
const inlineError = ref<{ id: number; code: string; message: string } | null>(null)

/** 新建成功后的行高亮 + 「去授权」引导（DESIGN：行底色 #EAF1FE 渐隐 1.2s） */
const createdId = ref(0)
let flashTimer: ReturnType<typeof setTimeout> | undefined

const page = ref(1)
const PAGE_SIZE = 20

const filtered = computed(() => {
  const text = query.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (activeOnly.value && row.status !== 'ACTIVE') return false
    if (!text) return true
    return (
      row.code.toLowerCase().includes(text) ||
      row.name.toLowerCase().includes(text) ||
      (row.description ?? '').toLowerCase().includes(text)
    )
  })
})

/** 默认 updatedAt desc；同刻按 id 稳定排序，避免刷新后行序跳动 */
const sorted = computed(() =>
  [...filtered.value].sort((a, b) => {
    const left = a.updatedAt ?? ''
    const right = b.updatedAt ?? ''
    if (left === right) return a.id - b.id
    return right.localeCompare(left)
  }),
)

/** ≤50 行不分页；>50 每页 20（DESIGN §3.1） */
const showPager = computed(() => sorted.value.length > 50)
const paged = computed(() =>
  showPager.value ? sorted.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE) : sorted.value,
)
const filterActive = computed(() => Boolean(query.value.trim()) || activeOnly.value)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    rows.value = await listRoles()
  } catch (err) {
    error.value = {
      message: (err as Error).message || '角色列表加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function applySearch(): void {
  query.value = keyword.value.trim()
  page.value = 1
}

function clearFilters(): void {
  keyword.value = ''
  query.value = ''
  activeOnly.value = false
  page.value = 1
}

function toggleActiveOnly(): void {
  page.value = 1
}

/* ---------------- 新建 / 编辑抽屉（640，label-position="top"） ---------------- */

const CODE_RE = /^[a-z][a-z0-9_]{1,31}$/

const drawer = reactive({
  open: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  code: '',
  name: '',
  description: '',
  submitting: false,
  error: '',
})
const fieldErrors = reactive({ code: '', name: '' })

function openCreate(): void {
  Object.assign(drawer, {
    open: true,
    mode: 'create',
    id: 0,
    code: '',
    name: '',
    description: '',
    submitting: false,
    error: '',
  })
  fieldErrors.code = ''
  fieldErrors.name = ''
}

function openEdit(row: RoleRow): void {
  Object.assign(drawer, {
    open: true,
    mode: 'edit',
    id: row.id,
    code: row.code,
    name: row.name,
    description: row.description ?? '',
    submitting: false,
    error: '',
  })
  fieldErrors.code = ''
  fieldErrors.name = ''
}

function validate(): boolean {
  fieldErrors.name = ''
  fieldErrors.code = ''
  drawer.error = ''
  const name = drawer.name.trim()
  if (name.length < 2 || name.length > 30) {
    fieldErrors.name = '角色名需 2–30 个字符（首尾空格会被去掉）'
  }
  if (drawer.mode === 'create') {
    const code = drawer.code.trim()
    if (!code) {
      fieldErrors.code = '编码必填：授权行按编码落库，创建后不可改'
    } else if (!CODE_RE.test(code)) {
      fieldErrors.code = '编码需小写字母开头，2–32 位小写字母 / 数字 / 下划线，如 vendor_viewer'
    }
  }
  return !fieldErrors.name && !fieldErrors.code
}

async function submitForm(): Promise<void> {
  if (drawer.submitting) return
  if (!validate()) return
  drawer.submitting = true
  try {
    if (drawer.mode === 'create') {
      const created = await createRole({
        code: drawer.code.trim(),
        name: drawer.name.trim(),
        description: drawer.description.trim() || undefined,
      })
      drawer.open = false
      await load()
      flashRow(created.id)
      toastCreated(created)
    } else {
      await updateRole(drawer.id, {
        name: drawer.name.trim(),
        description: drawer.description.trim() || undefined,
      })
      drawer.open = false
      await load()
      ElMessage.success('已保存角色信息')
    }
  } catch (err) {
    const code = (err as { code?: string }).code
    const message = explain(code, (err as Error).message || '保存失败')
    if (code === 'ROLE_CODE_TAKEN' || code === 'ROLE_CODE_EXISTS' || code === 'INVALID_ROLE_CODE') {
      fieldErrors.code = message
    } else if (code === 'INVALID_ROLE_NAME') {
      fieldErrors.name = message
    } else {
      drawer.error = message
    }
  } finally {
    drawer.submitting = false
  }
}

/* ---------------- 停用 / 启用 / 删除（确认 480，红色态） ---------------- */

const confirm = reactive({
  open: false,
  kind: 'status' as 'status' | 'delete',
  row: null as RoleRow | null,
  loading: false,
})

const nextStatus = computed(() => (confirm.row?.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'))
const confirmTone = computed<'danger' | 'primary'>(() =>
  confirm.kind === 'delete' || nextStatus.value === 'DISABLED' ? 'danger' : 'primary',
)
const confirmTitle = computed(() => {
  const name = confirm.row?.name ?? ''
  if (confirm.kind === 'delete') return `删除角色「${name}」`
  return nextStatus.value === 'DISABLED' ? `停用角色「${name}」` : `启用角色「${name}」`
})
const confirmText = computed(() => {
  if (confirm.kind === 'delete') return '删除'
  return nextStatus.value === 'DISABLED' ? '停用' : '启用'
})
const confirmDescription = computed(() =>
  confirm.kind === 'delete'
    ? '删除会移除角色与其授权行；审计日志保留，但配置不可恢复。'
    : '状态立即生效：该角色的用户在下一次请求时按新状态判定，无需重新登录。',
)
const confirmImpact = computed<string[]>(() => {
  const row = confirm.row
  if (!row) return []
  const users = row.userCount
  if (confirm.kind === 'delete') {
    return [
      users > 0 ? `该角色仍关联 ${users} 名用户，服务端会拒绝删除（409 ROLE_IN_USE）` : '该角色当前没有关联用户',
      '删除后不可恢复；若只想临时收回权限，请改用「停用」',
    ]
  }
  if (nextStatus.value === 'DISABLED') {
    return [
      `${users} 名用户将在下一次请求失去该角色的页面与动作权限`,
      '关联关系与授权行保留，重新启用即恢复',
      users > 0 ? '若其中包含当前登录账号，可能立即失去管理入口' : '当前没有用户绑定，停用只影响之后的分配',
    ]
  }
  return [
    `${users} 名用户将在下一次请求恢复该角色的授权`,
    '停用期间未删除的授权行原样生效',
  ]
})

function askStatus(row: RoleRow): void {
  inlineError.value = null
  Object.assign(confirm, { open: true, kind: 'status', row, loading: false })
}

function askDelete(row: RoleRow): void {
  inlineError.value = null
  Object.assign(confirm, { open: true, kind: 'delete', row, loading: false })
}

async function doConfirm(): Promise<void> {
  const row = confirm.row
  if (!row || confirm.loading) return
  confirm.loading = true
  try {
    if (confirm.kind === 'delete') {
      await deleteRole(row.id)
      ElMessage.success(`已删除「${row.name}」`)
    } else {
      await setRoleStatus(row.id, nextStatus.value)
      ElMessage.success(nextStatus.value === 'ACTIVE' ? `已启用「${row.name}」` : `已停用「${row.name}」`)
    }
    confirm.open = false
    inlineError.value = null
    await load()
  } catch (err) {
    confirm.open = false
    const code = (err as { code?: string }).code || 'UNKNOWN'
    inlineError.value = {
      id: row.id,
      code,
      message: explain(code, (err as Error).message || `${confirmText.value}失败`),
    }
  } finally {
    confirm.loading = false
  }
}

/* ---------------- 入口与提示 ---------------- */

/** 写入口：三层授权在独立页面，列表只负责把用户送到那一页 */
function goPermissions(row: RoleRow): void {
  void router.push(`/admin/roles/${row.id}/permissions`)
}

function flashRow(id: number): void {
  createdId.value = id
  if (flashTimer) clearTimeout(flashTimer)
  flashTimer = setTimeout(() => {
    createdId.value = 0
  }, 1600)
}

/** 新建成功：toast 带「去授权」文字按钮（3–4s），把「建角色 → 配授权」两步串起来 */
function toastCreated(row: RoleRow): void {
  const action = h(
    'button',
    {
      type: 'button',
      style:
        'margin-left:10px;padding:0;border:0;background:transparent;color:#2b6fe5;' +
        'font:inherit;font-weight:500;font-size:12px;cursor:pointer',
      onClick: () => {
        ElMessage.closeAll()
        goPermissions(row)
      },
    },
    '去授权',
  )
  ElMessage({
    type: 'success',
    duration: 4000,
    message: h(
      'span',
      { style: 'display:inline-flex;align-items:center;font-size:13px' },
      [`已创建「${row.name}」`, action],
    ),
  })
}

/** 后端是唯一判定源：这里只把错误码翻成人话与下一步，不预判 */
function explain(code: string | undefined, fallback: string): string {
  switch (code) {
    case 'ROLE_CODE_TAKEN':
    case 'ROLE_CODE_EXISTS':
      return '角色编码已被占用：编码是审计与授权行的主语，创建后不可改，请换一个'
    case 'CODE_IMMUTABLE':
      return '角色编码创建后不可修改；如需换码请新建角色并迁移授权'
    case 'BUILTIN_ROLE_IMMUTABLE':
      return '内置角色不可停用、不可删除：它承载预设授权，演示重置要靠它复原'
    case 'ROLE_IN_USE':
      return '该角色仍有用户：先把这些用户改派到别的角色，再删除'
    case 'INVALID_ROLE_CODE':
      return '角色编码只能用小写字母开头，字母 / 数字 / 下划线，2–32 位'
    case 'INVALID_ROLE_NAME':
      return '角色名需 2–30 个字符'
    case 'INVALID_STATUS':
      return '状态只能是启用或停用，请刷新后重试'
    case 'ADMIN_LOCKOUT_RISK':
      return '该操作会让平台失去最后一个管理入口：先给另一个账号授予 org:manage，再回来改'
    case 'ROLE_NOT_FOUND':
      return '角色已不存在（可能已被删除），刷新列表后重试'
    default:
      return fallback
  }
}

function rowClassName({ row }: { row: RoleRow }): string {
  return row.id === createdId.value ? 'roles__row--flash' : ''
}

onMounted(load)
onBeforeUnmount(() => {
  if (flashTimer) clearTimeout(flashTimer)
})
</script>

<template>
  <div class="roles">
    <div class="adm-toolbar">
      <el-input
        v-model="keyword"
        class="roles__search"
        placeholder="搜索角色名 / 编码，回车确认"
        clearable
        data-test="role-search"
        @keyup.enter="applySearch"
        @clear="applySearch"
      />
      <el-switch
        v-model="activeOnly"
        size="small"
        active-text="仅看启用"
        data-test="role-active-only"
        @change="toggleActiveOnly"
      />
      <span class="adm-toolbar__spacer" />
      <span class="roles__count">
        {{ filterActive ? `筛选出 ${sorted.length} / ${rows.length} 个角色` : `共 ${rows.length} 个角色` }}
      </span>
      <el-button type="primary" data-test="role-create" @click="openCreate">新建角色</el-button>
    </div>

    <!-- 骨架：角色表 5 行 × 48（DESIGN §0.4，不用整页 spinner） -->
    <section v-if="loading && rows.length === 0" class="surface roles__skeleton" aria-busy="true" aria-label="正在加载角色">
      <div v-for="n in 5" :key="n" class="roles__skeleton-row">
        <span class="roles__bone" style="width: 220px" />
        <span class="roles__bone" style="width: 180px" />
        <span class="roles__bone" style="width: 60px" />
        <span class="roles__bone" style="width: 72px" />
        <span class="roles__bone" style="width: 80px" />
        <span class="roles__bone" style="width: 130px" />
        <span class="roles__bone roles__bone--end" style="width: 160px" />
      </div>
    </section>

    <ErrorState
      v-else-if="error"
      title="角色列表加载失败"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />

    <template v-else>
      <section class="surface">
        <!-- 409 / 400 内联：颜色与授权页警示条同族，说明「哪一条被拦住了、下一步做什么」 -->
        <div v-if="inlineError" class="roles__inline" role="alert" data-test="role-inline-error">
          <strong>{{ inlineError.code }}</strong>
          <span>{{ inlineError.message }}</span>
          <button type="button" class="roles__inline-close" @click="inlineError = null">知道了</button>
        </div>

        <EmptyState
          v-if="rows.length === 0"
          title="还没有角色"
          description="创建第一个角色，再进三级授权页配置它能进哪些页面、能点哪些动作。"
          icon="inbox"
          action-text="新建角色"
          @action="openCreate"
        />

        <EmptyState
          v-else-if="sorted.length === 0"
          variant="filtered"
          title="没有匹配的角色"
          description="换个关键词，或关掉「仅看启用」。"
          icon="filter"
          action-text="清除筛选"
          @action="clearFilters"
        />

        <el-table
          v-else
          v-loading="loading"
          :data="paged"
          row-key="id"
          data-test="role-table"
          :row-class-name="rowClassName"
          :row-style="{ height: '48px' }"
        >
          <el-table-column label="角色名" min-width="220">
            <template #default="{ row }">
              <div class="roles__name">
                <span class="roles__name-main">{{ row.name }}</span>
                <span v-if="row.description" class="roles__name-desc" :title="row.description">
                  {{ row.description }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="编码" :width="180">
            <template #default="{ row }">
              <span class="roles__code" :title="row.code">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="60">
            <template #default="{ row }">
              <span v-if="row.builtin" class="roles__pill roles__pill--type">内置</span>
              <span v-else class="roles__dash">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="72">
            <template #default="{ row }">
              <span
                class="roles__pill"
                :class="row.status === 'ACTIVE' ? 'roles__pill--on' : 'roles__pill--off'"
              >
                {{ row.status === 'ACTIVE' ? '启用' : '已停用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="用户数" width="80" align="right">
            <template #default="{ row }">
              <span class="roles__num" title="该角色关联用户数">{{ row.userCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="130">
            <template #default="{ row }">
              <span class="roles__time">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <!-- 行操作默认 opacity:0，hover / focus-within 出现（base.css .row-actions） -->
              <div class="row-actions">
                <el-button link type="primary" data-test="role-grant" @click="goPermissions(row)">
                  授权
                </el-button>
                <el-button link @click="openEdit(row)">编辑</el-button>
                <el-tooltip
                  :disabled="!row.builtin || row.status !== 'ACTIVE'"
                  content="内置角色不可停用，可改权限"
                  placement="top"
                >
                  <span class="roles__act">
                    <el-button
                      link
                      :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
                      :disabled="row.builtin && row.status === 'ACTIVE'"
                      @click="askStatus(row)"
                    >
                      {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                    </el-button>
                  </span>
                </el-tooltip>
                <el-tooltip :disabled="!row.builtin" content="内置角色不可删除" placement="top">
                  <span class="roles__act">
                    <el-button link type="danger" :disabled="row.builtin" @click="askDelete(row)">
                      删除
                    </el-button>
                  </span>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="showPager" class="adm-pager">
          <el-pagination
            layout="prev, pager, next"
            :total="sorted.length"
            :page-size="PAGE_SIZE"
            :current-page="page"
            background
            small
            @current-change="(next: number) => (page = next)"
          />
        </div>
      </section>
    </template>

    <el-drawer
      v-model="drawer.open"
      :title="drawer.mode === 'create' ? '新建角色' : '编辑角色'"
      size="640px"
      class="roles__drawer"
      :close-on-click-modal="false"
    >
      <el-form label-position="top" @submit.prevent="submitForm">
        <el-form-item label="角色名" required :error="fieldErrors.name">
          <el-input
            v-model="drawer.name"
            maxlength="30"
            show-word-limit
            placeholder="如 厂商查看者"
            data-test="role-name"
            @input="fieldErrors.name = ''"
          />
        </el-form-item>

        <el-form-item label="角色编码" required :error="fieldErrors.code">
          <div class="roles__field">
            <el-input
              v-model="drawer.code"
              class="roles__code-input"
              :disabled="drawer.mode === 'edit'"
              maxlength="32"
              placeholder="vendor_viewer"
              data-test="role-code"
              @input="fieldErrors.code = ''"
            />
            <p class="adm-hint">编码是审计与授权的稳定标识，创建后不可改。</p>
          </div>
        </el-form-item>

        <el-form-item label="描述">
          <el-input
            v-model="drawer.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="这个角色为什么存在、给谁用（可留空）"
          />
        </el-form-item>
      </el-form>

      <p v-if="drawer.error" class="roles__form-error" role="alert" data-test="role-form-error">
        {{ drawer.error }}
      </p>

      <template #footer>
        <div class="roles__drawer-foot">
          <el-button @click="drawer.open = false">取消</el-button>
          <el-button
            type="primary"
            class="roles__save"
            :loading="drawer.submitting"
            data-test="role-submit"
            @click="submitForm"
          >
            保存
          </el-button>
        </div>
      </template>
    </el-drawer>

    <ConfirmDialog
      v-model="confirm.open"
      :title="confirmTitle"
      :description="confirmDescription"
      :impact="confirmImpact"
      :reason-required="false"
      :tone="confirmTone"
      :confirm-text="confirmText"
      :loading="confirm.loading"
      @confirm="doConfirm"
    />
  </div>
</template>

<style scoped>
.roles {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.roles__search {
  width: 280px;
}

.roles__count {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* ---------------- 骨架 ---------------- */
.roles__skeleton {
  overflow: hidden;
}

.roles__skeleton-row {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 48px;
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
}

.roles__bone {
  height: 12px;
  border-radius: var(--r-cell);
  background: var(--divider);
  animation: roles-pulse 1.4s var(--ease) infinite;
}

.roles__bone--end {
  margin-left: auto;
}

@keyframes roles-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

/* ---------------- 表格（行高 48，表头 #FAFBFC / 12 / 500） ---------------- */
.roles :deep(.el-table) {
  font-size: 13px;
  color: var(--text-body);
}

.roles :deep(.el-table th.el-table__cell) {
  height: 40px;
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-size: 12px;
  font-weight: 500;
}

.roles :deep(.el-table td.el-table__cell) {
  border-bottom: 1px solid var(--divider);
}

.roles :deep(.el-table__row) {
  height: 48px;
}

.roles :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: var(--bg-hover);
}

/* 新建成功：行底色 #EAF1FE 渐隐 1.2s */
.roles :deep(.el-table__row.roles__row--flash > td.el-table__cell) {
  animation: roles-flash 1.2s var(--ease);
}

@keyframes roles-flash {
  from {
    background: var(--bg-selected);
  }
  to {
    background: transparent;
  }
}

.roles__name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.roles__name-main {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.roles__name-desc {
  font-size: 12px;
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.roles__code {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--text-body);
}

/* 状态 pill：高 20、圆角 6、11/500、padding 0 8（DESIGN §0.2） */
.roles__pill {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.roles__pill--type,
.roles__pill--off {
  background: var(--color-info-bg);
  color: var(--info-ink);
}

.roles__pill--on {
  background: var(--color-success-bg);
  color: var(--success-ink);
}

.roles__dash {
  color: var(--text-weak);
}

.roles__num {
  font-variant-numeric: tabular-nums;
}

.roles__time {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.roles__act {
  display: inline-flex;
}

/* ---------------- 内联错误（409 / 400） ---------------- */
.roles__inline {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 12px 16px 0;
  padding: 6px 12px;
  min-height: 36px;
  border-left: 2px solid var(--perm-half, #c77700);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.6;
}

.roles__inline strong {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.02em;
  color: var(--color-warning);
  white-space: nowrap;
}

.roles__inline-close {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--warning-ink);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.roles__inline-close:hover {
  color: var(--text-title);
}

/* ---------------- 抽屉（640，label-position top） ---------------- */
.roles__drawer :deep(.el-drawer__body) {
  padding: 4px 24px 24px;
}

.roles__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.roles__code-input :deep(.el-input__inner) {
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
}

.roles__form-error {
  margin: 0 24px 8px;
  padding: 6px 12px;
  border-left: 2px solid var(--danger-ink);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}

.roles__drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.roles__save {
  width: 96px;
}

@media (prefers-reduced-motion: reduce) {
  .roles__bone,
  .roles :deep(.el-table__row.roles__row--flash > td.el-table__cell) {
    animation: none;
  }
}
</style>
