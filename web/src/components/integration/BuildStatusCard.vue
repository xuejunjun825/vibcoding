<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  isActiveBuild,
  refreshBuild,
  triggerBuild,
  type BuildView,
} from '@/api/integration'
import { useBuildPolling } from '@/composables/useBuildPolling'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime, formatShortTime } from '@/utils/format'

/**
 * 构建卡（DESIGN I3 / §6.4，AC-3）。
 *
 * - 轮询交给 `useBuildPolling`（5s、仅在活动构建时开表、页面不可见暂停）；
 * - 状态文案只走 `StatusPill kind="build"`，前端不自造第三套状态字符串；
 * - console_url 由服务端做过同源校验，卡上只做展示，不做“点开一定是 Jenkins”的假设；
 * - 触发是 202 + 后台提交：卡上立刻显示 QUEUED，不在这里等 Jenkins 的 10s。
 */
const props = withDefaults(
  defineProps<{
    orderId: number
    /** deploy:create / deploy:execute 之外的触发权限 */
    canTrigger?: boolean
    triggerDisabledReason?: string
    /** 卡片内锚点，追溯矩阵跳转用 */
    anchorId?: string
  }>(),
  {
    canTrigger: false,
    triggerDisabledReason: '',
    anchorId: 'build-card',
  },
)

const emit = defineEmits<{
  /** 活动构建进入终态：父页面据此重算准入第 7 项 */
  (event: 'settled'): void
}>()

const { builds, loading, error, polling, activeBuild, refresh } = useBuildPolling(props.orderId, {
  intervalMs: 5000,
  limit: 5,
})

const triggerOpen = ref(false)
const branch = ref('')
const triggering = ref(false)
const actionError = ref('')
const conflictBuildId = ref<number | null>(null)

const latest = computed<BuildView | null>(() => builds.value[0] ?? null)

function isTerminal(status: string): boolean {
  return status === 'SUCCESS' || status === 'FAILURE' || status === 'ABORTED'
}

/** 2h 超时：服务端已把 timedOut 置位；未置位但已过 timeoutAt 时卡上提前提示 */
function timeoutSoon(build: BuildView): boolean {
  if (build.timedOut) return true
  if (!isActiveBuild(build.status) || !build.timeoutAt) return false
  const at = new Date(build.timeoutAt).getTime()
  return Number.isFinite(at) && at <= Date.now()
}

function buildDuration(build: BuildView): string {
  if (build.durationMs != null && build.durationMs > 0) return formatMs(build.durationMs)
  if (!build.startedAt) return '—'
  const start = new Date(build.startedAt).getTime()
  const end = build.finishedAt ? new Date(build.finishedAt).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—'
  return formatMs(end - start)
}

function formatMs(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60)
    return `${hours}h ${minutes % 60}m`
  }
  return minutes > 0 ? `${minutes}m ${String(seconds).padStart(2, '0')}s` : `${seconds}s`
}

function buildLabel(build: BuildView): string {
  if (build.buildNumber != null) return `#${build.buildNumber}`
  return build.queueId ? `排队 ${build.queueId}` : `#${build.id}`
}

/** console 外链只在终态展示；URL 由服务端同源校验，非法时服务端已置 NULL */
function consoleUrl(build: BuildView): string {
  return build.consoleUrl && isTerminal(build.status) ? build.consoleUrl : ''
}

function openTrigger(): void {
  actionError.value = ''
  conflictBuildId.value = null
  branch.value = ''
  triggerOpen.value = true
}

async function submitTrigger(): Promise<void> {
  if (triggering.value) return
  triggering.value = true
  actionError.value = ''
  conflictBuildId.value = null
  try {
    const result = await triggerBuild(props.orderId, {
      branch: branch.value.trim() || undefined,
    })
    ElMessage.success(`构建已排队（${result.correlationId}），状态由轮询与回调收敛`)
    triggerOpen.value = false
    await refresh()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    const code = String(apiError?.code ?? '')
    if (code === 'BUILD_IN_PROGRESS') {
      const detail = apiError?.detail as { buildId?: number } | undefined
      conflictBuildId.value = detail?.buildId ?? null
      triggerOpen.value = false
    } else if (code === 'INTEGRATION_DISABLED') {
      actionError.value = 'Jenkins 集成未启用：请到「管理端 › 集成配置」启用并保存后重试。'
      triggerOpen.value = false
    } else {
      actionError.value = apiError?.message || '触发构建失败，请稍后重试'
    }
  } finally {
    triggering.value = false
  }
}

async function onRefresh(build: BuildView): Promise<void> {
  if (!isActiveBuild(build.status)) return
  try {
    await refreshBuild(build.id)
    await refresh()
  } catch (err) {
    actionError.value = err instanceof ApiError ? err.message : '刷新构建失败'
  }
}

