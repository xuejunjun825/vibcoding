<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { ArtifactView } from '@/api/integration'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime } from '@/utils/format'

/**
 * 发布单制品卡（DESIGN I3 / §6.5，AC-7）。
 *
 * - sha256 是这张卡的身份证：只显示前 12 位 + 复制完整值，页面不截断证据；
 * - MANUAL 用手工登记黄条明说「准入第 9 项跳过」，不把“填过”当作“验过”；
 * - 晋级入口只在 VERIFIED 时可用；CHECKSHA 不一致由 PromoteDialog 展示服务端结论。
 */
const props = withDefaults(
  defineProps<{
    orderId: number
    artifact: ArtifactView | null
    loading?: boolean
    canBind?: boolean
    canPromote?: boolean
    /** 晋级目标仓库（版本级配置下发；未配置时按钮禁用并说明） */
    targetRepo?: string
  }>(),
  {
    loading: false,
    canBind: false,
    canPromote: false,
    targetRepo: '',
  },
)

const emit = defineEmits<{
  (event: 'pick'): void
  (event: 'promote', artifact: ArtifactView): void
}>()

const shaShort = computed(() => {
  const sha = props.artifact?.sha256 ?? ''
  return sha ? sha.slice(0, 12) : '—'
})

const verifyText = computed(() => {
  const artifact = props.artifact
  if (!artifact) return ''
  if (artifact.status === 'PROMOTED') return '已晋级'
  if (artifact.status === 'VERIFIED') return '校验一致'
  return artifact.source === 'MANUAL' ? '手工登记 · 未校验' : '待校验'
})

const promoteDisabledReason = computed(() => {
  if (!props.canPromote) return '需要权限：deploy:submit'
  if (!props.artifact) return '尚未绑定制品'
  if (props.artifact.status === 'PROMOTED') return '该制品已晋级'
  if (props.artifact.source === 'MANUAL') return '手工登记的制品不能晋级'
  if (props.artifact.status !== 'VERIFIED') return '制品 sha256 尚未通过校验'
  return ''
})

