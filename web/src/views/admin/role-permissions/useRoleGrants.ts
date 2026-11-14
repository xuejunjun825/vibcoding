import { computed, ref, type Ref } from 'vue'
import { getRoleGrants, saveRoleGrants } from '@/api/admin'
import type {
  AccessLevel,
  GrantInput,
  GrantSaveResult,
  GrantWarning,
  RoleGrantView,
  SubjectType,
} from '@/api/types'

/**
 * 授权草稿模型（DESIGN §3.2 / TECHNICAL §4.4）。
 *
 * 两层状态：`baseline` 是服务端最近一次确认的 168 行，`draft` 是用户此刻看到的档位。
 * 未保存计数、离开拦截、重置、diff 都从这一对 map 推导——不额外维护第二份状态，
 * 否则「改了又改回去」会被记成脏，「重置」也会和界面对不上。
 *
 * 保存是**一次 PUT 全量**（该角色 60+36+72 = 168 行）：成功后只把 baseline 换成
 * 当前 draft，**不重新 GET** —— 重取会覆盖用户在保存期间继续做的修改，
 * 也会让「一次保存 = 一条审计」在服务端多出一次读。
 */
export interface GrantDiff {
  added: number
  removed: number
  changed: number
}

export interface GrantError {
  message: string
  code?: string
  status?: number
  detail?: unknown
}

/** 草稿键：`FUNCTION|<code>` / `MENU|<pageCode>` / `ACTION|<actionCode>`（TECHNICAL §4.4） */
export function draftKey(type: SubjectType, code: string): string {
  return `${type}|${code}`
}

const SUBJECT_ORDER: Record<SubjectType, number> = { FUNCTION: 0, MENU: 1, ACTION: 2 }

