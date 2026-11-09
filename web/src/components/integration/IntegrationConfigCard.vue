<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import CredentialField from '@/components/integration/CredentialField.vue'
import ConnectionTestBar from '@/components/integration/ConnectionTestBar.vue'
import type { ApiError } from '@/api/client'
import type {
  IntegrationAuthType,
  IntegrationConfigCmd,
  IntegrationConfigView,
  IntegrationExtra,
  IntegrationSystem,
  IntegrationTestResult,
} from '@/api/integration'

/**
 * 集成配置卡（DESIGN I1 / §6.1）。
 *
 * 一张卡 = 一个系统槽位。三种形态共用同一份模板：
 * - 未配置：虚线边框 + 灰底，字段可直接填，保存=POST；
 * - 已关闭：黄条说明"业务入口禁用 + 准入项记 CLOSED"，开关可重新启用；
 * - 已启用：绿 pill + 开关，字段改动后 [保存] 才亮（dirty 门）。
 *
 * 凭据永远交给 `CredentialField`：卡片自身不碰明文，只在提交时把"用户新输入的串"塞进 cmd；
 * 空串 = 不发送 = 服务端保持原值（AC-1.2 的"只写不读"在这里落地）。
 */
const props = withDefaults(
  defineProps<{
    type: IntegrationSystem
    config: IntegrationConfigView | null
    readonly?: boolean
    saving?: boolean
    /** 上一次保存/启停失败（常驻条，不用 toast） */
    saveError?: ApiError | null
    testing?: boolean
    testResult?: IntegrationTestResult | null
    /** 回调地址（仅 Jenkins/GitLab，由父层按 config.id 拼） */
    webhookUrl?: string | null
  }>(),
  {
    readonly: false,
    saving: false,
    saveError: null,
    testing: false,
    testResult: null,
    webhookUrl: null,
  },
)

const emit = defineEmits<{
  (event: 'save', cmd: IntegrationConfigCmd): void
  (event: 'toggle', enabled: boolean): void
  (event: 'test'): void
  (event: 'open-help'): void
}>()

interface SystemMeta {
  label: string
  letter: string
  purpose: string
  authType: IntegrationAuthType
  defaultName: string
  credentialLabel: string
  credentialKind: string
  username: boolean
  webhook: boolean
}

const META: Record<IntegrationSystem, SystemMeta> = {
  JENKINS: {
    label: 'Jenkins',
    letter: 'J',
    purpose: '构建触发 · 状态回报 · 失败转缺陷',
    authType: 'BASIC',
    defaultName: 'Jenkins CI',
    credentialLabel: 'API 令牌 / 密码',
    credentialKind: '密码',
    username: true,
    webhook: true,
  },
  GITLAB: {
    label: 'GitLab',
    letter: 'G',
    purpose: 'MR 状态同步 · 代码行数 · 合并推进',
    authType: 'PRIVATE_TOKEN',
    defaultName: 'GitLab',
    credentialLabel: 'Private Token',
    credentialKind: 'Private Token',
    username: false,
    webhook: true,
  },
  SONAR: {
    label: 'Sonar',
    letter: 'S',
    purpose: '质量门快照 · 五度量阈值',
    authType: 'TOKEN',
    defaultName: 'SonarQube',
    credentialLabel: '分析 Token',
    credentialKind: 'Token',
    username: false,
    webhook: false,
  },
  NEXUS: {
    label: 'Nexus',
    letter: 'N',
    purpose: '制品检索 · 登记 · sha256 校验',
    authType: 'BASIC',
    defaultName: 'Nexus 制品库',
    credentialLabel: '访问密码',
    credentialKind: '密码',
    username: true,
    webhook: false,
  },
}

const meta = computed(() => META[props.type])

