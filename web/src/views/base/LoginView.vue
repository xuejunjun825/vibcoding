<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { landingRoute } from '@/router/landing'
import { ApiError } from '@/api/client'

/**
 * 登录（AC-1）：租户 + 账号 → 签发 JWT。
 * 租户用可输入的 select（allow-create）：租户清单没有公共查询接口，
 * 选项来自部署配置 VITE_TENANT_CODES，输入其它编码也允许提交。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const tenantCode = ref('')
const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const errorCode = ref('')

const tenantOptions = computed(() => {
  const raw = (import.meta.env.VITE_TENANT_CODES as string | undefined) || 'DEMO'
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .map((code) => ({ value: code, label: code }))
})

const canSubmit = computed(
  () => tenantCode.value.trim().length > 0 && username.value.trim().length > 0 && password.value.length > 0,
)

const redirectTarget = computed(() => {
  const raw = route.query.redirect
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) return value
  return ''
})

onMounted(() => {
  tenantCode.value = tenantOptions.value[0]?.value ?? 'DEMO'
})

function fail(caught: unknown): void {
  if (caught instanceof ApiError) {
    errorCode.value = caught.code
    if (caught.status === 401) {
      errorMessage.value = '用户名或密码不正确，请重新输入。'
      return
    }
    if (caught.status === 403) {
      errorMessage.value = caught.message || '该账号没有登录此租户的权限。'
      return
    }
    if (caught.isNetworkError) {
      errorMessage.value = caught.message
      return
    }
    errorMessage.value = caught.message || '登录失败，请稍后重试。'
    return
  }
  errorCode.value = 'UNKNOWN'
  errorMessage.value = '登录失败，请稍后重试。'
}

async function submit(): Promise<void> {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  errorCode.value = ''
  try {
    await auth.login({
      tenantCode: tenantCode.value.trim(),
      username: username.value.trim(),
      password: password.value,
    })
    await router.replace(landingRoute(router, redirectTarget.value || auth.homeRoute))
  } catch (caught) {
    fail(caught)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="login__brand" aria-hidden="true">
      <div class="login__brand-inner">
        <span class="login__mark">
          <svg viewBox="0 0 32 32">
            <path d="M4 9 16 3l12 6-12 6L4 9Z" fill="currentColor" opacity=".9" />
            <path d="M4 16 16 10l12 6-12 6-12-6Z" fill="currentColor" opacity=".5" />
            <path d="M4 23 16 17l12 6-12 6-12-6Z" fill="currentColor" opacity=".22" />
          </svg>
        </span>
        <h1 class="login__title">供应链研发管理平台</h1>
        <p class="login__slogan">从需求提报到版本发布，一条链路里完成评审、审批、测试与准入。</p>

        <ul class="login__points">
          <li>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3.4 8.3 3 3 6.2-6.6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
            需求 / 版本 / 缺陷 / 测试全链路追溯
          </li>
          <li>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3.4 8.3 3 3 6.2-6.6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
            域 × 厂商双维隔离，只能看到授权范围
          </li>
          <li>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3.4 8.3 3 3 6.2-6.6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
            测试门禁与发布准入同源判定，拒绝不可绕过
          </li>
        </ul>

        <div class="login__art">
          <svg viewBox="0 0 320 120" preserveAspectRatio="none">
            <path d="M0 96h48l16-28 20 44 18-64 22 48h40l18-36 20 56 20-40h58" fill="none" stroke="currentColor" stroke-width="1" opacity=".35" />
            <path d="M0 108h320" stroke="currentColor" stroke-width="1" opacity=".2" />
            <circle cx="86" cy="48" r="2.5" fill="currentColor" opacity=".5" />
            <circle cx="186" cy="60" r="2.5" fill="currentColor" opacity=".5" />
            <circle cx="258" cy="32" r="2.5" fill="currentColor" opacity=".5" />
          </svg>
        </div>
      </div>
    </section>

    <section class="login__panel">
      <form class="login__form" novalidate @submit.prevent="submit">
        <h2 class="login__form-title">登录</h2>
        <p class="login__form-sub">使用团队分配的账号进入研发管理工作台</p>

        <div class="login__field">
          <label class="login__label" for="login-tenant">租户</label>
          <el-select
            id="login-tenant"
            v-model="tenantCode"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入租户编码"
            class="login__control"
          >
            <el-option v-for="item in tenantOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </div>

        <div class="login__field">
          <label class="login__label" for="login-username">用户名</label>
          <el-input
            id="login-username"
            v-model="username"
            placeholder="请输入用户名"
            autocomplete="username"
            maxlength="64"
            class="login__control"
          />
        </div>

        <div class="login__field">
          <label class="login__label" for="login-password">密码</label>
          <el-input
            id="login-password"
            v-model="password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            maxlength="128"
            class="login__control"
            @keyup.enter="submit"
          />
        </div>

        <div v-if="errorMessage" class="login__error" role="alert">
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="8" r="6.2" fill="none" stroke="currentColor" stroke-width="1.3" />
            <path d="M8 4.9v3.6M8 10.9h.01" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
          <span>{{ errorMessage }}</span>
          <code v-if="errorCode" class="login__error-code">{{ errorCode }}</code>
        </div>

        <el-button
          type="primary"
          native-type="submit"
          class="login__submit"
          :loading="submitting"
          :disabled="!canSubmit || submitting"
        >
          {{ submitting ? '登录中…' : '登录' }}
        </el-button>

        <p class="login__hint">
          忘记密码或需要开通权限，请联系平台管理员。登录状态仅保存在当前浏览器标签页。
        </p>
      </form>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 480px;
  min-height: 100vh;
  background: var(--bg-card);
}
.login__brand {
  position: relative;
  display: flex;
  align-items: center;
  padding: 56px;
  background: var(--bg-page);
  border-right: 1px solid var(--border);
  overflow: hidden;
}
.login__brand-inner {
  max-width: 460px;
}
.login__mark {
  display: inline-flex;
  width: 40px;
  height: 40px;
  color: var(--color-primary);
}
.login__mark svg {
  width: 40px;
  height: 40px;
}
.login__title {
  margin-top: 20px;
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  color: var(--text-title);
  letter-spacing: -0.01em;
}
.login__slogan {
  margin-top: 10px;
  font-size: 13px;
  line-height: 22px;
  color: var(--text-muted-aa);
}
.login__points {
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.login__points li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-body);
}
.login__points svg {
  width: 16px;
  height: 16px;
  flex: none;
  margin-top: 2px;
  color: var(--color-success);
}
.login__art {
  margin-top: 40px;
  width: 100%;
  height: 120px;
  color: var(--color-primary);
}
.login__art svg {
  width: 100%;
  height: 100%;
}
.login__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
.login__form {
  width: 100%;
  max-width: 320px;
}
.login__form-title {
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  color: var(--text-title);
}
.login__form-sub {
  margin-top: 6px;
  margin-bottom: 24px;
  font-size: 12px;
  line-height: 19px;
  color: var(--text-muted-aa);
}
.login__field + .login__field {
  margin-top: 14px;
}
.login__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-body);
}
.login__control {
  width: 100%;
}
.login__error {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 14px;
  padding: 8px 10px;
  background: var(--color-danger-bg);
  border-left: 2px solid var(--color-danger);
  border-radius: var(--r-ctl);
  font-size: 12px;
  line-height: 19px;
  color: var(--danger-ink);
}
.login__error svg {
  width: 14px;
  height: 14px;
  flex: none;
  margin-top: 2px;
}
.login__error-code {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  opacity: 0.8;
  white-space: nowrap;
}
.login__submit {
  width: 100%;
  height: 36px;
  margin-top: 20px;
}
.login__hint {
  margin-top: 16px;
  font-size: 11px;
  line-height: 18px;
  color: var(--text-weak);
}
@media (max-width: 960px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login__brand {
    display: none;
  }
}
</style>
