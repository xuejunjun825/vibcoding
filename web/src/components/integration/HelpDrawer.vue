<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { IntegrationConfigView, IntegrationSystem } from '@/api/integration'

/**
 * 接入文档抽屉（DESIGN §6.6）。
 *
 * 这张抽屉回答"我要在对面系统里做什么"，而不是复述表单字段：
 * 字段表 → 凭据与最小权限 → 回调地址与 secret → 网络白名单 → 验证顺序 → 桩的边界。
 *
 * 最后一节（桩与真实差异）不是免责声明，是**已知差异清单**：本地桩通过不等于真实系统联调，
 * 差异点写清楚，才不会在第一次真实回调时才发现签名算法对不上。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    system: IntegrationSystem
    configs?: IntegrationConfigView[]
  }>(),
  { configs: () => [] },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

interface FieldDoc {
  field: string
  example: string
  required: string
  note: string
}

interface SystemDoc {
  title: string
  intro: string
  fields: FieldDoc[]
  credential: string
  permission: string
  webhook: boolean
  webhookStep: string
  whitelist: string
}

const DOCS: Record<IntegrationSystem, SystemDoc> = {
  JENKINS: {
    title: 'Jenkins 接入',
    intro: '用于触发发布构建、回传构建状态、构建失败自动转缺陷。',
    fields: [
      { field: 'baseUrl', example: 'https://jenkins.example.com', required: '必填', note: 'Jenkins 根地址，不带 /job/ 路径' },
      { field: 'jobName', example: 'release-build', required: '必填', note: '构建任务名；嵌套目录写 folder/job 形式' },
      { field: '用户名 + API 令牌', example: 'deploy-bot / 11a2…', required: '必填', note: 'Basic 认证；令牌在用户 Configure → API Token 生成' },
      { field: 'Webhook Secret', example: '随机 32 位串', required: '建议', note: '与 Jenkins 任务里的 secret 保持一致，用于 HMAC-SHA256 验签' },
      { field: '超时', example: '10000 ms', required: '默认', note: '单次出站超时；后台写另有 1/2/4/8/16s 退避重试' },
    ],
    credential: 'API Token（不是登录密码）。凭据只写不读：保存后界面只显示末四位。',
    permission: '最小权限：Job/Build（触发与读取构建）、Job/Read。不要给管理员。',
    webhook: true,
    webhookStep:
      '在任务配置里勾选 "GitHub hook trigger" 或安装 Generic Webhook Trigger，把回调地址填进去，并设置相同的 secret；签名头 X-Jenkins-Signature（HMAC-SHA256）+ 时间戳头，服务端容忍 ±300s。',
    whitelist: 'allowedHosts 留空表示仅允许 baseUrl 同主机；跨主机（如制品下载域名）需逐条列出，私网/169.254/file: 一律拒绝。',
  },
  GITLAB: {
    title: 'GitLab 接入',
    intro: '用于同步 MR 状态、代码行数与合并状态，按需推进需求阶段。',
    fields: [
      { field: 'baseUrl', example: 'https://gitlab.example.com', required: '必填', note: 'GitLab 根地址（自建实例）' },
      { field: 'projectPath', example: 'group/sub/project', required: '必填', note: '支持嵌套 group；MR URL 解析也以此为准' },
      { field: 'Private Token', example: 'glpat-…', required: '必填', note: '需要 api 或 read_api scope' },
      { field: 'Webhook Secret', example: '随机 32 位串', required: '必填', note: 'GitLab 用 X-Gitlab-Token 明文头做常量时间比较' },
      { field: '超时', example: '10000 ms', required: '默认', note: '兜底轮询固定 10 分钟一次，不在此配置' },
    ],
    credential: 'Private Token（或 Project Access Token）。凭据只写不读，仅显示末四位。',
    permission: '最小权限：api（读 MR、写 MR note）。只读同步可用 read_api，但自动推进需 api。',
    webhook: true,
    webhookStep:
      '在项目 Settings → Webhooks 填回调地址，Secret token 与这里一致，勾选 Merge request events；未识别事件服务端返回 200 IGNORED，避免 GitLab 反复重投。',
    whitelist: '默认仅允许 baseUrl 同主机；企业内网 GitLab 若走独立域名，请在 allowedHosts 明确列出。',
  },
  SONAR: {
    title: 'SonarQube 接入',
    intro: '用于拉取质量门快照（覆盖/重复率/复杂度/问题数/安全热点），支撑准入第 8 项。',
    fields: [
      { field: 'baseUrl', example: 'https://sonar.example.com', required: '必填', note: 'SonarQube 根地址' },
      { field: '默认 projectKey', example: 'com.example:release', required: '可选', note: '版本级「默认 Sonar key」优先；需求级可单独指定' },
      { field: '分析 Token', example: 'squ_…', required: '必填', note: '在 My Account → Security 生成' },
      { field: '超时', example: '10000 ms', required: '默认', note: '快照由后台 30 分钟刷新，准入路径不发起外呼' },
    ],
    credential: 'User Token（squ_ 前缀）。凭据只写不读，仅显示末四位。',
    permission: '最小权限：Browse（读项目与度量）。不要使用全局分析 Token。',
    webhook: false,
    webhookStep: '无需回调：Sonar 侧不推送，服务端每 30 分钟按已配置 key 主动刷新快照。',
    whitelist: '默认仅允许 baseUrl 同主机；Sonar 若经反向代理暴露在别的域名，需加入 allowedHosts。',
  },
  NEXUS: {
    title: 'Nexus 制品库接入',
    intro: '用于检索制品、登记发布单绑定制品、校验 sha256 与晋级。',
    fields: [
      { field: 'baseUrl', example: 'https://nexus.example.com', required: '必填', note: 'Nexus 3 根地址（不带 /service/rest）' },
      { field: 'repository', example: 'releases', required: '必填', note: '检索默认仓库；晋级目标仓库由服务端配置决定' },
      { field: 'allowedHosts', example: 'nexus-cdn.example.com', required: '可选', note: '下载域名与检索域名不同时逐条列出，换行或逗号分隔' },
      { field: '用户名 + 密码', example: 'deploy-bot / ••••', required: '必填', note: 'Basic 认证；只读账号即可完成检索与校验' },
      { field: '超时', example: '10000 ms', required: '默认', note: '检索为交互读：单次尝试，失败降级为空列表 + 原因' },
    ],
    credential: '访问密码或 Token。凭据只写不读，仅显示末四位。',
    permission: '最小权限：nx-repository-view-*-*-read（读）。晋级到目标仓库需要对应 browse/read。',
    webhook: false,
    webhookStep: '无需回调：制品通过页内检索或手工登记绑定到发布单。',
    whitelist: '下载地址与 baseUrl 不同源时必须列入 allowedHosts；SSRF 守卫在建立连接前拦截私网与 file:。',
  },
}

const doc = computed(() => DOCS[props.system])

const active = ref<string[]>([props.system])

watch(
  () => [props.modelValue, props.system] as const,
  ([open, system]) => {
    if (open) active.value = [system]
  },
)

const webhookBase: Record<IntegrationSystem, string> = {
  JENKINS: '/api/integration/webhooks/jenkins',
  GITLAB: '/api/integration/webhooks/gitlab',
  SONAR: '',
  NEXUS: '',
}

const webhookUrl = computed(() => {
  if (!doc.value.webhook) return ''
  const config = props.configs.find((item) => item.system === props.system)
  return config ? `${webhookBase[props.system]}/${config.id}` : `${webhookBase[props.system]}/{configId}`
})

const DIFFS: Array<{ item: string; stub: string; real: string }> = [
  { item: '鉴权', stub: '接受任意 Basic/Token', real: '401/403 会真实发生，CSRF/SSO 可能另需配置' },
  { item: '分页', stub: '一次返回全部', real: 'Nexus/Sonar 默认分页，需按 page/continuation 翻页' },
  { item: '时间格式', stub: 'ISO-8601 字符串', real: 'Jenkins 用 epoch 毫秒，GitLab 用带时区 ISO' },
  { item: 'Webhook 签名', stub: '固定 secret + 本机时钟', real: '时间戳偏移 >300s 即 401，需 NTP 对齐' },
  { item: '速率限制', stub: '不限速', real: 'GitLab 429 / Jenkins 队列满，重试策略需真正生效' },
  { item: '错误码', stub: '统一 JSON {code,message}', real: 'Jenkins 返回 HTML 错误页，GitLab 用 error 字段' },
  { item: '字段大小写', stub: 'camelCase', real: 'GitLab MR 用 iid/target_branch，Sonar 用 project.key' },
]

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

async function copy(value: string, label: string): Promise<void> {
  if (!value) return
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value)
    else if (!fallbackCopy(value)) throw new Error('copy unavailable')
    ElMessage.success(`已复制${label}`)
  } catch {
    ElMessage.warning('浏览器不允许读取剪贴板，请手动选择复制')
  }
}

const STEPS = [
  '在「集成配置」填入 baseUrl 与凭据，点「测试连接」（≤6s，不写库）',
  '保存后打开开关：启用即意味着该系统的准入项会真实参与判定',
  '按上面的回调地址在对面系统配置 webhook，并设置相同 secret',
  '在发布单触发一次构建/刷新一次 MR 或 Sonar 快照，确认「同步日志」出现 SUCCESS 记录',
]
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="640px"
    append-to-body
    :with-header="false"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="help" role="document" aria-labelledby="help-title">
      <header class="help__head">
        <h2 id="help-title" class="help__title">{{ doc.title }}</h2>
        <p class="help__intro">{{ doc.intro }}</p>
        <button type="button" class="help__close" aria-label="关闭接入文档" @click="emit('update:modelValue', false)">
          ✕
        </button>
      </header>

      <p class="help__warn" role="note">
        本地桩通过 ≠ 真实系统已联调：桩只覆盖协议形状与失败分支，鉴权、限流、真实字段差异以对面系统为准（见文末差异表）。
      </p>

      <el-collapse v-model="active">
        <el-collapse-item :name="system" :title="`${doc.title} · 字段与权限`">
          <table class="help__table">
            <thead>
              <tr>
                <th>字段</th>
                <th>示例</th>
                <th>必填</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="field in doc.fields" :key="field.field">
                <td><code>{{ field.field }}</code></td>
                <td class="help__mono">{{ field.example }}</td>
                <td>{{ field.required }}</td>
                <td>{{ field.note }}</td>
              </tr>
            </tbody>
          </table>
          <dl class="help__dl">
            <dt>凭据</dt>
            <dd>{{ doc.credential }}</dd>
            <dt>最小权限</dt>
            <dd>{{ doc.permission }}</dd>
            <dt>网络白名单</dt>
            <dd>{{ doc.whitelist }}</dd>
          </dl>
        </el-collapse-item>

        <el-collapse-item name="webhook" :title="doc.webhook ? '回调地址与 secret' : '回调（本系统不需要）'">
          <template v-if="doc.webhook">
            <div class="help__copy">
              <code class="help__copy-text">{{ webhookUrl }}</code>
              <el-button size="small" :disabled="!webhookUrl" @click="copy(webhookUrl, '回调地址')">复制地址</el-button>
            </div>
            <ol class="help__steps">
              <li>{{ doc.webhookStep }}</li>
              <li>secret 只写不读：填一次即加密存库，之后只能「更换」或「清除」。</li>
              <li>验签失败一律 401，且不写任何业务行；时间戳超出 ±300s 同样拒绝。</li>
              <li>同一 configId 限流 20 req/s，超限返回 429（服务端不排队）。</li>
            </ol>
          </template>
          <p v-else class="help__para">{{ doc.webhookStep }}</p>
        </el-collapse-item>

        <el-collapse-item name="verify" title="验证顺序（4 步）">
          <ol class="help__steps">
            <li v-for="step in STEPS" :key="step">{{ step }}</li>
          </ol>
          <p class="help__para">
            准入第 7/8/9 项只读本地事实：构建记录、Sonar 快照、制品绑定。集成未启用时这三项记
            <code>CLOSED(INTEGRATION_DISABLED)</code>，不会阻断提交。
          </p>
        </el-collapse-item>

        <el-collapse-item name="diff" title="桩与真实差异（7 项）">
          <table class="help__table help__table--diff">
            <thead>
              <tr>
                <th>维度</th>
                <th>本地桩</th>
                <th>真实系统</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in DIFFS" :key="row.item">
                <td>{{ row.item }}</td>
                <td>{{ row.stub }}</td>
                <td>{{ row.real }}</td>
              </tr>
            </tbody>
          </table>
        </el-collapse-item>
      </el-collapse>

      <footer class="help__foot">
        <span>需要更多？发布单详情与需求详情也有对应卡片的就地说明。</span>
        <el-button size="small" @click="emit('update:modelValue', false)">知道了</el-button>
      </footer>
    </div>
  </el-drawer>
</template>

<style scoped>
.help {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 4px 8px;
  border-left: 1px solid var(--divider);
}

.help__head {
  position: relative;
  padding-right: 28px;
}

.help__title {
  margin: 0;
  color: var(--text-title);
  font-size: 16px;
  font-weight: 600;
}

.help__intro {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.help__close {
  position: absolute;
  top: 0;
  right: 0;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: var(--r-cell);
  background: none;
  color: var(--text-secondary);
  cursor: pointer;
}

.help__close:hover {
  background: var(--bg-hover);
  color: var(--text-title);
}

.help__close:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.help__warn {
  margin: 0;
  padding: 8px 10px;
  border-left: 2px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.6;
}

.help__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.help__table th,
.help__table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--divider);
  text-align: left;
  vertical-align: top;
}

.help__table th {
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  white-space: nowrap;
}

.help__table td {
  color: var(--text-body);
  line-height: 1.5;
}

.help__table code,
.help__para code {
  padding: 0 4px;
  border-radius: var(--r-cell);
  background: var(--intg-code-bg);
  font-family: var(--font-mono);
  font-size: 11px;
}

.help__mono {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  word-break: break-all;
}

.help__dl {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 12px 0 0;
  font-size: 12px;
}

.help__dl dt {
  color: var(--text-muted-aa);
}

.help__dl dd {
  margin: 0;
  color: var(--text-body);
  line-height: 1.6;
}

.help__copy {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--intg-code-bg);
}

.help__copy-text {
  flex: 1;
  overflow: hidden;
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.help__steps {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--text-body);
  font-size: 12px;
  line-height: 1.8;
}

.help__para {
  margin: 10px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.help__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--divider);
  color: var(--text-muted-aa);
  font-size: 12px;
}

.help__table--diff td:nth-child(2) {
  color: var(--text-secondary);
}
</style>
