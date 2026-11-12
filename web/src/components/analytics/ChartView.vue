<script setup lang="ts">
/**
 * 图表渲染（T3 · TECHNICAL §6.5）：只画 `ChartModel`，不取数、不算指标。
 *
 * echarts 6 已装但全仓此前从未 import —— 这里按需注册（Bar/Line/Pie/Heatmap +
 * Grid/Tooltip/Legend/VisualMap + Canvas），避免整包进首屏 bundle（AC-18 无新依赖）。
 * 生命周期：init → setOption → ResizeObserver → dispose；空数据不初始化（§8-9）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import type { ComposeOption, EChartsType } from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import type {
  BarSeriesOption,
  HeatmapSeriesOption,
  LineSeriesOption,
  PieSeriesOption,
} from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
  VisualMapComponentOption,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import EmptyState from '@/components/EmptyState.vue'
import { formatNumber } from '@/utils/format'
import type { ChartModel } from './chartModel'
import '@/views/analytics/shared.css'

use([
  BarChart,
  LineChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  CanvasRenderer,
])

type ECOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | HeatmapSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | LegendComponentOption
  | VisualMapComponentOption
>

const props = withDefaults(
  defineProps<{
    model: ChartModel
    /** 2 维柱状是否堆叠（由 chartPlanFor 判定，默认不堆叠） */
    stacked?: boolean
    /** 画布高度（px）；宽度始终 100%，自适应容器 */
    height?: number
  }>(),
  { stacked: false, height: 280 },
)

const wrap = ref<HTMLDivElement | null>(null)
const host = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null
let observer: ResizeObserver | null = null

/**
 * 分类色板：主色对齐 token `--color-primary`，其余是与 tokens 语义色同族的辅助色。
 * canvas 里的颜色无法直接读 CSS 变量，轴/文字/分割线在 buildOption 里按 token 取。
 */
const BASE_PALETTE = ['#2b6fe5', '#1f9d45', '#c77700', '#7c5cd6', '#00a3a3', '#d9363e', '#e08d3c', '#4e5969']

function cssToken(name: string, fallback: string): string {
  if (typeof window === 'undefined' || !document?.documentElement) return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

const isEmpty = computed(() => {
  const model = props.model
  if (model.mode === 'HEATMAP') return model.heat.cells.length === 0
  if (model.categories.length === 0 || model.series.length === 0) return true
  return model.series.every((series) =>
    series.values.every((value) => value === null || value === undefined),
  )
})

const ariaLabel = computed(() => {
  const model = props.model
  const unit = model.unit ? `，单位 ${model.unit}` : ''
  if (model.mode === 'HEATMAP') {
    return `热力图：${model.heat.y.length} 行 × ${model.heat.x.length} 列${unit}`
  }
  return `${model.mode} 图：${model.categories.length} 个分类，${model.series.length} 个系列${unit}`
})

function formatValue(value: number): string {
  return Number.isInteger(value) ? formatNumber(value, 0) : formatNumber(value, 2)
}

function unitSuffix(): string {
  return props.model.unit ? ` ${props.model.unit}` : ''
}

function buildTooltip(): TooltipComponentOption {
  const textColor = cssToken('--text-title', '#1f2329')
  if (props.model.mode === 'HEATMAP') {
    return {
      trigger: 'item',
      formatter: (params: unknown) => {
        const point = params as { value?: [number, number, number] }
        const cell = point.value
        if (!cell) return '—'
        return `${props.model.heat.y[cell[1]] ?? ''} / ${props.model.heat.x[cell[0]] ?? ''}<br/><b>${formatValue(cell[2])}${unitSuffix()}</b>`
      },
      textStyle: { color: textColor, fontSize: 12 },
    }
  }
  if (props.model.mode === 'PIE') {
    return {
      trigger: 'item',
      formatter: (params: unknown) => {
        const point = params as { name?: string; value?: number; percent?: number }
        const value =
          typeof point.value === 'number' ? `${formatValue(point.value)}${unitSuffix()}` : '—'
        return `${point.name ?? ''}<br/><b>${value}</b>（${point.percent ?? 0}%）`
      },
      textStyle: { color: textColor, fontSize: 12 },
    }
  }
  return {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params: unknown) => {
      const items = Array.isArray(params) ? params : [params]
      const first = items[0] as { name?: string } | undefined
      const lines = items.map((raw) => {
        const point = raw as { marker?: string; seriesName?: string; value?: unknown }
        const value =
          typeof point.value === 'number' ? `${formatValue(point.value)}${unitSuffix()}` : '—'
        const name = point.seriesName ? `${point.seriesName}：` : ''
        return `${point.marker ?? ''}${name}${value}`
      })
      return [`<b>${first?.name ?? ''}</b>`, ...lines].join('<br/>')
    },
    textStyle: { color: textColor, fontSize: 12 },
  }
}

