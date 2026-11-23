<script lang="ts">
/**
 * 执行结论五态 + 原因码人话映射 —— **全站唯一一份**（TECHNICAL §9.3）。
 *
 * 契约要求（DESIGN §4.3）：界面不出现裸枚举码；码只允许进 `title` 与展开区 mono 行。
 * 未知码一律原样渲染（不吞），否则服务端新增一个原因，前端会显示成空白。
 */
export const STATUS_LABELS: Record<string, { label: string; tone: string }> = {
  RUNNING: { label: '运行中', tone: 'info' },
  SUCCESS: { label: '成功', tone: 'success' },
  PARTIAL: { label: '部分成功', tone: 'warning' },
  FAILED: { label: '失败', tone: 'danger' },
  SKIPPED: { label: '已跳过', tone: 'muted' },
}

export const SKIP_LABELS: Record<string, string> = {
  CONDITION_NOT_MET: '条件未命中',
  RATE_LIMITED: '超过每分钟执行上限',
  SCOPE_LOST: '需求已移出规则作用域',
  NO_RECIPIENT: '没有能收到通知的人',
  TOO_MANY_RECIPIENTS: '收件人超过 50 人，未发送',
  TARGET_INVALID: '目标用户不可指派（不在作用域/已停用）',
  SCHEMA_INVALID: '规则条件或动作已无法解析（schema 非法）',
  SCHEDULE_TRUNCATED: '定时扫描达到单日 200 条上限',
  // 动作失败的原因码（r39 Q-3）：执行器把异常归一成这几种，日志里不再只有裸 FAILED。
  SECRET_MISSING: '集成配置缺少签名密钥',
  SSRF_BLOCKED: '外呼地址不在允许范围，已拦截',
  TIMEOUT: '对端响应超时',
  UNREACHABLE: '对端不可达',
}

/** 动作结果的正常码（不是失败，别吓用户） */
export const MESSAGE_LABELS: Record<string, string> = {
  noop: '已是负责人，无变化',
  exists: '标签已存在（幂等）',
}

export function verdictLabel(status?: string | null): string {
  return STATUS_LABELS[String(status ?? '').toUpperCase()]?.label ?? (status ? String(status) : '—')
}

export function skipLabel(code?: string | null): string {
  if (!code) return ''
  return SKIP_LABELS[code] ?? MESSAGE_LABELS[code] ?? code
}
</script>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 执行结果印章（DESIGN §4.3）：20 高、6px 方角 + 12px 人话原因。
 * 状态色只表达 tone，**文字本身**必须能读出来（不靠颜色区分成败）。
 */
const props = withDefaults(
  defineProps<{
    status?: string | null
    /** run 级跳过原因（SKIPPED 时才有） */
    skipReason?: string | null
    /** 动作级结果码（展开区小表用） */
    message?: string | null
    size?: 'stamp' | 'sm'
    showReason?: boolean
  }>(),
  { status: '', skipReason: null, message: null, size: 'stamp', showReason: true },
)

const meta = computed(
  () => STATUS_LABELS[String(props.status ?? '').toUpperCase()] ?? { label: verdictLabel(props.status), tone: 'muted' },
)

const code = computed(() => props.skipReason || props.message || '')

const reasonText = computed(() => {
  if (code.value) return skipLabel(code.value)
  if (String(props.status ?? '').toUpperCase() === 'RUNNING') return '执行中…'
  return ''
})
</script>

<template>
  <span class="run-verdict" :class="[`run-verdict--${meta.tone}`, { 'run-verdict--sm': size === 'sm' }]">
    <span class="run-verdict__stamp">{{ meta.label }}</span>
    <span v-if="showReason && reasonText" class="run-verdict__reason" :title="code || reasonText">{{
      reasonText
    }}</span>
  </span>
</template>
