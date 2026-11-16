/**
 * 导航准入的唯一判定（TECHNICAL §4.3 / §8.4）。
 *
 * 侧栏（SideNav.vue）与顶栏（TopNav.vue）都必须调用 `navVisible`，不允许各自写
 * `canAny` —— 三套判定（省略=公共 / 有码=判定 / 顶栏 admin 特判）正是
 * 「菜单看得见、点开 403」的来源。抽成纯函数后，菜单与入口守卫同源。
 *
 * r18 起是**双条件**（AC-16）：页面码（MENU 层）已授 **且** 绑定的功能码
 * （FUNCTION 层）非 DENY。只判功能码会让「功能码给同模块其它页共用」的页面
 * 一起冒出来，只判页面码会让 RESTRICTED/DENY 的功能码失去意义。
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
  /**
   * `internalOnly` 的**窄例外**：仅该角色（当前只有 VENDOR_PM/用例库）可进。
   *
   * 不等于「厂商账号一律放行」——`vendorScoped` 仍为真，只是判到这一项时
   * 由 `ctx.vendorPm`（角色码）决定，而不是由 `vendorId` 决定（TECHNICAL §3.4）。
   */
  vendorPmAllowed?: boolean
  /**
   * 页面码（MENU 层，36 个之一）。空串/缺省 = 该条目不受页面保护（如通知中心），
   * 退化为「登录即可见」。页面码与 `MenuRegistry`、`sys_menu` 三处同源。
   */
  pageCode?: string
}

export interface NavContext {
  hasRoute(name: string): boolean
  canAny(codes: string[]): boolean
  /** 当前登录账号是否厂商账号（`user.vendorId != null`） */
  vendorScoped: boolean
  /** 当前账号是否厂商项目经理（`roles` 含 `VENDOR_PM`），`internalOnly` 窄例外的唯一依据 */
  vendorPm: boolean
  /**
   * 页面码判定（MENU 层）。
   *
   * 可选是为了让尚未接入 `auth.canMenu` 的调用方继续工作：缺省时页面码条件
   * 被跳过，只判功能码（与 r17 行为一致）。接入后即生效，无需改动本函数。
   */
  hasMenu?(code: string): boolean
}

export function navVisible(item: NavGuard, ctx: NavContext): boolean {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped && !(item.vendorPmAllowed && ctx.vendorPm)) return false
  const pageCode = item.pageCode?.trim() ?? ''
  if (pageCode && ctx.hasMenu && !ctx.hasMenu(pageCode)) return false
  const codes = Array.isArray(item.functionCode) ? item.functionCode : [item.functionCode]
  if (codes.length === 0) return false
  if (codes.includes(AUTHENTICATED)) return true
  return ctx.canAny(codes)
}
