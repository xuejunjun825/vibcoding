<script setup lang="ts">
import { computed } from 'vue'
import type {
  CacheState,
  Metric,
  PortalCard,
  PortalSection as PortalSectionData,
  TodoMode,
} from '@/api/analytics'
import DataStamp from '@/components/analytics/DataStamp.vue'
import { formatNumber } from '@/utils/format'

/**
 * 单区渲染（AC-3/AC-4/AC-5）。
 *
 * 每区自带状态：一区 `DEGRADED` 只让本区显示原因与「重试」，其余四区照常渲染
 * （后端按 provider 逐个 try/catch，前端不能因为一个区失败就整页白屏）。
 *
 * 类型名与组件名同名，按 TECHNICAL §6.1 的提醒改别名 `PortalSectionData`，
 * 避免 SFC 自引用与类型标识符撞名。
 */
const props = defineProps<{
  section: PortalSectionData
  todoMode: TodoMode
  /** 指标区右上角的口径戳（AC-4）：与页头同一份 dataAsOf / cache */
  dataAsOf?: string | null
  cache?: CacheState | string | null
}>()

const emit = defineEmits<{ (event: 'retry', key: string): void }>()

const degraded = computed(() => props.section.state === 'DEGRADED')
const readOnly = computed(() => props.todoMode === 'READONLY')
const isTodo = computed(() => props.section.key === 'todo')
const isActions = computed(() => props.section.key === 'actions')
const isMessages = computed(() => props.section.key === 'messages')
/** 只读态下 actions 区整区不渲染（AC-3）：这是按区 key 隐藏，不是角色→模板映射 */
const hidden = computed(() => readOnly.value && isActions.value)

/** 待办区的空态文案是 AC-3 的硬断言；其余区空态用中性文案。 */
const emptyText = computed(() => (isTodo.value ? '暂无待办' : '暂无内容'))

/**
 * 卡片去向：个人卡固定进报表详情（`reportId` 由后端给，不用自己从 key 里解析）；
 * 模板卡走后端下发的 `link`。只读态的待办区不产生任何可点入口（AC-3）。
 */
function cardTo(card: PortalCard): string | null {
  if (readOnly.value && isTodo.value) return null
  if (card.reportId) return `/reports/${card.reportId}`
  return card.link || null
}

function isExternal(card: PortalCard): boolean {
  return (card.link || '').startsWith('http')
}

function metricTitle(metric: Metric): string {
  const head = metric.caliber ? `${metric.label} · ${metric.caliber}` : metric.label
  if (metric.value === null || metric.value === undefined) return `${head}（暂无数值，不补 0）`
  return head
}

function cardTitle(card: PortalCard): string {
  if (!card.metrics.length) return '暂无数据'
  return card.metrics.map((metric) => metricTitle(metric)).join('；')
}
</script>

