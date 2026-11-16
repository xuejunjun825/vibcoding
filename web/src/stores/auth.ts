import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearSession, fetchMyPermissions, readSession, writeSession } from '@/api/session'
import { fetchMe, login as loginApi } from '@/api/req'
import type {
  LayerSets,
  LoginPayload,
  LoginResponse,
  MyPermissions,
  ScopeInfo,
  SessionUser,
} from '@/api/types'

const FALLBACK_ROUTE = '/requirements'

const EMPTY_LAYERS: LayerSets = { functionCodes: [], menus: [], actions: [] }

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

/** 去重 + 只留非空字符串：服务端集合是权威，前端只做防御性整形 */
function sanitize(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  const seen = new Set<string>()
  for (const item of raw) {
    if (typeof item === 'string' && item.length > 0) seen.add(item)
  }
  return [...seen]
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
  return { codes: [...new Set(codes)], restricted: [...new Set(restricted)] }
}

/**
 * 会话与授权集合中心（TECHNICAL §8.2）。
 *
 * 三层集合（FUNCTION/MENU/ACTION）+ epoch 都由 `/auth/me/permissions` 下发，
 * 与后端 `PermissionService` 同源；菜单与按钮判据只能来自这里，否则刷新时机
 * 不同会让入口与 403 互相打脸。
 *
 * 只持久化 token / 租户 / 用户（sessionStorage）：授权集合带 epoch，持久化后
 * 会被"上一个 epoch 的旧集合"回填，宁可首次进页面多取一次。
 */
