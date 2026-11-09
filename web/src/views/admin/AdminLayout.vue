<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { usePermission } from '@/composables/usePermission'
import '@/views/admin/admin-shared.css'

/**
 * 系统管理二级骨架（DESIGN P12）。
 * 左子菜单 + 右内容；入口按权限矩阵三态渲染（无权限直接不渲染，而不是点进去 403）。
 */
interface AdminNavItem {
  to: string
  label: string
  codes?: string[]
  hint: string
}

const route = useRoute()
const perm = usePermission()

const items: AdminNavItem[] = [
  { to: '/admin/orgs', label: '组织与租户', codes: ['tenant:manage', 'org:manage'], hint: '租户隔离形态、组织树与负责人' },
  { to: '/admin/users', label: '用户与角色', codes: ['org:manage'], hint: '账号、角色绑定、授权域与厂商归属' },
  { to: '/admin/vendors', label: '厂商与业务域', codes: ['org:manage'], hint: '7 个业务域与软件厂商的对应关系' },
  { to: '/admin/permissions', label: '权限矩阵', hint: '10 角色 × 功能码，只读可断言' },
  { to: '/admin/workflows', label: '流程定义', codes: ['workflow:manage'], hint: '审批节点图、发布校验与版本固化' },
  { to: '/admin/api-clients', label: 'API 客户端', codes: ['apiclient:manage'], hint: '开放 API 的 AppKey、scopes、限流与 IP 白名单' },
  { to: '/admin/integrations', label: '集成配置', codes: ['integration:manage'], hint: 'Jenkins · GitLab · Sonar · Nexus 四槽接线与同步日志' },
  { to: '/admin/metrics', label: '指标字典', codes: ['metric:manage'], hint: '指标编码、公式与生效版本' },
  { to: '/admin/audit', label: '审计日志', codes: ['audit:view'], hint: '越权、强制放行与关键写操作留痕' },
]

const visibleItems = computed(() =>
  items.filter((item) => !item.codes || perm.canAny(item.codes)),
)

const current = computed(() => items.find((item) => route.path.startsWith(item.to)))
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