interface Draft {
  baseUrl: string
  username: string
  timeoutMs: number
  jobName: string
  projectPath: string
  projectKey: string
  repository: string
  allowedHosts: string
  credential: string
  secret: string
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function fromConfig(config: IntegrationConfigView | null): Draft {
  const extra = (config?.extra ?? {}) as IntegrationExtra
  return {
    baseUrl: config?.baseUrl ?? '',
    username: config?.username ?? '',
    timeoutMs: config?.timeoutMs ?? 10000,
    jobName: text(extra.jobName),
    projectPath: text(extra.projectPath),
    projectKey: text(extra.projectKey),
    repository: text(extra.repository),
    allowedHosts: Array.isArray(extra.allowedHosts) ? extra.allowedHosts.join('\n') : '',
    credential: '',
    secret: '',
  }
}

// 用 ref<Draft> 而不是 reactive：模板里 v-model="draft.baseUrl" 需要顶层的 ref 自动解包，
// 与项目的既有写法一致（reactive 成员在 vue-tsc 的 v-model 推断里会被当成 ref 再取 .value）。
const draft = ref<Draft>(fromConfig(props.config))
const baseline = ref<string>(JSON.stringify(fromConfig(props.config)))
const touched = ref(false)
const pendingClear = ref(false)

const configName = computed(() => props.config?.name ?? meta.value.defaultName)
const configured = computed(() => props.config !== null)
const enabled = computed(() => props.config?.enabled ?? false)

const dirty = computed(() => JSON.stringify(draft.value) !== baseline.value)

/** 外部快照变化（保存成功、启停、测试回写）→ 重设基线；无未保存编辑时同步草稿 */
watch(
  () => props.config,
  (config) => {
    const next = fromConfig(config)
    baseline.value = JSON.stringify(next)
    if (!dirty.value) draft.value = { ...next }
    if (!config?.hasCredential) pendingClear.value = false
  },
  { deep: true },
)

const baseUrlError = computed(() => {
  const raw = draft.value.baseUrl.trim()
  if (!raw) return '必填：填写系统根地址'
  if (raw.length > 500) return '不能超过 500 个字符'
  if (!/^https?:\/\//i.test(raw)) return '必须以 http:// 或 https:// 开头'
  if (/^https?:\/\/[^/]*@/i.test(raw)) return '不要包含用户名/口令（凭据请填在下方字段）'
  return ''
})

const extraError = computed(() => {
  if (props.type === 'JENKINS' && !draft.value.jobName.trim()) return 'Jenkins 需要 jobName（构建任务名）'
  if (props.type === 'GITLAB' && !draft.value.projectPath.trim()) return 'GitLab 需要 projectPath（如 group/sub/project）'
  if (props.type === 'NEXUS' && !draft.value.repository.trim()) return 'Nexus 需要 repository（检索默认仓库）'
  return ''
})

const showBaseUrlError = computed(() => (touched.value || dirty.value) && !!baseUrlError.value)

const canSave = computed(
  () =>
    !props.readonly &&
    !props.saving &&
    (dirty.value || pendingClear.value) &&
    !baseUrlError.value &&
    !extraError.value,
)

const saveHint = computed(() => {
  if (props.readonly) return '没有 integration:manage 权限，只读'
  if (props.saving) return '正在保存'
  if (!dirty.value && !pendingClear.value) return '没有改动'
  if (baseUrlError.value) return baseUrlError.value
  if (extraError.value) return extraError.value
  return ''
})

const dirtyCount = computed(() => {
  const before = JSON.parse(baseline.value) as Draft
  return (Object.keys(draft.value) as Array<keyof Draft>).filter(
    (key) => draft.value[key] !== before[key],
  ).length
})

function buildExtra(): IntegrationExtra {
  // 以服务端返回的 extra 为底：不认识的键原样带回，避免一次保存把别的字段抹掉
  const extra: IntegrationExtra = { ...((props.config?.extra ?? {}) as IntegrationExtra) }
  delete extra.jobName
  delete extra.projectPath
  delete extra.projectKey
  delete extra.repository
  delete extra.allowedHosts

  if (props.type === 'JENKINS') extra.jobName = draft.value.jobName.trim()
  if (props.type === 'GITLAB') extra.projectPath = draft.value.projectPath.trim()
  if (props.type === 'SONAR' && draft.value.projectKey.trim())
    extra.projectKey = draft.value.projectKey.trim()
  if (props.type === 'NEXUS') {
    extra.repository = draft.value.repository.trim()
    const hosts = draft.value.allowedHosts
      .split(/[\s,]+/)
      .map((item) => item.trim())
      .filter(Boolean)
    if (hosts.length) extra.allowedHosts = hosts
  }
  return extra
}

function submit(): void {
  touched.value = true
  if (!canSave.value) return

  const cmd: IntegrationConfigCmd = {
    system: props.type,
    name: configName.value,
    baseUrl: draft.value.baseUrl.trim(),
    authType: props.config?.authType ?? meta.value.authType,
    username: meta.value.username ? draft.value.username.trim() || null : undefined,
    extra: buildExtra(),
    timeoutMs: draft.value.timeoutMs,
  }
  // 空串 = 保持原值：这是"凭据只写不读"的写侧语义，不能靠服务端猜
  if (draft.value.credential) cmd.credential = draft.value.credential
  if (meta.value.webhook && draft.value.secret) cmd.secret = draft.value.secret
  if (pendingClear.value && props.config) {
    cmd.clearCredential = true
    cmd.confirmName = props.config.name
  }
  emit('save', cmd)
}

/** 清除凭据：不需要用户再点一次保存 —— 二代确认已经表达了意图，立即 PATCH */
function onCredentialClear(): void {
  pendingClear.value = true
  submit()
}

function onToggle(next: string | number | boolean): void {
  if (props.readonly || props.saving) return
  emit('toggle', Boolean(next))
}

function onTest(): void {
  if (!props.config || props.testing) return
  emit('test')
}

function fallbackCopy(value: string): boolean {
  try {
    const area = document.createElement('textarea')
    area.value = value
    area.setAttribute('readonly', 'readonly')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    area.remove()
    return ok
  } catch {
    return false
  }
}

async function copyWebhook(): Promise<void> {
  if (!props.webhookUrl) return
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(props.webhookUrl)
    else if (!fallbackCopy(props.webhookUrl)) throw new Error('copy unavailable')
    ElMessage.success('已复制回调地址')
  } catch {
    ElMessage.warning('浏览器不允许读取剪贴板，请手动选择复制')
  }
}
</script>

<template>
  <section
    class="icard"
    :class="{
      'icard--unconfigured': !configured,
      'icard--disabled': configured && !enabled,
    }"
    :aria-label="`${meta.label} 集成配置`"
  >
    <header class="icard__head">
      <span class="icard__icon" aria-hidden="true">{{ meta.letter }}</span>
      <div class="icard__title">
        <p class="icard__name">{{ meta.label }}</p>
        <p class="icard__purpose">{{ meta.purpose }}</p>
      </div>
      <span class="icard__spacer" />
      <StatusPill
        kind="integration"
        :value="configured ? (enabled ? 'ENABLED' : 'DISABLED') : 'UNCONFIGURED'"
        size="sm"
      />
      <el-switch
        :model-value="enabled"
        :disabled="!configured || readonly || saving"
        :loading="saving"
        :aria-label="`${enabled ? '关闭' : '启用'} ${meta.label}`"
        :aria-describedby="!configured ? `icard-switch-${type}` : undefined"
        @change="onToggle"
      />
    </header>
    <p v-if="!configured" :id="`icard-switch-${type}`" class="icard__sr">
      未配置：先保存 base_url 与凭据后才能启用
    </p>

    <!-- 已关闭：黄条说明影响（关闭 ≠ 跳过） -->
    <div v-if="configured && !enabled" class="icard__note icard__note--warning">
      <strong>已关闭</strong>
      <span>业务入口禁用；发布准入第 {{ type === 'JENKINS' ? 7 : type === 'SONAR' ? 8 : 9 }} 项记 CLOSED（INTEGRATION_DISABLED），不影响其他项。</span>
    </div>

    <div class="icard__body">
      <label class="icard__field">
        <span class="icard__label">Base URL</span>
        <el-input
          v-model="draft.baseUrl"
          :disabled="readonly"
          placeholder="https://jenkins.example.com"
          aria-label="Base URL"
          @blur="touched = true"
        />
        <span v-if="showBaseUrlError" class="icard__err" role="alert">{{ baseUrlError }}</span>
      </label>

      <div class="icard__row">
        <CredentialField
          v-model="draft.credential"
          class="icard__grow"
          :configured="!!config?.hasCredential"
          :last4="config?.credentialLast4 ?? null"
          :label="meta.credentialLabel"
          :kind-label="meta.credentialKind"
          :confirm-name="configName"
          :readonly="readonly"
          :disabled="saving"
          @clear="onCredentialClear"
        />
        <label v-if="meta.username" class="icard__field icard__field--user">
          <span class="icard__label">用户名</span>
          <el-input
            v-model="draft.username"
            :disabled="readonly"
            autocomplete="off"
            placeholder="例如 deploy-bot"
            aria-label="用户名"
          />
        </label>
      </div>

      <!-- Webhook：secret 只写不读 + 只读回调地址（不回显 secret，也不允许在这里猜地址） -->
      <div v-if="meta.webhook" class="icard__webhook">
        <CredentialField
          v-model="draft.secret"
          :configured="!!config?.hasSecret"
          :last4="config?.secretLast4 ?? null"
          label="Webhook Secret"
          kind-label="签名密钥"
          :confirm-name="configName"
          :readonly="readonly"
          :disabled="saving"
          hint="用于校验回调签名；保存后不可查看"
        />
        <div class="icard__callbc">
          <span class="icard__label">回调地址</span>
          <div v-if="webhookUrl" class="icard__url">
            <code class="icard__url-text" :title="webhookUrl">{{ webhookUrl }}</code>
            <el-button link size="small" type="primary" @click="copyWebhook">复制</el-button>
          </div>
          <p v-else class="icard__hint">保存配置后生成回调地址</p>
        </div>
      </div>

      <div class="icard__row">
        <label class="icard__field icard__field--timeout">
          <span class="icard__label">超时（ms）</span>
          <el-input-number
            v-model="draft.timeoutMs"
            :min="1000"
            :max="60000"
            :step="1000"
            :disabled="readonly"
            controls-position="right"
            aria-label="超时毫秒"
          />
        </label>
        <div class="icard__field icard__field--policy">
          <span class="icard__label">失败重试</span>
          <p class="icard__policy">最多 5 次 · 1/2/4/8/16s 退避（服务端策略）</p>
        </div>
      </div>

      <!-- extra：按系统渲染具名字段，不提供 JSON 框（服务端只校验这些键） -->
      <div class="icard__row">
        <label v-if="type === 'JENKINS'" class="icard__field icard__grow">
          <span class="icard__label">jobName <em>必填</em></span>
          <el-input v-model="draft.jobName" :disabled="readonly" placeholder="release-build" aria-label="jobName" />
        </label>
        <label v-else-if="type === 'GITLAB'" class="icard__field icard__grow">
          <span class="icard__label">projectPath <em>必填</em></span>
          <el-input v-model="draft.projectPath" :disabled="readonly" placeholder="group/sub/project" aria-label="projectPath" />
        </label>
        <label v-else-if="type === 'SONAR'" class="icard__field icard__grow">
          <span class="icard__label">默认 projectKey</span>
          <el-input v-model="draft.projectKey" :disabled="readonly" placeholder="release 级设置可覆盖" aria-label="默认 projectKey" />
        </label>
        <template v-else>
          <label class="icard__field icard__grow">
            <span class="icard__label">repository <em>必填</em></span>
            <el-input v-model="draft.repository" :disabled="readonly" placeholder="releases" aria-label="repository" />
          </label>
          <label class="icard__field icard__grow">
            <span class="icard__label">allowedHosts（可选，换行分隔）</span>
            <el-input v-model="draft.allowedHosts" :disabled="readonly" type="textarea" :rows="1" autosize placeholder="repo.internal.example.com" aria-label="allowedHosts" />
          </label>
        </template>
      </div>

      <p v-if="extraError && (touched || dirty)" class="icard__err" role="alert">{{ extraError }}</p>

      <ConnectionTestBar
        v-if="testing || testResult"
        :result="testResult"
        :testing="testing"
        @retry="onTest"
      />

      <div v-if="!configured" class="icard__empty">
        <span class="icard__empty-title">尚未配置</span>
        <span>填写 Base URL 与凭据后保存，再把开关打开；在此之前准入对应项记 CLOSED。</span>
      </div>

      <div v-if="saveError" class="icard__note icard__note--danger" role="alert">
        <strong>{{ saveError.code || 'SAVE_FAILED' }}</strong>
        <span>{{ saveError.message }}</span>
      </div>
    </div>

    <footer class="icard__foot">
      <button type="button" class="icard__help" @click="emit('open-help')">如何接入 ›</button>
      <span class="icard__spacer" />
      <span v-if="dirty && !saving" class="icard__dirty">{{ dirtyCount }} 处未保存</span>
      <el-tooltip v-if="saveHint" :content="saveHint" placement="top" :show-after="200">
        <span class="icard__btn-wrap">
          <el-button :disabled="!canSave" :loading="saving" type="primary" @click="submit">保存</el-button>
        </span>
      </el-tooltip>
      <el-button v-else :disabled="!canSave" :loading="saving" type="primary" @click="submit">保存</el-button>
      <el-tooltip :content="configured ? '探测一次，不改动配置' : '先保存配置才能测试'" placement="top" :show-after="200">
        <span class="icard__btn-wrap">
          <el-button :disabled="!configured || testing || readonly" :loading="testing" @click="onTest">
            测试连接
          </el-button>
        </span>
      </el-tooltip>
    </footer>
  </section>