/** 轮询发现活动构建转终态：只在状态真的变化时通知一次，避免每 tick 都重算准入 */
const lastActiveId = ref<number | null>(null)
watch(activeBuild, (current) => {
  if (current) {
    lastActiveId.value = current.id
    return
  }
  if (lastActiveId.value != null) {
    lastActiveId.value = null
    emit('settled')
  }
})
</script>

<template>
  <section :id="props.anchorId" class="build-card">
    <header class="build-card__head">
      <h2 class="build-card__title">构建</h2>
      <span v-if="polling" class="build-card__polling" title="存在活动构建时每 5s 刷新一次">
        <i class="build-card__pulse" aria-hidden="true" />自动刷新
      </span>
      <button type="button" class="build-card__link" :disabled="loading" @click="refresh">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </header>

    <div v-if="error && builds.length" class="build-card__degraded" role="status">
      构建状态刷新失败，显示的是上一次结果
    </div>
    <div v-if="actionError" class="build-card__banner" role="alert">
      <span>{{ actionError }}</span>
      <button type="button" class="build-card__link" @click="actionError = ''">关闭</button>
    </div>
    <div v-if="conflictBuildId" class="build-card__banner build-card__banner--warn" role="status">
      已有构建在进行中（#{{ conflictBuildId }}），等它结束后再触发；同一发布单不允许双活动构建。
    </div>

    <div v-if="activeBuild || latest" class="build-card__current">
      <div class="build-card__current-head">
        <span class="build-card__number mono">{{ buildLabel(activeBuild ?? latest!) }}</span>
        <StatusPill kind="build" :value="(activeBuild ?? latest!).status" size="sm" />
        <span class="build-card__duration num">{{ buildDuration(activeBuild ?? latest!) }}</span>
      </div>
      <p class="build-card__job">
        <span class="mono">{{ (activeBuild ?? latest!).jobName || '未命名 job' }}</span>
        <span v-if="(activeBuild ?? latest!).triggeredBy" class="build-card__muted">
          · {{ (activeBuild ?? latest!).triggeredBy }}
        </span>
      </p>
      <ul v-if="(activeBuild ?? latest!).stages?.length" class="build-card__stages">
        <li
          v-for="stage in (activeBuild ?? latest!).stages"
          :key="stage.name"
          class="build-card__stage"
          :class="`build-card__stage--${String(stage.status).toLowerCase()}`"
          :title="`${stage.name} · ${stage.status}${stage.durationMs != null ? ` · ${formatMs(stage.durationMs)}` : ''}`"
        >
          <i aria-hidden="true" />
          {{ stage.name }}
        </li>
      </ul>
      <div v-if="timeoutSoon(activeBuild ?? latest!)" class="build-card__timeout" role="status">
        构建已超过 2h 未收敛，按超时处理并自动转缺陷。
      </div>
      <a
        v-if="consoleUrl(activeBuild ?? latest!)"
        class="build-card__console"
        :href="consoleUrl(activeBuild ?? latest!)"
        target="_blank"
        rel="noopener noreferrer"
      >
        查看构建日志 ↗
      </a>
      <span v-else-if="isTerminal((activeBuild ?? latest!).status)" class="build-card__console build-card__console--muted">
        构建日志不可用（外链未通过同源校验）
      </span>
    </div>

    <p v-else-if="loading" class="build-card__empty">正在读取构建记录…</p>
    <div v-else class="build-card__empty">
      <p>尚未触发构建。触发后立即返回 202，状态由轮询与回调收敛。</p>
    </div>

    <ul v-if="builds.length > 1" class="build-card__recent">
      <li class="build-card__recent-head">
        <span>最近构建</span>
        <span class="build-card__muted">最近 {{ builds.length }} 条</span>
      </li>
      <li v-for="build in builds" :key="build.id" class="build-card__recent-row">
        <span class="build-card__recent-no mono">{{ buildLabel(build) }}</span>
        <StatusPill kind="build" :value="build.status" size="sm" />
        <span class="build-card__recent-meta">
          <span class="num">{{ buildDuration(build) }}</span>
          <span class="build-card__muted">{{ formatShortTime(build.startedAt || build.queuedAt) }}</span>
        </span>
        <button
          v-if="isActiveBuild(build.status)"
          type="button"
          class="build-card__link"
          @click="onRefresh(build)"
        >
          刷新
        </button>
        <span v-else-if="build.status === 'FAILURE'" class="build-card__auto" :title="`job#${build.buildNumber ?? build.id} 已自动转缺陷`">
          已转缺陷
        </span>
        <span v-else class="build-card__auto">{{ formatDateTime(build.finishedAt) }}</span>
      </li>
    </ul>

    <footer class="build-card__foot">
      <template v-if="!triggerOpen">
        <span v-if="props.triggerDisabledReason" class="build-card__muted">{{ props.triggerDisabledReason }}</span>
        <el-button
          type="primary"
          size="small"
          :disabled="!props.canTrigger || Boolean(props.triggerDisabledReason)"
          :title="props.triggerDisabledReason || (props.canTrigger ? '' : '需要权限：deploy:create')"
          @click="openTrigger"
        >
          触发构建
        </el-button>
      </template>
      <template v-else>
        <el-input
          v-model="branch"
          size="small"
          placeholder="分支（留空用默认）"
          class="build-card__branch"
          @keyup.enter="submitTrigger"
        />
        <div class="build-card__foot-actions">
          <el-button size="small" text @click="triggerOpen = false">取消</el-button>
          <el-button type="primary" size="small" :loading="triggering" @click="submitTrigger">提交</el-button>
        </div>
      </template>
    </footer>
  </section>
