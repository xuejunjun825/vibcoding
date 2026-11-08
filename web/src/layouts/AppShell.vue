<script setup lang="ts">
import { onBeforeUnmount, onMounted, provide, ref } from 'vue'
import { useRouter } from 'vue-router'
import SideNav from './SideNav.vue'
import TopNav from './TopNav.vue'
import { useAuthStore } from '@/stores/auth'
import { listApprovalTodo, listNotifications } from '@/api/req'
import { SHELL_REFRESH_KEY } from '@/composables/useShellRefresh'

/**
 * 主框架：侧栏 200 + 顶栏 48 + 内容（DESIGN §3.2）。
 * 角标（我的待办 / 未读通知）在这里统一取，页面通过 useShellRefresh() 触发刷新。
 */
const auth = useAuthStore()
const router = useRouter()

const todoCount = ref(0)
const unreadCount = ref(0)
const navigating = ref(false)

let pollTimer: number | undefined
let removeBefore: (() => void) | undefined
let removeAfter: (() => void) | undefined

async function refreshCounts(): Promise<void> {
  if (!auth.isAuthenticated) return
  const [todo, unread] = await Promise.all([
    // 没有审批权限的角色会拿到 403：静默处理，不能把用户弹去 /403
    listApprovalTodo({ page: 1, size: 1 }, { silentForbidden: true, silentAuth: true }).catch(() => null),
    listNotifications({ unreadOnly: true, page: 1, size: 1 }).catch(() => null),
  ])
  todoCount.value = todo?.total ?? 0
  unreadCount.value = unread?.total ?? 0
}

provide(SHELL_REFRESH_KEY, () => {
  void refreshCounts()
})

onMounted(async () => {
  removeBefore = router.beforeEach(() => {
    navigating.value = true
    return true
  })
  removeAfter = router.afterEach(() => {
    navigating.value = false
  })

  if (auth.isAuthenticated && !auth.scope) await auth.loadMe()
  await refreshCounts()

  pollTimer = window.setInterval(() => {
    if (document.hidden) return
    void refreshCounts()
  }, 60_000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  removeBefore?.()
  removeAfter?.()
})
</script>

<template>
  <div class="shell">
    <a class="shell__skip" href="#main">跳到主要内容</a>
    <SideNav :todo-count="todoCount" :unread-count="unreadCount" />

    <div class="shell__main">
      <TopNav :unread-count="unreadCount" />
      <div v-if="navigating" class="shell__progress" role="progressbar" aria-label="页面加载中" />

      <main id="main" class="shell__content" tabindex="-1">
        <RouterView v-slot="{ Component, route }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  background: var(--bg-page);
}
.shell__main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  position: relative;
}
.shell__content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px 36px;
  outline: none;
}
.shell__progress {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  z-index: 5;
  background: linear-gradient(90deg, transparent, var(--color-primary), transparent);
  background-size: 40% 100%;
  background-repeat: no-repeat;
  animation: shell-progress 1.1s var(--ease) infinite;
}
@keyframes shell-progress {
  0% {
    background-position: -40% 0;
  }
  100% {
    background-position: 140% 0;
  }
}
.shell__skip {
  position: absolute;
  left: 8px;
  top: -40px;
  z-index: 20;
  padding: 6px 12px;
  border-radius: var(--r-ctl);
  background: var(--color-primary);
  color: #fff;
  font-size: 12px;
  text-decoration: none;
  transition: top var(--t-color) var(--ease);
}
.shell__skip:focus-visible {
  top: 8px;
}
.page-enter-active,
.page-leave-active {
  transition:
    opacity 120ms var(--ease),
    transform 120ms var(--ease);
}
.page-enter-from {
  opacity: 0;
  transform: translateY(2px);
}
.page-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .page-enter-active,
  .page-leave-active {
    transition: none;
  }
  .shell__progress {
    animation: none;
    background: var(--color-primary);
  }
}
</style>