</template>

<style scoped>
.icard {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: var(--h-intg-card);
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  transition:
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease);
}

.icard:hover {
  border-color: #c9d6ee;
}

.icard--unconfigured {
  border-style: dashed;
  border-color: var(--intg-unconfigured-border);
  background: var(--intg-unconfigured-bg);
}

.icard--disabled {
  border-color: var(--border-strong);
}

.icard__head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}

.icard__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 13px;
  font-weight: 600;
}

.icard__title {
  min-width: 0;
}

.icard__name {
  margin: 0;
  color: var(--text-title);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
}

.icard__purpose {
  margin: 1px 0 0;
  overflow: hidden;
  color: var(--text-muted-aa);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icard__spacer {
  flex: 1;
}

.icard__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.icard__note {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  border-left: 2px solid var(--border-strong);
  border-radius: var(--r-ctl);
  font-size: 12px;
  line-height: 1.5;
}

.icard__note strong {
  font-weight: 600;
}

.icard__note--warning {
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}

.icard__note--danger {
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}

.icard__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.icard__row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.icard__grow {
  flex: 1;
  min-width: 0;
}

.icard__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.icard__field--user {
  width: 132px;
  flex: none;
}

.icard__field--timeout {
  width: 148px;
  flex: none;
}

.icard__field--policy {
  flex: 1;
}

.icard__label {
  color: var(--text-body);
  font-size: 12px;
}

.icard__label em {
  color: var(--danger-ink);
  font-size: 11px;
  font-style: normal;
}

.icard__err {
  margin: 0;
  color: var(--danger-ink);
  font-size: 11px;
  line-height: 1.5;
}

.icard__webhook {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--divider);
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
}

.icard__callbc {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.icard__url {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.icard__url-text {
  flex: 1;
  overflow: hidden;
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icard__hint,
.icard__policy {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 1.5;
}

.icard__policy {
  padding-top: 6px;
}

.icard__empty {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-ctl);
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 1.5;
}

.icard__empty-title {
  color: var(--text-body);
  font-size: 12px;
  font-weight: 500;
}

.icard__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}

.icard__help {
  padding: 0;
  border: 0;
  background: none;
  color: var(--color-primary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.icard__help:hover {
  text-decoration: underline;
}

.icard__help:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}

.icard__dirty {
  color: var(--warning-ink);
  font-size: 11px;
}

.icard__btn-wrap {
  display: inline-flex;
}

@media (max-width: 960px) {
  .icard {
    min-height: 0;
  }
}
</style>
