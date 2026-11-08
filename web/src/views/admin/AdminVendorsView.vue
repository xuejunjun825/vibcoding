<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listDomains, listVendors, saveDomain, saveVendor } from '@/api/admin'
import type { DomainRow, VendorRow } from '@/api/admin'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 厂商与业务域（DESIGN P12 / AC-2、AC-13）。
 * 域 × 厂商是隔离的两个维度：域决定“做什么”，厂商决定“谁能看”。
 */
const tab = ref<'domain' | 'vendor'>('domain')

const domains = ref<DomainRow[]>([])
const vendors = ref<VendorRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [domainList, vendorList] = await Promise.all([listDomains(), listVendors()])
    domains.value = domainList ?? []
    vendors.value = vendorList ?? []
  } catch (err) {
    error.value = {
      message: (err as Error).message || '配置加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/* ---------------- 业务域 ---------------- */

const domainVisible = ref(false)
const domainSaving = ref(false)
const domainForm = reactive<{ id?: number; code: string; name: string; ownerId?: number }>({
  id: undefined,
  code: '',
  name: '',
  ownerId: undefined,
})

function openDomain(row?: DomainRow): void {
  domainForm.id = row?.id
  domainForm.code = row?.code ?? ''
  domainForm.name = row?.name ?? ''
  domainForm.ownerId = row?.ownerId ?? undefined
  domainVisible.value = true
}

async function submitDomain(): Promise<void> {
  if (!domainForm.name.trim()) {
    ElMessage.warning('请填写域名称')
    return
  }
  domainSaving.value = true
  try {
    await saveDomain({
      id: domainForm.id,
      code: domainForm.code || undefined,
      name: domainForm.name.trim(),
      ownerId: domainForm.ownerId,
    })
    domainVisible.value = false
    ElMessage.success('业务域已保存')
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '保存失败')
  } finally {
    domainSaving.value = false
  }
}

/* ---------------- 厂商 ---------------- */

const vendorVisible = ref(false)
const vendorSaving = ref(false)
const vendorForm = reactive<{
  id?: number
  name: string
  contact: string
  status: string
  domainIds: number[]
}>({ id: undefined, name: '', contact: '', status: 'ACTIVE', domainIds: [] })

function openVendor(row?: VendorRow): void {
  vendorForm.id = row?.id
  vendorForm.name = row?.name ?? ''
  vendorForm.contact = row?.contact ?? ''
  vendorForm.status = row?.status ?? 'ACTIVE'
  vendorForm.domainIds = [...(row?.domainIds ?? [])]
  vendorVisible.value = true
}

async function submitVendor(): Promise<void> {
  if (!vendorForm.name.trim()) {
    ElMessage.warning('请填写厂商名称')
    return
  }
  if (!vendorForm.domainIds.length) {
    ElMessage.warning('厂商至少关联一个业务域，否则看不到任何需求')
    return
  }
  vendorSaving.value = true
  try {
    await saveVendor({
      id: vendorForm.id,
      name: vendorForm.name.trim(),
      contact: vendorForm.contact || undefined,
      status: vendorForm.status,
      domainIds: vendorForm.domainIds,
    })
    vendorVisible.value = false
    ElMessage.success('厂商已保存')
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '保存失败')
  } finally {
    vendorSaving.value = false
  }
}

function domainNames(ids?: number[]): string {
  if (!ids?.length) return '—'
  return ids
    .map((id) => domains.value.find((domain) => domain.id === id)?.name ?? `#${id}`)
    .join('、')
}

onMounted(load)
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <div class="tabs">
        <button type="button" class="tabs__item" :class="{ 'tabs__item--active': tab === 'domain' }" @click="tab = 'domain'">
          业务域（{{ domains.length }}）
        </button>
        <button type="button" class="tabs__item" :class="{ 'tabs__item--active': tab === 'vendor' }" @click="tab = 'vendor'">
          厂商（{{ vendors.length }}）
        </button>
      </div>
      <div class="adm-toolbar__spacer" />
      <el-button v-if="tab === 'domain'" type="primary" @click="openDomain()">新建业务域</el-button>
      <el-button v-else type="primary" @click="openVendor()">新建厂商</el-button>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <section v-else-if="tab === 'domain'" class="surface">
      <EmptyState
        v-if="!loading && !domains.length"
        title="还没有业务域"
        description="7 个业务域通常由租户初始化预置，也可在此补充"
        icon="chart"
        action-text="新建业务域"
        @action="openDomain()"
      />
      <el-table v-else v-loading="loading" :data="domains" row-key="id">
        <el-table-column label="code" width="150">
          <template #default="{ row }"><span class="t-code">{{ row.code || `DOM-${row.id}` }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="业务域" min-width="220" />
        <el-table-column label="负责人" width="160">
          <template #default="{ row }">{{ row.ownerName || (row.ownerId ? `用户 ${row.ownerId}` : '未指定') }}</template>
        </el-table-column>
        <el-table-column label="关联厂商" width="200">
          <template #default="{ row }">
            {{ vendors.filter((vendor) => vendor.domainIds?.includes(row.id)).map((vendor) => vendor.name).join('、') || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openDomain(row)">编辑</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-else class="surface">
      <EmptyState
        v-if="!loading && !vendors.length"
        title="还没有厂商"
        description="厂商账号默认只能看到关联域内分配给自己的需求"
        icon="lock"
        action-text="新建厂商"
        @action="openVendor()"
      />
      <el-table v-else v-loading="loading" :data="vendors" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="厂商" min-width="200" />
        <el-table-column prop="contact" label="联系人" width="160">
          <template #default="{ row }">{{ row.contact || '—' }}</template>
        </el-table-column>
        <el-table-column label="业务域" min-width="240">
          <template #default="{ row }">{{ domainNames(row.domainIds) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.status || 'ACTIVE' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openVendor(row)">编辑</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="domainVisible" :title="domainForm.id ? '编辑业务域' : '新建业务域'" width="460px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="code">
          <el-input v-model="domainForm.code" maxlength="32" placeholder="如 payment" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="domainForm.name" maxlength="60" />
        </el-form-item>
        <el-form-item label="负责人用户 ID">
          <el-input-number v-model="domainForm.ownerId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <p class="adm-hint">负责人用于审批人解析（域负责人）与发布通知，不会改变数据隔离范围。</p>
      </el-form>
      <template #footer>
        <el-button @click="domainVisible = false">取消</el-button>
        <el-button type="primary" :loading="domainSaving" @click="submitDomain">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="vendorVisible" :title="vendorForm.id ? '编辑厂商' : '新建厂商'" width="520px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="厂商名称" required>
          <el-input v-model="vendorForm.name" maxlength="80" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="vendorForm.contact" maxlength="80" />
        </el-form-item>
        <el-form-item label="关联业务域" required>
          <el-select v-model="vendorForm.domainIds" multiple filterable collapse-tags style="width: 100%">
            <el-option v-for="domain in domains" :key="domain.id" :label="domain.name" :value="domain.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="vendorForm.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vendorVisible = false">取消</el-button>
        <el-button type="primary" :loading="vendorSaving" @click="submitVendor">保存</el-button>
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
