import 'vue-router'

/**
 * 路由 meta 契约（TECHNICAL §6.4）：
 * functionCode 只用于展示层拦截（不渲染入口 / 提前跳 403），
 * 真正的判定永远在服务端 —— 前端不复制状态机与权限矩阵。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（浏览器标题与面包屑共用） */
    title?: string
    /** 默认 true；登录、403、404 显式 false */
    requiresAuth?: boolean
    /** 需要的功能码；数组表示任一满足即可 */
    functionCode?: string | string[]
    /** blank = 不套 AppShell（登录页） */
    blank?: boolean
    /** 侧栏/顶栏哪一项高亮（缺省按路径前缀推断） */
    navKey?: string
    /** 详情页面包屑父级路径 */
    parentPath?: string
  }
}

export interface NavItem {
  label: string
  to: string
  icon: string
  /** 目标路由名：未注册时该条目自动隐藏，避免死链 */
  routeName?: string
  /** 需要的功能码（任一） */
  functionCode?: string | string[]
  /** 角标来源：待办数 / 未读数 */
  badge?: 'todo' | 'unread'
}

export interface NavGroup {
  title: string
  items: NavItem[]
  /** 仅管理员可见（组内所有条目都无权限时整组隐藏） */
  admin?: boolean
}
