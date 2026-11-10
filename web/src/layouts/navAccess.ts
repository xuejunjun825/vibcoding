/**
 * 导航准入的唯一判定（TECHNICAL §4.3）。
 *
 * 侧栏（SideNav.vue）与顶栏（TopNav.vue）都必须调用 `navVisible`，不允许各自写
 * `canAny` —— 三套判定（省略=公共 / 有码=判定 / 顶栏 admin 特判）正是
 * 「菜单看得见、点开 403」的来源。抽成纯函数后，菜单与入口守卫同源。
 *
 * `AUTHENTICATED` 是「登录即可读」的哨兵值，不是权限码：
 * 服务端权限集合里永远没有它，所以必须在 `canAny` 之前处理，
 * 否则所有公共页会一起消失。
 */
export const AUTHENTICATED = 'AUTHENTICATED'

export type FunctionCode = string | string[]

export interface NavGuard {
  /** 目标路由名；未注册时该条目隐藏（避免死链） */
  routeName?: string
  /** 需要的功能码（任一满足即可）；登录可读页写 `AUTHENTICATED`。必填 */
  functionCode: FunctionCode
  /** 仅内部账号可见（厂商账号不渲染，深链由路由守卫拦） */
  internalOnly?: boolean
}

export interface NavContext {
  hasRoute(name: string): boolean
  canAny(codes: string[]): boolean
  /** 当前登录账号是否厂商账号（`user.vendorId != null`） */
  vendorScoped: boolean
}

export function navVisible(item: NavGuard, ctx: NavContext): boolean {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped) return false
  const codes = Array.isArray(item.functionCode) ? item.functionCode : [item.functionCode]
  if (codes.length === 0) return false
  if (codes.includes(AUTHENTICATED)) return true
  return ctx.canAny(codes)
}
