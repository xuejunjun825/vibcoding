import type { RouteRecordRaw } from 'vue-router'

/**
 * 基座路由：登录、403、404、通知。
 * 各业务域路由由 routes/{req,flow,deliver}.routes.ts 提供（T8/T9 写），
 * router/index.ts 用 glob 汇总，所以文件边界天然不重叠。
 */
const LoginView = () => import('@/views/base/LoginView.vue')
const ForbiddenView = () => import('@/views/base/ForbiddenView.vue')
const NotFoundView = () => import('@/views/base/NotFoundView.vue')
const NotificationsView = () => import('@/views/base/NotificationsView.vue')

/** 不套 AppShell 的页面 */
export const publicRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录', requiresAuth: false, blank: true },
  },
]

/** 套在 AppShell 里的基座页面 */
export const shellRoutes: RouteRecordRaw[] = [
  {
    path: '/notifications',
    name: 'notifications',
    component: NotificationsView,
    meta: { title: '通知', requiresAuth: true, navKey: 'notifications' },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: ForbiddenView,
    meta: { title: '无权访问', requiresAuth: false },
  },
  {
    path: '/404',
    name: 'not-found',
    component: NotFoundView,
    meta: { title: '页面不存在', requiresAuth: false },
  },
]

// 组件总览只在开发期存在，生产构建不会包含（也不进路由表）
if (import.meta.env.DEV) {
  shellRoutes.push({
    path: '/__gallery',
    name: 'component-gallery',
    component: () => import('@/views/base/ComponentGallery.vue'),
    meta: { title: '组件总览', requiresAuth: false },
  })
}

export default shellRoutes
