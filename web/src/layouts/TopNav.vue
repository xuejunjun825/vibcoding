<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NAV_ICONS, TOP_NAV } from './nav'

/**
 * 顶栏 48px（DESIGN §3.2）：8 项一级导航 + 全局搜索 + 通知 + 用户菜单。
 * 当前项 = 主色文字 + 2px 下划线；路由未注册的项不渲染。
 */
const props = withDefaults(defineProps<{ unreadCount?: number }>(), { unreadCount: 0 })

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const keyword = ref('')

const navItems = computed(() =>
  TOP_NAV.filter((item) => {
    if (!router.hasRoute(item.routeName)) return false
    if (item.key === 'admin') {
      return (
        auth.can('tenant:manage') ||
        auth.can('org:manage') ||
        auth.can('workflow:manage') ||
        auth.can('apiclient:manage') ||
        auth.can('metric:manage') ||
        auth.can('audit:view')
      )
    }
    return true
  }),
)

/** 最长前缀命中，避免 /defects/board 同时点亮“缺陷”以外的东西 */
const activeKey = computed(() => {
  const matched = navItems.value
    .filter((item) => item.match.some((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`)))
    .sort((a, b) => {
      const longest = (item: typeof a) => Math.max(...item.match.map((prefix) => prefix.length))
      return longest(b) - longest(a)
    })
  return matched[0]?.key ?? ''
})

const canSearch = computed(() => router.hasRoute('requirements'))

function search(): void {
  const value = keyword.value.trim()
  if (!value) return
  if (!canSearch.value) return
  void router.push({ path: '/requirements', query: { keyword: value } })
  keyword.value = ''
}

async function onCommand(command: string | number | object): Promise<void> {
  if (command === 'logout') {
    auth.logout()
    await router.replace({ path: '/login' })
  }
  if (command === 'notifications') await router.push('/notifications')
}
</script>

<template>
  <header class="top">
    <nav class="top__nav" aria-label="一级导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.key"
        :to="item.to"
        class="top__item"
        :class="{ 'top__item--active': activeKey === item.key }"
      >
        {{ item.label }}
      </RouterLink>
    </nav>

    <div class="top__right">
      <div v-if="canSearch" class="top__search">
        <svg class="top__search-icon" viewBox="0 0 16 16" aria-hidden="true">
          <path :d="NAV_ICONS.search" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
        </svg>
        <input
          v-model="keyword"
          class="top__search-input"
          type="search"
          placeholder="搜索需求编号 / 标题"
          aria-label="搜索需求"
          @keyup.enter="search"
        />
      </div>

      <button
        type="button"
        class="top__icon"
        :title="props.unreadCount > 0 ? `${props.unreadCount} 条未读通知` : '通知'"
        aria-label="通知"
        @click="router.push('/notifications')"
      >
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path :d="NAV_ICONS.bell" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <i v-if="props.unreadCount > 0" class="top__dot" />
      </button>

      <el-dropdown trigger="click" @command="onCommand">
        <button type="button" class="top__user">
          <span class="top__avatar">{{ auth.initials }}</span>
          <span class="top__user-name">{{ auth.displayName }}</span>
          <svg class="top__chevron" viewBox="0 0 16 16" aria-hidden="true">
            <path :d="NAV_ICONS.chevron" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ auth.tenantLabel }} · {{ auth.workspaceLabel }}</el-dropdown-item>
            <el-dropdown-item command="notifications">通知中心</el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.top {
  flex: none;
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 16px 0 8px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
}
.top__nav {
  display: flex;
  align-items: stretch;
  height: 100%;
  gap: 2px;
  overflow: hidden;
}
.top__item {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  color: var(--text-body);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
  transition: color var(--t-color) var(--ease);
}
.top__item:hover {
  color: var(--text-title);
}
.top__item--active {
  color: var(--color-primary);
  font-weight: 500;
}
.top__item--active::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 0;
  height: 2px;
  border-radius: 1px;
  background: var(--color-primary);
}
.top__right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.top__search {
  position: relative;
  display: flex;
  align-items: center;
  width: 200px;
}
.top__search-icon {
  position: absolute;
  left: 8px;
  width: 13px;
  height: 13px;
  color: var(--text-weak);
  pointer-events: none;
}
.top__search-input {
  width: 100%;
  height: 28px;
  padding: 0 8px 0 26px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  color: var(--text-body);
  font-size: 12px;
  font-family: inherit;
  outline: none;
  transition:
    border-color var(--t-color) var(--ease),
    background var(--t-color) var(--ease);
}
.top__search-input::placeholder {
  color: var(--text-weak);
}
.top__search-input:hover {
  border-color: #c9cdd4;
}
.top__search-input:focus {
  background: var(--bg-card);
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(43, 111, 229, 0.12);
}
.top__icon {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  color: var(--text-muted-aa);
  cursor: pointer;
  transition:
    background var(--t-color) var(--ease),
    color var(--t-color) var(--ease);
}
.top__icon:hover {
  background: var(--bg-hover);
  color: var(--text-body);
}
.top__icon svg {
  width: 15px;
  height: 15px;
}
.top__dot {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-danger);
  border: 1px solid var(--bg-card);
}
.top__user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 6px 0 4px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition: background var(--t-color) var(--ease);
}
.top__user:hover {
  background: var(--bg-hover);
}
.top__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 600;
}
.top__user-name {
  font-size: 12px;
  color: var(--text-body);
  max-width: 88px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.top__chevron {
  width: 12px;
  height: 12px;
  color: var(--text-weak);
}
</style>
