<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

/**
 * 页面级 403（DESIGN §5）：图标 + 一句人话 + 返回 + 申请入口。
 * 不做“先渲染再 403”：路由守卫在进入前就拦，服务端 403 由 client 带 message 跳到这里。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const requestOpen = ref(false)
const copied = ref(false)

const firstName = computed(() => (Array.isArray(route.query.from) ? route.query.from[0] : route.query.from) ?? '')
const fromPath = computed(() => (typeof firstName.value === 'string' ? firstName.value : ''))
const codeRaw = computed(() => {
  const raw = route.query.code
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value : ''
})
const codes = computed(() => codeRaw.value.split(',').filter(Boolean))
const serverMessage = computed(() => {
  const raw = route.query.msg
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value ? value : ''
})

const reason = computed(
  () => serverMessage.value || '当前角色没有该功能的权限。可以返回上一页，或联系管理员开通。',
)

const requestText = computed(() =>
  [
    '【权限申请】',
    `租户：${auth.tenantLabel}`,
    `账号：${auth.displayName}（${auth.user?.name ?? '-'}）`,
    `当前角色：${auth.user?.roles?.join(' / ') || '未分配'}`,
    codes.value.length ? `需要功能码：${codes.value.join(', ')}` : '需要的功能码：未指定（页面被服务端拒绝）',
    fromPath.value ? `来源页面：${fromPath.value}` : '',
  ]
    .filter(Boolean)
    .join('\n'),
)

async function copyRequest(): Promise<void> {
  try {
    await navigator.clipboard.writeText(requestText.value)
    copied.value = true
    ElMessage.success('申请内容已复制，可直接发给平台管理员')
  } catch {
    ElMessage.warning('浏览器未授权剪贴板，请手动复制申请内容')
  }
}

function back(): void {
  if (window.history.length > 1) router.back()
  else void router.push(auth.homeRoute)
}
</script>

<template>
  <div class="fb">
    <div class="fb__card">
      <span class="fb__icon" aria-hidden="true">
        <svg viewBox="0 0 48 48">
          <circle cx="24" cy="24" r="19" fill="none" stroke="currentColor" stroke-width="1.6" opacity=".35" />
          <rect x="16" y="22" width="16" height="12" rx="2" fill="none" stroke="currentColor" stroke-width="1.8" />
          <path d="M20 22v-3.6a4 4 0 0 1 8 0V22" fill="none" stroke="currentColor" stroke-width="1.8" />
          <circle cx="24" cy="28" r="1.6" fill="currentColor" />
        </svg>
      </span>

      <p class="fb__code">403</p>
      <h1 class="fb__title">无权访问该单据</h1>
      <p class="fb__desc">{{ reason }}</p>

      <div v-if="codes.length || fromPath" class="fb__meta">
        <div v-if="codes.length" class="fb__meta-row">
          <span class="fb__meta-label">需要功能码</span>
          <code v-for="item in codes" :key="item" class="fb__chip">{{ item }}</code>
        </div>
        <div v-if="fromPath" class="fb__meta-row">
          <span class="fb__meta-label">来源页面</span>
          <code class="fb__path">{{ fromPath }}</code>
        </div>
      </div>

      <div class="fb__actions">
        <el-button @click="back">返回上一页</el-button>
        <el-button type="primary" @click="router.push(auth.homeRoute)">回到首页</el-button>
        <el-button text @click="requestOpen = true">申请权限</el-button>
      </div>
    </div>

    <el-dialog v-model="requestOpen" title="申请权限" width="480px" align-center append-to-body>
      <p class="fb__dialog-hint">把下面的内容发给平台管理员，附带功能码与来源页面能加快处理。</p>
      <pre class="fb__request">{{ requestText }}</pre>
      <template #footer>
        <el-button @click="requestOpen = false">关闭</el-button>
        <el-button type="primary" @click="copyRequest">{{ copied ? '已复制' : '复制申请内容' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fb {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 56px;
}
.fb__card {
  width: 100%;
  max-width: 520px;
  padding: 32px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  text-align: center;
}
.fb__icon {
  display: inline-flex;
  width: 48px;
  height: 48px;
  color: var(--color-warning);
}
.fb__icon svg {
  width: 48px;
  height: 48px;
}
.fb__code {
  margin-top: 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  color: var(--text-weak);
}
.fb__title {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.fb__desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 21px;
  color: var(--text-muted-aa);
}
.fb__meta {
  margin-top: 16px;
  padding: 10px 12px;
  background: var(--bg-page);
  border-radius: var(--r-ctl);
  text-align: left;
}
.fb__meta-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}
.fb__meta-row + .fb__meta-row {
  margin-top: 6px;
}
.fb__meta-label {
  color: var(--text-weak);
  min-width: 72px;
}
.fb__chip {
  padding: 1px 6px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-body);
}
.fb__path {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted-aa);
  word-break: break-all;
}
.fb__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 24px;
}
.fb__dialog-hint {
  font-size: 12px;
  line-height: 19px;
  color: var(--text-muted-aa);
}
.fb__request {
  margin-top: 10px;
  padding: 12px;
  background: var(--bg-page);
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 20px;
  color: var(--text-body);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
