import { http } from './client'
import type { LayerSets, MyPermissions, SessionUser } from './types'

/**
 * 会话状态：token 只放 sessionStorage（TECHNICAL §6.3，禁止 localStorage）。
 * 单独成模块是为了让 api/client 与 stores/auth 共享同一份读写，
 * 同时避免 client → store → client 的循环依赖。
 */
export interface SessionState {
  token: string
  /** 租户 id（字符串，直接用于 X-Tenant-Id 头） */
  tenantId: string
  tenantCode: string
  user: SessionUser | null
  /** 服务端下发的功能码（唯一事实源） */
  permissions: string[]
  /** 登录后默认落点（角色不同落点不同，见 DESIGN §3.4） */
  defaultRoute: string
}

const STORAGE_KEY = 'devops.session'

export function readSession(): SessionState | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as SessionState
    if (!parsed || typeof parsed.token !== 'string' || !parsed.token) return null
    return {
      token: parsed.token,
      tenantId: String(parsed.tenantId ?? ''),
      tenantCode: parsed.tenantCode ?? '',
      user: parsed.user ?? null,
      permissions: Array.isArray(parsed.permissions) ? parsed.permissions : [],
      defaultRoute: parsed.defaultRoute || '/requirements',
    }
  } catch {
    return null
  }
}

export function writeSession(state: SessionState): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    /* 隐私模式写不进去时降级为内存态：本次会话仍可用 */
  }
}

/**
 * 三层授权集合（TECHNICAL §8.2）：与 token 同一模块取，登录后 loadMe 一次拼装。
 * 403 不跳 /403（silentForbidden）：取不到集合时按空集合渲染，服务端 403 才是权威。
 */
const PERMISSIONS_PATH = '/api/req/auth/me/permissions'

function strings(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  const seen = new Set<string>()
  for (const item of raw) {
    if (typeof item === 'string' && item.length > 0) seen.add(item)
  }
  return [...seen]
}

export async function fetchMyPermissions(): Promise<MyPermissions> {
  const body = await http.get<Partial<MyPermissions> | null>(PERMISSIONS_PATH, undefined, {
    silentForbidden: true,
  })
  const epoch = Number(body?.epoch ?? 0)
  const limited = (body?.restricted ?? null) as Partial<LayerSets> | null
  return {
    epoch: Number.isFinite(epoch) ? epoch : 0,
    functionCodes: strings(body?.functionCodes),
    menus: strings(body?.menus),
    actions: strings(body?.actions),
    restricted: {
      functionCodes: strings(limited?.functionCodes),
      menus: strings(limited?.menus),
      actions: strings(limited?.actions),
    },
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* ignore */
  }
}
