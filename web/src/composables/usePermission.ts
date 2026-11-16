import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 权限三态（DESIGN §3.4）：
 * - can(code)        → ✓ 全功能，渲染入口
 * - isRestricted(code) → ⚪ 入口保留 + 范围 chip
 * - 否则              → — 不渲染（不是点了才 403）
 * 这里只管“看不看得见”，能不能写由服务端 403/409 兜底。
 *
 * r18 三层（TECHNICAL §8.5）：
 * - `can / canAny`         FUNCTION 层，历史页面继续用（行为不变）
 * - `canMenu / canAnyMenu` MENU 层，页面码；导航与路由准入读同一份
 * - `canAction`            ACTION 层，按钮判据；**新增/改造页面一律用它**，
 *                          没有动作码的按钮 = 服务端 403 的候选人
 */
export function usePermission() {
  const auth = useAuthStore()

  const showSettings = computed(
    () =>
      auth.can('tenant:manage') ||
      auth.can('org:manage') ||
      auth.can('workflow:manage') ||
      auth.can('apiclient:manage') ||
      auth.can('metric:manage') ||
      auth.can('audit:view'),
  )

  function can(code?: string | null): boolean {
    return auth.can(code)
  }

  function canAny(codes: Array<string | undefined>): boolean {
    return auth.canAny(codes)
  }

  /** 页面码（MENU 层）：空码 = 不受页面保护，返回 true */
  function canMenu(code?: string | null): boolean {
    return auth.canMenu(code)
  }

  function canAnyMenu(codes: Array<string | undefined>): boolean {
    return auth.canAnyMenu(codes)
  }

  /** 动作码（ACTION 层）：按钮级判据，空码返回 true */
  function canAction(code?: string | null): boolean {
    return auth.canAction(code)
  }

  function restricted(code: string): boolean {
    return auth.isRestricted(code)
  }

  /** 用于按钮 disabled 时的统一解释（DESIGN §1.3：禁用必须说明原因） */
  function denyReason(code: string, action = '该操作'): string {
    if (auth.isRestricted(code)) return `你仅能在授权范围内${action}`
    return `你没有「${action}」的权限`
  }

  /** 动作缺失时的解释：区分「没授权」与「页面层级就不通」 */
  function denyActionReason(actionCode: string, action = '该操作'): string {
    if (auth.canAction(actionCode)) return ''
    if (auth.isRestricted(actionCode)) return `你仅能在授权范围内${action}`
    return `你没有「${action}」的操作权限`
  }

  return {
    can,
    canAny,
    canMenu,
    canAnyMenu,
    canAction,
    restricted,
    denyReason,
    denyActionReason,
    showSettings,
    permissions: auth.permissionSet,
    menus: auth.menus,
    actions: auth.actions,
  }
}