async function copySha(): Promise<void> {
  const sha = props.artifact?.sha256
  if (!sha) return
  try {
    await navigator.clipboard.writeText(sha)
    ElMessage.success('sha256 已复制')
  } catch {
    ElMessage.info('浏览器不允许写剪贴板，请手动选择完整值复制')
  }
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${bytes} B`
}
</script>

<template>
  <section class="artifact-card" :class="{ 'artifact-card--manual': artifact?.source === 'MANUAL' }">
    <header class="artifact-card__head">
      <h2 class="artifact-card__title">发布制品</h2>
      <span v-if="artifact" class="artifact-card__repo mono">{{ artifact.repo }}</span>
      <button
        v-if="artifact && props.canBind"
        type="button"
        class="artifact-card__link"
        @click="emit('pick')"
      >
        更换制品
      </button>
    </header>

    <p v-if="props.loading" class="artifact-card__empty">正在读取制品绑定…</p>

    <template v-else-if="artifact">
      <div v-if="artifact.source === 'MANUAL'" class="artifact-card__manual" role="status">
        手工填写 · 准入第 9 项跳过（不计为已验证制品）
      </div>

      <div class="artifact-card__body">
        <div class="artifact-card__line artifact-card__line--title">
          <span class="artifact-card__version mono">{{ artifact.artifactName }}:{{ artifact.version }}</span>
          <StatusPill kind="artifactSource" :value="artifact.source" size="sm" />
          <span class="artifact-card__verify" :class="`artifact-card__verify--${artifact.status.toLowerCase()}`">
            {{ verifyText }}
          </span>
        </div>

        <div class="artifact-card__line">
          <span class="artifact-card__label">sha256</span>
          <button
            type="button"
            class="artifact-card__sha mono"
            :title="`${artifact.sha256}\n点击复制完整值`"
            @click="copySha"
          >
            {{ shaShort }}<span class="artifact-card__copy">复制</span>
          </button>
          <span v-if="artifact.sizeBytes" class="artifact-card__muted">{{ formatSize(artifact.sizeBytes) }}</span>
        </div>

        <div class="artifact-card__line">
          <span class="artifact-card__label">坐标</span>
          <span class="mono artifact-card__coord">
            {{ [artifact.groupId, artifact.artifactName, artifact.version, artifact.packaging].filter(Boolean).join(' · ') }}
          </span>
        </div>

        <div v-if="artifact.promotedAt" class="artifact-card__line">
          <span class="artifact-card__label">晋级</span>
          <span class="artifact-card__muted">
            已于 {{ formatDateTime(artifact.promotedAt) }} 晋级{{ props.targetRepo ? `至 ${props.targetRepo}` : '' }}
          </span>
        </div>
      </div>

      <footer class="artifact-card__foot">
        <span v-if="promoteDisabledReason" class="artifact-card__muted">{{ promoteDisabledReason }}</span>
        <span v-else class="artifact-card__muted">校验通过后可按版本晋级到生产仓库</span>
        <el-tooltip :content="promoteDisabledReason" placement="top" :disabled="!promoteDisabledReason">
          <span>
            <el-button
              size="small"
              :disabled="Boolean(promoteDisabledReason)"
              @click="emit('promote', artifact)"
            >
              晋级至 {{ props.targetRepo || 'PROD' }}
            </el-button>
          </span>
        </el-tooltip>
      </footer>
    </template>

    <div v-else class="artifact-card__empty">
      <p>尚未绑定制品。准入第 9 项会以 SKIP 记录，不会伪装成已校验。</p>
      <el-button v-if="props.canBind" size="small" type="primary" @click="emit('pick')">
        选择制品
      </el-button>
      <span v-else class="artifact-card__muted">需要权限：deploy:submit</span>
    </div>
  </section>
</template>

<style scoped>
.artifact-card {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.artifact-card--manual::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  background: var(--color-warning);
}
.artifact-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 14px;
  border-bottom: 1px solid var(--divider);
}
.artifact-card__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.artifact-card__repo {
  font-size: 11px;
  color: var(--text-weak);
}
.artifact-card__link {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--color-primary, #2f6fed);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--r-cell);
}
.artifact-card__link:hover {
  background: var(--bg-hover);
}
.artifact-card__link:focus-visible {
  outline: 2px solid var(--focus-ring, #2f6fed);
  outline-offset: 1px;
}
.artifact-card__manual {
  padding: 8px 14px;
  background: var(--color-warning-bg);
  border-bottom: 1px solid #f0dcae;
  color: var(--warning-ink);
  font-size: 12px;
}
.artifact-card__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
}
.artifact-card__line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  min-width: 0;
}
.artifact-card__line--title {
  gap: 8px;
  flex-wrap: wrap;
}
.artifact-card__version {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
  line-height: 20px;
}
.artifact-card__verify {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.artifact-card__verify--verified,
.artifact-card__verify--promoted {
  color: var(--success-ink);
}
.artifact-card__verify--registered {
  color: var(--warning-ink);
}
.artifact-card__label {
  flex: none;
  width: 44px;
  color: var(--text-weak);
  font-size: 11px;
}
.artifact-card__sha {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: var(--w-sha-chip, 160px);
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-hover);
  color: var(--text-body);
  font-size: 11px;
  cursor: pointer;
}
.artifact-card__sha:hover {
  border-color: var(--color-primary, #2f6fed);
}
.artifact-card__copy {
  color: var(--color-primary, #2f6fed);
  font-family: var(--font-body, inherit);
}
.artifact-card__coord {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted-aa);
  font-size: 11px;
}
.artifact-card__muted {
  color: var(--text-weak);
  font-size: 11px;
}
.artifact-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--divider);
  background: var(--bg-table-head);
  flex-wrap: wrap;
}
.artifact-card__empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 16px 14px;
  font-size: 12px;
  color: var(--text-weak);
  line-height: 18px;
}
.artifact-card__empty p {
  margin: 0;
}
</style>