export function useRoleGrants(roleId: Ref<number>) {
  const view = ref<RoleGrantView | null>(null)
  const baseline = ref<Record<string, AccessLevel>>({})
  const draft = ref<Record<string, AccessLevel>>({})
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<GrantError | null>(null)
  const savedDiff = ref<GrantDiff | null>(null)
  const savedHighRisk = ref<string[]>([])

  function flatten(source: RoleGrantView): Record<string, AccessLevel> {
    const map: Record<string, AccessLevel> = {}
    for (const module of source.modules) {
      for (const page of module.pages) {
        map[draftKey('MENU', page.code)] = page.access
        for (const fn of page.functionCodes) map[draftKey('FUNCTION', fn.code)] = fn.access
        for (const action of page.actions) map[draftKey('ACTION', action.code)] = action.access
      }
    }
    return map
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const data = await getRoleGrants(roleId.value)
      view.value = data
      baseline.value = flatten(data)
      // 必须完整物化：页面 DENY 的页也要有格，否则保存时漏行、脏计数也会失真
      draft.value = { ...baseline.value }
      savedDiff.value = null
      savedHighRisk.value = []
    } catch (err) {
      const apiErr = err as { message?: string; code?: string; status?: number; detail?: unknown }
      error.value = {
        message: apiErr.message || '授权树加载失败',
        code: apiErr.code,
        status: apiErr.status,
        detail: apiErr.detail,
      }
      view.value = null
      baseline.value = {}
      draft.value = {}
    } finally {
      loading.value = false
    }
  }

  function accessOf(type: SubjectType, code: string): AccessLevel {
    return draft.value[draftKey(type, code)] ?? 'DENY'
  }

  function setAccess(type: SubjectType, code: string, access: AccessLevel): void {
    const key = draftKey(type, code)
    if (!(key in draft.value)) return
    if (draft.value[key] === access) return
    draft.value = { ...draft.value, [key]: access }
  }

  /**
   * 页面档位**只写 MENU 格**（TECHNICAL §4.4）。级联写动作/功能码会让「页面 DENY 但动作
   * 保留可编辑」这条 I4 规则失效：用户关掉页面再打开，原本的动作配置已被抹平。
   */
  function setPage(pageCode: string, access: AccessLevel): void {
    setAccess('MENU', pageCode, access)
  }

  /** 模块批量：该模块下所有页面的 MENU 格（动作不批量，高危动作不给人顺手全开） */
  function setModule(moduleCode: string, access: AccessLevel): void {
    const module = view.value?.modules.find((m) => m.code === moduleCode)
    if (!module) return
    const next = { ...draft.value }
    for (const page of module.pages) {
      const key = draftKey('MENU', page.code)
      if (key in next) next[key] = access
    }
    draft.value = next
  }

  /** 一页的动作整列置档（「全选本页动作」：与页头档位是两种粒度） */
  function setAllActions(pageCode: string, access: AccessLevel): void {
    const page = view.value?.modules.flatMap((m) => m.pages).find((p) => p.code === pageCode)
    if (!page) return
    const next = { ...draft.value }
    for (const action of page.actions) {
      const key = draftKey('ACTION', action.code)
      if (key in next) next[key] = access
    }
    draft.value = next
  }

  function reset(): void {
    draft.value = { ...baseline.value }
  }

  const dirtyCount = computed(() => {
    let count = 0
    for (const [key, access] of Object.entries(draft.value)) {
      if ((baseline.value[key] ?? 'DENY') !== access) count++
    }
    return count
  })

  const dirty = computed(() => dirtyCount.value > 0)

  const diff = computed<GrantDiff>(() => {
    let added = 0
    let removed = 0
    let changed = 0
    for (const [key, access] of Object.entries(draft.value)) {
      const before = baseline.value[key] ?? 'DENY'
      if (before === access) continue
      if (before === 'DENY') added++
      else if (access === 'DENY') removed++
      else changed++
    }
    return { added, removed, changed }
  })

  /**
   * 客户端预警：页面 DENY 而动作仍授。服务端也会算同一组（保存后以返回值为准），
   * 这里先算一遍是为了用户改的当下就能看见「这格不生效」，而不是保存后才发现。
   */
  const warnings = computed<GrantWarning[]>(() => {
    const list: GrantWarning[] = []
    for (const module of view.value?.modules ?? []) {
      for (const page of module.pages) {
        if (accessOf('MENU', page.code) !== 'DENY') continue
        const actions = page.actions
          .filter((action) => accessOf('ACTION', action.code) !== 'DENY')
          .map((action) => action.code)
        if (actions.length > 0) {
          list.push({ code: 'PAGE_DENY_ACTION_GRANTED', page: page.code, actions })
        }
      }
    }
    return list
  })

  /** 生效预览：页面 DENY 的动作一律不算生效（与服务端 effective 同口径，I4） */
  const effective = computed(() => {
    const menus: string[] = []
    const actions: string[] = []
    for (const module of view.value?.modules ?? []) {
      for (const page of module.pages) {
        const pageAccess = accessOf('MENU', page.code)
        if (pageAccess !== 'DENY') menus.push(page.code)
        for (const action of page.actions) {
          if (pageAccess === 'DENY') continue
          if (accessOf('ACTION', action.code) !== 'DENY') actions.push(action.code)
        }
      }
    }
    return { menus, actions }
  })

  /** 本次从 DENY 抬到非 DENY 的高危动作（二次确认文案用，I7） */
  const highRiskPending = computed(() =>
    (view.value?.modules ?? [])
      .flatMap((m) => m.pages.flatMap((p) => p.actions))
      .filter((action) => action.highRisk)
      .filter(
        (action) =>
          accessOf('ACTION', action.code) !== 'DENY' &&
          (baseline.value[draftKey('ACTION', action.code)] ?? 'DENY') === 'DENY',
      )
      .map((action) => action.code),
  )

  /**
   * 一 PUT 全量保存。载荷按 FUNCTION → MENU → ACTION、码升序稳定输出：
   * 顺序稳定后审计 diff 与人工比对都读得下去。
   */
  function payload(): GrantInput[] {
    return Object.entries(draft.value)
      .map(([key, access]): GrantInput => {
        const at = key.indexOf('|')
        return {
          subjectType: key.slice(0, at) as SubjectType,
          subjectCode: key.slice(at + 1),
          access,
        }
      })
      .sort((a, b) => {
        const byType = SUBJECT_ORDER[a.subjectType] - SUBJECT_ORDER[b.subjectType]
        if (byType !== 0) return byType
        return a.subjectCode < b.subjectCode ? -1 : a.subjectCode > b.subjectCode ? 1 : 0
      })
  }

  /**
   * 一次 PUT 全量保存。409 由调用方按 `code` 渲染（ADMIN_LOCKOUT_RISK / HIGH_RISK_REQUIRES_CONFIRM），
   * 这里把 ApiError 原样抛出，不吞、不转 toast。
   *
   * `warnings` / `effective` 是 computed（对 draft 求值），保存时 draft 即服务端已确认的集合，
   * 因此两者在保存后自动等于服务端返回值；服务端原始返回仍从 result 交给调用方做 toast 口径。
   */
  async function save(confirmHighRisk = false): Promise<GrantSaveResult> {
    saving.value = true
    error.value = null
    // 先把本次要发的全量载荷定格：请求在飞的时候用户可能继续改，
    // 成功后 baseline 必须切成**已提交的那一份**，不能切到「现在的 draft」——
    // 否则刚改的格会被误标成已保存。
    const body = payload()
    try {
      const result = await saveRoleGrants(roleId.value, {
        grants: body,
        confirmHighRisk,
      })
      const sent: Record<string, AccessLevel> = {}
      for (const grant of body) sent[draftKey(grant.subjectType, grant.subjectCode)] = grant.access
      baseline.value = sent
      if (view.value) {
        view.value = { ...view.value, epoch: result.epoch, warnings: result.warnings }
      }
      savedDiff.value = { added: result.added, removed: result.removed, changed: result.changed }
      savedHighRisk.value = result.highRiskGranted
      return result
    } catch (err) {
      const apiErr = err as { message?: string; code?: string; status?: number; detail?: unknown }
      error.value = {
        message: apiErr.message || '保存失败',
        code: apiErr.code,
        status: apiErr.status,
        detail: apiErr.detail,
      }
      throw err
    } finally {
      saving.value = false
    }
  }

  return {
    view,
    baseline,
    draft,
    loading,
    saving,
    error,
    dirty,
    dirtyCount,
    diff,
    warnings,
    effective,
    highRiskPending,
    savedDiff,
    savedHighRisk,
    load,
    accessOf,
    setAccess,
    setPage,
    setModule,
    setAllActions,
    reset,
    save,
  }
}

export type RoleGrantsModel = ReturnType<typeof useRoleGrants>
