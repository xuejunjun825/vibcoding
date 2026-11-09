import type { RouteRecordRaw } from 'vue-router'

/**
 * CCode 域路由（T8）。
 *
 * 只登记「路由 → 页面 → 权限码」这一层；name 与 layouts/nav.ts 的 routeName 一致，
 * 侧栏按路由是否注册决定显隐（未注册的入口自动隐藏，不留死链）。
 * 核验状态由服务端派生，页面没有第二条判定路径。
 */
const codeRoutes: RouteRecordRaw[] = [
  {
    path: '/code-policies',
    name: 'code-policies',
    component: () => import('@/views/code/CodePolicyView.vue'),
    meta: { title: '分支策略', navKey: 'code-policies', functionCode: 'CCODE_POLICY_VIEW' },
  },
]

export default codeRoutes
