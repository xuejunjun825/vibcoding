import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 质量域路由（T9 · TECHNICAL §7.1）。由 `router/index.ts` 的
 * `import.meta.glob('./routes/*.ts')` 自动挂载，无需改入口。
 *
 * 两条约定与 r2 一致：
 * - name 与 `layouts/nav.ts` 的 routeName 一一对应，侧栏按“路由是否注册”显隐；
 * - functionCode 只是入口守卫（服务端仍逐请求鉴权）。
 *
 * 组件为什么用 glob 解析而不是 `() => import('@/views/quality/Xxx.vue')`：
 * 本页面的 8 个视图由 T10/T11 并行落地，此刻文件还不存在，静态 import 会让
 * `vue-tsc` 直接报 TS2307 —— 基座不能因为同批次页面未就绪而红。因此这里按文件名
 * 惰性解析；页面确实缺失时**导航直接失败并抛出可读错误**（响亮失败），不挂空白占位页
 * 去伪装“已实现”。T10/T11 落文件后无需改动本文件。
 */
// 相对本文件：src/router/routes → src/views
const VIEWS = import.meta.glob<{ default: Component }>('../../views/**/*.vue')

export function lazyView(file: string): () => Promise<Component> {
  const key = Object.keys(VIEWS).find((path) => path.endsWith(`/${file}`))
  if (!key) {
    return () =>
      Promise.reject(
        new Error(
          `[quality.routes] 视图 ${file} 尚未生成：该页面归 T10/T11，路由已注册但不挂占位页`,
        ),
      )
  }
  return () => VIEWS[key]().then((module) => module.default)
}

const qualityRoutes: RouteRecordRaw[] = [
  {
    path: '/test-strategies',
    name: 'test-strategies',
    component: lazyView('quality/TestStrategiesView.vue'),
    meta: { title: '测试策略', navKey: 'test-strategies', functionCode: 'strategy:manage' },
  },
  {
    path: '/test-reports',
    name: 'test-reports',
    component: lazyView('quality/TestReportsView.vue'),
    meta: { title: '测试报告', navKey: 'test-reports' },
  },
  {
    path: '/test-reports/:id(\\d+)',
    name: 'test-report-detail',
    component: lazyView('quality/TestReportDetailView.vue'),
    meta: { title: '测试报告', navKey: 'test-reports', parentPath: '/test-reports' },
  },
  {
    path: '/quality-gates',
    name: 'quality-gates',
    component: lazyView('quality/QualityGatesView.vue'),
    meta: { title: '质量门', navKey: 'quality-gates', functionCode: 'gate:view' },
  },
  {
    // 门的配置与快照都以 Release 为键（一份配置 + 不可变快照）
    path: '/quality-gates/:releaseId(\\d+)',
    name: 'quality-gate-detail',
    component: lazyView('quality/QualityGateDetailView.vue'),
    meta: {
      title: '质量门详情',
      navKey: 'quality-gates',
      parentPath: '/quality-gates',
      functionCode: 'gate:view',
    },
  },
  {
    path: '/test-envs',
    name: 'test-envs',
    component: lazyView('quality/TestEnvsView.vue'),
    meta: { title: '环境台账', navKey: 'test-envs', functionCode: 'env:manage' },
  },
  {
    path: '/quality-metrics',
    name: 'quality-metrics',
    component: lazyView('quality/QualityMetricsView.vue'),
    meta: { title: '质量指标', navKey: 'quality-metrics', functionCode: 'qualitymetric:view' },
  },
  {
    // 发布决策记录挂在版本下；与 /releases/:id 靠路径具体度区分，不依赖声明顺序
    path: '/releases/:id(\\d+)/go-no-go',
    name: 'release-go-no-go',
    component: lazyView('release/GoNoGoView.vue'),
    meta: {
      title: 'Go/No-Go',
      parentPath: '/releases',
      functionCode: 'review:manage',
    },
  },
]

export default qualityRoutes
