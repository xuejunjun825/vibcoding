import type { RouteRecordRaw } from 'vue-router'

/**
 * 交付域 + 系统管理路由（T9）。
 *
 * 约定（nav.ts 注释 / DESIGN §3.1）：
 * - name 必须与 layouts/nav.ts 里的 routeName 一一对应，侧栏按“路由是否注册”决定显隐；
 * - 路径一律绝对路径（与 base.routes.ts 一致，挂在 AppShell children 下）；
 * - 详情页放在列表页之后声明，`/defects/board` 必须早于 `/defects/:id`；
 * - functionCode 只做入口守卫（服务端仍逐请求鉴权），没有权限的入口直接 403，
 *   绝不出现“点进去才 403”的三态反模式。
 */
const deliverRoutes: RouteRecordRaw[] = [
  {
    path: '/defects',
    name: 'defects',
    component: () => import('@/views/deliver/DefectListView.vue'),
    meta: { title: '缺陷', navKey: 'defects' },
  },
  {
    path: '/defects/board',
    name: 'defect-board',
    component: () => import('@/views/deliver/DefectBoardView.vue'),
    meta: { title: '质量看板', navKey: 'defect-board', functionCode: 'defect:board' },
  },
  {
    path: '/defects/:id(\\d+)',
    name: 'defect-detail',
    component: () => import('@/views/deliver/DefectDetailView.vue'),
    meta: { title: '缺陷详情', navKey: 'defects', parentPath: '/defects' },
  },
  {
    path: '/iterations',
    name: 'iterations',
    component: () => import('@/views/deliver/IterationsView.vue'),
    meta: { title: '迭代', navKey: 'iterations' },
  },
  {
    path: '/iterations/:id(\\d+)/board',
    name: 'iteration-board',
    component: () => import('@/views/deliver/IterationBoardView.vue'),
    meta: { title: '迭代看板', navKey: 'iterations', parentPath: '/iterations' },
  },
  {
    path: '/testcases',
    name: 'testcases',
    component: () => import('@/views/deliver/TestCaseLibraryView.vue'),
    meta: { title: '用例库', navKey: 'testcases' },
  },
  {
    path: '/test-plans',
    name: 'test-plans',
    component: () => import('@/views/deliver/TestPlansView.vue'),
    meta: { title: '测试计划', navKey: 'test-plans' },
  },
  {
    path: '/test-plans/:id(\\d+)/execute',
    name: 'test-plan-execute',
    component: () => import('@/views/deliver/TestPlanExecuteView.vue'),
    meta: { title: '计划执行', navKey: 'test-plans', parentPath: '/test-plans' },
  },
  {
    path: '/traceability',
    name: 'traceability',
    component: () => import('@/views/deliver/TraceabilityView.vue'),
    meta: { title: '追溯矩阵', navKey: 'traceability', functionCode: 'trace:view' },
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    redirect: { name: 'admin-orgs' },
    children: [
      {
        path: 'orgs',
        name: 'admin-orgs',
        component: () => import('@/views/admin/AdminOrgsView.vue'),
        meta: {
          title: '组织与租户',
          navKey: 'admin-orgs',
          functionCode: ['tenant:manage', 'org:manage'],
        },
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/AdminUsersView.vue'),
        meta: { title: '用户与角色', navKey: 'admin-users', functionCode: 'org:manage' },
      },
      {
        path: 'vendors',
        name: 'admin-vendors',
        component: () => import('@/views/admin/AdminVendorsView.vue'),
        meta: { title: '厂商与业务域', navKey: 'admin-vendors', functionCode: 'org:manage' },
      },
      {
        path: 'permissions',
        name: 'admin-permissions',
        component: () => import('@/views/admin/AdminPermissionsView.vue'),
        meta: { title: '权限矩阵', navKey: 'admin-permissions' },
      },
      {
        path: 'workflows',
        name: 'admin-workflows',
        component: () => import('@/views/admin/AdminWorkflowsView.vue'),
        meta: {
          title: '流程定义',
          navKey: 'admin-workflows',
          functionCode: 'workflow:manage',
        },
      },
      {
        path: 'api-clients',
        name: 'admin-api-clients',
        component: () => import('@/views/admin/AdminApiClientsView.vue'),
        meta: {
          title: 'API 客户端',
          navKey: 'admin-api-clients',
          functionCode: 'apiclient:manage',
        },
      },
      {
        path: 'metrics',
        name: 'admin-metrics',
        component: () => import('@/views/admin/AdminMetricsView.vue'),
        meta: { title: '指标字典', navKey: 'admin-metrics', functionCode: 'metric:manage' },
      },
      {
        path: 'audit',
        name: 'admin-audit',
        component: () => import('@/views/admin/AdminAuditView.vue'),
        meta: { title: '审计日志', navKey: 'admin-audit', functionCode: 'audit:view' },
      },
    ],
  },
]

export default deliverRoutes
