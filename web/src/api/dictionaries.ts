/**
 * 非管理页字典的唯一入口（TECHNICAL §4.1 / PRD AC-2、AC-3）。
 *
 * 背景：需求池/版本/审批/交付/质量等页面过去借 `api/admin.ts` 的
 * `listDomains/listVendors/listUsers` 读下拉选项，而管理面 `/admin/*` 由
 * `org:manage` 把守——非管理员一进页面就 403，且默认 403 会全局跳
 * `/403`，页面直接打不开。字典是「读选项」不是管理面，因此后端新开
 * 只读端点 `/dictionaries/*`（仅认证）。
 *
 * 约束：
 * - 三个请求一律带 `silentForbidden` 标记：字典拿不到只能降级为空表，
 *   绝不允许把用户甩到 `/403`（那正是本需求要修的故障形态）。
 * - 页面不要直接 import `http` 拼字典 URL，也不要在管理页之外 import
 *   `api/admin` 的 `list*`（`scripts/tests/access/a3-nav-audit.mjs` 静态断言）。
 * - 管理页（`views/admin/*`）仍用 `api/admin.ts`：分页与管理字段不该被字典污染。
 */
import { http } from './client'

/** 与 `api/admin.ts` 的 `ADMIN` 同网关前缀（a3 断言两者一致，改前缀只改一处） */
const DICTS = '/api/req/dictionaries'

/** 域选项：后端 `DictionaryViews.DomainOption` 的字段白名单 */
export interface DictionaryDomain {
  id: number
  code: string
  name: string
}

/** 厂商选项：后端 `DictionaryViews.VendorOption` */
export interface DictionaryVendor {
  id: number
  code: string
  name: string
}

/** 人员选项：后端 `DictionaryViews.UserOption`（故意不含 roles/vendorId 等管理字段） */
export interface DictionaryUser {
  id: number
  username: string
  name: string
}

export interface DictionaryBundle {
  domains: DictionaryDomain[]
  vendors: DictionaryVendor[]
  users: DictionaryUser[]
}

/** 域字典（登录可读，按租户/厂商裁剪） */
export function listDictionaryDomains(): Promise<DictionaryDomain[]> {
  return http.get<DictionaryDomain[]>(`${DICTS}/domains`, undefined, { silentForbidden: true })
}

/** 厂商字典（登录可读，按租户/厂商裁剪） */
export function listDictionaryVendors(): Promise<DictionaryVendor[]> {
  return http.get<DictionaryVendor[]>(`${DICTS}/vendors`, undefined, { silentForbidden: true })
}

/** 人员字典（登录可读，按租户/厂商裁剪；上限 1000 条，前端不做分页） */
export function listDictionaryUsers(): Promise<DictionaryUser[]> {
  return http.get<DictionaryUser[]>(`${DICTS}/users`, undefined, { silentForbidden: true })
}

/**
 * 一次取齐三张字典，**三项各自降级**：任一路失败只丢该项，其余照常返回。
 * 页面拿到空数组后渲染空态/隐藏该筛选项，绝不升级为页面失败。
 * 这里不缓存——缓存留在页面层（`views/req/platform.ts`），换租户=重新登录=整页刷新。
 */
export function loadDictionaries(): Promise<DictionaryBundle> {
  return Promise.all([
    listDictionaryDomains().catch(() => [] as DictionaryDomain[]),
    listDictionaryVendors().catch(() => [] as DictionaryVendor[]),
    listDictionaryUsers().catch(() => [] as DictionaryUser[]),
  ]).then(([domains, vendors, users]) => ({ domains, vendors, users }))
}
