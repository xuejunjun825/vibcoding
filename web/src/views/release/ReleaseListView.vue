<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createRelease, listReleases } from '@/api/req'
import { listDomains } from '@/api/admin'
import type { DomainRow } from '@/api/admin'
import type { ReleaseRow } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { formatDate } from '@/utils/format'
import { messageOf, releasePath } from '@/views/req/platform'

/**
 * P4 版本计划（AC-11）：双栏卡片列表 + 锁定态 + 新建版本。
 * 筛选（domainId / status）与分页保存在 URL，便于分享与复现。
 */
const router = useRouter()
const { can } = usePermission()

const list = useListQuery<ReleaseRow>(
  (query) =>
    listReleases({
      domainId: (query.filters.domainId as string) || undefined,
      status: (query.filters.status as string) || undefined,
      page: query.page,
      size: query.size,
    }),
  { domainId: '', status: '' },
)

const domains = ref<DomainRow[]>([])
onMounted(async () => {
  try {
    domains.value = await listDomains()
  } catch {
    domains.value = []
  }
})

const createOpen = ref(false)
const name = ref('')
const domainId = ref<number | undefined>()
const startDate = ref('')
const endDate = ref('')
const plannedReleaseDate = ref('')
const gateMode = ref<'STRICT' | 'LOOSE'>('STRICT')
const saving = ref(false)
const formError = ref('')

function openCreate(): void {
  createOpen.value = true
  name.value = ''
  domainId.value = domains.value[0]?.id
  startDate.value = ''
  endDate.value = ''
  plannedReleaseDate.value = ''
  gateMode.value = 'STRICT'
  formError.value = ''
}

const canCreate = computed(() => name.value.trim().length >= 2 && domainId.value != null && !saving.value)

async function submitCreate(): Promise<void> {
  if (!canCreate.value || domainId.value == null) return
  saving.value = true
  formError.value = ''
  try {
    const created = await createRelease({
      name: name.value.trim(),
      domainId: domainId.value,
      startDate: startDate.value || undefined,
      endDate: endDate.value || undefined,
      plannedReleaseDate: plannedReleaseDate.value || undefined,
      gateMode: gateMode.value,
    })
    ElMessage.success(`版本 ${created.code} 已创建`)
    createOpen.value = false
    await router.push(releasePath(created.id))
  } catch (error) {
    formError.value = messageOf(error)
  } finally {
    saving.value = false
  }
}

function progressOf(row: ReleaseRow): number {
  const total = row.requirementCount ?? 0
  if (!total) return 0
  return Math.min(100, Math.round(((row.doneCount ?? 0) / total) * 100))
}

function domainName(id?: number): string {
  return domains.value.find((domain) => domain.id === id)?.name ?? (id != null ? `域 #${id}` : '—')
}
</script>

