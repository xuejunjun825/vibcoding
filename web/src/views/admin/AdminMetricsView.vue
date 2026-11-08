<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listMetricDefinitions } from '@/api/deliver'
import type { MetricDefinition } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 指标字典（DESIGN P12 / AC-14）。
 * 只读：公式与口径的唯一实现在 contract 的 MetricFormulas，
 * 字典只声明“这个指标叫什么、口径是什么、哪个版本生效”。
 * 口径变更视为新版本，历史数字仍按当时版本可解释。
 */
const rows = ref<MetricDefinition[]>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    rows.value = (await listMetricDefinitions()) ?? []
  } catch (err) {
    error.value = {
      message: (err as Error).message || '指标字典加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return rows.value
  return rows.value.filter(
    (row) =>
      row.code?.toLowerCase().includes(text) ||
      row.name?.toLowerCase().includes(text) ||
      row.formula?.toLowerCase().includes(text),
  )
})

/** 同 code 的行按版本倒序聚合，便于看“这一版公式和上一版差在哪”。 */
const grouped = computed(() => {
  const map = new Map<string, MetricDefinition[]>()
  for (const row of filtered.value) {
    const key = row.code || row.name || '未命名'
    const list = map.get(key) ?? []
    list.push(row)
    map.set(key, list)
  }
  return [...map.entries()].map(([code, list]) => ({
    code,
    list: [...list].sort((a, b) => (b.version ?? 0) - (a.version ?? 0)),
  }))
})

function versionLabel(row: MetricDefinition): string {
  return row.version ? `v${row.version}` : '未标版本'
}

onMounted(load)
</script>

<template>
  <div class="adm-pages">
    <div class="adm-toolbar">
      <el-input v-model="keyword" placeholder="搜索编码 / 名称 / 公式" clearable style="width: 260px" />
      <div class="adm-toolbar__spacer" />
      <el-button link :loading="loading" @click="load">刷新</el-button>
      <el-button link type="primary" @click="$router.push({ name: 'defect-board' })">去看板看实际数字</el-button>
    </div>

    <section class="surface surface--pad">
      <p class="adm-hint" style="margin: 0">
        前端与脚本都不重算指标：看板卡片、下钻明细、发布统计读的是同一份服务端结果。
        下方只展示服务端下发的口径定义，供审计与对账使用。
      </p>
    </section>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <section v-else class="surface">
      <EmptyState
        v-if="!loading && !grouped.length"
        title="没有匹配的指标定义"
        description="指标定义随服务端下发；清空搜索可看全部"
        icon="chart"
      />
      <div v-else v-loading="loading" class="metric-list">
        <article v-for="item in grouped" :key="item.code" class="metric-item">
          <header class="metric-item__head">
            <span class="t-code">{{ item.code }}</span>
            <span class="t-block-title">{{ item.list[0]?.name || item.code }}</span>
            <span class="adm-scope-pill">{{ versionLabel(item.list[0]) }}</span>
            <span v-if="item.list.length > 1" class="ink-muted" style="font-size: 12px">
              共 {{ item.list.length }} 个版本
            </span>
          </header>
          <div v-for="definition in item.list" :key="`${definition.code}-${definition.version ?? 0}`" class="metric-version">
            <div class="metric-version__formula">
              <span class="adm-hint">公式</span>
              <code>{{ definition.formula || '—' }}</code>
            </div>
            <div class="metric-version__meta">
              <span>{{ versionLabel(definition) }}</span>
              <span v-if="definition.ownerName">口径负责人 {{ definition.ownerName }}</span>
              <span v-if="definition.updatedAt">更新于 {{ definition.updatedAt }}</span>
            </div>
            <p v-if="definition.caliberNote" class="adm-hint" style="margin: 4px 0 0">
              {{ definition.caliberNote }}
            </p>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.adm-pages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-list {
  display: flex;
  flex-direction: column;
}

.metric-item {
  padding: 14px 16px;
  border-bottom: 1px solid var(--divider);
}

.metric-item:last-child {
  border-bottom: 0;
}

.metric-item__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.metric-version {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-table-head);
}

.metric-version + .metric-version {
  margin-top: 6px;
}

.metric-version__formula {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.metric-version__formula code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-body);
}

.metric-version__meta {
  display: flex;
  gap: 14px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
</style>
