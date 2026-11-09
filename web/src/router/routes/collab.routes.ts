import type { RouteRecordRaw } from 'vue-router'

/**
 * 协作域路由（T7 · TECHNICAL §4.1 / DESIGN §3）。
 *
 * 由 `router/index.ts` 的 `import.meta.glob('./routes/*.ts', { eager: true })` 自动挂载。
 * 三条约定：
 * - `name` 必须与 `layouts/nav.ts` 的 `routeName` 逐字一致，否则入口自动隐藏；
 * - `functionCode` 只做入口守卫（服务端 PermissionRouteTable 仍逐请求鉴权）；
 * - `meta.title` 用于页签/面包屑，`navKey` 决定侧栏哪一项高亮。
 *
 * `/value-stream/stages` 不进侧栏（只从看板右上角「阶段配置」进入），
 * 且要求 `FLOW_CONFIG`：改阶段会影响所有人的指标口径，不该是可点开的只读页。
 */
const collabRoutes: RouteRecordRaw[] = [
  {
    path: '/gantt',
    name: 'gantt',
    component: () => import('@/views/collab/GanttView.vue'),
    meta: { title: '甘特', navKey: 'gantt', functionCode: 'GANTT_VIEW' },
  },
  {
    path: '/wiki',
    name: 'wiki',
    component: () => import('@/views/collab/WikiView.vue'),
    meta: { title: 'Wiki', navKey: 'wiki', functionCode: 'WIKI_VIEW' },
  },
  {
    path: '/value-stream',
    name: 'value-stream',
    component: () => import('@/views/collab/ValueStreamView.vue'),
    meta: { title: '价值流', navKey: 'value-stream', functionCode: 'FLOW_VIEW' },
  },
  {
    path: '/value-stream/stages',
    name: 'value-stream-stages',
    component: () => import('@/views/collab/StageConfigView.vue'),
    meta: {
      title: '阶段配置',
      navKey: 'value-stream',
      parentPath: '/value-stream',
      functionCode: 'FLOW_CONFIG',
    },
  },
]

export default collabRoutes