export const useAuthStore = defineStore('auth', () => {
  const cached = readSession()

  const token = ref(cached?.token ?? '')
  const tenantId = ref(cached?.tenantId ?? '')
  const tenantCode = ref(cached?.tenantCode ?? '')
  const user = ref<SessionUser | null>(cached?.user ?? null)
  /** FUNCTION 层：功能码（60 个），历史页面仍用 can() 读这里 */
  const functionCodes = ref<string[]>(cached?.permissions ?? [])
  /** MENU 层：页面码（36 个），导航与路由准入的判据 */
  const menus = ref<string[]>([])
  /** ACTION 层：动作码（72 个），按钮判据（新增/改造页面用 canAction） */
  const actions = ref<string[]>([])
  const restricted = ref<LayerSets>({ ...EMPTY_LAYERS })
  const defaultRoute = ref(cached?.defaultRoute || FALLBACK_ROUTE)
  const scope = ref<ScopeInfo | null>(null)
  const loading = ref(false)
  /** 服务端授权快照版本：用于丢弃迟到的旧响应 */
  const epoch = ref(0)
  /** 是否已经拿到过一次服务端三层集合（决定路由守卫要不要先 await） */
  const permissionsLoaded = ref(false)

  // 请求序号与已应用序号：并发 refreshPermissions 时后发先至也不会回填旧集合
  let requestSeq = 0
  let appliedSeq = 0
  // 见过服务端 epoch 之后才启用"epoch 变小即丢弃"（服务重启后 epoch 归零仍可应用）
  let epochSeen = false
  let pendingRefresh: Promise<void> | null = null

  const functionSet = computed(() => new Set(functionCodes.value))
  const menuSet = computed(() => new Set(menus.value))
  const actionSet = computed(() => new Set(actions.value))
  const restrictedSet = computed(() => new Set(restricted.value.functionCodes))

  /** 兼容旧读取点（页面历史上读 auth.permissions），与 functionCodes 同一份数据 */
  const permissionSet = functionSet
  const permissions = computed(() => functionCodes.value)

  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => user.value?.name ?? user.value?.roles?.[0] ?? '未登录')
  const initials = computed(() => displayName.value.slice(-2))
  const homeRoute = computed(() => defaultRoute.value || FALLBACK_ROUTE)

  /** 服务端权限是唯一事实源；无 functionCode 的入口视为公共 */
  function can(code?: string | null): boolean {
    if (!code) return true
    return functionSet.value.has(code)
  }

  function canAny(codes: Array<string | undefined>): boolean {
    const real = codes.filter((code): code is string => Boolean(code))
    if (real.length === 0) return true
    return real.some((code) => functionSet.value.has(code))
  }

  /** 页面码：空码 = 不受页面保护（如通知中心），返回 true */
  function canMenu(code?: string | null): boolean {
    if (!code) return true
    return menuSet.value.has(code)
  }

  function canAnyMenu(codes: Array<string | undefined>): boolean {
    const real = codes.filter((code): code is string => Boolean(code))
    if (real.length === 0) return true
    return real.some((code) => menuSet.value.has(code))
  }

  /** 动作码：空码 = 端点没有动作语义，返回 true */
  function canAction(code?: string | null): boolean {
    if (!code) return true
    return actionSet.value.has(code)
  }

  /** RESTRICTED = 入口保留但数据范围受限（功能层），需要渲染范围 chip */
  function isRestricted(code: string): boolean {
    return restrictedSet.value.has(code)
  }

  function persist(): void {
    writeSession({
      token: token.value,
      tenantId: tenantId.value,
      tenantCode: tenantCode.value,
      user: user.value,
      permissions: functionCodes.value,
      defaultRoute: defaultRoute.value,
    })
  }

  /**
   * 应用一次 `/auth/me/permissions` 快照。
   *
   * 单调规则（AC-10 前端侧）：`order` 比已应用的旧 ⇒ 丢弃；见过 epoch 之后
   * 服务端 epoch 变小 ⇒ 一定是旧快照 ⇒ 丢弃。返回是否真正应用，便于单测。
   */
  function applyPermissions(snapshot: MyPermissions, order = ++requestSeq): boolean {
    if (order < appliedSeq) return false
    const incoming = Number(snapshot?.epoch ?? 0)
    const safeEpoch = Number.isFinite(incoming) ? incoming : 0
    if (epochSeen && safeEpoch < epoch.value) return false

    appliedSeq = order
    epochSeen = true
    epoch.value = safeEpoch
    functionCodes.value = sanitize(snapshot?.functionCodes)
    menus.value = sanitize(snapshot?.menus)
    actions.value = sanitize(snapshot?.actions)
    const limited = snapshot?.restricted ?? EMPTY_LAYERS
    restricted.value = {
      functionCodes: sanitize(limited?.functionCodes),
      menus: sanitize(limited?.menus),
      actions: sanitize(limited?.actions),
    }
    permissionsLoaded.value = true
    persist()
    return true
  }

  /** 拉一次三层集合；失败静默（判定权威在服务端，403 才是最终答案） */
  async function refreshPermissions(): Promise<void> {
    if (!token.value) return
    const order = ++requestSeq
    try {
      const snapshot = await fetchMyPermissions()
      applyPermissions(snapshot, order)
    } catch {
      /* 静默：拿不到集合时按已有集合渲染，不阻塞进入页面 */
    }
  }

  /** 守卫用：只有第一次需要等网络，之后同步返回 */
  async function ensurePermissions(): Promise<void> {
    if (!token.value) return
    if (permissionsLoaded.value) return
    if (!pendingRefresh) {
      pendingRefresh = refreshPermissions().finally(() => {
        pendingRefresh = null
      })
    }
    await pendingRefresh
  }

  function apply(res: LoginResponse, code: string): void {
    const { codes, restricted: limited } = normalizePermissions(res.permissions)
    token.value = res.token
    user.value = res.user ?? null
    tenantCode.value = res.tenantCode ?? code
    tenantId.value = String(
      res.tenantId ?? res.user?.tenantId ?? decodeTenantId(res.token) ?? '',
    )
    functionCodes.value = codes
    // 登录响应只有功能码：菜单/动作必须重新取，否则会沿用上一个会话的判断
    menus.value = []
    actions.value = []
    restricted.value = { functionCodes: limited, menus: [], actions: [] }
    permissionsLoaded.value = false
    epoch.value = 0
    epochSeen = false
    appliedSeq = 0
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
      // 登录响应不含 scope 与页面/动作层，异步补齐即可（失败不阻断进入系统）
      void loadMe()
    } finally {
      loading.value = false
    }
  }

  async function loadMe(): Promise<void> {
    if (!token.value) return
    // 两个请求并发：/auth/me 失败不影响授权集合，反之亦然。
    // 用 ensurePermissions（缺才取）：硬刷新时路由守卫已经取过一次，不再重复打
    const permissionsTask = ensurePermissions()
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
        functionCodes.value = next.codes
        restricted.value = { ...restricted.value, functionCodes: next.restricted }
      }
      scope.value = me.scope ?? null
      persist()
    } catch {
      /* 静默：/auth/me 失败不影响已建立的会话 */
    }
    await permissionsTask
  }

  function logout(): void {
    token.value = ''
    tenantId.value = ''
    tenantCode.value = ''
    user.value = null
    functionCodes.value = []
    menus.value = []
    actions.value = []
    restricted.value = { ...EMPTY_LAYERS }
    permissionsLoaded.value = false
    epoch.value = 0
    epochSeen = false
    appliedSeq = 0
    pendingRefresh = null
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
    functionCodes,
    menus,
    actions,
    restricted,
    epoch,
    permissionsLoaded,
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
    canMenu,
    canAnyMenu,
    canAction,
    isRestricted,
    applyPermissions,
    refreshPermissions,
    ensurePermissions,
    login,
    loadMe,
    logout,
  }
})