<template>
  <section
    v-if="!hidden"
    class="ps"
    :class="{ 'ps--degraded': degraded, 'ps--readonly': readOnly }"
    :data-testid="`portal-section-${section.key}`"
    :data-state="section.state"
  >
    <header class="ps__head">
      <h2 class="ps__title">{{ section.title }}</h2>
      <!-- 指标区是唯一需要单独标口径的区：其余区的数字都来自同一份 dataAsOf -->
      <DataStamp
        v-if="section.key === 'metrics'"
        :data-as-of="dataAsOf ?? null"
        :cache="cache ?? null"
      />
      <el-tag v-if="degraded" size="small" type="warning" effect="plain">降级</el-tag>
    </header>

    <!-- 区内降级：只影响本区，原因可读，重试由页面统一重取一次 -->
    <div v-if="degraded" class="ps__error" role="alert">
      <p class="ps__error-title">本区数据读取失败，其他区域不受影响</p>
      <p class="ps__error-reason">{{ section.error || '未知原因' }}</p>
      <el-button size="small" @click="emit('retry', section.key)">重试</el-button>
    </div>

    <div v-else-if="!section.cards.length" class="ps__empty">{{ emptyText }}</div>

    <!-- 快捷入口：只有「去哪」，没有数字 -->
    <div v-else-if="isActions" class="ps__actions">
      <router-link
        v-for="card in section.cards"
        :key="card.key"
        class="ps__action"
        :to="cardTo(card) || '/portal'"
      >
        <span class="ps__action-label">{{ card.title }}</span>
        <span v-if="card.hint" class="ps__action-hint">{{ card.hint }}</span>
      </router-link>
    </div>

    <!-- 提示区：一句话一条，不占卡片网格 -->
    <ul v-else-if="isMessages" class="ps__messages">
      <li v-for="card in section.cards" :key="card.key" class="ps__message">
        <span class="ps__message-dot" aria-hidden="true" />
        <span class="ps__message-text">{{ card.hint || card.title }}</span>
      </li>
    </ul>

    <!-- 卡片网格：一张卡上的每个 Metric 都要渲染（模板卡多数带 2 个指标，AC-4 逐位对照） -->
    <div v-else class="ps__grid" :class="{ 'ps__grid--todo': isTodo }">
      <component
        :is="isExternal(card) && cardTo(card) ? 'a' : cardTo(card) ? 'router-link' : 'div'"
        v-for="card in section.cards"
        :key="card.key"
        class="ps__card"
        :data-card="card.key"
        :to="isExternal(card) ? undefined : cardTo(card) || undefined"
        :href="isExternal(card) ? cardTo(card) || undefined : undefined"
        :title="cardTitle(card)"
      >
        <span class="ps__card-title">{{ card.title }}</span>

        <div v-if="card.metrics.length" class="ps__metrics">
          <div
            v-for="metric in card.metrics"
            :key="metric.key"
            class="ps__metric"
            :class="{
              'ps__metric--muted': metric.value === null || metric.value === undefined,
              'ps__metric--primary': card.metrics.length === 1,
            }"
          >
            <span v-if="card.metrics.length > 1" class="ps__metric-label">{{ metric.label }}</span>
            <span class="ps__metric-value">
              {{ formatNumber(metric.value, metric.digits) }}<em v-if="metric.unit" class="ps__metric-unit">{{ metric.unit }}</em>
            </span>
          </div>
        </div>

        <span v-if="card.hint" class="ps__card-hint">{{ card.hint }}</span>
      </component>
    </div>
  </section>
</template>

<style scoped>
.ps {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  padding: 12px 14px 14px;
  min-width: 0;
  transition: border-color 0.15s ease;
}

.ps--degraded {
  border-color: var(--color-warning, #ff7d00);
  background: #fffdf9;
}

.ps__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  min-height: 22px;
}

.ps__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #1d2129);
}

.ps__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
}

.ps__grid--todo {
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
}

.ps__card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  text-decoration: none;
  color: inherit;
  min-height: 74px;
  min-width: 0;
  transition: border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

a.ps__card:hover,
.ps__card:focus-visible {
  border-color: var(--color-primary);
  background: #f8fbff;
  box-shadow: 0 1px 4px rgba(22, 93, 255, 0.08);
  outline: none;
}

.ps__card:focus-visible {
  box-shadow: 0 0 0 2px var(--color-primary-light, #e8f0ff);
}

.ps__card-title {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ps__metrics {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ps__metric {
  display: flex;
  gap: 6px;
  align-items: baseline;
  min-width: 0;
}

.ps__metric--primary {
  flex-direction: column;
  gap: 0;
}

.ps__metric-label {
  flex: none;
  max-width: 45%;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ps__metric-value {
  font-size: 18px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-primary, #1d2129);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ps__metric--primary .ps__metric-value {
  font-size: 24px;
  line-height: 30px;
}

.ps__metric--muted .ps__metric-value {
  color: var(--text-muted-aa, #6b7280);
  font-weight: 500;
}

.ps__metric-unit {
  margin-left: 3px;
  font-size: 12px;
  font-style: normal;
  font-weight: 400;
  color: var(--text-muted-aa, #6b7280);
}

.ps__card-hint {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  line-height: 18px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ps__messages {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ps__message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  background: #fafbfc;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-primary, #1d2129);
}

.ps__message-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--color-primary);
}

.ps__message-text {
  min-width: 0;
  word-break: break-word;
}

.ps__empty {
  padding: 18px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted-aa, #6b7280);
}

.ps__error {
  padding: 12px;
  border: 1px dashed var(--border-strong, #c9cdd4);
  border-radius: 6px;
  background: #fffaf3;
}

.ps__error-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #1d2129);
}

.ps__error-reason {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
  word-break: break-all;
}

.ps__actions {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}

.ps__action {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  text-decoration: none;
  color: inherit;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.ps__action:hover,
.ps__action:focus-visible {
  border-color: var(--color-primary);
  background: #f8fbff;
  outline: none;
}

.ps__action-label {
  font-size: 13px;
  font-weight: 500;
}

.ps__action-hint {
  font-size: 12px;
  color: var(--text-muted-aa, #6b7280);
}
</style>
