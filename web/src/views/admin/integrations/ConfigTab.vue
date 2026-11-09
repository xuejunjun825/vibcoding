<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError } from '@/api/client'
import {
  createConfig,
  listConfigs,
  patchConfig,
  testConfig,
  type IntegrationConfigCmd,
  type IntegrationConfigView,
  type IntegrationSystem,
  type IntegrationTestResult,
} from '@/api/integration'
import { usePermission } from '@/composables/usePermission'
import ErrorState from '@/components/ErrorState.vue'
import IntegrationConfigCard from '@/components/integration/IntegrationConfigCard.vue'
import HelpDrawer from '@/components/integration/HelpDrawer.vue'

/**
 * 集成配置 tab（DESIGN I1）。
 *
 * 四槽位永远渲染四张卡（Jenkins/GitLab/Sonar/Nexus）：未配置也要出现，
 * 否则"缺哪一类"只能靠人记 —— 而这正是准入第 7/8/9 项要回答的问题。
 *
 * 状态全部按 system 分槽：一张卡在保存时，别的卡仍可编辑（DESIGN：探针期间不锁整卡）。
 */
const SYSTEMS: IntegrationSystem[] = ['JENKINS', 'GITLAB', 'SONAR', 'NEXUS']

const { can } = usePermission()
const readonly = computed(() => !can('integration:manage'))

const configs = ref<Record<IntegrationSystem, IntegrationConfigView | null>>({
  JENKINS: null,
  GITLAB: null,
  SONAR: null,
  NEXUS: null,
})
const loading = ref(false)
const loadError = ref<unknown>(null)

const saving = reactive<Record<IntegrationSystem, boolean>>({
  JENKINS: false,
  GITLAB: false,
  SONAR: false,
  NEXUS: false,
})
const saveErrors = reactive<Record<IntegrationSystem, ApiError | null>>({
  JENKINS: null,
  GITLAB: null,
  SONAR: null,
  NEXUS: null,
})
const testing = reactive<Record<IntegrationSystem, boolean>>({
  JENKINS: false,
  GITLAB: false,
  SONAR: false,
  NEXUS: false,
})
const testResults = reactive<Record<IntegrationSystem, IntegrationTestResult | null>>({
  JENKINS: null,
  GITLAB: null,
  SONAR: null,
  NEXUS: null,
})

const helpOpen = ref(false)
const helpSystem = ref<IntegrationSystem>('JENKINS')

const counts = computed(() => {
  let enabled = 0
  let disabled = 0
  let unconfigured = 0
  for (const system of SYSTEMS) {
    const config = configs.value[system]
    if (!config) unconfigured += 1
    else if (config.enabled) enabled += 1
    else disabled += 1
  }
  return { enabled, disabled, unconfigured }
})

const forbidden = computed(
  () => loadError.value instanceof ApiError && loadError.value.isForbidden,
)

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('UNKNOWN', error instanceof Error ? error.message : '请求失败')
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const rows = await listConfigs()
    const next: Record<IntegrationSystem, IntegrationConfigView | null> = {
      JENKINS: null,
      GITLAB: null,
      SONAR: null,
      NEXUS: null,
    }
    for (const row of rows) {
      if (row.system in next) next[row.system] = row
    }
    configs.value = next
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

function webhookUrlOf(system: IntegrationSystem): string | null {
  const config = configs.value[system]
  if (!config) return null
  if (system !== 'JENKINS' && system !== 'GITLAB') return null
  return `POST /api/integration/webhooks/${system.toLowerCase()}/${config.id}`
}

/** 整批返回体直接覆盖本地槽位：保存后不再整页 reload（DESIGN §6.1） */
async function save(system: IntegrationSystem, cmd: IntegrationConfigCmd): Promise<void> {
  if (readonly.value || saving[system]) return
  saving[system] = true
  saveErrors[system] = null
  try {
    const existing = configs.value[system]
    const saved = existing ? await patchConfig(existing.id, cmd) : await createConfig(cmd)
    configs.value = { ...configs.value, [saved.system]: saved }
    testResults[system] = null
  } catch (error) {
    saveErrors[system] = toApiError(error)
  } finally {
    saving[system] = false
  }
}

/**
 * 启停：契约的 PATCH 入参没有 `enabled`，但 enabled 是服务端既有列（V8 uk_cfg_active），
 * 只能按真实字段发送；失败即回滚并落卡内常驻条（不假装成功）。
 */
async function toggle(system: IntegrationSystem, enabled: boolean): Promise<void> {
  const existing = configs.value[system]
  if (!existing || readonly.value || saving[system]) return
  const patchBody = { enabled } as unknown as IntegrationConfigCmd
  saving[system] = true
  saveErrors[system] = null
  const before = existing.enabled
  configs.value = {
    ...configs.value,
    [system]: { ...existing, enabled },
  }
  try {
    const saved = await patchConfig(existing.id, patchBody)
    configs.value = { ...configs.value, [system]: saved }
  } catch (error) {
    configs.value = { ...configs.value, [system]: { ...existing, enabled: before } }
    saveErrors[system] = toApiError(error)
  } finally {
    saving[system] = false
  }
}

