<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createTenant, listOrgs, listTenants, saveOrg } from '@/api/admin'
import type { OrgRow, TenantRow } from '@/api/admin'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import '@/views/admin/admin-shared.css'

/**
 * 组织与租户（DESIGN P12 / AC-1）。
 * 租户创建 = 建库结构 + 预置 10 角色 / 7 域 / 字典；组织树用于审批人解析与数据范围。
 */
const tab = ref<'tenant' | 'org'>('tenant')

/* ---------------- 租户 ---------------- */

const tenants = ref<TenantRow[]>([])
const tenantTotal = ref(0)
const tenantPage = ref(1)
const tenantSize = 20
const tenantLoading = ref(false)
const tenantError = ref<{ message: string; code?: string; status?: number } | null>(null)

async function loadTenants(): Promise<void> {
  tenantLoading.value = true
  tenantError.value = null
  try {
    const data = await listTenants({ page: tenantPage.value, size: tenantSize })
    tenants.value = data.items ?? []
    tenantTotal.value = data.total ?? 0
  } catch (err) {
    tenantError.value = {
      message: (err as Error).message || '租户列表加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    tenantLoading.value = false
  }
}

const tenantVisible = ref(false)
const tenantCreating = ref(false)
const tenantForm = reactive({ code: '', name: '' })
const provisioned = ref<Record<string, number> | null>(null)

function openTenant(): void {
  tenantForm.code = ''
  tenantForm.name = ''
  provisioned.value = null
  tenantVisible.value = true
}

async function submitTenant(): Promise<void> {
  if (!/^[a-z0-9][a-z0-9-]{1,15}$/.test(tenantForm.code)) {
    ElMessage.warning('租户 code 需 2–16 位小写字母、数字或连字符')
    return
  }
  if (!tenantForm.name.trim()) {
    ElMessage.warning('请填写租户名称')
    return
  }
  tenantCreating.value = true
  try {
    const result = await createTenant({ code: tenantForm.code, name: tenantForm.name.trim() })
    provisioned.value = result.provisioned ?? null
    ElMessage.success('租户已创建并完成库结构初始化')
    await loadTenants()
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    ElMessage.error(apiError.status === 409 ? apiError.message || '租户 code 已存在（409）' : apiError.message || '创建失败')
  } finally {
    tenantCreating.value = false
  }
}

/* ---------------- 组织 ---------------- */

const orgs = ref<OrgRow[]>([])
const orgLoading = ref(false)
const orgError = ref<{ message: string; code?: string; status?: number } | null>(null)

/** 后端可能返回扁平列表或树；两种都收敛成树，避免树形表格空白。 */
function toTree(rows: OrgRow[]): OrgRow[] {
  if (rows.some((row) => (row.children?.length ?? 0) > 0)) return rows
  const byId = new Map<number, OrgRow>()
  for (const row of rows) byId.set(row.id, { ...row, children: [] })
  const roots: OrgRow[] = []
  for (const row of byId.values()) {
    const parent = row.parentId ? byId.get(row.parentId) : undefined
    if (parent) parent.children!.push(row)
    else roots.push(row)
  }
  return roots
}

const orgTree = computed(() => toTree(orgs.value))

async function loadOrgs(): Promise<void> {
  orgLoading.value = true
  orgError.value = null
  try {
    orgs.value = await listOrgs()
  } catch (err) {
    orgError.value = {
      message: (err as Error).message || '组织树加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    orgLoading.value = false
  }
}

const orgVisible = ref(false)
const orgSaving = ref(false)
const orgForm = reactive<{ id?: number; name: string; parentId?: number; tenantId?: number }>({
  id: undefined,
  name: '',
  parentId: undefined,
  tenantId: undefined,
})

function openOrg(parent?: OrgRow): void {
  orgForm.id = undefined
  orgForm.name = ''
  orgForm.parentId = parent?.id
  orgForm.tenantId = parent?.tenantId
  orgVisible.value = true
}

function editOrg(row: OrgRow): void {
  orgForm.id = row.id
  orgForm.name = row.name
  orgForm.parentId = row.parentId ?? undefined
  orgForm.tenantId = row.tenantId ?? undefined
  orgVisible.value = true
}

async function submitOrg(): Promise<void> {
  if (!orgForm.name.trim()) {
    ElMessage.warning('请填写组织名称')
    return
  }
  orgSaving.value = true
  try {
    await saveOrg({
      id: orgForm.id,
      name: orgForm.name.trim(),
      parentId: orgForm.parentId,
      tenantId: orgForm.tenantId,
    })
    orgVisible.value = false
    ElMessage.success('组织已保存')
    await loadOrgs()
  } catch (err) {
    ElMessage.error((err as Error).message || '保存失败')
  } finally {
    orgSaving.value = false
  }
}

async function switchTab(next: 'tenant' | 'org'): Promise<void> {
  tab.value = next
  if (next === 'tenant' && !tenants.value.length) await loadTenants()
  if (next === 'org' && !orgs.value.length) await loadOrgs()
}

onMounted(async () => {
  await Promise.all([loadTenants(), loadOrgs()])
})
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <div class="tabs">
        <button type="button" class="tabs__item" :class="{ 'tabs__item--active': tab === 'tenant' }" @click="switchTab('tenant')">
          租户（{{ tenantTotal }}）
        </button>
        <button type="button" class="tabs__item" :class="{ 'tabs__item--active': tab === 'org' }" @click="switchTab('org')">
          组织树
        </button>
      </div>
      <div class="adm-toolbar__spacer" />
      <el-button v-if="tab === 'tenant'" type="primary" @click="openTenant">新建租户</el-button>
      <el-button v-else type="primary" @click="openOrg()">新建顶级组织</el-button>
    </div>

    <section v-if="tab === 'tenant'" class="surface">
      <ErrorState v-if="tenantError" :message="tenantError.message" :code="tenantError.code" :status="tenantError.status" @retry="loadTenants" />
      <EmptyState v-else-if="!tenantLoading && !tenants.length" title="还没有租户" description="每次创建会初始化库结构并预置角色/域/字典" icon="inbox" action-text="新建租户" @action="openTenant" />
      <template v-else>
        <el-table v-loading="tenantLoading" :data="tenants" row-key="tenantId">
          <el-table-column prop="tenantId" label="ID" width="80" />
          <el-table-column prop="code" label="code" width="160">
            <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="200" />
          <el-table-column label="隔离形态" width="140">
            <template #default>
              <span class="adm-cell adm-cell--restricted">ROW · 行级</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ row.status || '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</template>
          </el-table-column>
        </el-table>
        <div class="adm-pager">
          <el-pagination layout="prev, pager, next" :current-page="tenantPage" :page-size="tenantSize" :total="tenantTotal" background small @current-change="(next: number) => { tenantPage = next; loadTenants() }" />
        </div>
      </template>
    </section>

    <section v-else class="surface">
      <ErrorState v-if="orgError" :message="orgError.message" :code="orgError.code" :status="orgError.status" @retry="loadOrgs" />
      <EmptyState v-else-if="!orgLoading && !orgTree.length" title="还没有组织" description="组织树决定审批人解析范围（本部门 / 上级 / 全租户）" icon="inbox" action-text="新建顶级组织" @action="openOrg()" />
      <el-table v-else v-loading="orgLoading" :data="orgTree" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <el-table-column prop="name" label="组织" min-width="260" />
        <el-table-column prop="id" label="ID" width="90">
          <template #default="{ row }"><span class="t-code">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="上级" width="120">
          <template #default="{ row }">{{ row.parentId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="租户" width="90">
          <template #default="{ row }">{{ row.tenantId ?? '当前' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openOrg(row)">新增子部门</el-button>
              <el-button link @click="editOrg(row)">编辑</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="tenantVisible" title="新建租户" width="480px" :close-on-click-modal="false" @closed="provisioned = null">
      <el-form label-position="top">
        <el-form-item label="租户 code" required>
          <el-input v-model="tenantForm.code" placeholder="如 acme-supply" maxlength="16" />
        </el-form-item>
        <el-form-item label="租户名称" required>
          <el-input v-model="tenantForm.name" maxlength="60" />
        </el-form-item>
        <p class="adm-hint">本期仅支持 ROW 行级隔离；创建后自动预置 10 角色、7 业务域与字典项。</p>
      </el-form>
      <div v-if="provisioned" class="adm-secret">
        <strong>创建完成</strong>
        <p class="adm-hint">已初始化：{{ Object.entries(provisioned).map(([key, value]) => `${key} ${value}`).join(' · ') }}</p>
      </div>
      <template #footer>
        <el-button @click="tenantVisible = false">{{ provisioned ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!provisioned" type="primary" :loading="tenantCreating" @click="submitTenant">创建并初始化</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="orgVisible" :title="orgForm.id ? '编辑组织' : '新建组织'" width="440px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="orgForm.name" maxlength="60" />
        </el-form-item>
        <el-form-item label="上级组织 ID">
          <el-input-number v-model="orgForm.parentId" :min="1" :controls="false" style="width: 100%" placeholder="留空 = 顶级组织" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orgVisible = false">取消</el-button>
        <el-button type="primary" :loading="orgSaving" @click="submitOrg">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.adm-pages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
