import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { useAuthStore } from '@/stores/auth'
import { landingRoute } from './landing'
import { publicRoutes, shellRoutes } from './routes/base.routes'
import './types'

/**
 * `meta.internalOnly` 的类型跟随守卫实现声明（TECHNICAL §4.5）：
 * router/types.ts 由另一任务维护，这里做接口合并，避免并发写同一文件。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 仅内部账号可进：厂商账号深链 → 403（code=INTERNAL_ONLY） */
    internalOnly?: boolean
  }
}

/**
 * 各域路由文件由不同的开发线维护，用 glob 汇总：
 * 新增一个域只需要新建 routes/<domain>.routes.ts 并默认导出一个数组。
 */
const modules = import.meta.glob<{ default: RouteRecordRaw[] }>('./routes/*.ts', { eager: true })

const domainRoutes: RouteRecordRaw[] = []
for (const [path, module] of Object.entries(modules)) {
  if (path.endsWith('/base.routes.ts')) continue
  if (Array.isArray(module.default)) domainRoutes.push(...module.default)
}

const NotFoundView = () => import('@/views/base/NotFoundView.vue')

export const routes: RouteRecordRaw[] = [
  ...publicRoutes,
  {
    path: '/',
    component: AppShell,
    children: [
      ...shellRoutes,
      ...domainRoutes,
      {
        path: '/:pathMatch(.*)*',
        name: 'catch-all',
        component: NotFoundView,
        meta: { title: '页面不存在', requiresAuth: false },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 16 }
    return { top: 0 }
  },
})

function requiredCodes(meta: unknown): string[] {
  const raw = (meta as { functionCode?: string | string[] } | undefined)?.functionCode
  if (!raw) return []
  return Array.isArray(raw) ? raw : [raw]
}

router.beforeEach((to) => {
  const auth = useAuthStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (to.path === '/') {
    return auth.isAuthenticated ? landingRoute(router, auth.homeRoute) : { path: '/login' }
  }

  if (!requiresAuth) {
    // 已登录还去登录页 → 回默认落点，避免“登录后又看到登录页”
    if (to.path === '/login' && auth.isAuthenticated) return landingRoute(router, auth.homeRoute)
    return true
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 展示层策略先于功能码：厂商账号不作用例，深链也不让进（不新增权限码）
  if (to.meta.internalOnly && auth.user?.vendorId != null) {
    return { path: '/403', query: { from: to.fullPath, code: 'INTERNAL_ONLY' } }
  }

  const codes = requiredCodes(to.meta)
  if (codes.length > 0 && !auth.canAny(codes)) {
    return { path: '/403', query: { from: to.fullPath, code: codes.join(',') } }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · DevOps 研发管理平台` : 'DevOps 研发管理平台'
})

export default router
