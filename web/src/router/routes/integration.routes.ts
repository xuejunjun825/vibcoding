import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 集成域路由（M7 · TECHNICAL §8.1 / DESIGN §3.2）。由 `router/index.ts` 的
 * `import.meta.glob('./routes/*.ts')` 自动挂载，因此新增本域不需要改入口文件。
 *
 * 三条约定：
 * - `name` 与 `layouts/nav.ts` 的 `routeName` 一一对应（侧栏按"路由是否注册"显隐，无死链）；
 * - `functionCode: 'integration:manage'` 只是**入口**守卫（无该码直接跳 403），
 *   服务端仍逐请求鉴权 —— 前端不复制权限矩阵；
 * - 深链参数由页面自己读（`?tab=config|admission|logs&correlationId=`），路由只提供一条路径，
 *   tab 不进路由段：一个页面三 tab，刷新/分享都保持同一地址，也不需要三份守卫。
 *
 * 组件用 glob 惰性解析而不是静态 import：`AdminIntegrationsView.vue` 由 T9 并行落地，
 * 此刻文件还不存在，静态 import 会让 `vue-tsc` 直接 TS2307 红掉基座；按文件名解析后，
 * 页面缺失时**导航直接失败并抛出可读错误**（响亮失败），不挂空白占位页假装已实现。
 * T9 落文件后本文件无需改动。
 */
// 相对本文件：src/router/routes → src/views
const VIEWS = import.meta.glob<{ default: Component }>('../../views/**/*.vue')

function lazyView(file: string): () => Promise<Component> {
  const key = Object.keys(VIEWS).find((path) => path.endsWith(`/${file}`))
  if (!key) {
    return () =>
      Promise.reject(
        new Error(`[integration.routes] 视图 ${file} 尚未生成：该页面归 T9，路由已注册但不挂占位页`),
      )
  }
  return () => VIEWS[key]().then((module) => module.default)
}

const integrationRoutes: RouteRecordRaw[] = [
  // `/admin` 父路由（含 AdminLayout 二级菜单）由 deliver.routes.ts 拥有，本域不重复声明同一
  // 路径的父记录（同名父级会让 /admin/* 出现两份匹配）。这里把 AdminLayout 挂在本条路径上，
  // 渲染结果与其他管理页完全一致（同一组件、同一 admin-shared.css），且只需一处子记录。
  {
    path: '/admin/integrations',
    component: () => import('@/views/admin/AdminLayout.vue'),
    children: [
      {
        path: '',
        name: 'admin-integrations',
        component: lazyView('admin/AdminIntegrationsView.vue'),
        meta: {
          title: '集成配置',
          navKey: 'admin-integrations',
          functionCode: 'integration:manage',
        },
      },
    ],
  },
]

export default integrationRoutes
