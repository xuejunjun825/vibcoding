import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { useAuthStore } from '@/stores/auth'
import { pageAdmission } from '@/layouts/pageRegistry'
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
    /**
     * `internalOnly` 的窄例外（TECHNICAL §3.4）：只对 VENDOR_PM 放开指定页，
     * 与 `navAccess.navVisible` 同一判定，避免「侧栏可见、深链 403」互相打脸。
     */
    vendorPmAllowed?: boolean
    /**
     * 页面码（MENU 层，r18）：路由显式声明的准入主体，与 nav 的 pageCode
     * 一起进 `pageRegistry` 做 any-of。空/缺省 = 该路由不按页面码拦。
     */
    pageCode?: string | string[]
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

/**
 * 路由页面码准入：判定实现放在 `pageRegistry.pageAdmission`（纯函数、可单测），
 * 守卫只负责「先取集合 → 不通过就跳 403」。
 */
async function admitByPage(
  auth: ReturnType<typeof useAuthStore>,
  to: { name?: unknown; meta: unknown },
  fullPath: string,
): Promise<true | { path: string; query: Record<string, string> }> {
  const routeName = typeof to.name === 'string' ? to.name : null
  const declared = (to.meta as { pageCode?: string | string[] } | undefined)?.pageCode
  const probe = pageAdmission(routeName, declared, { loaded: true, canAnyMenu: () => true })
  if (probe.codes.length === 0) return true

  // 刷新直开深链时本地没有集合，先取一次
  await auth.ensurePermissions()
  const gate = pageAdmission(routeName, declared, {
    loaded: auth.permissionsLoaded,
    canAnyMenu: (codes) => auth.canAnyMenu(codes),
  })
  if (gate.allowed) return true
  return { path: '/403', query: { from: fullPath, code: gate.codes.join(',') } }
}

router.beforeEach(async (to) => {
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

  // 展示层策略先于功能码：厂商账号不作用例，深链也不让进（不新增权限码）。
  // 例外只认角色码 VENDOR_PM（`vendorPmAllowed`），不认 vendorId——否则整个厂商域都会放行。
  if (
    to.meta.internalOnly &&
    auth.user?.vendorId != null &&
    !(to.meta.vendorPmAllowed && auth.isVendorPm)
  ) {
    return { path: '/403', query: { from: to.fullPath, code: 'INTERNAL_ONLY' } }
  }

  // 页面码准入（MENU 层，AC-16）
  const pageGate = await admitByPage(auth, to, to.fullPath)
  if (pageGate !== true) return pageGate

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
