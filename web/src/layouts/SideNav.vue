<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NAV_GROUPS, NAV_ICONS, type NavItem } from './nav'
import { navVisible, type NavContext } from './navAccess'

/**
 * 侧栏 200px（DESIGN §3.2）：
 * 分组标题 + 40px 条目；当前项浅蓝底 + 左 2px 主色 + 主色文字。
 * 条目在「路由未注册 / 无功能码」时直接不渲染（三态里的 —），不做点了才 403。
 */
const props = withDefaults(
  defineProps<{ todoCount?: number; unreadCount?: number }>(),
  { todoCount: 0, unreadCount: 0 },
)

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const helpOpen = ref(false)

function pathOf(to: string): string {
  return to.split('?')[0] ?? to
}

/** 侧栏准入：与顶栏共用 navVisible（唯一判定，见 navAccess.ts） */
function permitted(item: NavItem): boolean {
  const ctx: NavContext = {
    hasRoute: (name) => router.hasRoute(name),
    canAny: (codes) => auth.canAny(codes),
    vendorScoped: auth.user?.vendorId != null,
  }
  return navVisible(item, ctx)
}

const visibleGroups = computed(() =>
  NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter(permitted),
  })).filter((group) => group.items.length > 0),
)

const allItems = computed(() => visibleGroups.value.flatMap((group) => group.items))

/** 带 query 的条目（如 ownerId=me）优先精确匹配，避免与同路径条目同时高亮 */
const matchedQueryItem = computed(() =>
  allItems.value.find((item) => {
    if (!item.to.includes('?')) return false
    const [path, queryString] = item.to.split('?')
    if (route.path !== path) return false
    const params = new URLSearchParams(queryString)
    for (const [key, value] of params.entries()) {
      if (String(route.query[key] ?? '') !== value) return false
    }
    return true
  }),
)

const activePath = computed(() => {
  const candidates = allItems.value
    .filter((item) => !item.to.includes('?'))
    .map((item) => pathOf(item.to))
    .filter((path) => route.path === path || route.path.startsWith(`${path}/`))
  return candidates.sort((a, b) => b.length - a.length)[0] ?? ''
})

function isActive(item: NavItem): boolean {
  if (matchedQueryItem.value) {
    if (item.to === matchedQueryItem.value.to) return true
    if (item.to.includes('?')) return false
    if (pathOf(item.to) === pathOf(matchedQueryItem.value.to)) return false
  }
  if (item.to.includes('?')) return false
  return pathOf(item.to) === activePath.value
}

function badgeOf(item: NavItem): number {
  if (item.badge === 'todo') return props.todoCount
  if (item.badge === 'unread') return props.unreadCount
  return 0
}

const domainIds = computed(() => auth.user?.domainIds ?? [])

const scopeLabel = computed(() => {
  const current = route.query.domainId
  if (!current) return '全部可见域'
  return `域 #${String(current)}`
})

async function switchDomain(command: string | number | object): Promise<void> {
  const query = { ...route.query }
  if (command === 'all') delete query.domainId
  else query.domainId = String(command)
  delete query.page
  await router.replace({ path: route.path, query })
}

const roleLabel = computed(() => auth.user?.roles?.join(' / ') ?? '')

async function onUserCommand(command: string | number | object): Promise<void> {
  if (command === 'logout') {
    auth.logout()
    await router.replace({ path: '/login' })
  }
  if (command === 'notifications') await router.push('/notifications')
}
</script>

