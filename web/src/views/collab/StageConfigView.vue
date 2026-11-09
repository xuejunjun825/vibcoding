<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/client'
import { fetchStages, saveStages, type FlowStageDefinition, type FlowStageDraft } from '@/api/collab'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/deliver/shared.css'

/**
 * 价值流阶段配置（DESIGN V5 · AC-08）。
 *
 * 保存即审计：启停与排序会改变看板列和指标口径，所以这里只有「保存」一个写入口，
 * 并且把服务端的校验结果原样展示（最少 2 个阶段 / 类型不能重复 / 映射必须是合法 JSON）。
 */
const router = useRouter()

interface StageRow extends FlowStageDraft {
  rowKey: string
  enabled: boolean
  reqTypes: string
  mappingJson: string
}

const rows = ref<StageRow[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const saveError = ref('')
const dirty = ref(false)
let seq = 0

function toRow(stage: FlowStageDefinition, index: number): StageRow {
  seq += 1
  return {
    rowKey: `row-${stage.id ?? 'new'}-${seq}`,
    id: stage.id,
    code: stage.code,
    name: stage.name,
    orderNo: stage.orderNo ?? index + 1,
    enabled: stage.enabled !== false,
    reqTypes: stage.reqTypes ?? '',
    mappingJson: stage.mappingJson ?? '',
  }
}

const count = computed(() => rows.value.length)
const enabledCount = computed(() => rows.value.filter((row) => row.enabled).length)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  saveError.value = ''
  try {
    const stages = await fetchStages()
    rows.value = stages.map(toRow)
    dirty.value = false
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message ?? '阶段配置加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

function addRow(): void {
  seq += 1
  rows.value = [
    ...rows.value,
    {
      rowKey: `row-new-${seq}`,
      code: '',
      name: '',
      orderNo: rows.value.length + 1,
      enabled: true,
      reqTypes: '',
      mappingJson: '',
    },
  ]
  dirty.value = true
}

function removeRow(index: number): void {
  const row = rows.value[index]
  if (!row) return
  if (row.id && rows.value.length <= 2) {
    saveError.value = '至少保留 2 个阶段（否则指标无法计算）'
    return
  }
  rows.value = rows.value.filter((_, i) => i !== index).map((item, i) => ({ ...item, orderNo: i + 1 }))
  dirty.value = true
}

function move(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= rows.value.length) return
  const next = [...rows.value]
  const [row] = next.splice(index, 1)
  if (!row) return
  next.splice(target, 0, row)
  rows.value = next.map((item, i) => ({ ...item, orderNo: i + 1 }))
  dirty.value = true
}

function markDirty(): void {
  dirty.value = true
  saveError.value = ''
}

function validate(): string {
  if (rows.value.length < 2) return '至少需要 2 个阶段'
  const codes = new Set<string>()
  for (const [index, row] of rows.value.entries()) {
    const no = index + 1
    const code = (row.code ?? '').trim().toUpperCase()
    if (!code) return `第 ${no} 行缺少阶段编码`
    if (!/^[A-Z][A-Z0-9_]{1,31}$/.test(code)) return `第 ${no} 行编码需为大写字母/数字/下划线（2–32 位）`
    if (codes.has(code)) return `阶段编码重复：${code}`
    codes.add(code)
    if (!(row.name ?? '').trim()) return `第 ${no} 行缺少阶段名称`
    if (row.mappingJson && row.mappingJson.trim()) {
      try {
        JSON.parse(row.mappingJson)
      } catch {
        return `第 ${no} 行的映射不是合法 JSON`
      }
    }
  }
  return ''
}

async function save(): Promise<void> {
  const message = validate()
  if (message) {
    saveError.value = message
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const drafts: FlowStageDraft[] = rows.value.map((row, index) => ({
      id: row.id,
      code: (row.code ?? '').trim().toUpperCase(),
      name: (row.name ?? '').trim(),
      orderNo: index + 1,
      enabled: row.enabled,
      reqTypes: (row.reqTypes ?? '').trim() || null,
      mappingJson: (row.mappingJson ?? '').trim() || null,
    }))
    const saved = await saveStages(drafts)
    rows.value = saved.map(toRow)
    dirty.value = false
    ElMessage.success('已保存，看板会按新口径重新计算')
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    if (apiError?.status === 409) {
      saveError.value = `${apiError.message}（建议先刷新再改）`
    } else {
      saveError.value = apiError?.message ?? '保存失败'
    }
  } finally {
    saving.value = false
  }
}

async function reset(): Promise<void> {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('放弃未保存的修改？', '重置', {
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  await load()
}

function goBoard(): void {
  void router.push({ name: 'value-stream' })
}
</script>

<template>
  <div class="dl-page stage-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">价值流阶段配置</h1>
        <span class="dl-meta">
          {{ count }} 个阶段 · {{ enabledCount }} 个启用 ·
          <span :class="dirty ? 'stage-dirty' : 'stage-clean'">{{ dirty ? '有未保存修改' : '已同步' }}</span>
        </span>
      </div>
      <div class="dl-head__actions">
        <el-button size="small" @click="goBoard">返回看板</el-button>
        <el-button size="small" :disabled="!dirty" @click="reset">重置</el-button>
        <el-button size="small" type="primary" :loading="saving" :disabled="loading || !dirty" @click="save">
          保存
        </el-button>
      </div>
    </header>

    <p class="dl-hint">
      阶段定义是看板列与指标口径的唯一来源：停用某阶段会让它从看板消失、也不再计入流动效率。
      保存会写一条审计；改动不保存不会影响任何人。
    </p>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load()" />

    <section v-else class="surface stage-surface">
      <div v-if="saveError" class="stage-error" role="alert">{{ saveError }}</div>

      <div v-if="loading" class="stage-skeleton" aria-busy="true">
        <div v-for="n in 4" :key="n" class="stage-skeleton__row" />
      </div>

      <EmptyState
        v-else-if="!rows.length"
        title="还没有阶段"
        description="至少配置 2 个阶段：它们会按顺序成为看板的列，并决定流动效率的分母。"
        icon="chart"
        action-text="添加第一个阶段"
        @action="addRow"
      />

      <table v-else class="stage-table">
        <thead>
          <tr>
            <th class="col-order">顺序</th>
            <th class="col-code">阶段编码</th>
            <th class="col-name">阶段名称</th>
            <th class="col-type">适用需求类型</th>
            <th class="col-mapping">映射（JSON）</th>
            <th class="col-enabled">启用</th>
            <th class="col-act">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="row.rowKey">
            <td class="col-order">
              <div class="stage-order">
                <span class="stage-order__no">{{ index + 1 }}</span>
                <button
                  type="button"
                  class="stage-move"
                  :disabled="index === 0"
                  aria-label="上移"
                  @click="move(index, -1)"
                >
                  ↑
                </button>
                <button
                  type="button"
                  class="stage-move"
                  :disabled="index === rows.length - 1"
                  aria-label="下移"
                  @click="move(index, 1)"
                >
                  ↓
                </button>
              </div>
            </td>
            <td class="col-code">
              <el-input v-model="row.code" size="small" placeholder="DEV" maxlength="32" @input="markDirty" />
            </td>
            <td class="col-name">
              <el-input v-model="row.name" size="small" placeholder="开发中" maxlength="40" @input="markDirty" />
            </td>
            <td class="col-type">
              <el-input
                v-model="row.reqTypes"
                size="small"
                placeholder="REQUIREMENT,TASK"
                @input="markDirty"
              />
            </td>
            <td class="col-mapping">
              <el-input
                v-model="row.mappingJson"
                size="small"
                placeholder='{"status":["IN_DEV"]}'
                @input="markDirty"
              />
            </td>
            <td class="col-enabled">
              <el-switch v-model="row.enabled" size="small" @change="markDirty" />
            </td>
            <td class="col-act">
              <button type="button" class="stage-remove" @click="removeRow(index)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="!loading && rows.length" class="stage-foot">
        <el-button size="small" @click="addRow">+ 添加阶段</el-button>
        <span class="stage-foot__hint">编码用于接口与报表，改名不改码；停用不会删除历史指标。</span>
      </div>
    </section>

    <section class="stage-notes surface surface--pad">
      <h2 class="stage-notes__title">口径说明</h2>
      <ul class="stage-notes__list">
        <li>流动效率 =(开发 + 测试的停留) ÷ (提报 → 交付的总时长)，阶段停用或样本不足会被排除并计数。</li>
        <li>平均停留与 P90 只统计有完整区间的事件；缺失事件的需求计入「无数据」，不按 0 处理。</li>
        <li>瓶颈 = 平均停留最长且样本 ≥5 的阶段；样本不足的列只显示黄色 chip，不参与比较。</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.stage-page {
  --stage-row: 64px;
}
.stage-surface {
  padding: 0 0 12px;
  border-radius: var(--r-card);
  overflow: hidden;
}
.stage-dirty {
  color: var(--warning-ink);
}
.stage-clean {
  color: var(--text-weak);
}
.stage-error {
  margin: 12px 16px 0;
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.stage-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.stage-table th {
  height: 40px;
  padding: 0 10px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  text-align: left;
  font-weight: 500;
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.stage-table td {
  height: var(--stage-row);
  padding: 6px 10px;
  border-bottom: 1px solid var(--divider);
  vertical-align: middle;
}
.stage-table tbody tr:hover {
  background: var(--bg-hover);
}
.col-order {
  width: 96px;
}
.col-code {
  width: 132px;
}
.col-name {
  width: 168px;
}
.col-type {
  width: 190px;
}
.col-mapping {
  min-width: 220px;
}
.col-enabled {
  width: 64px;
  text-align: center;
}
.col-act {
  width: 64px;
  text-align: right;
}
.stage-order {
  display: flex;
  align-items: center;
  gap: 4px;
}
.stage-order__no {
  width: 20px;
  font-family: var(--font-mono);
  color: var(--text-muted-aa);
}
.stage-move {
  width: 20px;
  height: 20px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
}
.stage-move:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.stage-move:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.stage-remove {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--danger-ink);
  cursor: pointer;
}
.stage-remove:hover {
  text-decoration: underline;
}
.stage-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px 0;
}
.stage-foot__hint {
  font-size: 11px;
  color: var(--text-weak);
}
.stage-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
}
.stage-skeleton__row {
  height: 40px;
  border-radius: var(--r-ctl);
  background: var(--bg-hover);
  animation: stage-pulse 1.2s ease-in-out infinite;
}
@keyframes stage-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 1;
  }
}
.stage-notes {
  margin-top: 16px;
}
.stage-notes__title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.stage-notes__list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--text-secondary);
}
</style>
