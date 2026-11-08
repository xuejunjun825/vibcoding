import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 权限三态（DESIGN §3.4）：
 * - can(code)        → ✓ 全功能，渲染入口
 * - isRestricted(code) → ⚪ 入口保留 + 范围 chip
 * - 否则              → — 不渲染（不是点了才 403）
 * 这里只管“看不看得见”，能不能写由服务端 403/409 兜底。
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

  function restricted(code: string): boolean {
    return auth.isRestricted(code)
  }

  /** 用于按钮 disabled 时的统一解释（DESIGN §1.3：禁用必须说明原因） */
  function denyReason(code: string, action = '该操作'): string {
    if (auth.isRestricted(code)) return `你仅能在授权范围内${action}`
    return `你没有「${action}」的权限`
  }

  return { can, canAny, restricted, denyReason, showSettings, permissions: auth.permissionSet }
}
