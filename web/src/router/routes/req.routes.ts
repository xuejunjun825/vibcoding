import type { RouteRecordRaw } from 'vue-router'

/** 需求域路由（T8）：需求池、提报、详情（10 tab）、变更单深链。 */
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
    meta: { title: '需求详情', parentPath: '/requirements', pageCode: 'req.pool' },
  },
  {
    // 变更单详情深链（r40）：零新菜单码——挂在需求池的 pageCode 下，刷新仍走菜单准入。
    // 必须排在 /requirements/:id 之后：路由匹配按声明顺序，但 :id 只吃两段，不会吞掉本路径。
    path: '/requirements/:id/changes/:changeId',
    name: 'requirement-change-detail',
    component: () => import('@/views/req/RequirementChangeDetailView.vue'),
    meta: { title: '需求变更详情', parentPath: '/requirements', pageCode: 'req.pool' },
  },
]

export default routes
