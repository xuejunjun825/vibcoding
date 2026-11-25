import { NAV_GROUPS } from './nav'

/**
 * 路由名 → 页面码（MENU 层）的唯一汇总（TECHNICAL §8.4）。
 *
 * 路由文件分散在 9 个域文件里，而页面码是**共享**的：`/requirements` 同时承载
 * 「我的任务（me.tasks）」与「需求池（req.pool）」，任一已授即可进。守卫只能
 * 在这里做 any-of，不能在每个页面各写一遍。
 *
 * 来源有两处，合并进同一张表：
 * 1. 导航表 `NAV_GROUPS`（每项的 `pageCode`）——36 个页面码的日常来源；
 * 2. 路由 `meta.pageCode`（新页面在路由里显式声明，守卫直接读，避免双源漂移）。
 *
 * 空页面码（通知中心）不入表 = 该路由只受登录态保护。
 */
const registry = new Map<string, string[]>()

function add(routeName: string, code: string): void {
  const name = routeName.trim()
  const page = code.trim()
  if (!name || !page) return
  const current = registry.get(name)
  if (!current) {
    registry.set(name, [page])
    return
  }
  if (!current.includes(page)) current.push(page)
}

for (const group of NAV_GROUPS) {
  for (const item of group.items) {
    if (item.routeName) add(item.routeName, item.pageCode)
  }
}

// r39：自动化四个路由共用同一页面码（导航只加 1 项，页内双 tab）
registerPageRoute('automation-runs', ['automation.rules'])
registerPageRoute('automation-rule-create', ['automation.rules'])
registerPageRoute('automation-rule-edit', ['automation.rules'])

// r49：评审单详情不是导航项（列表→详情是页内跳转），但深链刷新同样要过页面准入，
// 所以显式登记到「评审中心」的页面码下——缺这一行，直接打开 /pdr-reviews/12 会 403。
registerPageRoute('pdr-review-detail', ['pdr.review'])

/** 该路由接受的页面码（any-of）；空数组 = 不受页面保护 */
export function pageCodesFor(routeName?: string | null): string[] {
  if (!routeName) return []
  return [...(registry.get(String(routeName)) ?? [])]
}

/** 显式注册（新页面/共享路由）；重复调用幂等，不会覆盖已有页面码 */
export function registerPageRoute(routeName: string, pageCodes: string[]): void {
  for (const code of pageCodes) add(routeName, code)
}

/** 审计/脚本用：当前整张表（顺序稳定，便于 s1 三向比对） */
export function pageRouteTable(): Array<{ routeName: string; pageCodes: string[] }> {
  return [...registry.entries()]
    .map(([routeName, pageCodes]) => ({ routeName, pageCodes: [...pageCodes] }))
    .sort((left, right) => left.routeName.localeCompare(right.routeName))
}

/** 去重后的全部页面码（36 个），供 s1 与 MenuRegistry 比对 */
export function registeredPageCodes(): string[] {
  const codes = new Set<string>()
  for (const list of registry.values()) {
    for (const code of list) codes.add(code)
  }
  return [...codes].sort()
}

/** 准入判定的输入（由 store 提供，纯函数才可单测） */
export interface PageGate {
  /** 是否已拿到服务端三层集合 */
  loaded: boolean
  canAnyMenu(codes: string[]): boolean
}

/**
 * 路由页面码准入（AC-16，守卫与单测同一份实现）：
 * 1. 该路由没有页面码（如通知中心）→ 放行；
 * 2. 集合还没取到（网络/接口异常）→ 放行，服务端 403 才是权威；
 * 3. 否则 any-of：共享路由（/requirements 同时承载 me.tasks 与 req.pool）任一已授即可。
 */
export function pageAdmission(
  routeName: string | null | undefined,
  declared: string | string[] | undefined,
  gate: PageGate,
): { allowed: boolean; codes: string[] } {
  const codes = new Set<string>(pageCodesFor(routeName))
  const list = Array.isArray(declared) ? declared : declared ? [declared] : []
  for (const code of list) {
    if (code) codes.add(code)
  }
  const all = [...codes]
  if (all.length === 0) return { allowed: true, codes: all }
  if (!gate.loaded) return { allowed: true, codes: all }
  return { allowed: gate.canAnyMenu(all), codes: all }
}
