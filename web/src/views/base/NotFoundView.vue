<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 404：地址可能过期（单据被删/链接手改），给“回首页”和“返回上一页”两条明确出路 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const attempted = computed(() => route.fullPath)

function back(): void {
  if (window.history.length > 1) router.back()
  else void router.push(auth.homeRoute)
}
</script>

<template>
  <div class="nf">
    <div class="nf__card">
      <span class="nf__icon" aria-hidden="true">
        <svg viewBox="0 0 48 48">
          <circle cx="24" cy="24" r="19" fill="none" stroke="currentColor" stroke-width="1.6" opacity=".3" />
          <path d="M18 18.6h12M18 24h8M18 29.4h5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" opacity=".55" />
          <path d="m30 28 6 6m0-6-6 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
      </span>

      <p class="nf__code">404</p>
      <h1 class="nf__title">页面不存在</h1>
      <p class="nf__desc">地址可能有误，或该单据已被删除。也可以从需求池重新查找。</p>
      <code class="nf__path">{{ attempted }}</code>

      <div class="nf__actions">
        <el-button @click="back">返回上一页</el-button>
        <el-button type="primary" @click="router.push(auth.homeRoute)">回到首页</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 56px;
}
.nf__card {
  width: 100%;
  max-width: 480px;
  padding: 32px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  text-align: center;
}
.nf__icon {
  display: inline-flex;
  width: 48px;
  height: 48px;
  color: var(--text-weak);
}
.nf__icon svg {
  width: 48px;
  height: 48px;
}
.nf__code {
  margin-top: 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  color: var(--text-weak);
}
.nf__title {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.nf__desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 21px;
  color: var(--text-muted-aa);
}
.nf__path {
  display: inline-block;
  margin-top: 10px;
  padding: 2px 8px;
  background: var(--bg-page);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-weak);
  word-break: break-all;
}
.nf__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 24px;
}
</style>
