<script setup lang="ts">
/**
 * 三级会签轨（DESIGN §6 / Q7）：横向节点 184×56 + 40px 连接线。
 *
 * 严格顺序由服务端 SignoffService 判定（越序 409 SIGNOFF_ORDER_VIOLATION），
 * 这里只在 `step.current && step.canSign` 时渲染签署按钮 —— 页面不判断“轮到谁”，
 * 也不把已签节点渲染成可点击，避免看起来能补签。
 */
import { computed } from 'vue'
import type { SignoffProgress, SignoffStep } from '@/api/types'

const props = withDefaults(
  defineProps<{
    progress: SignoffProgress | null
    /** 只读（历史版本 / 已驳回 / 无权限） */
    readonly?: boolean
  }>(),
  { progress: null, readonly: false },
)

const emit = defineEmits<{ sign: [step: SignoffStep] }>()

const steps = computed<SignoffStep[]>(() => props.progress?.steps ?? [])
const approved = computed(() => steps.value.filter((s) => s.status === 'SIGNED').length)
const rejected = computed(() => steps.value.some((s) => s.status === 'REJECTED'))
const pendingLabel = computed(() => {
  const current = steps.value.find((step) => step.current)
  return current?.roleName || current?.signRole || ''
})

function nodeState(step: SignoffStep): 'signed' | 'rejected' | 'current' | 'pending' {
  const status = String(step.status ?? '').toUpperCase()
  if (status === 'REJECTED') return 'rejected'
  if (status === 'SIGNED') return 'signed'
  if (step.current) return 'current'
  return 'pending'
}

function canSign(step: SignoffStep): boolean {
  return !props.readonly && step.current === true && step.canSign === true
}
</script>

<template>
  <section class="signoff" :class="{ 'signoff--rejected': rejected }">
    <header class="signoff__head">
      <h3 class="signoff__title">三级会签</h3>
      <span class="signoff__meta" :class="{ 'ink-danger': rejected }">
        <template v-if="rejected">已驳回 · 本版本不可再签</template>
        <template v-else-if="approved >= steps.length && steps.length">
          已会签 {{ approved }}/{{ steps.length }}
        </template>
        <template v-else>
          已签 {{ approved }}/{{ steps.length || 3 }}
          <template v-if="pendingLabel"> · 待 {{ pendingLabel }}</template>
        </template>
      </span>
      <span class="signoff__hint">按 PMO → 质量 → 业务负责人 顺序签署，越序会被服务端拒绝</span>
    </header>

    <ol class="signoff__track" role="list">
      <li
        v-for="(step, index) in steps"
        :key="`${step.signRole}-${index}`"
        class="signoff-node"
        :class="`is-${nodeState(step)}`"
      >
        <el-popover
          placement="bottom"
          :width="320"
          trigger="click"
          :show-after="120"
        >
          <template #reference>
            <button
              type="button"
              class="signoff-node__card"
              :aria-label="`${step.roleName || step.signRole} ${nodeState(step)}`"
            >
              <span class="signoff-node__order t-num">{{ index + 1 }}</span>
              <span class="signoff-node__body">
                <span class="signoff-node__role">{{ step.roleName || step.signRole }}</span>
                <span class="signoff-node__who">
                  <template v-if="step.status === 'SIGNED'">
                    ✓ {{ step.signerName || '已签署' }}
                  </template>
                  <template v-else-if="step.status === 'REJECTED'">✕ 已驳回</template>
                  <template v-else-if="step.current">待签署</template>
                  <template v-else>等待上一级</template>
                </span>
              </span>
              <span v-if="step.current" class="signoff-node__current-dot" aria-hidden="true" />
            </button>
          </template>
          <div class="signoff-pop">
            <div class="signoff-pop__head">
              <strong>{{ step.roleName || step.signRole }}</strong>
              <span class="signoff-pop__state">
                {{
                  step.status === 'SIGNED'
                    ? '已签署'
                    : step.status === 'REJECTED'
                      ? '已驳回'
                      : step.current
                        ? '当前待签'
                        : '未开始'
                }}
              </span>
            </div>
            <dl class="signoff-pop__grid">
              <div><dt>签署人</dt><dd>{{ step.signerName || '—' }}</dd></div>
              <div><dt>时间</dt><dd>{{ step.signedAt || '—' }}</dd></div>
            </dl>
            <p class="signoff-pop__comment">
              <span>意见</span>{{ step.comment || '（无意见）' }}
            </p>
            <button
              v-if="canSign(step)"
              type="button"
              class="signoff-pop__sign"
              @click="emit('sign', step)"
            >
              签署本版本
            </button>
          </div>
        </el-popover>

        <button
          v-if="canSign(step)"
          type="button"
          class="signoff-node__action"
          @click="emit('sign', step)"
        >
          签署
        </button>

        <span v-if="index < steps.length - 1" class="signoff-node__link" aria-hidden="true" />
      </li>
    </ol>

    <p v-if="!steps.length" class="signoff__empty">
      尚无会签进度。生成报告后由 PMO 先签，越序签署会被服务端拒绝。
    </p>
    <p v-if="rejected" class="signoff__reject-note">
      该版本已被驳回，整版本不可用；请生成新版本重新走会签。
    </p>
  </section>
