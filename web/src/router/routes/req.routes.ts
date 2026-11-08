import type { RouteRecordRaw } from 'vue-router'

/** 需求域路由（T8）：需求池、提报、详情 7 tab。 */
const routes: RouteRecordRaw[] = [
  {
    path: '/requirements',
    name: 'requirements',
    component: () => import('@/views/req/RequirementPoolView.vue'),
    meta: { title: '需求池' },
  },
  {
    path: '/requirements/new',
    name: 'requirement-create',
    component: () => import('@/views/req/RequirementCreateView.vue'),
    meta: {
      title: '提报需求',
      functionCode: ['req:report:bus', 'req:report:dev'],
      parentPath: '/requirements',
    },
  },
  {
    path: '/requirements/:id',
    name: 'requirement-detail',
    component: () => import('@/views/req/RequirementDetailView.vue'),
    meta: { title: '需求详情', parentPath: '/requirements' },
  },
]

export default routes