</template>

<style scoped>
.build-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.build-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 12px;
  border-bottom: 1px solid var(--divider);
}
.build-card__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.build-card__polling {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-weak);
}
.build-card__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-success);
  animation: build-pulse 1.6s var(--ease) infinite;
}
.build-card__link {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--color-primary, #2f6fed);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--r-cell);
}
.build-card__recent-row .build-card__link {
  margin-left: 0;
}
.build-card__link:hover {
  background: var(--bg-hover);
}
.build-card__link:disabled {
  color: var(--text-weak);
  cursor: default;
}
.build-card__link:focus-visible {
  outline: 2px solid var(--focus-ring, #2f6fed);
  outline-offset: 1px;
}
.build-card__degraded {
  padding: 6px 12px;
  background: var(--color-info-bg);
  color: var(--text-muted-aa);
  font-size: 11px;
}
.build-card__banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid #f7d4d5;
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 18px;
}
.build-card__banner--warn {
  border-bottom-color: #f0dcae;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.build-card__banner span {
  flex: 1;
}
.build-card__current {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-bottom: 1px solid var(--divider);
}
.build-card__current-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.build-card__number {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-title);
  line-height: 24px;
}
.build-card__duration {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.build-card__job {
  margin: 0;
  font-size: 12px;
  color: var(--text-body);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.build-card__muted {
  color: var(--text-weak);
  font-size: 11px;
}
.build-card__stages {
  display: flex;
  gap: 4px;
  margin: 2px 0 0;
  padding: 0;
  list-style: none;
  flex-wrap: wrap;
}
.build-card__stage {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  font-size: 11px;
  color: var(--text-muted-aa);
  background: var(--bg-hover);
  white-space: nowrap;
}
.build-card__stage i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-weak);
}
.build-card__stage--success i {
  background: var(--color-success);
}
.build-card__stage--failure i {
  background: var(--color-danger);
}
.build-card__stage--running i {
  background: var(--intg-running, var(--color-primary, #2f6fed));
}
.build-card__timeout {
  padding: 6px 8px;
  border-left: 2px solid var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  line-height: 16px;
  border-radius: 2px;
}
.build-card__console {
  font-size: 12px;
  color: var(--color-primary, #2f6fed);
  text-decoration: none;
  width: fit-content;
}
.build-card__console:hover {
  text-decoration: underline;
}
.build-card__console--muted {
  color: var(--text-weak);
  font-size: 11px;
}
.build-card__console--muted:hover {
  text-decoration: none;
}
.build-card__empty {
  padding: 16px 12px;
  font-size: 12px;
  color: var(--text-weak);
  line-height: 18px;
}
.build-card__empty p {
  margin: 0;
}
.build-card__recent {
  margin: 0;
  padding: 0;
  list-style: none;
}
.build-card__recent-head {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px 4px;
  font-size: 11px;
  color: var(--text-weak);
}
.build-card__recent-row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: var(--h-build-row, 44px);
  padding: 0 12px;
  border-top: 1px solid var(--divider);
  font-size: 12px;
}
.build-card__recent-no {
  font-size: 12px;
  color: var(--text-title);
  min-width: 44px;
}
.build-card__recent-meta {
  margin-left: auto;
  display: inline-flex;
  gap: 6px;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.build-card__auto {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-weak);
}
.build-card__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--divider);
  background: var(--bg-table-head);
  flex-wrap: wrap;
}
.build-card__foot > :first-child {
  margin-right: auto;
}
.build-card__branch {
  width: 150px;
}
.build-card__foot-actions {
  display: flex;
  gap: 4px;
}
@keyframes build-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}
</style>