</template>

<style scoped>
.signoff {
  min-width: 0;
}
.signoff__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.signoff__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.signoff__meta {
  font-size: 12px;
  color: var(--text-body);
}
.signoff__hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-weak);
}
.signoff__track {
  display: flex;
  align-items: flex-start;
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-x: auto;
}
.signoff-node {
  display: flex;
  align-items: center;
  position: relative;
  flex: none;
}
.signoff-node__card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 184px;
  height: 56px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease),
    box-shadow var(--t-color) var(--ease);
}
.signoff-node__card:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.signoff-node__card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.signoff-node.is-signed .signoff-node__card {
  border-color: #bfe3c9;
  background: var(--color-success-bg);
}
.signoff-node.is-rejected .signoff-node__card {
  border-color: #f3c2c5;
  background: var(--color-danger-bg);
}
.signoff-node.is-current .signoff-node__card {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  box-shadow: 0 0 0 3px var(--signoff-current, rgba(43, 111, 229, 0.18));
}
.signoff-node__order {
  flex: none;
  width: 18px;
  font-size: 12px;
  color: var(--text-weak);
}
.signoff-node__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.signoff-node__role {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.signoff-node__who {
  font-size: 11px;
  color: var(--text-muted-aa);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.signoff-node.is-signed .signoff-node__who {
  color: var(--success-ink);
}
.signoff-node.is-rejected .signoff-node__who {
  color: var(--danger-ink);
}
.signoff-node__current-dot {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: signoff-pulse 1.6s var(--ease) infinite;
}
@keyframes signoff-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
.signoff-node__action {
  margin-left: 8px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--color-primary);
  border-radius: var(--r-ctl);
  background: var(--color-primary);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
.signoff-node__action:hover {
  background: var(--color-primary-hover);
}
.signoff-node__link {
  width: 40px;
  height: 2px;
  margin: 0 4px;
  background: var(--border);
  flex: none;
}
.signoff-node.is-signed .signoff-node__link {
  background: #bfe3c9;
}
.signoff-node.is-rejected .signoff-node__link {
  background: #f3c2c5;
}
.signoff-pop__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.signoff-pop__head strong {
  font-size: 13px;
  color: var(--text-title);
}
.signoff-pop__state {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.signoff-pop__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 12px;
  margin: 8px 0;
}
.signoff-pop__grid dt {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.signoff-pop__grid dd {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-title);
}
.signoff-pop__comment {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.signoff-pop__comment span {
  margin-right: 6px;
  color: var(--text-muted-aa);
}
.signoff-pop__sign {
  margin-top: 10px;
  width: 100%;
  height: 28px;
  border: 1px solid var(--color-primary);
  border-radius: var(--r-ctl);
  background: var(--color-primary);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
.signoff__empty {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.signoff__reject-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
