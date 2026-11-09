<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/client'
import {
  listAdmissionItems,
  toggleAdmissionItems,
  type AdmissionItemView,
} from '@/api/integration'
import { usePermission } from '@/composables/usePermission'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import AdmissionItemTable from '@/components/integration/AdmissionItemTable.vue'

/**
 * 准入项 tab（DESIGN I2 / §6.3）。
 *
 * 这张表的唯一作用是让人看清"发布闸门检查什么、哪几项不能关"：
 * 必开项由服务端 `locked` 决定（代码常量锁死，DB 脏数据也改不了它），
 * 可关项关闭前把影响逐条列出 —— 关闭 ≠ 跳过，分母恒为 9。
 *
 * 切换是 PUT 整批语义，但每次只提交一行；返回的是完整 9 项，直接整表替换，
 * 不做本地拼接（服务端顺序与文案才是唯一真相，前端不排序）。
 */
const { can } = usePermission()
const readonly = computed(() => !can('integration:manage'))

const items = ref<AdmissionItemView[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
const saving = ref<string | null>(null)
const errors = ref<Record<string, string>>({})

const counts = computed(() => {
  let locked = 0
  let enabled = 0
  let closed = 0
  for (const item of items.value) {
    if (item.locked) locked += 1
    if (item.enabled) enabled += 1
    else closed += 1
  }
  return { locked, enabled, closed }
})

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('UNKNOWN', error instanceof Error ? error.message : '请求失败')
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    items.value = await listAdmissionItems()
    errors.value = {}
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

/** 乐观切换，失败回滚 —— 开关不能停在用户以为成功的位置 */
async function toggle(code: string, enabled: boolean): Promise<void> {
  if (readonly.value || saving.value) return
  const before = items.value
  const target = before.find((item) => item.code === code)
  if (!target || target.locked) return

  saving.value = code
  errors.value = { ...errors.value, [code]: '' }
  items.value = before.map((item) => (item.code === code ? { ...item, enabled } : item))
  try {
    items.value = await toggleAdmissionItems([{ code, enabled }])
  } catch (error) {
    items.value = before
    const apiError = toApiError(error)
    errors.value = {
      ...errors.value,
      [code]:
        apiError.status === 409
          ? `${apiError.code || 'ITEM_LOCKED'}：${apiError.message || '该项被服务端锁定，不能关闭'}`
          : `${apiError.code || 'TOGGLE_FAILED'}：${apiError.message}`,
    }
  } finally {
    saving.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="admtab">
    <div class="admtab__strip">
      <p class="admtab__summary">
        共 <b>{{ items.length || 9 }}</b> 项
        <span class="admtab__sep" aria-hidden="true">·</span>
        已锁定 <b>{{ counts.locked }}</b>
        <span class="admtab__sep" aria-hidden="true">·</span>
        已开启 <b>{{ counts.enabled }}</b>
        <span class="admtab__sep" aria-hidden="true">·</span>
        已关闭 <b>{{ counts.closed }}</b>
      </p>
      <span class="admtab__spacer" />
      <p class="admtab__note">关闭 ≠ 跳过：关闭项不计 PASS/FAIL/SKIP，分母恒为 9</p>
    </div>

    <p v-if="readonly" class="admtab__readonly" role="status">
      当前账号没有 <code>integration:manage</code> 权限：开关只读，仍可查看每项含义。
    </p>

    <ErrorState
      v-if="loadError && !loading"
      title="准入项加载失败"
      :message="toApiError(loadError).message"
      :code="toApiError(loadError).code"
      :status="toApiError(loadError).status"
      @retry="load"
    />

    <div v-else-if="loading" class="admtab__skeleton" aria-busy="true" aria-label="正在加载准入项">
      <span v-for="index in 9" :key="index" class="admtab__skeleton-row" />
    </div>

    <EmptyState
      v-else-if="!items.length"
      title="准入项没有返回任何内容"
      description="服务端应固定返回 9 项（6 项发布门禁 + 构建 / Sonar / 制品）。请检查后端版本或稍后重试。"
      action-text="重新加载"
      @action="load"
    />

    <AdmissionItemTable
      v-else
      :items="items"
      :readonly="readonly"
      :saving="saving"
      :errors="errors"
      @toggle="toggle"
    />

    <p class="admtab__legend">
      必开项由代码常量锁定（AdmissionItemPolicy.LOCKED），接口更新不会改变它；可关项关闭后该项记
      <code>CLOSED(ITEM_OFF)</code>，集成未配置时对应项记 <code>CLOSED(INTEGRATION_DISABLED)</code>，两者都不阻断提交。
    </p>
  </div>
</template>

<style scoped>
.admtab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.admtab__strip {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--h-count-strip);
  padding: 0 2px;
}

.admtab__summary {
  margin: 0;
  color: var(--text-body);
  font-size: 13px;
}

.admtab__summary b {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.admtab__sep {
  padding: 0 2px;
  color: var(--text-weak);
}

.admtab__spacer {
  flex: 1;
}

.admtab__note,
.admtab__legend {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.admtab__legend {
  line-height: 1.6;
}

.admtab__legend code {
  padding: 0 4px;
  border-radius: var(--r-cell);
  background: var(--intg-code-bg);
  font-family: var(--font-mono);
  font-size: 11px;
}

.admtab__readonly {
  margin: 0;
  padding: 8px 12px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}

.admtab__skeleton {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.admtab__skeleton-row {
  height: var(--h-admission-row);
  border-bottom: 1px solid var(--divider);
  background: linear-gradient(90deg, var(--divider), var(--bg-page), var(--divider));
  background-size: 200% 100%;
  animation: admtab-shimmer 1.2s linear infinite;
}

.admtab__skeleton-row:last-child {
  border-bottom: 0;
}

@keyframes admtab-shimmer {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .admtab__skeleton-row {
    animation: none;
  }
}
</style>