/** 探测是交互读，只发一次；6s 内没回就地报超时，不占着界面转圈 */
async function test(system: IntegrationSystem): Promise<void> {
  const config = configs.value[system]
  if (!config || testing[system]) return
  testing[system] = true
  testResults[system] = null
  try {
    const result = await Promise.race<IntegrationTestResult>([
      testConfig(config.id),
      new Promise<IntegrationTestResult>((resolve) => {
        window.setTimeout(
          () =>
            resolve({
              ok: false,
              code: 'CLIENT_TIMEOUT',
              message: '6 秒内没有响应：目标不可达或网络被阻断（未改动已保存配置）',
            }),
          6000,
        )
      }),
    ])
    testResults[system] = result
  } catch (error) {
    const apiError = toApiError(error)
    testResults[system] = {
      ok: false,
      code: apiError.code || 'TEST_FAILED',
      message: apiError.message,
    }
  } finally {
    testing[system] = false
  }
}

function openHelp(system: IntegrationSystem): void {
  helpSystem.value = system
  helpOpen.value = true
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="cfg">
    <div class="cfg__strip">
      <p class="cfg__summary">
        四类集成
        <span class="cfg__sep" aria-hidden="true">·</span>
        已启用 <b>{{ counts.enabled }}</b>
        <span class="cfg__sep" aria-hidden="true">·</span>
        已关闭 <b>{{ counts.disabled }}</b>
        <span class="cfg__sep" aria-hidden="true">·</span>
        未配置 <b>{{ counts.unconfigured }}</b>
      </p>
      <span class="cfg__spacer" />
      <p class="cfg__policy">
        凭据只写不读（仅显示末四位）；集成默认关闭，关闭时准入对应项记 CLOSED
      </p>
    </div>

    <p v-if="readonly" class="cfg__readonly" role="status">
      当前账号没有 <code>integration:manage</code> 权限：可以查看配置与测试结果，不能保存或启停。
    </p>

    <ErrorState
      v-if="loadError && !loading"
      :title="forbidden ? '无权查看集成配置' : '集成配置加载失败'"
      :message="forbidden ? '需要 integration:manage 或只读查看权限。' : toApiError(loadError).message"
      :code="toApiError(loadError).code"
      :status="toApiError(loadError).status"
      :retryable="!forbidden"
      @retry="load"
    />

    <div v-else-if="loading" class="cfg__grid" aria-busy="true" aria-label="正在加载集成配置">
      <div v-for="index in 4" :key="index" class="cfg__skeleton">
        <span class="cfg__skeleton-head" />
        <span class="cfg__skeleton-line" />
        <span class="cfg__skeleton-line cfg__skeleton-line--short" />
      </div>
    </div>

    <div v-else class="cfg__grid">
      <IntegrationConfigCard
        v-for="system in SYSTEMS"
        :key="system"
        :type="system"
        :config="configs[system]"
        :readonly="readonly"
        :saving="saving[system]"
        :save-error="saveErrors[system]"
        :testing="testing[system]"
        :test-result="testResults[system]"
        :webhook-url="webhookUrlOf(system)"
        @save="(cmd) => save(system, cmd)"
        @toggle="(enabled) => toggle(system, enabled)"
        @test="test(system)"
        @open-help="openHelp(system)"
      />
    </div>

    <HelpDrawer
      v-model="helpOpen"
      :system="helpSystem"
      :configs="Object.values(configs).filter((item): item is IntegrationConfigView => item !== null)"
    />
  </div>
</template>

<style scoped>
.cfg {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cfg__strip {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--h-count-strip);
  padding: 0 2px;
}

.cfg__summary {
  margin: 0;
  color: var(--text-body);
  font-size: 13px;
}

.cfg__summary b {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.cfg__sep {
  padding: 0 2px;
  color: var(--text-weak);
}

.cfg__spacer {
  flex: 1;
}

.cfg__policy {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 12px;
  text-align: right;
}

.cfg__readonly {
  margin: 0;
  padding: 8px 12px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}

.cfg__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, var(--w-intg-card)));
  gap: 12px;
  align-items: stretch;
}

.cfg__skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 140px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}

.cfg__skeleton-head,
.cfg__skeleton-line {
  display: block;
  border-radius: var(--r-cell);
  background: var(--divider);
  animation: cfg-pulse 1.1s ease-in-out infinite;
}

.cfg__skeleton-head {
  width: 40%;
  height: 14px;
}

.cfg__skeleton-line {
  width: 100%;
  height: 10px;
}

.cfg__skeleton-line--short {
  width: 60%;
}

@keyframes cfg-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

@media (max-width: 960px) {
  .cfg__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .cfg__policy {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cfg__skeleton-head,
  .cfg__skeleton-line {
    animation: none;
  }
}
</style>