function buildOption(model: ChartModel): ECOption {
  const textColor = cssToken('--text-body', '#4e5969')
  const muted = cssToken('--text-muted-aa', '#6b7280')
  const border = cssToken('--border', '#e5e6eb')
  const card = cssToken('--bg-card', '#ffffff')
  const showLegend = model.series.length > 1
  const option: ECOption = {
    color: BASE_PALETTE,
    textStyle: {
      fontFamily: cssToken('--font-sans', 'sans-serif'),
      color: textColor,
      fontSize: 12,
    },
    animationDuration: 220,
    tooltip: buildTooltip(),
  }

  if (showLegend) {
    option.legend = {
      type: 'scroll',
      top: 0,
      left: 0,
      itemWidth: 10,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: muted, fontSize: 12 },
    }
  }

  if (model.mode === 'PIE') {
    const values = model.series[0]?.values ?? []
    const dense = model.categories.length > 8
    option.series = [
      {
        type: 'pie',
        name: model.series[0]?.name ?? '合计',
        radius: ['42%', '68%'],
        center: ['50%', '54%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: card, borderWidth: 2 },
        label: {
          show: !dense,
          color: textColor,
          formatter: (params: unknown) => {
            const point = params as { name?: string; value?: number }
            const value = typeof point.value === 'number' ? formatValue(point.value) : '—'
            return `${point.name ?? ''} ${value}`
          },
        },
        labelLine: { show: !dense },
        data: model.categories.map((name, index) => ({ name, value: values[index] ?? undefined })),
      },
    ]
    return option
  }

  if (model.mode === 'HEATMAP') {
    const values = model.heat.cells.map((cell) => cell[2])
    const min = values.length > 0 ? Math.min(...values) : 0
    const max = values.length > 0 ? Math.max(...values) : min + 1
    option.grid = { left: 8, right: 16, top: 12, bottom: 56, containLabel: true }
    option.visualMap = {
      min,
      max: max === min ? min + 1 : max,
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemHeight: 90,
      textStyle: { color: muted, fontSize: 11 },
      formatter: (value: unknown) => (typeof value === 'number' ? formatValue(value) : String(value)),
      inRange: {
        color: [cssToken('--color-primary-light', '#eaf1fe'), cssToken('--color-primary', '#2b6fe5')],
      },
    }
    option.xAxis = {
      type: 'category',
      data: model.heat.x,
      splitArea: { show: true },
      axisLabel: { color: muted, fontSize: 11, hideOverlap: true },
      axisLine: { lineStyle: { color: border } },
      axisTick: { show: false },
    }
    option.yAxis = {
      type: 'category',
      data: model.heat.y,
      splitArea: { show: true },
      axisLabel: { color: muted, fontSize: 11 },
      axisLine: { lineStyle: { color: border } },
      axisTick: { show: false },
    }
    option.series = [{ type: 'heatmap', data: model.heat.cells }]
    return option
  }

  const rotate = model.categories.length > 8 ? 30 : 0
  option.grid = { left: 8, right: 16, top: showLegend ? 34 : 16, bottom: 4, containLabel: true }
  option.xAxis = {
    type: 'category',
    data: model.categories,
    boundaryGap: model.mode === 'BAR',
    axisLabel: { color: muted, fontSize: 11, interval: 0, rotate, hideOverlap: true },
    axisLine: { lineStyle: { color: border } },
    axisTick: { show: false },
  }
  option.yAxis = {
    type: 'value',
    name: model.unit || undefined,
    nameTextStyle: { color: muted, fontSize: 11, padding: [0, 0, 0, 4] },
    axisLabel: {
      color: muted,
      fontSize: 11,
      formatter: (value: number) => formatNumber(value, 0),
    },
    splitLine: { lineStyle: { color: border, type: 'dashed' } },
    axisLine: { show: false },
  }
  option.series =
    model.mode === 'BAR'
      ? model.series.map((series) => ({
          type: 'bar' as const,
          name: series.name,
          data: series.values,
          barMaxWidth: 28,
          stack: props.stacked && model.series.length > 1 ? 'total' : undefined,
          itemStyle: { borderRadius: [3, 3, 0, 0] },
          emphasis: { focus: 'series' as const },
        }))
      : model.series.map((series) => ({
          type: 'line' as const,
          name: series.name,
          data: series.values,
          smooth: false,
          connectNulls: false,
          symbol: 'circle',
          symbolSize: 4,
          lineStyle: { width: 2 },
          emphasis: { focus: 'series' as const },
        }))
  return option
}

function disposeChart(): void {
  if (chart) {
    chart.dispose()
    chart = null
  }
}

function render(): void {
  const el = host.value
  if (!el) return
  if (isEmpty.value) {
    disposeChart()
    return
  }
  if (!chart) chart = init(el, undefined, { renderer: 'canvas' })
  chart.setOption(buildOption(props.model), { notMerge: true })
}

function rerender(): void {
  void nextTick(render)
}

function onWindowResize(): void {
  chart?.resize()
}

onMounted(() => {
  rerender()
  if (typeof ResizeObserver !== 'undefined' && wrap.value) {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(wrap.value)
  } else {
    window.addEventListener('resize', onWindowResize)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  window.removeEventListener('resize', onWindowResize)
  disposeChart()
})

watch(() => props.model, rerender, { deep: true })
watch(() => props.stacked, rerender)
watch(
  () => props.height,
  () => void nextTick(() => chart?.resize()),
)
</script>

<template>
  <div ref="wrap" class="chart" :style="{ height: `${props.height}px` }">
    <EmptyState
      v-if="isEmpty"
      compact
      icon="chart"
      title="暂无可绘制的数据"
      description="当前筛选下没有可展示的数值，调整筛选后重试"
    />
    <div v-else ref="host" class="chart__canvas" role="img" :aria-label="ariaLabel" />
  </div>
</template>

<style scoped>
.chart {
  position: relative;
  width: 100%;
  min-width: 0;
}

.chart__canvas {
  width: 100%;
  height: 100%;
}
</style>
