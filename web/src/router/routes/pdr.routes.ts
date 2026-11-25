import type { RouteRecordRaw } from 'vue-router'

/**
 * 评审域路由（r49）：PDR 评审中心。
 *
 * 页面码只有 `pdr.review` 一个（`MenuRegistry` 的 `new MenuPage("pdr.review", "需求域", "PDR 评审", "pdr-reviews", …)`），
 * 列表与详情共用它；`pdr-review-detail` 不是导航项，靠 `pageRegistry.registerPageRoute()` 补登记。
 *
 * 深链约定：后端只有「需求维度」的读路径（`GET /requirements/{id}/pdr-review`），
 * 没有 `GET /pdr-reviews/{id}`，所以详情页的 `:id` 是**评审单号**，真实数据靠
 * `?requirementId=` 定位（列表页跳转时两者一起带）。只给评审单号时页面展示一条定位提示，
 * 不让用户看到空白页。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/pdr-reviews',
    name: 'pdr-reviews',
    component: () => import('@/views/req/PdrReviewListView.vue'),
    // 功能码留空 = 登录可读的页面壳，准入只认页面码（nav 项用 AUTHENTICATED，R3 要求菜单码 ⊆ 路由码）
    meta: { title: '评审中心', pageCode: 'pdr.review' },
  },
  {
    path: '/pdr-reviews/:id',
    name: 'pdr-review-detail',
    component: () => import('@/views/req/PdrReviewDetailView.vue'),
    meta: { title: '评审单详情', parentPath: '/pdr-reviews', pageCode: 'pdr.review' },
  },
]

export default routes
