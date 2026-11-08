import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearSession, readSession, writeSession } from '@/api/session'
import { fetchMe, login as loginApi } from '@/api/req'
import type { LoginPayload, LoginResponse, ScopeInfo, SessionUser } from '@/api/types'

const FALLBACK_ROUTE = '/requirements'

/** JWT 里取 tenantId 作为兜底：网关要求 X-Tenant-Id 与 token 内租户一致 */
function decodeTenantId(token: string): string {
  try {
    const part = token.split('.')[1]
    if (!part) return ''
    const normalized = part.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(normalized + '='.repeat((4 - (normalized.length % 4)) % 4))
        .split('')
        .map((char) => `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join(''),
    )
    const payload = JSON.parse(json) as Record<string, unknown>
    const raw = payload.tenantId ?? payload.tenant_id ?? payload.tenant
    return raw === undefined || raw === null ? '' : String(raw)
  } catch {
    return ''
  }
}

function normalizePermissions(
  raw: LoginResponse['permissions'] | undefined,
): { codes: string[]; restricted: string[] } {
  const codes: string[] = []
  const restricted: string[] = []
  for (const item of raw ?? []) {
    if (typeof item === 'string') {
      codes.push(item)
      continue
    }
    if (item && typeof item.code === 'string' && item.access !== 'DENY') {
      codes.push(item.code)
      if (item.access === 'RESTRICTED') restricted.push(item.code)
    }
  }
  return { codes, restricted }
}

export const useAuthStore = defineStore('auth', () => {
  const cached = readSession()

  const token = ref(cached?.token ?? '')
  const tenantId = ref(cached?.tenantId ?? '')
  const tenantCode = ref(cached?.tenantCode ?? '')
  const user = ref<SessionUser | null>(cached?.user ?? null)
  const permissions = ref<string[]>(cached?.permissions ?? [])
  const restricted = ref<string[]>([])
  const defaultRoute = ref(cached?.defaultRoute || FALLBACK_ROUTE)
  const scope = ref<ScopeInfo | null>(null)
  const loading = ref(false)

  const permissionSet = computed(() => new Set(permissions.value))
  const restrictedSet = computed(() => new Set(restricted.value))
  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => user.value?.name ?? user.value?.roles?.[0] ?? '未登录')
  const initials = computed(() => displayName.value.slice(-2))
  const homeRoute = computed(() => defaultRoute.value || FALLBACK_ROUTE)

  /** 服务端权限是唯一事实源；无 functionCode 的入口视为公共 */
  function can(code?: string | null): boolean {
    if (!code) return true
    return permissionSet.value.has(code)
  }

  function canAny(codes: Array<string | undefined>): boolean {
    const real = codes.filter((code): code is string => Boolean(code))
    if (real.length === 0) return true
    return real.some((code) => permissionSet.value.has(code))
  }

  /** RESTRICTED = 入口保留但数据范围受限，需要渲染范围 chip */
  function isRestricted(code: string): boolean {
    return restrictedSet.value.has(code)
  }

  function persist(): void {
    writeSession({
      token: token.value,
      tenantId: tenantId.value,
      tenantCode: tenantCode.value,
      user: user.value,
      permissions: permissions.value,
      defaultRoute: defaultRoute.value,
    })
  }

  function apply(res: LoginResponse, code: string): void {
    const { codes, restricted: limited } = normalizePermissions(res.permissions)
    token.value = res.token
    user.value = res.user ?? null
    tenantCode.value = res.tenantCode ?? code
    tenantId.value = String(
      res.tenantId ?? res.user?.tenantId ?? decodeTenantId(res.token) ?? '',
    )
    permissions.value = codes
    restricted.value = limited
    defaultRoute.value = res.defaultRoute || FALLBACK_ROUTE
    persist()
  }

  async function login(payload: LoginPayload): Promise<void> {
    loading.value = true
    try {
      const res = await loginApi({
        tenantCode: payload.tenantCode.trim(),
        username: payload.username.trim(),
        password: payload.password,
      })
      apply(res, payload.tenantCode.trim())
      // 登录响应不含 scope，异步补齐即可（失败不阻断进入系统）
      void loadMe()
    } finally {
      loading.value = false
    }
  }

  async function loadMe(): Promise<void> {
    if (!token.value) return
    try {
      const me = await fetchMe()
      const profile = me.user ?? me
      user.value = {
        id: profile.id ?? 0,
        name: profile.name ?? '',
        roles: profile.roles ?? [],
        domainIds: profile.domainIds ?? [],
        vendorId: profile.vendorId ?? null,
      }
      if (Array.isArray(me.permissions) && me.permissions.length > 0) {
        const next = normalizePermissions(
          me.permissions as Parameters<typeof normalizePermissions>[0],
        )
        permissions.value = next.codes
        restricted.value = next.restricted
      }
      scope.value = me.scope ?? null
      persist()
    } catch {
      /* 静默：/auth/me 失败不影响已建立的会话 */
    }
  }

  function logout(): void {
    token.value = ''
    tenantId.value = ''
    tenantCode.value = ''
    user.value = null
    permissions.value = []
    restricted.value = []
    scope.value = null
    defaultRoute.value = FALLBACK_ROUTE
    clearSession()
  }

  /** 工作区描述：租户 + 可见域（域名称需要管理接口，基座只展示范围） */
  const workspaceLabel = computed(() => {
    const domains = user.value?.domainIds ?? []
    if (domains.length === 0) return '全部可见域'
    if (domains.length === 1) return `域 #${domains[0]}`
    return `域 ${domains.length} 个`
  })

  const tenantLabel = computed(
    () => user.value?.tenantName ?? (tenantCode.value || `租户 #${tenantId.value}`),
  )

  return {
    token,
    tenantId,
    tenantCode,
    user,
    permissions,
    restricted,
    defaultRoute,
    scope,
    loading,
    permissionSet,
    isAuthenticated,
    displayName,
    initials,
    homeRoute,
    workspaceLabel,
    tenantLabel,
    can,
    canAny,
    isRestricted,
    login,
    loadMe,
    logout,
  }
})
