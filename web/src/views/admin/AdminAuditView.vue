<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listAudit } from '@/api/admin'
import type { AuditRow } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 审计日志（DESIGN P12 / AC-5、AC-7）。
 * 403、强制放行、密钥轮换等关键动作都在这里可回查；
 * 支持按 bizType + bizId 反查（从缺陷/需求详情直接跳进来）。
 */
const route = useRoute()
const router = useRouter()

const bizType = ref(String(route.query.bizType ?? ''))
const bizId = ref(String(route.query.bizId ?? ''))
const userId = ref(String(route.query.userId ?? ''))
const range = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const rows = ref<AuditRow[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

function syncUrl(): void {
  const query: Record<string, string> = {}
  if (bizType.value) query.bizType = bizType.value
  if (bizId.value) query.bizId = bizId.value
  if (userId.value) query.userId = userId.value
  if (page.value > 1) query.page = String(page.value)
  void router.replace({ name: 'admin-audit', query })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const response = await listAudit({
      bizType: bizType.value || undefined,
      bizId: bizId.value || undefined,
      userId: userId.value || undefined,
      from: range.value?.[0],
      to: range.value?.[1],
      page: page.value,
      size: size.value,
    })
    rows.value = response.items ?? []
    total.value = response.total ?? rows.value.length
  } catch (err) {
    error.value = {
      message: (err as Error).message || '审计日志加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  syncUrl()
  void load()
}

function reset(): void {
  bizType.value = ''
  bizId.value = ''
  userId.value = ''
  range.value = null
  search()
}

function onPage(next: number): void {
  page.value = next
  syncUrl()
  void load()
}

const isFiltered = computed(() => Boolean(bizType.value || bizId.value || userId.value || range.value))

function json(detail: unknown): string {
  if (detail == null) return '—'
  try {
    return JSON.stringify(detail, null, 2)
  } catch {
    return String(detail)
  }
}

function bizLabel(row: AuditRow): string {
  if (!row.bizType && !row.bizId) return '—'
  return `${row.bizType ?? '—'}${row.bizId != null ? ` #${row.bizId}` : ''}`
}

watch(
  () => route.query,
  () => {
    if (route.name !== 'admin-audit') return
    const nextBizType = String(route.query.bizType ?? '')
    const nextBizId = String(route.query.bizId ?? '')
    if (nextBizType !== bizType.value || nextBizId !== bizId.value) {
      bizType.value = nextBizType
      bizId.value = nextBizId
      page.value = 1
      void load()
    }
  },
)

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="adm-pages">
    <section class="surface surface--pad">
      <div class="adm-toolbar">
        <el-input v-model="bizType" placeholder="bizType，如 defect" clearable style="width: 170px" />
        <el-input v-model="bizId" placeholder="bizId" clearable style="width: 110px" />
        <el-input v-model="userId" placeholder="操作人 ID" clearable style="width: 120px" />
        <el-date-picker
          v-model="range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <p class="adm-hint" style="margin: 8px 0 0">
        按 bizType + bizId 反查单张单据的全部动作记录；审计写入不随业务事务回滚（403、限流也留证）。
      </p>
    </section>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <section v-else class="surface">
      <EmptyState
        v-if="!loading && !rows.length"
        :variant="isFiltered ? 'filtered' : 'empty'"
        :title="isFiltered ? '没有匹配的审计记录' : '还没有审计记录'"
        :description="isFiltered ? '换一个 bizType/bizId 或放宽时间范围' : '关键写操作会自动落审计'"
        icon="file"
      />
      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <pre class="adm-json">{{ json(row.detail) }}</pre>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="170" />
          <el-table-column label="动作" width="180">
            <template #default="{ row }"><span class="t-code">{{ row.action }}</span></template>
          </el-table-column>
          <el-table-column label="对象" width="190">
            <template #default="{ row }">{{ bizLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="操作人" width="150">
            <template #default="{ row }">{{ row.userName || '系统' }}</template>
          </el-table-column>
          <el-table-column label="详情" min-width="240">
            <template #default="{ row }">
              <span class="ink-muted" style="font-size: 12px">{{ json(row.detail).slice(0, 90) || '—' }}</span>
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
  </div>
</template>

<style scoped>
.adm-pages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
