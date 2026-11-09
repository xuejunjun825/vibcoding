import type { NavGroup } from '@/router/types'

/**
 * 导航是唯一来源：侧栏分组 + 顶栏 8 项都从这里取。
 * 每条都带 routeName —— 该路由尚未注册（其他开发线还没落地）时自动隐藏，
 * 侧栏不会出现点了 404 的死链。
 *
 * T8 / T9 注册路由时必须使用下列 name，否则对应入口不会出现：
 * requirements, requirement-create, requirement-detail, releases, release-detail,
 * approvals, deploy-orders, deploy-order-detail, defects, defect-board, defect-detail,
 * iterations, testcases, test-plans, test-plan-execute, traceability,
 * admin-orgs, admin-users, admin-vendors, admin-permissions, admin-workflows,
 * admin-api-clients, admin-metrics, admin-audit
 *
 * M6（T9）新增：workbench, test-plan-detail, test-strategies, test-reports,
 * test-report-detail, test-envs, quality-gates, quality-gate-detail,
 * quality-metrics, release-go-no-go
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    title: '我的',
    items: [
      {
        label: '我的待办',
        to: '/approvals?tab=todo',
        icon: 'inbox',
        routeName: 'approvals',
        badge: 'todo',
      },
      {
        label: '我的任务',
        to: '/requirements?ownerId=me',
        icon: 'list',
        routeName: 'requirements',
      },
      {
        label: '工作台',
        to: '/workbench',
        icon: 'workbench',
        routeName: 'workbench',
        functionCode: ['plan:execute', 'case:manage'],
      },
      {
        label: '通知',
        to: '/notifications',
        icon: 'bell',
        routeName: 'notifications',
        badge: 'unread',
      },
    ],
  },
  {
    title: '需求域',
    items: [
      { label: '需求池', to: '/requirements', icon: 'inbox', routeName: 'requirements' },
      {
        label: '提报需求',
        to: '/requirements/new',
        icon: 'plus',
        routeName: 'requirement-create',
        functionCode: ['req:report:bus', 'req:report:dev'],
      },
      { label: '版本计划', to: '/releases', icon: 'calendar', routeName: 'releases' },
      { label: '迭代', to: '/iterations', icon: 'layout', routeName: 'iterations' },
    ],
  },
  {
    title: '交付域',
    items: [
      { label: '缺陷', to: '/defects', icon: 'bug', routeName: 'defects' },
      { label: '用例库', to: '/testcases', icon: 'library', routeName: 'testcases' },
      { label: '测试计划', to: '/test-plans', icon: 'clipboard', routeName: 'test-plans' },
      {
        label: '测试策略',
        to: '/test-strategies',
        icon: 'compass',
        routeName: 'test-strategies',
        functionCode: 'strategy:manage',
      },
      { label: '测试报告', to: '/test-reports', icon: 'document', routeName: 'test-reports' },
      {
        label: '环境台账',
        to: '/test-envs',
        icon: 'server',
        routeName: 'test-envs',
        functionCode: 'env:manage',
      },
      {
        label: '发布单',
        to: '/deploy-orders',
        icon: 'rocket',
        routeName: 'deploy-orders',
        functionCode: 'deploy:create',
      },
    ],
  },
  {
    title: '分析域',
    items: [
      {
        label: '追溯矩阵',
        to: '/traceability',
        icon: 'matrix',
        routeName: 'traceability',
        functionCode: 'trace:view',
      },
      {
        label: '质量门总览',
        to: '/quality-gates',
        icon: 'gate',
        routeName: 'quality-gates',
        functionCode: 'gate:view',
      },
      {
        label: '质量指标',
        to: '/quality-metrics',
        icon: 'gauge',
        routeName: 'quality-metrics',
        functionCode: 'qualitymetric:view',
      },
      {
        label: '质量看板',
        to: '/defects/board',
        icon: 'chart',
        routeName: 'defect-board',
        functionCode: 'defect:board',
      },
    ],
  },
  {
    title: '设置',
    admin: true,
    items: [
      {
        label: '组织与租户',
        to: '/admin/orgs',
        icon: 'building',
        routeName: 'admin-orgs',
        functionCode: ['tenant:manage', 'org:manage'],
      },
      {
        label: '用户与角色',
        to: '/admin/users',
        icon: 'users',
        routeName: 'admin-users',
        functionCode: 'org:manage',
      },
      {
        label: '厂商与域',
        to: '/admin/vendors',
        icon: 'vendor',
        routeName: 'admin-vendors',
        functionCode: 'org:manage',
      },
      {
        label: '权限矩阵',
        to: '/admin/permissions',
        icon: 'shield',
        routeName: 'admin-permissions',
      },
      {
        label: '流程定义',
        to: '/admin/workflows',
        icon: 'flow',
        routeName: 'admin-workflows',
        functionCode: 'workflow:manage',
      },
      {
        label: 'API 客户端',
        to: '/admin/api-clients',
        icon: 'key',
        routeName: 'admin-api-clients',
        functionCode: 'apiclient:manage',
      },
      {
        label: '指标字典',
        to: '/admin/metrics',
        icon: 'ruler',
        routeName: 'admin-metrics',
        functionCode: 'metric:manage',
      },
      {
        label: '审计日志',
        to: '/admin/audit',
        icon: 'audit',
        routeName: 'admin-audit',
        functionCode: 'audit:view',
      },
    ],
  },
]

export interface TopNavItem {
  key: string
  label: string
  to: string
  routeName: string
  match: string[]
}

/** 顶部 8 项（DESIGN §3.2：不再增加，工作台属 M6） */
export const TOP_NAV: TopNavItem[] = [
  { key: 'requirements', label: '需求', to: '/requirements', routeName: 'requirements', match: ['/requirements'] },
  { key: 'releases', label: '版本', to: '/releases', routeName: 'releases', match: ['/releases'] },
  { key: 'approvals', label: '审批', to: '/approvals', routeName: 'approvals', match: ['/approvals'] },
  { key: 'deploy', label: '发布', to: '/deploy-orders', routeName: 'deploy-orders', match: ['/deploy-orders'] },
  { key: 'defects', label: '缺陷', to: '/defects', routeName: 'defects', match: ['/defects'] },
  {
    key: 'testing',
    label: '测试',
    to: '/test-plans',
    routeName: 'test-plans',
    match: ['/test-plans', '/testcases', '/test-strategies', '/test-reports', '/test-envs'],
  },
  { key: 'trace', label: '追溯', to: '/traceability', routeName: 'traceability', match: ['/traceability'] },
  { key: 'admin', label: '设置', to: '/admin/orgs', routeName: 'admin-orgs', match: ['/admin'] },
]

