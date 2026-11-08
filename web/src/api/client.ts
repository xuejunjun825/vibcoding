import { clearSession, readSession } from './session'
import type { ApiErrorBody } from './types'

/**
 * 统一请求层（TECHNICAL §6.4）：
 * - 鉴权头与租户头在此一处注入，页面不再手写 fetch
 * - 401 清会话跳登录、403 跳 /403、**409 绝不弹 toast**（由页面渲染常驻清单）
 * - 网络错误统一成 ApiError(NETWORK_ERROR)，交给 ErrorState + 重试
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  url: string
  body?: unknown
  params?: Record<string, unknown>
  headers?: Record<string, string>
  /** 401 不跳登录（登录接口自身的失败由页面内联展示） */
  silentAuth?: boolean
  /** 403 不跳 /403，交回调用方（用于“可选能力探测”） */
  silentForbidden?: boolean
  responseType?: 'json' | 'text' | 'blob'
  signal?: AbortSignal
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly detail: unknown

  constructor(code: string, message: string, status = 0, detail?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.detail = detail
  }

  get isAuthError(): boolean {
    return this.status === 401
  }
  get isForbidden(): boolean {
    return this.status === 403
  }
  get isConflict(): boolean {
    return this.status === 409
  }
  get isNetworkError(): boolean {
    return this.code === 'NETWORK_ERROR'
  }
}

let navigationHandler: ((to: string) => void) | null = null

/** main.ts 注入 router.push，避免 api → router → views → api 的循环依赖 */
export function setNavigationHandler(handler: (to: string) => void): void {
  navigationHandler = handler
}

function navigate(to: string): void {
  if (navigationHandler) navigationHandler(to)
  else if (typeof window !== 'undefined') window.location.assign(to)
}

function currentLocation(): string {
  if (typeof window === 'undefined') return '/'
  return `${window.location.pathname}${window.location.search}`
}

const API_BASE: string = import.meta.env.VITE_API_BASE ?? ''

/** 数组按重复 key 展开（?domainId=1&domainId=2），空值一律丢弃 */
export function buildQuery(params?: Record<string, unknown>): string {
  if (!params) return ''
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item === undefined || item === null || item === '') continue
        search.append(key, String(item))
      }
    } else {
      search.append(key, String(value))
    }
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}

const FALLBACK_MESSAGE: Record<number, string> = {
  400: '提交的内容不合法，请检查后重试',
  401: '登录已过期，请重新登录',
  403: '你没有该操作的权限',
  404: '请求的资源不存在',
  409: '当前状态不允许该操作',
  422: '引用的数据不存在或已失效',
  429: '操作太频繁，请稍后再试',
  500: '服务端出错了，请稍后重试',
  502: '网关无法连接后端服务',
  503: '服务暂不可用，请稍后重试',
}

function readErrorMessage(body: unknown, status: number): string {
  if (body && typeof body === 'object') {
    const typed = body as ApiErrorBody
    if (typeof typed.message === 'string' && typed.message.trim()) return typed.message
  }
  return FALLBACK_MESSAGE[status] ?? `请求失败（HTTP ${status}）`
}

async function parseBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    return response.json().catch(() => null)
  }
  const text = await response.text().catch(() => '')
  return text || null
}

export async function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method ?? 'GET'
  const session = readSession()

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(options.headers ?? {}),
  }
  if (session?.token && !headers.Authorization) headers.Authorization = `Bearer ${session.token}`
  // 缺 X-Tenant-Id 会被网关判 403（AC-1），故有会话就必须带
  if (session?.tenantId && !headers['X-Tenant-Id']) headers['X-Tenant-Id'] = session.tenantId

  let payload: BodyInit | undefined
  if (options.body !== undefined && options.body !== null) {
    if (typeof FormData !== 'undefined' && options.body instanceof FormData) {
      payload = options.body
    } else if (options.body instanceof Blob) {
      payload = options.body
    } else {
      headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
      payload = JSON.stringify(options.body)
    }
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${options.url}${buildQuery(options.params)}`, {
      method,
      headers,
      body: payload,
      signal: options.signal,
      credentials: 'same-origin',
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('NETWORK_ERROR', '无法连接服务，请确认网关已启动后重试', 0, error)
  }

  if (response.status === 204) return undefined as T
  if (options.responseType === 'blob' && response.ok) return (await response.blob()) as T

  const body = await parseBody(response)

  if (!response.ok) {
    const errorBody = (body ?? {}) as ApiErrorBody
    const error = new ApiError(
      errorBody.code ?? `HTTP_${response.status}`,
      readErrorMessage(body, response.status),
      response.status,
      errorBody.detail,
    )

    if (response.status === 401) {
      clearSession()
      if (!options.silentAuth) {
        navigate(`/login?redirect=${encodeURIComponent(currentLocation())}`)
      }
    } else if (response.status === 403) {
      if (!options.silentForbidden) {
        const query = buildQuery({
          from: currentLocation(),
          code: error.code,
          msg: error.message,
        })
        navigate(`/403${query}`)
      }
    }
    // 409：显式不弹 toast、不跳转——页面用 GateChecklist 常驻条渲染 detail
    throw error
  }

  return body as T
}

/** 导出走 fetch + blob，才能带上 Authorization 头（<a download> 带不了） */
export async function download(
  url: string,
  params?: Record<string, unknown>,
  filename?: string,
): Promise<void> {
  const blob = await request<Blob>({ url, params, responseType: 'blob' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = filename ?? url.split('/').pop()?.split('?')[0] ?? 'download'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 让浏览器有机会开始下载再释放
  setTimeout(() => URL.revokeObjectURL(href), 2000)
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>, extra?: Partial<RequestOptions>) {
    return request<T>({ url, params, method: 'GET', ...extra })
  },
  post<T>(url: string, body?: unknown, extra?: Partial<RequestOptions>) {
    return request<T>({ url, method: 'POST', body, ...extra })
  },
  put<T>(url: string, body?: unknown, extra?: Partial<RequestOptions>) {
    return request<T>({ url, method: 'PUT', body, ...extra })
  },
  patch<T>(url: string, body?: unknown, extra?: Partial<RequestOptions>) {
    return request<T>({ url, method: 'PATCH', body, ...extra })
  },
  del<T>(url: string, extra?: Partial<RequestOptions>) {
    return request<T>({ url, method: 'DELETE', ...extra })
  },
}
