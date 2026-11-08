<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createApiClient,
  listApiClients,
  rotateApiClientSecret,
  setApiClientStatus,
} from '@/api/admin'
import type { ApiClientRow, PageResponse } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 开放 API 客户端（DESIGN P12 / AC-17）。
 * Secret 只在创建与轮换时返回一次，列表永不展示；新建弹窗必须勾选
 * 「我已保存」才可关闭，避免密钥丢失后无法自助找回（只能轮换）。
 */
const page = ref(1)
const size = ref(20)
const total = ref(0)
const rows = ref<ApiClientRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const SCOPE_OPTIONS = [
  'execution:write',
  'execution:read',
  'requirement:read',
  'defect:write',
  'metric:read',
]

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const response: PageResponse<ApiClientRow> = await listApiClients({ page: page.value, size: size.value })
    rows.value = response.items ?? []
    total.value = response.total ?? rows.value.length
  } catch (err) {
    error.value = {
      message: (err as Error).message || '客户端列表加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function onPage(next: number): void {
  page.value = next
  void load()
}

/* ---------------- 新建 ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const form = reactive<{ name: string; scopes: string[]; ipWhitelist: string; rateLimitQps: number }>({
  name: '',
  scopes: ['execution:write'],
  ipWhitelist: '',
  rateLimitQps: 20,
})

function openCreate(): void {
  form.name = ''
  form.scopes = ['execution:write']
  form.ipWhitelist = ''
  form.rateLimitQps = 20
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写客户端名称')
    return
  }
  if (!form.scopes.length) {
    ElMessage.warning('至少选择一个 scope')
    return
  }
  creating.value = true
  try {
    const created = await createApiClient({
      name: form.name.trim(),
      scopes: form.scopes,
      ipWhitelist: form.ipWhitelist.trim() || undefined,
      rateLimitQps: form.rateLimitQps || undefined,
    })
    createVisible.value = false
    openSecret(created.appKey, created.secret, '客户端已创建')
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '创建失败')
  } finally {
    creating.value = false
  }
}

/* ---------------- Secret 一次性展示 ---------------- */

const secretVisible = ref(false)
const secretAck = ref(false)
const secretPair = reactive<{ appKey: string; secret: string }>({ appKey: '', secret: '' })
const secretTitle = ref('')

function openSecret(appKey: string, secret: string, title: string): void {
  secretPair.appKey = appKey
  secretPair.secret = secret
  secretTitle.value = title
  secretAck.value = false
  secretVisible.value = true
}

async function copy(text: string, label: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label} 已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

async function rotate(row: ApiClientRow): Promise<void> {
  try {
    const rotated = await rotateApiClientSecret(row.id)
    openSecret(rotated.appKey, rotated.secret, 'Secret 已轮换')
    ElMessage.success('旧 Secret 立即失效，请更新 CI 侧配置')
  } catch (err) {
    ElMessage.error((err as Error).message || '轮换失败')
  }
}

/* ---------------- 状态 ---------------- */

const busyId = ref<number | string | null>(null)

async function toggleStatus(row: ApiClientRow): Promise<void> {
  const next = row.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
  busyId.value = row.id
  try {
    await setApiClientStatus(row.id, next)
    ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用，该客户端的上报将全部 401')
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '状态更新失败')
  } finally {
    busyId.value = null
  }
}

const isEmpty = computed(() => !loading.value && !rows.value.length)

onMounted(load)
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <span class="adm-hint">外部 CI 用 AppKey + HMAC-SHA256 签名上报执行结果；Secret 仅在创建/轮换时显示一次。</span>
      <div class="adm-toolbar__spacer" />
      <el-button link :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建客户端</el-button>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <section v-else class="surface">
      <EmptyState
        v-if="isEmpty"
        title="还没有 API 客户端"
        description="没有客户端时只有手工执行能写入测试结果"
        icon="lock"
        action-text="新建客户端"
        @action="openCreate"
      />
      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column label="AppKey" width="240">
            <template #default="{ row }">
              <span class="t-code">{{ row.appKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Scopes" min-width="220">
            <template #default="{ row }">
              <span class="adm-scope-pills">
                <span v-for="scope in row.scopes || []" :key="scope" class="adm-scope-pill">{{ scope }}</span>
                <span v-if="!row.scopes?.length" class="ink-muted">—</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="IP 白名单" width="180">
            <template #default="{ row }">{{ row.ipWhitelist || '不限制' }}</template>
          </el-table-column>
          <el-table-column label="QPS" width="90">
            <template #default="{ row }">{{ row.rateLimitQps ?? '默认' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span :class="row.status === 'DISABLED' ? 'ink-muted' : 'ink-success'">
                {{ row.status === 'DISABLED' ? '已停用' : '启用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" :loading="busyId === row.id" @click="toggleStatus(row)">
                  {{ row.status === 'DISABLED' ? '启用' : '停用' }}
                </el-button>
                <el-button link @click="rotate(row)">轮换 Secret</el-button>
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
            @current-change="onPage"
          />
        </div>
      </template>
    </section>

    <el-dialog v-model="createVisible" title="新建 API 客户端" width="520px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="60" placeholder="如 支付域 Jenkins" />
        </el-form-item>
        <el-form-item label="Scopes" required>
          <el-select v-model="form.scopes" multiple filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="scope in SCOPE_OPTIONS" :key="scope" :label="scope" :value="scope" />
          </el-select>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="form.ipWhitelist" placeholder="逗号分隔；留空表示不限制" />
        </el-form-item>
        <el-form-item label="限流 QPS">
          <el-input-number v-model="form.rateLimitQps" :min="1" :max="1000" :controls="false" style="width: 100%" />
        </el-form-item>
        <p class="adm-hint">Secret 由服务端生成并只返回一次，请立即交给 CI 侧保管。</p>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="secretVisible"
      :title="secretTitle"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <p class="adm-hint" style="margin-bottom: 12px">
        这是唯一一次显示 Secret 的机会，关闭后无法再次查看，只能轮换（旧 Secret 立即失效）。
      </p>
      <div class="adm-secret">
        <div class="adm-secret__row">
          <span class="adm-secret__label">AppKey</span>
          <code class="adm-secret__value">{{ secretPair.appKey }}</code>
          <el-button link type="primary" @click="copy(secretPair.appKey, 'AppKey')">复制</el-button>
        </div>
        <div class="adm-secret__row">
          <span class="adm-secret__label">Secret</span>
          <code class="adm-secret__value">{{ secretPair.secret }}</code>
          <el-button link type="primary" @click="copy(secretPair.secret, 'Secret')">复制</el-button>
        </div>
      </div>
      <el-checkbox v-model="secretAck" style="margin-top: 16px">我已妥善保存 AppKey 与 Secret</el-checkbox>
      <template #footer>
        <el-button type="primary" :disabled="!secretAck" @click="secretVisible = false">完成</el-button>
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

.adm-secret__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-table-head);
}

.adm-secret__row + .adm-secret__row {
  margin-top: 8px;
}

.adm-secret__label {
  width: 56px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.adm-secret__value {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  word-break: break-all;
}
</style>
