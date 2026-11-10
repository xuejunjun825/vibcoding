import type { RouteRecordRaw } from 'vue-router'

/** 流程域路由（T8）：版本计划、审批中心、发布单。 */
const routes: RouteRecordRaw[] = [
  {
    path: '/releases',
    name: 'releases',
    component: () => import('@/views/release/ReleaseListView.vue'),
    meta: { title: '版本计划', functionCode: 'release:plan' },
  },
  {
    path: '/releases/:id',
    name: 'release-detail',
    component: () => import('@/views/release/ReleaseDetailView.vue'),
    meta: { title: '版本详情', parentPath: '/releases' },
  },
  {
    path: '/approvals',
    name: 'approvals',
    component: () => import('@/views/approval/ApprovalCenterView.vue'),
    meta: { title: '审批中心' },
  },
  {
    path: '/deploy-orders',
    name: 'deploy-orders',
    component: () => import('@/views/deploy/DeployOrderListView.vue'),
    meta: { title: '发布单', functionCode: ['deploy:create', 'deploy:execute', 'deploy:force'] },
  },
  {
    path: '/deploy-orders/:id',
    name: 'deploy-order-detail',
    component: () => import('@/views/deploy/DeployOrderDetailView.vue'),
    meta: {
      title: '发布单详情',
      parentPath: '/deploy-orders',
      functionCode: ['deploy:create', 'deploy:execute', 'deploy:force'],
    },
  },
]

export default routes
