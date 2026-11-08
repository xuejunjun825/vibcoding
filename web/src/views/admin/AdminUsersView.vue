<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDomains,
  listPermissions,
  listUsers,
  listVendors,
  resetPassword,
  saveUser,
  setUserStatus,
} from '@/api/admin'
import type { DomainRow, UserRow, VendorRow } from '@/api/admin'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import '@/views/admin/admin-shared.css'

/**
 * 用户与角色（DESIGN P12 / AC-5）。
 * 角色只从服务端权限矩阵下发的集合里选，前端不维护角色表；
 * 授权域/厂商决定数据范围（域 × 厂商双维度），所以必须显式选择。
 */
const keyword = ref('')
const rows = ref<UserRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const roleOptions = ref<Array<{ code: string; name: string }>>([])
const domains = ref<DomainRow[]>([])
const vendors = ref<VendorRow[]>([])

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listUsers({ keyword: keyword.value || undefined, page: page.value, size })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    error.value = {
      message: (err as Error).message || '用户列表加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  const [domainResult, vendorResult, permissionResult] = await Promise.allSettled([
    listDomains(),
    listVendors(),
    listPermissions(),
  ])
  if (domainResult.status === 'fulfilled') domains.value = domainResult.value ?? []
  if (vendorResult.status === 'fulfilled') vendors.value = vendorResult.value ?? []
  if (permissionResult.status === 'fulfilled') {
    const roles = permissionResult.value.roles ?? []
    roleOptions.value = roles.map((role) =>
      typeof role === 'string' ? { code: role, name: role } : { code: role.code, name: role.name || role.code },
    )
  }
}

function search(): void {
  page.value = 1
  void load()
}

/* ---------------- 新建 / 编辑 ---------------- */

const visible = ref(false)
const saving = ref(false)
const form = reactive<{
  id?: number
  username: string
  name: string
  roles: string[]
  orgId?: number
  domainIds: number[]
  vendorId?: number
  password: string
}>({
  id: undefined,
  username: '',
  name: '',
  roles: [],
  orgId: undefined,
  domainIds: [],
  vendorId: undefined,
  password: '',
})

const isEdit = computed(() => Boolean(form.id))

function openCreate(): void {
  Object.assign(form, {
    id: undefined,
    username: '',
    name: '',
    roles: [],
    orgId: undefined,
    domainIds: [],
    vendorId: undefined,
    password: '',
  })
  visible.value = true
}

function openEdit(row: UserRow): void {
  Object.assign(form, {
    id: row.id,
    username: row.username,
    name: row.name,
    roles: [...(row.roles ?? [])],
    orgId: row.orgId,
    domainIds: [...(row.domainIds ?? [])],
    vendorId: row.vendorId ?? undefined,
    password: '',
  })
  visible.value = true
}

