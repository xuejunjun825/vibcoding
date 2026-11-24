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
  fieldLabel,
  impactSummaryLines,
  statusLabel,
  typeLabel,
  verdictText,
  verdictTone,
} from '@/views/req/change'
import { usePlatformDict } from '@/views/req/platform'
import { formatDateTime } from '@/utils/format'

/**
 * 需求详情第 10 个 Tab「变更」（DESIGN §3.5）。
 *
 * 列表与详情分离：这里只列表/筛选/分页，点行进深链详情页；
 * 角标（PENDING 总数）与 APPROVED 横幅由父级拿 counts 渲染——Tab 自身不占头部空间。
 *
 * r43：7 列两行制。新增「目标字段」（chips）与「审批结果」两列，把原来的「原因」
 * 「影响摘要」两列并入目标字段的次行——列数与横向宽度都必须落在 DESIGN §0.7 的
 * `620 固定 + 320 弹性 = 940 最小宽` 上，否则 1280 视口要横向滚动。
 * 字段名一律走 `change.ts:fieldLabel`（11 字段），审批结果词走 `verdictText`，
 * 这里不拼中文（AC-10/AC-12）。
 */
const props = defineProps<{ requirementId: number }>()
const emit = defineEmits<{
  (event: 'changed'): void
  (event: 'create'): void
  (event: 'counts', value: { pending: number; approved: number }): void
}>()

const router = useRouter()
const { ensure, userName } = usePlatformDict()

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

/**
 * `ChangeRow.fields` 由服务端 r43 新增（TECHNICAL §3.5）；声明已在 `api/req.ts`，
 * 字段缺失/为 null（老响应）→ 空列表 → 渲染「—」。
 */
function fieldsOf(row: ChangeRow): string[] {
  const value = row.fields
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

/** 次行 = 「原因：… ｜影响：…」；两条都空才返回 null（空行不占高） */
function noteOf(row: ChangeRow): { line: string; title: string } | null {
  const reason = (row.reason ?? '').trim()
  const impact = impactSummaryLines(row.impactSummary)
  const parts: string[] = []
  if (reason) parts.push(`原因：${reason}`)
  if (impact.line1) parts.push(`影响：${impact.line1}`)
  if (!parts.length) return null
  const titleParts = [...parts]
  if (impact.line2) titleParts.push(`影响：${impact.line2}`)
  return { line: parts.join(' ｜'), title: titleParts.join(' ｜') }
}

function applicantOf(row: ChangeRow): string {
  if (row.createdBy == null) return '—'
  return userName(row.createdBy)
}

interface RowView {
  row: ChangeRow
  fields: string[]
  note: { line: string; title: string } | null
  applicant: string
}

/** 每行只算一次（`userName` 依赖字典装载，字典到位后自动重算） */
const viewRows = computed<RowView[]>(() =>
  rows.value.map((row) => ({
    row,
    fields: fieldsOf(row),
    note: noteOf(row),
    applicant: applicantOf(row),
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
  // 提出人要显示姓名而不是 `用户 #12`：字典是幂等装载，页面别处也在 ensure
  void ensure()
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
          <col class="col--target" />
          <col style="width: var(--w-change-verdict, 88px)" />
          <col style="width: var(--w-change-applicant)" />
          <col style="width: var(--w-change-time)" />
        </colgroup>
        <thead>
          <tr>
            <th>变更单</th>
            <th>状态</th>
            <th>类型</th>
            <th>目标字段</th>
            <th>审批结果</th>
            <th>提出人</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading && !rows.length">
            <td colspan="7" class="change-table__placeholder">加载中…</td>
          </tr>
          <tr
            v-for="item in viewRows"
            :key="item.row.id"
            class="change-table__row"
            tabindex="0"
            @click="openRow(item.row)"
            @keyup.enter="openRow(item.row)"
          >
            <td>
              <router-link
                class="change-table__code"
                :to="changeDetailPath(requirementId, item.row.id)"
                @click.stop
              >
                #{{ item.row.id }}
              </router-link>
            </td>
            <td><StatusPill kind="reqChange" :value="item.row.status" size="sm" /></td>
            <td class="change-table__type">{{ typeLabel(item.row.changeType) }}</td>
            <td class="change-table__target" data-test="change-target-fields">
              <div v-if="item.fields.length" class="change-table__chips">
                <span v-for="field in item.fields" :key="field" class="field-chip">{{ fieldLabel(field) }}</span>
              </div>
              <div v-else class="change-table__chips change-table__chips--empty">—</div>
              <div v-if="item.note" class="change-table__note" :title="item.note.title">{{ item.note.line }}</div>
            </td>
            <td class="change-table__verdict" :class="`verdict--${verdictTone(item.row.status)}`">
              {{ verdictText(item.row.status) }}
            </td>
            <td class="change-table__applicant">{{ item.applicant }}</td>
            <td class="change-table__time">{{ formatDateTime(item.row.appliedAt || item.row.createdAt) }}</td>
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

/* 7 列固定 620 + 弹性 320 = 940；比这窄就横向滚动，而不是藏列（DESIGN §3.5） */
.change-tab__surface {
  padding: 0;
  overflow-x: auto;
  overflow-y: hidden;
}

.change-table {
  width: 100%;
  min-width: 940px;
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
  white-space: nowrap;
}

.change-table td {
  height: var(--h-change-row, 60px);
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  font-size: 13px;
  color: var(--text-body);
  vertical-align: middle;
}

.change-table__row {
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
  font-family: var(--font-mono);
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

/* —— 目标字段：chips（规范序、不可点）+ 次行原因/影响 —— */
.change-table__target {
  min-width: 0;
}

.change-table__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-height: 20px;
}

.change-table__chips--empty {
  align-items: center;
  color: var(--text-muted-aa);
}

.field-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  font-size: 12px;
  line-height: 1;
  color: var(--text-title);
  white-space: nowrap;
  cursor: default;
}

.change-table__note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 审批结果：语义色文字，不做 pill（§0 反模式） —— */
.change-table__verdict {
  font-size: 12px;
  white-space: nowrap;
}

.verdict--success {
  color: var(--success-ink);
}

.verdict--danger {
  color: var(--danger-ink);
}

.verdict--warning {
  color: var(--warning-ink);
}

.verdict--neutral {
  color: var(--text-muted-aa);
}

.change-table__applicant,
.change-table__time {
  font-size: 12px;
  color: var(--text-muted-aa);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.change-tab__pager {
  align-self: flex-end;
}
</style>
