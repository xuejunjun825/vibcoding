<script setup lang="ts">
import { computed } from 'vue'
import type { FlowDrillRow, FlowStageCard } from '@/api/collab'

/**
 * 价值流单列（DESIGN §6 `FlowColumn`）：160 宽 = 列头 44 + 指标 72 + 卡 56×n。
 *
 * 列里所有数字都来自 `/flow/board`，卡片来自 `/flow/drill`（可见性同源），
 * 本组件不做任何均值/P90/瓶颈计算——`bottleneck` 是服务端布尔值，这里只画。
 */
const props = withDefaults(
  defineProps<{
    stage: FlowStageCard
    cards?: FlowDrillRow[]
    cardsLoading?: boolean
    selected?: boolean
    previewSize?: number
  }>(),
  { cards: () => [], cardsLoading: false, selected: false, previewSize: 5 },
)

const emit = defineEmits<{
  (e: 'drill', stageCode: string): void
  (e: 'open-requirement', row: FlowDrillRow): void
}>()

const applicable = computed(() => props.stage.applicable !== false)
const insufficient = computed(() => applicable.value && props.stage.sampleSize > 0 && props.stage.sampleSize < 5)
const noMetric = computed(() => applicable.value && props.stage.sampleSize === 0)

function num(value?: number | null, digits = 1): string {
  if (value === null || value === undefined) return '—'
  return Number(value).toFixed(digits)
}

const shownCards = computed(() => props.cards.slice(0, props.previewSize))
const more = computed(() => Math.max(0, (props.stage.wip ?? 0) - shownCards.value.length))

function clickColumn(): void {
  if (!applicable.value) return
  emit('drill', props.stage.code)
}
</script>

<template>
  <section
    class="fc"
    :class="{
      'fc--bottleneck': stage.bottleneck,
      'fc--na': !applicable,
      'fc--selected': selected,
      'fc--empty': noMetric,
    }"
    :aria-label="`${stage.name} 阶段`"
  >
    <header
      class="fc__head"
      :class="{ 'fc__head--clickable': applicable }"
      role="button"
      :tabindex="applicable ? 0 : -1"
      :aria-disabled="!applicable"
      @click="clickColumn"
      @keydown.enter.prevent="clickColumn"
      @keydown.space.prevent="clickColumn"
    >
      <span class="fc__name" :title="stage.name">{{ stage.name }}</span>
      <span v-if="stage.bottleneck" class="fc__badge fc__badge--bottleneck">瓶颈</span>
      <span v-else-if="insufficient" class="fc__badge fc__badge--warn" title="样本少于 5，不参与瓶颈判定">样本不足</span>
      <span class="fc__order">P{{ stage.orderNo }}</span>
    </header>

    <div v-if="applicable" class="fc__metrics">
      <div class="fc__metric">
        <span class="fc__metric-label">WIP</span>
        <span class="fc__metric-value">{{ stage.wip }}</span>
      </div>
      <div class="fc__metric">
        <span class="fc__metric-label">平均停留</span>
        <span class="fc__metric-value">
          {{ num(stage.dwellAvg) }}<span class="fc__unit">天</span>
        </span>
      </div>
      <div class="fc__metric">
        <span class="fc__metric-label">P90</span>
        <span class="fc__metric-value">
          {{ num(stage.dwellP90) }}<span class="fc__unit">天</span>
        </span>
      </div>
      <div class="fc__metric">
        <span class="fc__metric-label">样本</span>
        <span class="fc__metric-value">{{ stage.sampleSize }}</span>
      </div>
    </div>
    <div v-else class="fc__na">
      <p class="fc__na-title">该需求类型不适用</p>
      <p class="fc__na-hint">阶段映射未包含此类型，指标与下钻均不提供</p>
    </div>

    <div v-if="applicable" class="fc__cards">
      <div v-if="cardsLoading" class="fc__cards-skeleton" aria-hidden="true">
        <div v-for="n in Math.min(2, previewSize)" :key="n" class="fc__card-skeleton" />
      </div>
      <template v-else>
        <button
          v-for="card in shownCards"
          :key="card.id"
          type="button"
          class="fc__card"
          :title="`${card.code} ${card.title} · 停留 ${num(card.dwellDays)} 天`"
          @click.stop="emit('open-requirement', card)"
        >
          <span class="fc__card-title">{{ card.title }}</span>
          <span class="fc__card-meta">
            <span class="fc__card-code">{{ card.code }}</span>
            <span class="fc__card-dwell" :class="{ 'fc__card-dwell--na': card.dwellDays === null }">
              {{ card.dwellDays === null ? '无数据' : `${num(card.dwellDays)} 天` }}
            </span>
          </span>
        </button>
        <button v-if="more > 0 || (stage.wip > 0 && !shownCards.length)" type="button" class="fc__more" @click.stop="clickColumn">
          查看全部 {{ stage.wip }} 条 →
        </button>
        <p v-if="!stage.wip && !shownCards.length" class="fc__no-wip">当前没有停留在此阶段的条目</p>
      </template>
    </div>

    <footer v-if="applicable" class="fc__foot">
      <button type="button" class="fc__drill" @click.stop="clickColumn">下钻</button>
      <span v-if="stage.noDataCount > 0" class="fc__nodata" :title="`${stage.noDataCount} 条缺少阶段审计，未计入指标`">
        无数据 {{ stage.noDataCount }}
      </span>
    </footer>
  </section>
