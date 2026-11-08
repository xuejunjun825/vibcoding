import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

/** 统一的时间/数字格式：表格、时间线、指标卡共用一套，避免每页各写一次 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : value
}

export function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD') : value
}

/** 时间线用短格式：今天显示 HH:mm，其余显示 MM-DD HH:mm */
export function formatShortTime(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  if (!date.isValid()) return value
  return date.isSame(dayjs(), 'day') ? date.format('HH:mm') : date.format('MM-DD HH:mm')
}

export function fromNow(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.fromNow() : value
}

export function formatNumber(value?: number | string | null, digits = 0): string {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return numeric.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

/** 0 分母显示 —（与 MetricFormulas 的零分母约定一致，绝不显示 NaN%） */
export function formatPercent(value?: number | string | null, digits = 1): string {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric)) return '—'
  const percent = numeric <= 1 ? numeric * 100 : numeric
  return `${percent.toFixed(digits)}%`
}

export function formatDays(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  return `${value} 人天`
}

export function formatDuration(minutes?: number | string | null): string {
  if (minutes === null || minutes === undefined || minutes === '') return '—'
  const numeric = Number(minutes)
  if (!Number.isFinite(numeric)) return String(minutes)
  if (numeric < 60) return `${Math.round(numeric)} 分钟`
  const hours = numeric / 60
  if (hours < 24) return `${hours.toFixed(1)} 小时`
  return `${(hours / 24).toFixed(1)} 天`
}
