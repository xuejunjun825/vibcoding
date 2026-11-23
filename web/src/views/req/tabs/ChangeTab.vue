<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listRequirementChanges } from '@/api/req'
import type { ChangeRow, ChangeStatus } from '@/api/req'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  changeDetailPath,
  impactSummaryLines,
  statusLabel,
  typeLabel,
} from '@/views/req/change'
import { formatDateTime } from '@/utils/format'

/**
 * 需求详情第 10 个 Tab「变更」（DESIGN §3.1）。
 *
 * 列表与详情分离：这里只列表/筛选/分页，点行进深链详情页；
 * 角标（PENDING 总数）与 APPROVED 横幅由父级拿 counts 渲染——Tab 自身不占头部空间。
 */
const props = defineProps<{ requirementId: number }>()
const emit = defineEmits<{
  (event: 'changed'): void
  (event: 'create'): void
  (event: 'counts', value: { pending: number; approved: number }): void
}>()

const router = useRouter()

const rows = ref<ChangeRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref<ChangeStatus | ''>('')
const loading = ref(false)
const loadError = ref('')

const statusOptions = computed(() =>
  (['PENDING', 'APPROVED', 'APPLIED', 'REJECTED', 'WITHDRAWN'] as ChangeStatus[]).map((value) => ({
    value,
    label: statusLabel(value),
  })),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const result = await listRequirementChanges(props.requirementId, {
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = result.items ?? []
    total.value = result.total ?? 0
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = (error as { message?: string })?.message ?? '变更单加载失败'
  } finally {
    loading.value = false
  }
}

/** 角标与横幅要的是各自状态的**总数**，与当前筛选无关，因此单独拉两次 count */
async function loadCounts(): Promise<void> {
  try {
    const [pending, approved] = await Promise.all([
      listRequirementChanges(props.requirementId, { status: 'PENDING', page: 1, size: 1 }),
      listRequirementChanges(props.requirementId, { status: 'APPROVED', page: 1, size: 1 }),
    ])
    emit('counts', { pending: pending.total ?? 0, approved: approved.total ?? 0 })
  } catch {
    // 角标是辅助信息：拉不到就不显示，绝不弹错
    emit('counts', { pending: 0, approved: 0 })
  }
}

async function refresh(): Promise<void> {
  await Promise.all([load(), loadCounts()])
}

function openRow(row: ChangeRow): void {
  void router.push(changeDetailPath(props.requirementId, row.id))
}

function onFilterChange(): void {
  page.value = 1
  void load()
}

function onPageChange(value: number): void {
  page.value = value
  void load()
}

watch(
  () => props.requirementId,
  () => {
    page.value = 1
    void refresh()
  },
)

onMounted(() => {
  void refresh()
})

defineExpose({ refresh })
</script>

<template>
  <div class="change-tab">
    <div class="change-tab__bar">
      <el-select v-model="status" size="small" class="change-tab__filter" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <span class="change-tab__count">共 {{ total }} 条</span>
      <span class="change-tab__spacer" />
      <el-button size="small" type="primary" @click="emit('create')">发起变更</el-button>
    </div>

    <ErrorState v-if="loadError" compact title="变更单加载失败" :message="loadError" @retry="refresh" />

    <div v-else class="change-tab__surface surface">
      <table v-if="rows.length || loading" class="change-table" data-test="change-table">
        <colgroup>
          <col style="width: 96px" />
          <col style="width: var(--w-change-status)" />
          <col style="width: var(--w-change-type)" />
          <col />
          <col style="width: var(--w-change-impact)" />
          <col style="width: var(--w-change-applicant)" class="col--applicant" />
          <col style="width: var(--w-change-time)" />
        </colgroup>
        <thead>
          <tr>
            <th>变更单</th>
            <th>状态</th>
            <th>类型</th>
            <th>原因</th>
            <th>影响摘要</th>
            <th class="col--applicant">申请人</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading && !rows.length">
            <td colspan="7" class="change-table__placeholder">加载中…</td>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.id"
            class="change-table__row"
            tabindex="0"
            @click="openRow(row)"
            @keyup.enter="openRow(row)"
          >
            <td>
              <router-link
                class="change-table__code mono"
                :to="changeDetailPath(requirementId, row.id)"
                @click.stop
              >
                #{{ row.id }}
              </router-link>
            </td>
            <td><StatusPill kind="reqChange" :value="row.status" size="sm" /></td>
            <td class="change-table__type">{{ typeLabel(row.changeType) }}</td>
            <td class="change-table__reason">{{ row.reason }}</td>
            <td class="change-table__impact">
              <template v-if="row.impactSummary">
                <span class="change-table__impact-line">{{ impactSummaryLines(row.impactSummary).line1 }}</span>
                <span v-if="impactSummaryLines(row.impactSummary).line2" class="change-table__impact-line">
                  {{ impactSummaryLines(row.impactSummary).line2 }}
                </span>
              </template>
              <span v-else class="change-table__impact-line">无法评估</span>
            </td>
            <td class="col--applicant change-table__applicant">{{ row.createdBy ? `#${row.createdBy}` : '—' }}</td>
            <td class="change-table__time">{{ formatDateTime(row.appliedAt || row.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <EmptyState
        v-if="!rows.length && !loading"
        icon="inbox"
        title="暂无变更"
        description="已入池需求的改动从这里发起，审批通过后需显式应用"
        action-text="发起变更"
        @action="emit('create')"
      />
    </div>

    <el-pagination
      v-if="total > size"
      class="change-tab__pager"
      layout="prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      background
      small
      @current-change="onPageChange"
    />
  </div>
</template>

<style scoped>
.change-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.change-tab__bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.change-tab__filter {
  width: 132px;
}

.change-tab__count {
  font-size: 12px;
  color: var(--text-muted-aa);
}

.change-tab__spacer {
  flex: 1;
}

.change-tab__surface {
  padding: 0;
  overflow: hidden;
}

.change-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.change-table th {
  height: 36px;
  padding: 0 12px;
  background: var(--bg-table-head);
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted-aa);
  text-align: left;
}

.change-table td {
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  font-size: 13px;
  color: var(--text-body);
  vertical-align: top;
}

.change-table__row {
  min-height: 48px;
  cursor: pointer;
  transition: background 120ms var(--ease);
}

.change-table__row:hover,
.change-table__row:focus-visible {
  background: var(--bg-hover);
}

.change-table__row:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.change-table__placeholder {
  padding: 24px 12px;
  text-align: center;
  color: var(--text-muted-aa);
}

.change-table__code {
  font-size: 12px;
  color: var(--color-primary);
  text-decoration: none;
}

.change-table__code:hover {
  text-decoration: underline;
}

.change-table__type {
  color: var(--text-title);
}

.change-table__reason {
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.change-table__impact {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.change-table__impact-line {
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
}

.change-table__applicant,
.change-table__time {
  font-size: 12px;
  color: var(--text-muted-aa);
  white-space: nowrap;
}

.change-tab__pager {
  align-self: flex-end;
}

@media (max-width: 1280px) {
  .col--applicant {
    display: none;
  }
}
</style>