async function submit(): Promise<void> {
  if (!form.username.trim() || !form.name.trim()) {
    ElMessage.warning('用户名与姓名必填')
    return
  }
  if (!form.roles.length) {
    ElMessage.warning('至少分配一个角色（权限矩阵按角色求解）')
    return
  }
  if (!isEdit.value && form.password.length < 8) {
    ElMessage.warning('初始密码至少 8 位')
    return
  }
  saving.value = true
  try {
    await saveUser({
      id: form.id,
      username: form.username.trim(),
      name: form.name.trim(),
      roles: form.roles,
      orgId: form.orgId,
      domainIds: form.domainIds,
      vendorId: form.vendorId,
      password: isEdit.value ? form.password || undefined : form.password,
    })
    visible.value = false
    ElMessage.success(isEdit.value ? '用户已更新（权限下一请求生效）' : '用户已创建')
    await load()
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    ElMessage.error(apiError.status === 409 ? apiError.message || '用户名已存在（409）' : apiError.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- 状态 / 重置密码 ---------------- */

async function toggleStatus(row: UserRow): Promise<void> {
  const next = row.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
  try {
    await ElMessageBox.confirm(
      next === 'DISABLED'
        ? '停用后该账号立即失效（在途审批仍保留历史记录），确定停用？'
        : '恢复后该账号可立即登录，确定启用？',
      next === 'DISABLED' ? '停用账号' : '启用账号',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await setUserStatus(row.id, next)
    row.status = next
    ElMessage.success(next === 'DISABLED' ? '已停用' : '已启用')
  } catch (err) {
    ElMessage.error((err as Error).message || '状态变更失败')
  }
}

async function resetUserPassword(row: UserRow): Promise<void> {
  let password = ''
  try {
    const result = await ElMessageBox.prompt(`为 ${row.name}（${row.username}）设置新密码`, '重置密码', {
      inputType: 'password',
      inputPlaceholder: '至少 8 位',
      confirmButtonText: '重置',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value && value.length >= 8 ? true : '至少 8 位'),
    })
    password = result.value
  } catch {
    return
  }
  try {
    await resetPassword(row.id, password)
    ElMessage.success('密码已重置，请通过安全渠道转交')
  } catch (err) {
    ElMessage.error((err as Error).message || '重置失败')
  }
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索用户名 / 姓名"
        clearable
        style="width: 240px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button @click="search">搜索</el-button>
      <div class="adm-toolbar__spacer" />
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </div>

    <section class="surface">
      <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
      <EmptyState
        v-else-if="!loading && !rows.length"
        :variant="keyword ? 'filtered' : 'empty'"
        :title="keyword ? '没有匹配的用户' : '还没有用户'"
        description="用户必须绑定角色与授权域，权限按矩阵求解"
        icon="search"
        :action-text="keyword ? '清空搜索' : '新建用户'"
        @action="keyword ? ((keyword = ''), search()) : openCreate()"
      />
      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id">
          <el-table-column prop="username" label="用户名" width="150">
            <template #default="{ row }"><span class="t-code">{{ row.username }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="姓名" width="130" />
          <el-table-column label="角色" min-width="200">
            <template #default="{ row }">
              <span class="adm-scope-pills">
                <span v-for="role in row.roles" :key="role" class="adm-scope-pill">{{ role }}</span>
                <span v-if="!row.roles?.length" class="ink-muted">未分配</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="组织 / 厂商" min-width="170">
            <template #default="{ row }">
              <span>{{ row.orgName || '—' }}</span>
              <span v-if="row.vendorId" class="ink-muted"> · 厂商 {{ row.vendorId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="授权域" width="150">
            <template #default="{ row }">
              <span class="t-aux">{{ row.domainIds?.length ? `${row.domainIds.length} 个域` : '全部（按角色）' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusPill kind="release" :value="row.status || 'ACTIVE'" size="sm" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link @click="toggleStatus(row)">{{ row.status === 'DISABLED' ? '启用' : '停用' }}</el-button>
                <el-button link @click="resetUserPassword(row)">重置密码</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="adm-pager">
          <el-pagination
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="total"
            background
            small
            @current-change="(next: number) => { page = next; load() }"
          />
        </div>
      </template>
    </section>

    <el-dialog v-model="visible" :title="isEdit ? '编辑用户' : '新建用户'" width="640px" :close-on-click-modal="false">
      <el-form label-position="top">
        <div class="adm-form-grid">
          <el-form-item label="用户名" required>
            <el-input v-model="form.username" :disabled="isEdit" maxlength="32" />
          </el-form-item>
          <el-form-item label="姓名" required>
            <el-input v-model="form.name" maxlength="32" />
          </el-form-item>
        </div>
        <el-form-item label="角色" required>
          <el-select v-model="form.roles" multiple filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="role in roleOptions" :key="role.code" :label="`${role.name}（${role.code}）`" :value="role.code" />
          </el-select>
        </el-form-item>
        <div class="adm-form-grid">
          <el-form-item label="授权域">
            <el-select v-model="form.domainIds" multiple filterable collapse-tags style="width: 100%">
              <el-option v-for="domain in domains" :key="domain.id" :label="domain.name" :value="domain.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属厂商">
            <el-select v-model="form.vendorId" clearable filterable style="width: 100%">
              <el-option v-for="vendor in vendors" :key="vendor.id" :label="vendor.name" :value="vendor.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="adm-form-grid">
          <el-form-item label="组织 ID">
            <el-input-number v-model="form.orgId" :min="1" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="isEdit ? '重置密码（留空不变）' : '初始密码'" :required="!isEdit">
            <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
          </el-form-item>
        </div>
        <p class="adm-hint">权限 = 角色在矩阵中的并集 ∩ 授权域；修改角色后每请求重新读取，秒级生效。</p>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
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
