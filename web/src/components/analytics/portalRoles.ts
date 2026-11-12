/**
 * 门户角色常量（AC-20）。
 *
 * ⚠️ 重复点（G-2）：后端没有「角色字典」端点，这里的 10 个 code 与中文名是
 * `deliver-service/.../portal/PortalTemplates.ROLE_ORDER` 的手工镜像；中文名取
 * `req-service/.../sys/RoleCatalog`（= `db/migration/V3__role_catalog_prd.sql`）的权威值，
 * 后端新增角色时前端必须同步。后端 `PortalService.resolveConfigRole` 同时接受这里的大写
 * 角色码（经 `PortalTemplates.byRole` 归一）和小写模板 code，所以抽屉直接传大写码即可。
 *
 * 顺序即 `ROLE_ORDER`：抽屉的选择列表与后端模板的兜底顺序必须肉眼可对齐。
 */
export interface PortalRole {
  code: string
  label: string
}

export const PORTAL_ROLES: ReadonlyArray<PortalRole> = Object.freeze([
  { code: 'PLATFORM_ADMIN', label: '平台管理员' },
  { code: 'PRODUCT', label: '产品经理' },
  { code: 'PMO', label: '项目·域(PMO)' },
  { code: 'SUB_BUSINESS_LEADER', label: '二级企业负责人' },
  { code: 'SUB_BUSINESS', label: '二级企业业务' },
  { code: 'BUSINESS', label: '业务部门' },
  { code: 'DEV', label: '研发工程师' },
  { code: 'QA', label: '测试工程师' },
  { code: 'OPS', label: '运维工程师' },
  { code: 'MGMT', label: '管理层' },
])

const ROLE_BY_CODE = new Map<string, PortalRole>(
  PORTAL_ROLES.map((role) => [role.code, role] as const),
)

/** 大小写不敏感：JWT 里可能下发小写码，未知码原样回显（不吞掉信息） */
export function portalRoleLabel(code: string): string {
  if (!code) return ''
  const normalized = code.trim().toUpperCase()
  return ROLE_BY_CODE.get(normalized)?.label ?? code
}