<template>
  <aside class="side" aria-label="主导航">
    <div class="side__brand">
      <span class="side__mark" aria-hidden="true">
        <svg viewBox="0 0 20 20">
          <path d="M3 5.4 10 2l7 3.4-7 3.4-7-3.4Z" fill="currentColor" opacity=".9" />
          <path d="M3 10 10 6.6l7 3.4-7 3.4-7-3.4Z" fill="currentColor" opacity=".55" />
          <path d="M3 14.6 10 11.2l7 3.4L10 18l-7-3.4Z" fill="currentColor" opacity=".28" />
        </svg>
      </span>
      <div class="side__brand-text">
        <p class="side__brand-title">DevOps 研发管理</p>
        <p class="side__brand-sub">供应链平台</p>
      </div>
    </div>

    <el-dropdown trigger="click" class="side__ws-drop" @command="switchDomain">
      <button type="button" class="side__ws" :title="`租户：${auth.tenantLabel}`">
        <span class="side__ws-label">{{ auth.tenantLabel }}</span>
        <span class="side__ws-scope">{{ scopeLabel }}</span>
        <svg class="side__ws-chevron" viewBox="0 0 16 16" aria-hidden="true">
          <path :d="NAV_ICONS.chevron" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled class="side__ws-hint">工作区范围（切换后列表按新范围刷新）</el-dropdown-item>
          <el-dropdown-item command="all" :class="{ 'is-current': !route.query.domainId }">
            全部可见域
          </el-dropdown-item>
          <el-dropdown-item
            v-for="id in domainIds"
            :key="id"
            :command="String(id)"
            :class="{ 'is-current': String(route.query.domainId ?? '') === String(id) }"
          >
            域 #{{ id }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <nav class="side__nav">
      <div v-for="group in visibleGroups" :key="group.title" class="side__group">
        <p class="side__group-title">{{ group.title }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="side__item"
          :class="{ 'side__item--active': isActive(item) }"
        >
          <svg class="side__icon" viewBox="0 0 16 16" aria-hidden="true">
            <path
              :d="NAV_ICONS[item.icon] ?? NAV_ICONS.list"
              fill="none"
              stroke="currentColor"
              stroke-width="1.3"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span class="side__label">{{ item.label }}</span>
          <span v-if="badgeOf(item) > 0" class="side__badge">{{ badgeOf(item) > 99 ? '99+' : badgeOf(item) }}</span>
        </RouterLink>
      </div>

      <p v-if="visibleGroups.length === 0" class="side__empty">
        暂无可访问的模块，请联系管理员分配权限。
      </p>
    </nav>

    <div class="side__foot">
      <div class="side__foot-actions">
        <button type="button" class="side__ghost" @click="helpOpen = true">
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path :d="NAV_ICONS.help" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          帮助
        </button>
        <button
          type="button"
          class="side__ghost side__ghost--icon"
          :title="props.unreadCount > 0 ? `${props.unreadCount} 条未读通知` : '通知'"
          @click="router.push('/notifications')"
        >
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path :d="NAV_ICONS.bell" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <i v-if="props.unreadCount > 0" class="side__dot" />
        </button>
      </div>

      <el-dropdown trigger="click" class="side__user-drop" @command="onUserCommand">
        <button type="button" class="side__user">
          <span class="side__avatar">{{ auth.initials }}</span>
          <span class="side__user-text">
            <span class="side__user-name">{{ auth.displayName }}</span>
            <span class="side__user-role">{{ roleLabel || '未分配角色' }}</span>
          </span>
          <svg class="side__user-chevron" viewBox="0 0 16 16" aria-hidden="true">
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

    <el-dialog v-model="helpOpen" title="使用帮助" width="420px" align-center append-to-body>
      <div class="help">
        <p><strong>提报需求</strong>：需求域 → 提报需求；业务需求需审批通过后才进入评审。</p>
        <p><strong>能力受限于角色</strong>：看不到的入口说明当前角色无该功能码；只读入口会标注数据范围。</p>
        <p><strong>提交被拒绝</strong>：门禁/准入未通过时，页面顶部常驻清单会逐项说明原因与修复方式。</p>
        <p><strong>联系管理员</strong>：权限、组织与租户问题请联系平台管理员。</p>
      </div>
    </el-dialog>
  </aside>
</template>

<style scoped>
.side {
  flex: none;
  width: 200px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border-right: 1px solid var(--border);
}
.side__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 14px;
  border-bottom: 1px solid var(--divider);
  flex: none;
}
.side__mark {
  display: inline-flex;
  width: 24px;
  height: 24px;
  color: var(--color-primary);
}
.side__mark svg {
  width: 24px;
  height: 24px;
}
.side__brand-text {
  min-width: 0;
}
.side__brand-title {
  font-size: 13px;
  font-weight: 600;
  line-height: 17px;
  color: var(--text-title);
  white-space: nowrap;
}
.side__brand-sub {
  font-size: 11px;
  line-height: 14px;
  color: var(--text-weak);
}
.side__ws-drop {
  display: block;
  margin: 10px 10px 4px;
}
.side__ws {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 36px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition:
    background var(--t-color) var(--ease),
    border-color var(--t-color) var(--ease);
}
.side__ws:hover {
  background: var(--bg-hover);
  border-color: #c9cdd4;
}
.side__ws-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title);
  max-width: 92px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.side__ws-scope {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.side__ws-chevron {
  width: 12px;
  height: 12px;
  flex: none;
  color: var(--text-weak);
}
.side__ws-hint {
  font-size: 11px;
  color: var(--text-weak);
}
.side__nav {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 12px;
}
.side__group + .side__group {
  margin-top: 10px;
}
.side__group-title {
  padding: 8px 8px 4px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-weak);
  letter-spacing: 0.02em;
}
.side__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  color: var(--text-body);
  font-size: 13px;
  text-decoration: none;
  transition:
    background var(--t-color) var(--ease),
    color var(--t-color) var(--ease);
}
.side__item:hover {
  background: var(--bg-hover);
}
.side__item--active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}
.side__item--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 2px;
  border-radius: 1px;
  background: var(--color-primary);
}
.side__icon {
  width: 16px;
  height: 16px;
  flex: none;
  color: currentColor;
  opacity: 0.85;
}
.side__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.side__badge {
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--color-danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.side__empty {
  padding: 16px 8px;
  font-size: 12px;
  line-height: 19px;
  color: var(--text-weak);
}
.side__foot {
  flex: none;
  border-top: 1px solid var(--divider);
  padding: 8px;
}
.side__foot-actions {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
}
.side__ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  position: relative;
  transition:
    background var(--t-color) var(--ease),
    color var(--t-color) var(--ease);
}
.side__ghost:hover {
  background: var(--bg-hover);
  color: var(--text-body);
}
.side__ghost svg {
  width: 14px;
  height: 14px;
}
.side__ghost--icon {
  margin-left: auto;
  padding: 0 6px;
}
.side__dot {
  position: absolute;
  top: 5px;
  right: 4px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-danger);
  border: 1px solid var(--bg-card);
}
.side__user-drop {
  display: block;
}
.side__user {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 6px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: background var(--t-color) var(--ease);
}
.side__user:hover {
  background: var(--bg-hover);
}
.side__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 50%;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 600;
}
.side__user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.side__user-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.side__user-role {
  font-size: 11px;
  color: var(--text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 122px;
}
.side__user-chevron {
  width: 12px;
  height: 12px;
  margin-left: auto;
  flex: none;
  color: var(--text-weak);
}
.help p {
  font-size: 13px;
  line-height: 21px;
  color: var(--text-body);
}
.help p + p {
  margin-top: 8px;
}
.help strong {
  color: var(--text-title);
}
</style>