<template>
  <div class="releases">
    <header class="page-head">
      <div class="page-head__left">
        <h1 class="page-head__title">版本计划</h1>
        <span class="count-badge">{{ list.total.value }}</span>
      </div>
      <div class="page-head__actions">
        <el-select
          :model-value="(list.filters.value.domainId as string) || ''"
          size="small"
          placeholder="全部域"
          clearable
          style="width: 150px"
          @update:model-value="(value: string) => list.setFilter('domainId', value ?? '')"
        >
          <el-option v-for="domain in domains" :key="domain.id" :label="domain.name" :value="String(domain.id)" />
        </el-select>
        <el-select
          :model-value="(list.filters.value.status as string) || ''"
          size="small"
          placeholder="全部状态"
          clearable
          style="width: 130px"
          @update:model-value="(value: string) => list.setFilter('status', value ?? '')"
        >
          <el-option label="规划中" value="PLAN" />
          <el-option label="已锁定" value="LOCKED" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button
          type="primary"
          size="small"
          :disabled="!can('release:plan')"
          :title="can('release:plan') ? '' : '需要权限：release:plan'"
          @click="openCreate"
        >
          新建版本
        </el-button>
      </div>
    </header>

    <div v-if="list.loading.value && list.rows.value.length === 0" class="cards">
      <div v-for="index in 4" :key="index" class="skeleton" style="height: 148px" />
    </div>

    <ErrorState
      v-else-if="list.error.value"
      title="版本列表加载失败"
      :message="list.error.value?.message ?? '加载失败'"
      @retry="list.load"
    />

    <EmptyState
      v-else-if="list.isEmpty.value"
      :variant="list.isFiltered.value ? 'filtered' : 'empty'"
      :title="list.isFiltered.value ? '当前筛选没有版本' : '还没有版本计划'"
      :description="list.isFiltered.value ? '清空筛选或换一个域试试。' : '版本计划把需求、测试与发布串起来；先建一个版本再纳入需求。'"
      :action-text="list.isFiltered.value ? '清空筛选' : ''"
      icon="file"
      @action="list.reset"
    />

    <div v-else class="cards">
      <button
        v-for="row in list.rows.value"
        :key="row.id"
        type="button"
        class="release-card"
        @click="router.push(releasePath(row.id))"
      >
        <div class="release-card__head">
          <span class="release-card__code mono">{{ row.code }}</span>
          <StatusPill kind="release" :value="row.status" size="sm" />
          <span class="filter-bar__spacer" />
          <span class="release-card__mode" :class="{ 'is-loose': row.gateMode === 'LOOSE' }">
            {{ row.gateMode === 'LOOSE' ? '宽松门禁' : '严格门禁' }}
          </span>
        </div>
        <h2 class="release-card__title">{{ row.name }}</h2>
        <div class="release-card__meta">
          <span>{{ domainName(row.domainId) }}</span>
          <span>{{ formatDate(row.startDate) }} → {{ formatDate(row.endDate) }}</span>
          <span v-if="row.plannedReleaseDate">计划发布 {{ formatDate(row.plannedReleaseDate) }}</span>
        </div>
        <div class="release-card__progress">
          <div class="release-card__bar" role="progressbar" :aria-valuenow="progressOf(row)" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${progressOf(row)}%` }" />
          </div>
          <span class="release-card__stat">
            {{ row.doneCount ?? 0 }}/{{ row.requirementCount ?? 0 }} 需求完成
          </span>
        </div>
        <div class="release-card__foot">
          <span v-if="(row.riskCount ?? 0) > 0" class="release-card__risk">{{ row.riskCount }} 项风险</span>
          <span v-else class="release-card__ok">无风险项</span>
          <span v-if="row.status === 'LOCKED'" class="release-card__locked">已锁定 · 需求范围不可变更</span>
        </div>
      </button>
    </div>

    <div v-if="list.total.value > list.size.value" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :current-page="list.page.value"
        :page-size="list.size.value"
        :total="list.total.value"
        @current-change="list.setPage"
      />
    </div>

    <el-dialog v-model="createOpen" title="新建版本" width="520px" append-to-body>
      <el-form label-position="top" size="small" @submit.prevent>
        <el-form-item label="版本名称" required>
          <el-input v-model="name" maxlength="60" show-word-limit placeholder="如：2026 年 11 月迭代" />
        </el-form-item>
        <el-form-item label="业务域" required>
          <el-select v-model="domainId" filterable placeholder="选择业务域" style="width: 100%">
            <el-option v-for="domain in domains" :key="domain.id" :label="`${domain.code} · ${domain.name}`" :value="domain.id" />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="开始日期">
            <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
          <el-form-item label="结束日期">
            <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
          <el-form-item label="计划发布">
            <el-date-picker v-model="plannedReleaseDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
        </div>
        <el-form-item label="测试门禁模式">
          <el-radio-group v-model="gateMode">
            <el-radio-button value="STRICT">严格：用例必须全通过</el-radio-button>
            <el-radio-button value="LOOSE">宽松：允许带未执行</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <p v-if="formError" class="dialog-error">{{ formError }}</p>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canCreate" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.releases {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.release-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.release-card:hover {
  border-color: #c9d6ee;
  background: var(--bg-hover);
}
.release-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.release-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.release-card__code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.release-card__mode {
  font-size: 11px;
  color: var(--info-ink);
  background: var(--color-info-bg);
  border-radius: var(--r-pill);
  padding: 1px 8px;
}
.release-card__mode.is-loose {
  color: var(--warning-ink);
  background: var(--color-warning-bg);
}
.release-card__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.release-card__meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.release-card__progress {
  display: flex;
  align-items: center;
  gap: 10px;
}
.release-card__bar {
  flex: 1;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--divider);
  overflow: hidden;
}
.release-card__bar span {
  display: block;
  height: 100%;
  background: var(--color-primary);
  transition: width var(--t-expand) var(--ease);
}
.release-card__stat {
  font-size: 12px;
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.release-card__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.release-card__risk {
  color: var(--warning-ink);
}
.release-card__ok {
  color: var(--success-ink);
}
.release-card__locked {
  margin-left: auto;
  color: var(--text-muted-aa);
}
.pager {
  display: flex;
  justify-content: flex-end;
}
.form-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
@media (max-width: 1080px) {
  .cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