/** 16×16 线性图标（1.3px 描边，与 Element Plus 图标观感一致） */
export const NAV_ICONS: Record<string, string> = {
  inbox: 'M2.5 9.5 5 3.5h6l2.5 6M2.5 9.5v3h11v-3M6 9.5h4',
  list: 'M5.6 4h7.8M5.6 8h7.8M5.6 12h7.8M2.7 4h.01M2.7 8h.01M2.7 12h.01',
  bell: 'M8 2.4a3.5 3.5 0 0 1 3.5 3.5c0 3 1 3.8 1 3.8h-9s1-.8 1-3.8A3.5 3.5 0 0 1 8 2.4ZM6.7 12.3a1.4 1.4 0 0 0 2.6 0',
  plus: 'M8 3.4v9.2M3.4 8h9.2',
  calendar: 'M3.2 6.4h9.6M4.6 3.2h6.8a1.4 1.4 0 0 1 1.4 1.4v7.6H3.2V4.6a1.4 1.4 0 0 1 1.4-1.4ZM5.9 2.4v1.8M10.1 2.4v1.8',
  layout: 'M3 3h4.1v4.3H3V3ZM8.9 3H13v4.3H8.9V3ZM3 8.7h4.1V13H3V8.7ZM8.9 8.7H13V13H8.9V8.7Z',
  bug: 'M8 4.7a2.8 2.8 0 0 1 2.8 2.8v2a2.8 2.8 0 0 1-5.6 0v-2A2.8 2.8 0 0 1 8 4.7ZM8 2.3v2.4M3.5 6.7h1.8M10.7 6.7h1.8M3.5 11.4h1.8M10.7 11.4h1.8M5.6 3 6.6 4.1M10.4 3 9.4 4.1',
  library: 'M3 3.4h4.1v9.2H3V3.4ZM8.7 3.4h2.2v9.2H8.7V3.4ZM12.4 4l1.2 8.2',
  clipboard: 'M5.9 3.3H5a1.2 1.2 0 0 0-1.2 1.2v8h8.4v-8A1.2 1.2 0 0 0 11 3.3h-.9M5.9 2.4h4.2v1.7H5.9V2.4ZM6.5 7.2h3M6.5 9.9h3',
  rocket: 'M8 9.4c3-1.2 4.3-3.6 4.1-6.4-2.8-.2-5.2 1.2-6.4 4.1M8 9.4 6 7.4M8 9.4c-.8 1.4-2.2 2.3-3.9 2.5.2-1.8 1.2-3.1 2.5-3.9M3.3 12.7c.6-.6 1-1.4 1-2.2',
  matrix: 'M3 3h10v10H3V3ZM3 6.4h10M3 9.6h10M6.4 3v10M9.6 3v10',
  chart: 'M3.2 13V7.2M7 13V4M10.8 13V9M2.2 13.4h11.6',
  building: 'M3.3 13V3.4h6.1V13M9.4 6.6h3.3V13M2.3 13h11.4M5 5.6h1.4M5 8h1.4M5 10.4h1.4',
  users: 'M6 7.4a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4ZM2.6 13c0-1.9 1.5-3.2 3.4-3.2s3.4 1.3 3.4 3.2M10.4 3.4a2 2 0 0 1 0 3.9M11.2 9.9c1.3.3 2.2 1.4 2.2 3.1',
  vendor: 'M3 3.2h7.1v9.6H3V3.2ZM10.1 6.6h2.4v6.2M2.2 12.8h11.6M4.8 5.4h1.3M4.8 8h1.3',
  shield: 'M8 2.3 3.5 4.1v3.5c0 2.6 1.8 4.8 4.5 5.9 2.7-1.1 4.5-3.3 4.5-5.9V4.1L8 2.3ZM6.2 8l1.4 1.4 2.4-2.6',
  flow: 'M3 3h4v3H3V3ZM9 10h4v3H9v-3ZM5 6v2.4h6V10M3.5 6.6h3M8 3.4h3.6v3',
  key: 'M9.5 6.4a2.6 2.6 0 1 1-3.6 2.4l-.4.4H4v1.4H2.6v1.3H2V10l3.9-3.9a2.6 2.6 0 0 1 3.6.3ZM10.4 5.5h.01',
  ruler: 'M2.9 9.6 9.6 2.9l3.5 3.5-6.7 6.7-3.5-3.5ZM6 5.2l1.2 1.2M7.8 7l1.2 1.2M4.2 7l1.2 1.2',
  audit: 'M3.4 2.6h9.2v10.8H3.4V2.6ZM5.8 6h4.4M5.8 8.6h4.4M5.8 11.2h2.6',
  help: 'M8 13.6a5.6 5.6 0 1 0 0-11.2 5.6 5.6 0 0 0 0 11.2ZM6.4 6.3a1.7 1.7 0 1 1 2.4 1.6c-.5.3-.8.7-.8 1.3M8 11.4h.01',
  search: 'M7.2 12.4a5.2 5.2 0 1 0 0-10.4 5.2 5.2 0 0 0 0 10.4ZM13.5 13.5l-2.9-2.9',
  logout: 'M6.4 13.2H3.6V2.8h2.8M9.6 5.2 12.4 8l-2.8 2.8M6.2 8h6',
  chevron: 'M5.4 6.4 8 9l2.6-2.6',
  // —— M6 质量域 ——
  workbench: 'M2.5 13V3.4M2.5 8h11M13.5 13V3.4M2.5 13h11',
  compass: 'M8 13.6a5.6 5.6 0 1 0 0-11.2 5.6 5.6 0 0 0 0 11.2ZM10.2 5.8 8.8 9 6 10.2 7.4 7Z',
  document: 'M4 2.6h4.6l3.4 3.4v7.4H4V2.6ZM8.6 2.6v3.4H12M6 8.6h4M6 10.8h4',
  server: 'M2.8 3.2h10.4V6.6H2.8V3.2ZM2.8 9.4h10.4v3.4H2.8V9.4ZM5 4.9h.01M5 11.1h.01',
  gate: 'M2.4 5h11.2M2.4 11h11.2M5 2.6V5M11 2.6V5M5 11v3M11 11v3M8 5v6',
  gauge: 'M3 11.4a5 5 0 1 1 10 0M8 11.2l2.6-3.8M2.6 11.4h10.8',
}