</template>

<style scoped>
.fc {
  position: relative;
  flex: 0 0 160px;
  width: 160px;
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  overflow: hidden;
  transition: box-shadow var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.fc--bottleneck {
  border-top: 2px solid var(--color-primary);
  padding-top: 0;
}
.fc--selected {
  box-shadow: 0 0 0 2px var(--color-primary);
}
.fc--na {
  border-style: dashed;
  border-color: var(--border-strong);
  background: var(--bg-table-head);
}
.fc__head {
  flex: none;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--divider);
  outline: none;
}
.fc__head--clickable {
  cursor: pointer;
}
.fc__head--clickable:hover .fc__name {
  color: var(--color-primary);
}
.fc__head:focus-visible {
  box-shadow: inset 0 0 0 2px var(--color-primary);
}
.fc__name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fc__order {
  flex: none;
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--text-weak);
}
.fc__badge {
  flex: none;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-cell);
  font-size: 10px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
}
.fc__badge--bottleneck {
  background: var(--color-primary-light);
  color: var(--color-active);
}
.fc__badge--warn {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  border: 1px solid #e9c989;
}
.fc__metrics {
  flex: none;
  height: 72px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--divider);
}
.fc__metric {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}
.fc__metric-label {
  font-size: 10px;
  color: var(--text-weak);
  white-space: nowrap;
}
.fc__metric-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.fc__unit {
  font-size: 10px;
  font-weight: 400;
  color: var(--text-muted-aa);
  margin-left: 1px;
}
.fc__na {
  flex: 1 1 auto;
  padding: 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fc__na-title {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.fc__na-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-weak);
}
.fc__cards {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fc__card {
  flex: none;
  height: 56px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.fc__card:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.fc__card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.fc__card-title {
  font-size: 12px;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fc__card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: var(--text-weak);
}
.fc__card-code {
  font-family: var(--font-mono);
  white-space: nowrap;
}
.fc__card-dwell {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.fc__card-dwell--na {
  color: var(--warning-ink);
}
.fc__more {
  flex: none;
  height: 24px;
  border: 1px dashed var(--border);
  border-radius: var(--r-cell);
  background: none;
  font-size: 11px;
  color: var(--color-primary);
  cursor: pointer;
}
.fc__more:hover {
  background: var(--color-primary-light);
}
.fc__no-wip {
  margin: 4px 0;
  font-size: 11px;
  color: var(--text-weak);
  text-align: center;
}
.fc__cards-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fc__card-skeleton {
  height: 56px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  animation: fc-pulse 1.2s ease-in-out infinite;
}
@keyframes fc-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
.fc__foot {
  flex: none;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  border-top: 1px solid var(--divider);
}
.fc__drill {
  border: 0;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--color-primary);
  cursor: pointer;
}
.fc__drill:hover {
  text-decoration: underline;
}
.fc__nodata {
  font-size: 10px;
  color: var(--warning-ink);
}
</style>
