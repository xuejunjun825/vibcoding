<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { navVisible, type NavContext, type NavGuard } from '@/layouts/navAccess'
import '@/views/admin/admin-shared.css'

/**
 * 系统管理二级骨架（DESIGN P12 / §3.2）。
 * 左子菜单 + 右内容；入口与侧栏/顶栏共用 `navVisible`（唯一判定）：
 * 页面码（MENU 层）+ 功能码（FUNCTION 层）双条件，缺一项即不渲染 ——
 * 原来这里自己写 `canAny`，正是「菜单看得见、点开 403」的来源（AC-11）。
 */
interface AdminNavItem extends NavGuard {
  to: string
  label: string
  hint?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const perm = usePermission()

const items: AdminNavItem[] = [
  { to: '/admin/orgs', routeName: 'admin-orgs', label: '组织与租户', functionCode: ['tenant:manage', 'org:manage'], pageCode: 'admin.orgs', hint: '租户隔离形态、组织树与负责人' },
  { to: '/admin/users', routeName: 'admin-users', label: '用户与角色', functionCode: 'org:manage', pageCode: 'admin.users', hint: '账号、角色绑定、授权域与厂商归属' },
  { to: '/admin/vendors', routeName: 'admin-vendors', label: '厂商与域', functionCode: 'org:manage', pageCode: 'admin.vendors', hint: '7 个业务域与软件厂商的对应关系' },
  // 顺序照 DESIGN §3.2 的原子导航位置：权限总览（只读）在前，角色与权限（写入口）在后
  { to: '/admin/permissions', routeName: 'admin-permissions', label: '权限总览', functionCode: 'org:manage', pageCode: 'admin.permissions', hint: '10 角色 × 功能码只读总览，授权在「角色与权限」' },
  { to: '/admin/roles', routeName: 'admin-roles', label: '角色与权限', functionCode: 'org:manage', pageCode: 'admin.roles', hint: '角色列表与三级授权：功能码 / 页面 / 动作' },
  { to: '/admin/workflows', routeName: 'admin-workflows', label: '流程定义', functionCode: 'workflow:manage', pageCode: 'admin.workflows', hint: '审批节点图、发布校验与版本固化' },
  { to: '/admin/api-clients', routeName: 'admin-api-clients', label: 'API 客户端', functionCode: 'apiclient:manage', pageCode: 'admin.api-clients', hint: '开放 API 的 AppKey、scopes、限流与 IP 白名单' },
  { to: '/admin/integrations', routeName: 'admin-integrations', label: '集成配置', functionCode: 'integration:manage', pageCode: 'admin.integrations', hint: 'Jenkins · GitLab · Sonar · Nexus 四槽接线与同步日志' },
  { to: '/admin/metrics', routeName: 'admin-metrics', label: '指标字典', functionCode: 'metric:manage', pageCode: 'admin.metrics', hint: '指标编码、公式与生效版本' },
  { to: '/admin/audit', routeName: 'admin-audit', label: '审计日志', functionCode: 'audit:view', pageCode: 'admin.audit', hint: '越权、强制放行与关键写操作留痕' },
]

const ctx: NavContext = {
  hasRoute: (name) => router.hasRoute(name),
  canAny: (codes) => perm.canAny(codes),
  hasMenu: (code) => perm.canMenu(code),
  vendorScoped: auth.user?.vendorId != null,
  vendorPm: auth.isVendorPm,
}

const visibleItems = computed(() => items.filter((item) => navVisible(item, ctx)))

/** 最长前缀命中：/admin/roles/:id/permissions 仍归属「角色与权限」 */
const current = computed(() => {
  const hits = items.filter((item) => route.path === item.to || route.path.startsWith(`${item.to}/`))
  return hits.sort((a, b) => b.to.length - a.to.length)[0]
})
</script>

<template>
  <div class="adm">
    <aside class="adm-nav" aria-label="系统管理导航">
      <router-link
        v-for="item in visibleItems"
        :key="item.to"
        :to="item.to"
        class="adm-nav__item"
        active-class="is-active"
      >
        {{ item.label }}
      </router-link>
      <p v-if="visibleItems.length === 0" class="adm-hint adm-nav__empty">
        暂无可访问的系统管理页，请联系平台管理员。
      </p>
    </aside>
    <div class="adm-body">
      <header class="adm-head">
        <div>
          <div class="adm-head__title">
            <h1 class="t-page-title">{{ current?.label || '系统管理' }}</h1>
          </div>
          <p class="adm-head__desc">{{ current?.hint || '平台级配置：改动影响本租户全部用户' }}</p>
        </div>
        <slot name="actions" />
      </header>
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.adm-nav__empty {
  padding: 4px 10px 6px;
}
</style>
