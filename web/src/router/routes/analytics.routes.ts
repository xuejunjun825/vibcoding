import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 分析域路由（T6 · TECHNICAL §7.1）：岗位工作台 / 数据看板 / 自助报表。
 * 由 `router/index.ts` 的 `import.meta.glob('./routes/*.ts')` 自动挂载，
 * 新增域不需要改入口文件。
 *
 * 组件为什么按 glob 惰性解析：四个视图由 T7/T8 并行落地，此刻文件还不存在，
 * 静态 import 会让 `vue-tsc` 直接 TS2307。这里按文件名解析；页面确实缺失时
 * **导航直接失败并抛出可读错误**，不挂空白占位页去伪装「已实现」。
 *
 * name / navKey 必须与 `layouts/nav.ts` 一致（侧栏按“路由是否注册”显隐）；
 * functionCode 只是入口守卫，服务端逐请求鉴权（这里不复制权限矩阵）。
 */
// 相对本文件：src/router/routes → src/views
const VIEWS = import.meta.glob<{ default: Component }>('../../views/**/*.vue')

function lazyView(file: string): () => Promise<Component> {
  const key = Object.keys(VIEWS).find((path) => path.endsWith(`/${file}`))
  if (!key) {
    return () =>
      Promise.reject(
        new Error(`[analytics.routes] 视图 ${file} 尚未生成：该页面归 T7/T8，路由已注册但不挂占位页`),
      )
  }
  return () => VIEWS[key]().then((module) => module.default)
}

const analyticsRoutes: RouteRecordRaw[] = [
  {
    path: '/portal',
    name: 'portal',
    component: lazyView('portal/PortalView.vue'),
    meta: {
      title: '岗位工作台',
      navKey: 'portal',
      // 工作台与看板共用读码：能看工作台的人就能看自己的数据看板
      functionCode: 'PORTAL_VIEW',
    },
  },
  {
    path: '/dashboard',
    name: 'analytics-dashboard',
    component: lazyView('analytics/DashboardView.vue'),
    meta: { title: '数据看板', navKey: 'analytics-dashboard', functionCode: 'PORTAL_VIEW' },
  },
  {
    path: '/reports',
    name: 'report-list',
    component: lazyView('reports/ReportListView.vue'),
    meta: { title: '自助报表', navKey: 'report-list', functionCode: 'REPORT_VIEW' },
  },
  {
    // `:id` 收 `new` 或纯数字：/reports/abc 落 404，而不是进编辑器再炸
    path: '/reports/:id(new|\\d+)',
    name: 'report-editor',
    component: lazyView('reports/ReportEditorView.vue'),
    meta: {
      title: '报表编辑器',
      navKey: 'report-list',
      parentPath: '/reports',
      functionCode: 'REPORT_VIEW',
    },
  },
]

export default analyticsRoutes
