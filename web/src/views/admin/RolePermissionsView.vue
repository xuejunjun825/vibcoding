<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AccessSegmented from './role-permissions/AccessSegmented.vue'
import RoleGrantTree, { UNBOUND_SELECTION } from './role-permissions/RoleGrantTree.vue'
import { useRoleGrants, draftKey } from './role-permissions/useRoleGrants'
import ErrorState from '@/components/ErrorState.vue'
import { useAuthStore } from '@/stores/auth'
import type { AccessLevel, ActionGrant, FunctionGrant, GrantSaveResult, PageGrant } from '@/api/types'

/**
 * 三级授权页（DESIGN §3.2 / TECHNICAL §4.5）。
 *
 * 三条主链路：
 * 1. 草稿即改即算：所有档位写进 `useRoleGrants` 的 draft，未保存计数/预警/生效预览
 *    全部由它派生，保存只发**一次** PUT（该角色 171 行）。
 * 2. 一次点击到达：进入即选中「第一个有动作授权的页面」，从列表点「授权」到改档 = 2 击。
 * 3. 失败不弹 toast：409 用 480 对话框、400/403/网络用保存条上方内联条（DESIGN §3.2）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const roleId = computed(() => Number(route.params.id))

const {
  view,
  baseline,
  draft,
  loading,
  saving,
  error,
  dirty,
  dirtyCount,
  unboundGroup,
  unboundDirty,
  warnings,
  effective,
  highRiskPending,
  load,
  accessOf,
  setAccess,
  setPage,
  setModule,
  setAllActions,
  reset,
  save,
} = useRoleGrants(roleId)

/** 平台管理员冻结码（与 req-service RoleGuard.java:33 `ADMIN_CODES` 同值，前端只做禁用提示） */
const FROZEN_CODES = ['org:manage', 'tenant:manage']

const role = computed(() => view.value?.role ?? null)
const allPages = computed<PageGrant[]>(() => (view.value?.modules ?? []).flatMap((m) => m.pages))
const allActions = computed<ActionGrant[]>(() =>
  (view.value?.modules ?? []).flatMap((m) => m.pages.flatMap((p) => p.actions)),
)

const selectedPage = ref('')
const onlyGranted = ref(false)
const advancedOpen = ref(false)
const actionQuery = ref('')
const slow = ref(false)
const inlineError = ref<{ kind: 'unknown' | 'forbidden' | 'network'; message: string } | null>(null)
const panelRef = ref<HTMLElement | null>(null)

const currentPage = computed<PageGrant | null>(
  () => allPages.value.find((page) => page.code === selectedPage.value) ?? null,
)

/**
 * 右栏的「未绑定页面的功能码」态（r20）。
 *
 * `selectedPage` 用哨兵值表示这一组，和页面共用同一个选中通道（树上只有一处选中态，
 * 右栏只认一个来源）。这 30 个码不属任何页面，却派生 `req:review` / `deploy:force` /
 * `WIKI_EDIT` 等动作——把它们做成只读展示就等于继续让它们当黑洞，所以这里必须是可编辑的三档表，
 * 且不新增任何保存接口（仍走底部那一次全量 PUT）。
 */
const unboundSelected = computed(() => selectedPage.value === UNBOUND_SELECTION)
const unboundRows = computed<FunctionGrant[]>(() => unboundGroup.value?.functions ?? [])
const unboundGranted = computed(
  () => unboundRows.value.filter((fn) => accessOf('FUNCTION', fn.code) !== 'DENY').length,
)

function isFnDirty(code: string): boolean {
  const key = draftKey('FUNCTION', code)
  return (baseline.value[key] ?? 'DENY') !== (draft.value[key] ?? 'DENY')
}

/** 功能码前缀即它所属的域（req / deploy / wiki / org …），仅用于帮人扫读，不参与判定 */
function domainOf(code: string): string {
  // 功能码是 `<域>:<动作>`（req:review / deploy:force / test:quick），不是页面码那种 `admin.roles`
  // 点号形式——只按 '.' 切会把整串原样吐回来，那一列就成了「功能码」的重复列。两种分隔都认。
  const cut = code.search(/[:.]/)
  return cut > 0 ? code.slice(0, cut) : code
}

// ---------------------------------------------------------------- 计数（I2 全量口径）

const grantedPages = computed(() => allPages.value.filter((p) => accessOf('MENU', p.code) !== 'DENY').length)
const grantedActions = computed(
  () => allActions.value.filter((a) => accessOf('ACTION', a.code) !== 'DENY').length,
)

const pageGrantedActions = computed(
  () => (currentPage.value?.actions ?? []).filter((a) => accessOf('ACTION', a.code) !== 'DENY').length,
)

/**
 * 本页「生效」动作数：直接读 `effective`（与服务端同口径——页面 DENY 的动作不在其中），
 * 而不是自己再算一遍，免得 I4 的两处口径漂移。
 */
const pageEffectiveActions = computed(() => {
  const page = currentPage.value
  if (!page) return 0
  const effectiveSet = new Set(effective.value.actions)
  return page.actions.filter((action) => effectiveSet.has(action.code)).length
})

/** 未保存项按层拆分（保存条 tooltip 摘要「页面 x / 动作 y / 功能码 z」） */
const dirtyBreakdown = computed(() => {
  let pages = 0
  let actions = 0
  let functions = 0
  for (const [key, access] of Object.entries(draft.value)) {
    // baseline 与 draft 同构；未出现的键按 DENY 计（服务端默认）
    if ((baseline.value[key] ?? 'DENY') === access) continue
    if (key.startsWith('MENU|')) pages++
    else if (key.startsWith('ACTION|')) actions++
    else functions++
  }
  return { pages, actions, functions }
})

const dirtySummary = computed(() => {
  const { pages, actions, functions } = dirtyBreakdown.value
  const base = `页面 ${pages} / 动作 ${actions} / 功能码 ${functions}`
  // 只改了看不见的 30 格时，前三个数字会「看起来没动」，这段把差额说清楚
  return unboundDirty.value > 0 ? `${base}（含未绑定功能码 ${unboundDirty.value}）` : base
})

const pageWarning = computed(() => {
  const page = currentPage.value
  if (!page) return false
  if (accessOf('MENU', page.code) !== 'DENY') return false
  return page.actions.some((action) => accessOf('ACTION', action.code) !== 'DENY')
})

const actionsTri = computed<'ALLOW' | 'DENY' | 'MIXED'>(() => {
  const actions = currentPage.value?.actions ?? []
  if (actions.length === 0) return 'DENY'
  const states = actions.map((action) => accessOf('ACTION', action.code))
  if (states.every((state) => state === 'ALLOW')) return 'ALLOW'
  if (states.every((state) => state === 'DENY')) return 'DENY'
  return 'MIXED'
})

const filteredActions = computed(() => {
  const q = actionQuery.value.trim().toLowerCase()
  const actions = currentPage.value?.actions ?? []
  if (!q) return actions
  return actions.filter(
    (action) => action.code.toLowerCase().includes(q) || action.name.toLowerCase().includes(q),
  )
})

// ---------------------------------------------------------------- 判定辅助

function isFrozen(code: string): boolean {
  return role.value?.code === 'PLATFORM_ADMIN' && FROZEN_CODES.includes(code)
}

function functionUsage(code: string): number {
  return allPages.value.filter((page) => page.functionCodes.some((fn) => fn.code === code)).length
}

function accessLabel(access: AccessLevel): string {
  return access === 'ALLOW' ? '允许' : access === 'RESTRICTED' ? '受限' : '拒绝'
}

/** 动作码 `<module>.action.<verb>`；授权数据不含 HTTP 方法，端点列只展示动词（不伪造 method） */
function endpointOf(code: string): string {
  const marker = '.action.'
  const at = code.lastIndexOf(marker)
  if (at >= 0) return code.slice(at + marker.length)
  const dot = code.lastIndexOf('.')
  return dot >= 0 ? code.slice(dot + 1) : code
}

function isIneffective(action: ActionGrant): boolean {
  const page = currentPage.value
  if (!page) return false
  return accessOf('MENU', page.code) === 'DENY' && accessOf('ACTION', action.code) !== 'DENY'
}

function setFunctionAccess(code: string, access: AccessLevel): void {
  if (isFrozen(code)) return
  setAccess('FUNCTION', code, access)
}

function toggleAllActions(event: Event): void {
  const page = currentPage.value
  if (!page) return
  const checked = (event.target as HTMLInputElement).checked
  setAllActions(page.code, checked ? 'ALLOW' : 'DENY')
}

// ---------------------------------------------------------------- 默认选中（≤3 击）

function pickDefaultPage(): void {
  // 刷新/保存后不要把人从「未绑定分组」上顶走：该分组还在就保留这次选择
  if (unboundSelected.value && unboundRows.value.length > 0) return
  const pages = allPages.value
  if (pages.length === 0) {
    selectedPage.value = ''
    return
  }
  const withActions = pages.find((page) =>
    page.actions.some((action) => accessOf('ACTION', action.code) !== 'DENY'),
  )
  selectedPage.value = (withActions ?? pages[0]).code
}

watch(
  () => view.value?.role.id,
  () => pickDefaultPage(),
)

watch(selectedPage, () => {
  advancedOpen.value = false
  actionQuery.value = ''
})

// 页面开着而绑定功能码被拒 → 自动展开「高级」（DESIGN §3.2）
watch(
  [() => selectedPage.value, () => draft.value],
  () => {
    const page = currentPage.value
    if (!page || advancedOpen.value) return
    if (accessOf('MENU', page.code) === 'DENY') return
    if (page.functionCodes.some((fn) => accessOf('FUNCTION', fn.code) === 'DENY')) advancedOpen.value = true
  },
)

async function reload(): Promise<void> {
  await load()
  pickDefaultPage()
}

// ---------------------------------------------------------------- 保存链路

let slowTimer: number | undefined

function startSlowTimer(): void {
  stopSlowTimer()
  slowTimer = window.setTimeout(() => {
    slow.value = true
  }, 2500)
}

function stopSlowTimer(): void {
  if (slowTimer !== undefined) {
    window.clearTimeout(slowTimer)
    slowTimer = undefined
  }
  slow.value = false
}

function setDiff(left: Set<string>, right: string[]): boolean {
  if (left.size !== right.length) return true
  return right.some((code) => !left.has(code))
}

async function afterSaved(result: GrantSaveResult): Promise<void> {
  const beforeMenus = new Set(auth.menus)
  const beforeActions = new Set(auth.actions)
  try {
    // 总是刷新：改的可能是自己所在角色，也可能不是——不判断，统一一次拉取（I6）
    await auth.refreshPermissions()
  } catch {
    // 刷新失败不影响「保存已成功」的结论；下一次进入页面守卫会再拉
  }
  const menuChanged =
    setDiff(beforeMenus, auth.menus) || setDiff(beforeActions, auth.actions)

  if (result.warnings.length > 0) {
    ElMessage({
      type: 'warning',
      duration: 4000,
      showClose: true,
      message: `已保存，有 ${result.warnings.length} 项不生效`,
    })
    return
  }
  let message = `已保存 · 新增 ${result.added} / 移除 ${result.removed} / 调整 ${result.changed}`
  if (result.highRiskGranted.length > 0) message += ` · 含 ${result.highRiskGranted.length} 个高危`
  if (menuChanged) message += '，你的菜单已更新'
  ElMessage({ type: 'success', duration: 2000, message })
}

async function doSave(confirmHighRisk: boolean): Promise<void> {
  inlineError.value = null
  startSlowTimer()
  try {
    const result = await save(confirmHighRisk)
    await afterSaved(result)
  } catch (err) {
    handleSaveError(err)
  } finally {
    stopSlowTimer()
  }
}

function handleSaveError(err: unknown): void {
  const apiErr = err as { code?: string; status?: number; message?: string; detail?: unknown }
  const detail = (apiErr.detail ?? {}) as Record<string, unknown>
  const status = apiErr.status ?? 0

  if (status === 409 && apiErr.code === 'HIGH_RISK_REQUIRES_CONFIRM') {
    const codes = Array.isArray(detail.actions) ? (detail.actions as string[]) : highRiskPending.value
    openHighRisk(codes)
    return
  }
  if (status === 409 && apiErr.code === 'ADMIN_LOCKOUT_RISK') {
    const codes = Array.isArray(detail.codes) ? (detail.codes as string[]) : FROZEN_CODES
    lockout.value = {
      open: true,
      codes,
      activeAdminCount: Number(detail.activeAdminCount ?? 0),
    }
    return
  }
  if (status === 400) {
    // 未知码：内联警示 + 把该码填进动作搜索，让用户立刻定位（DESIGN §3.2）
    const unknown = /[：:]\s*([^\s：:]+)\s*$/.exec(apiErr.message ?? '')?.[1] ?? ''
    if (unknown) actionQuery.value = unknown
    inlineError.value = {
      kind: 'unknown',
      message: unknown ? `授权内容里有未知码 ${unknown}，已填入搜索框` : '授权内容里有未知码，保存未生效',
    }
    return
  }
  if (status === 403) {
    inlineError.value = { kind: 'forbidden', message: '无权修改该角色授权，请联系租户管理员' }
    return
  }
  inlineError.value = {
    kind: 'network',
    message: apiErr.message ? `保存失败：${apiErr.message}` : '保存失败：无法连接服务，请重试',
  }
}

// ---------------------------------------------------------------- 对话框

const lockout = ref<{ open: boolean; codes: string[]; activeAdminCount: number }>({
  open: false,
  codes: [],
  activeAdminCount: 0,
})

const highRisk = ref<{
  open: boolean
  ack: boolean
  actions: Array<{ code: string; name: string; page: string }>
}>({ open: false, ack: false, actions: [] })

const confirmDlg = ref<{ open: boolean; mode: 'reset' | 'leave' }>({ open: false, mode: 'reset' })

function openHighRisk(codes: string[]): void {
  highRisk.value = {
    open: true,
    ack: false,
    actions: codes.map((code) => {
      const action = allActions.value.find((item) => item.code === code)
      const page = allPages.value.find((item) => item.actions.some((a) => a.code === code))
      return { code, name: action?.name ?? code, page: page ? `${page.name}（${page.code}）` : '—' }
    }),
  }
}

function confirmHighRiskGrant(): void {
  if (!highRisk.value.ack) return
  highRisk.value = { ...highRisk.value, open: false }
  void doSave(true)
}

/** 「返回调整」：聚焦首个被锁控件（冻结功能码的三档），只读不重试 */
async function closeLockout(): Promise<void> {
  const first = lockout.value.codes[0]
  lockout.value = { ...lockout.value, open: false }
  advancedOpen.value = true
  await nextTick()
  const selector = first ? `[data-fn="${CSS.escape(first)}"] .seg__item[aria-checked="true"]` : ''
  const target = selector ? panelRef.value?.querySelector<HTMLElement>(selector) : null
  target?.focus()
}

function onSave(): void {
  if (saving.value || !dirty.value) return
  if (role.value?.code !== 'PLATFORM_ADMIN' && highRiskPending.value.length > 0) {
    openHighRisk(highRiskPending.value)
    return
  }
  void doSave(false)
}

function askReset(): void {
  if (!dirty.value) return
  confirmDlg.value = { open: true, mode: 'reset' }
}

function onConfirmDiscard(): void {
  const mode = confirmDlg.value.mode
  confirmDlg.value = { ...confirmDlg.value, open: false }
  if (mode === 'reset') {
    reset()
    return
  }
  reset()
  const target = pendingTo.value
  pendingTo.value = null
  if (target) void router.push(target)
}

// ---------------------------------------------------------------- 离开拦截

const pendingTo = ref<string | null>(null)

onBeforeRouteLeave((to) => {
  if (!dirty.value) return true
  pendingTo.value = to.fullPath
  confirmDlg.value = { open: true, mode: 'leave' }
  return false
})

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  void reload()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  stopSlowTimer()
})

function goBack(): void {
  void router.push('/admin/roles')
}

watch(roleId, () => {
  void reload()
})
</script>

<template>
  <section class="perm">
    <header class="perm__role">
      <button class="perm__back" type="button" @click="goBack">← 角色与权限</button>
      <template v-if="role">
        <h2 class="perm__name">{{ role.name }}</h2>
        <code class="perm__code">{{ role.code }}</code>
        <span class="pill" :class="role.builtin ? 'pill--muted' : 'pill--builtin'">
          {{ role.builtin ? '内置' : '自定义' }}
        </span>
        <span class="pill" :class="role.status === 'DISABLED' ? 'pill--off' : 'pill--on'">
          {{ role.status === 'DISABLED' ? '已停用' : '启用' }}
        </span>
        <span class="perm__users">{{ role.userCount }} 人</span>
      </template>
      <div class="perm__spacer" />
      <span class="perm__summary">已授 {{ grantedPages }} 页 · {{ grantedActions }} 动作</span>
      <button class="btn btn--ghost" type="button" :disabled="loading" @click="reload">刷新</button>
    </header>

    <div v-if="role && role.status === 'DISABLED'" class="banner" role="status">
      该角色已停用，其用户当前不获得任何授权；仍可继续配置
    </div>

    <ErrorState
      v-if="error && !view"
      :status="error.status"
      :code="error.code"
      :message="error.message"
      @retry="reload"
    />

    <div v-else-if="loading && !view" class="perm__loading" role="status">正在加载授权树…</div>

    <div v-else-if="view" class="perm__cols">
      <aside class="surface perm__tree">
        <RoleGrantTree
          :modules="view.modules"
          :draft="draft"
          :selected="selectedPage"
          :only-granted="onlyGranted"
          :unbound="unboundGroup"
          @update:selected="selectedPage = $event"
          @update:only-granted="onlyGranted = $event"
          @set-page="setPage"
          @set-module="setModule"
        />
      </aside>

      <section ref="panelRef" class="surface perm__panel">
        <!-- 未绑定页面的功能码：独立分组，右栏是一张三档功能码表（不属任何页面） -->
        <template v-if="unboundSelected">
          <header class="page-head">
            <div class="page-head__row">
              <span class="page-head__name">{{ unboundGroup?.name ?? '未绑定页面的功能码' }}</span>
              <code class="page-head__code">{{ unboundGroup?.code ?? 'UNBOUND' }}</code>
              <span class="page-head__label">{{ unboundRows.length }} 个码</span>
              <span class="unb__granted">已授 {{ unboundGranted }}/{{ unboundRows.length }}</span>
            </div>
          </header>

          <p class="unb__hint">
            这些功能码没有页面承载，但仍是动作的权源：置为「拒绝」会让依赖它的动作（如评审通过、发布强制放行、Wiki
            编辑）一并失效。改动随底部「保存并生效」一次提交，不影响页面准入。
          </p>

          <div class="unb" role="table" aria-label="未绑定页面的功能码">
            <div class="unb__thead" role="row">
              <span class="unb__c-fn" role="columnheader">功能码</span>
              <span class="unb__c-domain" role="columnheader">所属域</span>
              <span class="unb__c-state" role="columnheader">当前</span>
              <span class="c-level" role="columnheader">档位</span>
            </div>

            <div class="unb__scroll">
              <p v-if="unboundRows.length === 0" class="acts__empty">
                当前服务端未返回未绑定功能码分组（旧后端或全部功能码均已绑定页面）
              </p>
              <div
                v-for="fn in unboundRows"
                :key="fn.code"
                class="unb__row"
                :class="{ 'is-dirty': isFnDirty(fn.code) }"
                role="row"
              >
                <code class="unb__code">{{ fn.code }}</code>
                <span class="unb__c-domain">{{ domainOf(fn.code) }}</span>
                <span
                  class="unb__c-state"
                  :class="`unb__state--${accessOf('FUNCTION', fn.code).toLowerCase()}`"
                >
                  {{ accessLabel(accessOf('FUNCTION', fn.code)) }}
                </span>
                <AccessSegmented
                  class="c-level"
                  :model-value="accessOf('FUNCTION', fn.code)"
                  :aria-label="`功能码 ${fn.code}`"
                  @update:model-value="setFunctionAccess(fn.code, $event)"
                />
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="currentPage">
          <header class="page-head">
            <div class="page-head__row">
              <span class="page-head__name">{{ currentPage.name }}</span>
              <code class="page-head__code">{{ currentPage.code }}</code>
              <span class="page-head__label">页面</span>
              <AccessSegmented
                :model-value="accessOf('MENU', currentPage.code)"
                aria-label="页面准入"
                @update:model-value="setPage(currentPage.code, $event)"
              />
            </div>
            <div class="page-head__fn">
              <span class="page-head__label">功能码</span>
              <span
                v-for="fn in currentPage.functionCodes"
                :key="fn.code"
                class="fn-chip"
                :class="`fn-chip--${accessOf('FUNCTION', fn.code).toLowerCase()}`"
                :title="`该功能码被 ${functionUsage(fn.code)} 个页面共用`"
              >
                <code>{{ fn.code }}</code>
                <span class="fn-chip__level">{{ accessLabel(accessOf('FUNCTION', fn.code)) }}</span>
                <svg
                  v-if="isFrozen(fn.code)"
                  class="fn-chip__lock"
                  viewBox="0 0 14 14"
                  aria-hidden="true"
                >
                  <rect x="2.6" y="6.2" width="8.8" height="6" rx="1.4" fill="none" stroke="currentColor" stroke-width="1.2" />
                  <path d="M4.6 6V4.6a2.4 2.4 0 0 1 4.8 0V6" fill="none" stroke="currentColor" stroke-width="1.2" />
                </svg>
                <span v-if="isFrozen(fn.code)" class="sr-only">平台管理员冻结码，不可移除</span>
              </span>
              <button
                class="link-btn"
                type="button"
                :aria-expanded="advancedOpen ? 'true' : 'false'"
                @click="advancedOpen = !advancedOpen"
              >
                <span class="link-btn__arrow" :class="{ 'is-open': advancedOpen }" aria-hidden="true">▸</span>
                高级
              </button>
              <span
                v-if="currentPage.functionCodes.some((fn) => isFrozen(fn.code))"
                class="page-head__lock-hint"
                title="平台管理员冻结码，不可移除"
              >
                冻结码不可移除
              </span>
            </div>
          </header>

          <div v-if="advancedOpen" class="adv">
            <p class="adv__hint">改动会影响共用此功能码的多个页面，保存后立即生效</p>
            <div
              v-for="fn in currentPage.functionCodes"
              :key="fn.code"
              class="adv__row"
              :data-fn="fn.code"
            >
              <code class="adv__code">{{ fn.code }}</code>
              <span class="adv__usage">{{ functionUsage(fn.code) }} 页共用</span>
              <span v-if="isFrozen(fn.code)" class="adv__lock" title="平台管理员冻结码，不可移除">冻结</span>
              <AccessSegmented
                :model-value="accessOf('FUNCTION', fn.code)"
                :disabled="isFrozen(fn.code)"
                :aria-label="`功能码 ${fn.code}`"
                @update:model-value="setFunctionAccess(fn.code, $event)"
              />
            </div>
          </div>

          <div v-if="pageWarning" class="warn" role="status">
            本页 {{ pageGrantedActions }} 个动作已授但页面未开放，不生效 · 生效
            {{ pageEffectiveActions }}/{{ currentPage.actions.length }}
          </div>

          <div class="acts">
            <div class="acts__tools">
              <label class="acts__all">
                <input
                  type="checkbox"
                  :checked="actionsTri === 'ALLOW'"
                  :indeterminate="actionsTri === 'MIXED'"
                  aria-label="全选本页动作"
                  @change="toggleAllActions"
                />
                全选本页动作
              </label>
              <span class="acts__count">已授 {{ pageGrantedActions }}/{{ currentPage.actions.length }}</span>
              <span class="acts__count acts__count--muted">生效 {{ pageEffectiveActions }}</span>
              <input
                v-model="actionQuery"
                class="acts__search"
                type="search"
                placeholder="搜索动作"
                aria-label="搜索动作"
              />
            </div>

            <div class="acts__scroll">
              <div class="acts__table" role="table" aria-label="动作授权">
                <div class="acts__thead" role="row">
                  <span class="c-act" role="columnheader">动作</span>
                  <span class="c-ep" role="columnheader">端点</span>
                  <span class="c-risk" role="columnheader">高危</span>
                  <span class="c-level" role="columnheader">档位</span>
                </div>

                <p v-if="currentPage.actions.length === 0" class="acts__empty">
                  本页只有页面级准入，没有可单独配置的按钮
                </p>
                <p v-else-if="filteredActions.length === 0" class="acts__empty">
                  没有匹配「{{ actionQuery }}」的动作
                </p>

                <div v-for="action in filteredActions" :key="action.code" class="acts__row" role="row">
                  <div class="c-act">
                    <code class="acts__code">{{ action.code }}</code>
                    <div class="acts__meta">
                      <span class="acts__name">{{ action.name }}</span>
                      <span v-if="action.highRisk" class="pill pill--risk">高危</span>
                      <span v-if="isIneffective(action)" class="acts__off">不生效</span>
                    </div>
                  </div>
                  <span class="c-ep" :title="action.code">{{ endpointOf(action.code) }}</span>
                  <span class="c-risk" :class="{ 'is-risk': action.highRisk }">
                    {{ action.highRisk ? '是' : '—' }}
                  </span>
                  <AccessSegmented
                    class="c-level"
                    :model-value="accessOf('ACTION', action.code)"
                    :aria-label="`动作 ${action.name}`"
                    @update:model-value="setAccess('ACTION', action.code, $event)"
                  />
                </div>
              </div>
            </div>
          </div>
        </template>

        <p v-else class="perm__placeholder">左侧选择一个页面开始配置</p>
      </section>
    </div>

    <div v-if="inlineError" class="errbar" :class="`errbar--${inlineError.kind}`" role="alert">
      <span>{{ inlineError.message }}</span>
      <button
        v-if="inlineError.kind === 'network'"
        class="link-btn"
        type="button"
        :disabled="saving"
        @click="doSave(false)"
      >
        重试
      </button>
    </div>

    <footer v-if="view" class="savebar" aria-label="保存授权">
      <div class="savebar__left">
        <el-tooltip :content="dirtySummary" placement="top" :disabled="dirtyCount === 0 || saving">
          <span class="savebar__count" tabindex="0" aria-live="polite">
            {{ saving ? '正在保存…' : dirtyCount > 0 ? `${dirtyCount} 项未保存` : '无改动' }}
          </span>
        </el-tooltip>
        <span
          v-if="unboundDirty > 0"
          class="savebar__unbound"
          :title="`${unboundDirty} 个未绑定页面的功能码被改动`"
        >
          含未绑定 {{ unboundDirty }} 处
        </span>
        <span v-if="slow" class="savebar__slow">保存较慢，请勿离开</span>
        <span v-else-if="warnings.length > 0" class="savebar__slow">
          {{ warnings.length }} 项配置不生效（页面未开放）
        </span>
      </div>
      <div class="savebar__right">
        <button class="btn btn--text" type="button" :disabled="!dirty || saving" @click="askReset">
          重置
        </button>
        <button
          class="btn btn--primary savebar__save"
          type="button"
          :disabled="!dirty || saving"
          :aria-busy="saving ? 'true' : 'false'"
          @click="onSave"
        >
          {{ saving ? '保存中…' : '保存并生效' }}
        </button>
      </div>
    </footer>

    <!-- 409 锁死：只读，不重试 -->
    <el-dialog
      v-model="lockout.open"
      title="无法保存：会锁死管理员入口"
      width="480px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p class="dlg__text">
        移除平台管理员的冻结功能码后，将没有人能再进入权限管理页，因此本次保存被服务端拒绝，授权数据未变更。
      </p>
      <ul class="dlg__list">
        <li v-for="code in lockout.codes" :key="code"><code>{{ code }}</code></li>
      </ul>
      <p class="dlg__hint">当前可用的平台管理员：{{ lockout.activeAdminCount }} 人</p>
      <template #footer>
        <button class="btn btn--primary" type="button" @click="closeLockout">返回调整</button>
      </template>
    </el-dialog>

    <!-- 高危二次确认：确认后重放同一份草稿 -->
    <el-dialog
      v-model="highRisk.open"
      title="确认授予高危动作"
      width="480px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p class="dlg__text">以下动作会影响生产或权限本身，确认后立即生效：</p>
      <ul class="dlg__list">
        <li v-for="item in highRisk.actions" :key="item.code">
          <code>{{ item.code }}</code>
          <span class="dlg__name">{{ item.name }}</span>
          <span class="dlg__page">{{ item.page }}</span>
          <span class="pill pill--risk">高危</span>
        </li>
      </ul>
      <label class="dlg__ack">
        <input v-model="highRisk.ack" type="checkbox" />
        我了解该动作的影响
      </label>
      <template #footer>
        <button class="btn" type="button" @click="highRisk.open = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="!highRisk.ack" @click="confirmHighRiskGrant">
          确认授予
        </button>
      </template>
    </el-dialog>

    <!-- 重置 / 离开 共用同一确认框 -->
    <el-dialog
      v-model="confirmDlg.open"
      :title="confirmDlg.mode === 'leave' ? '有未保存的授权改动' : '放弃未保存的改动？'"
      width="480px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p class="dlg__text">
        有 {{ dirtyCount }} 项未保存{{ confirmDlg.mode === 'leave' ? '，离开后这些改动会丢弃。' : '，确认后回到服务端快照。' }}
      </p>
      <template #footer>
        <button class="btn" type="button" @click="confirmDlg.open = false">留下</button>
        <button class="btn btn--danger" type="button" @click="onConfirmDiscard">放弃并离开</button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.perm {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 72px;
}
.perm__role {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  flex-wrap: wrap;
}
.perm__back {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-body);
  font-size: 13px;
  cursor: pointer;
  transition: color 120ms var(--ease);
}
.perm__back:hover {
  color: var(--color-primary);
}
.perm__back:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
.perm__name {
  margin: 0 0 0 4px;
  color: var(--text-title);
  font-size: 14px;
  font-weight: 600;
}
.perm__code {
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.perm__users {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.perm__spacer {
  flex: 1;
}
.perm__summary {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.perm__loading {
  padding: 24px 0;
  color: var(--text-muted-aa);
  font-size: 13px;
}
.perm__cols {
  display: flex;
  gap: 12px;
  align-items: stretch;
}
.perm__tree {
  flex: 0 0 auto;
  width: var(--w-perm-tree, 320px);
  height: var(--perm-card-h);
  min-height: 480px;
  overflow: hidden;
}
.perm__panel {
  flex: 1 1 auto;
  min-width: 0;
  height: var(--perm-card-h);
  min-height: 480px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.perm__placeholder {
  margin: auto;
  color: var(--text-muted-aa);
  font-size: 13px;
}

/* ---- 右栏页头（60 起） ---- */
.page-head {
  padding: 10px 16px;
  border-bottom: 1px solid var(--divider);
}
.page-head__row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
}
.page-head__name {
  color: var(--text-title);
  font-size: 14px;
  font-weight: 600;
}
.page-head__code {
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.page-head__label {
  color: var(--text-muted-aa);
  font-size: 12px;
}
.page-head__row .page-head__label {
  margin-left: auto;
}
.page-head__fn {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.page-head__lock-hint {
  color: var(--text-muted-aa);
  font-size: 11px;
}
.fn-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 12px;
}
.fn-chip code {
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
  color: var(--text-body);
}
.fn-chip__level {
  font-size: 11px;
}
.fn-chip--allow {
  background: var(--color-success-bg);
  border-color: #cdebd7;
}
.fn-chip--allow .fn-chip__level {
  color: var(--success-ink);
}
.fn-chip--restricted {
  background: var(--color-warning-bg);
  border-color: #f0ddb8;
}
.fn-chip--restricted .fn-chip__level {
  color: var(--warning-ink);
}
.fn-chip--deny {
  background: var(--color-danger-bg);
  border-color: #f5d3d3;
}
.fn-chip--deny .fn-chip__level {
  color: var(--danger-ink);
}
.fn-chip__lock {
  width: 12px;
  height: 12px;
  color: var(--perm-lock, #86909c);
}

/* ---- 未绑定页面的功能码（r20：不属任何页面，但仍派生动作，必须可编辑） ---- */
.unb__granted {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.unb__hint {
  margin: 8px 16px 0;
  padding: 8px 12px;
  border-left: 2px solid var(--color-warning);
  border-radius: 6px;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.65;
}
.unb {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  margin-top: 8px;
}
.unb__thead {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 16px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  color: var(--text-muted-aa);
  font-size: 12px;
  font-weight: 500;
}
.unb__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.unb__row {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
  transition: background 120ms var(--ease);
}
.unb__row:hover {
  background: var(--bg-hover);
}
.unb__row.is-dirty {
  background: var(--color-warning-bg);
}
.unb__code,
.unb__c-fn {
  flex: 1 1 auto;
  min-width: 200px;
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.unb__c-domain {
  flex: 0 0 72px;
  width: 72px;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.unb__c-state {
  flex: 0 0 44px;
  width: 44px;
  font-size: 12px;
}
.unb__state--allow {
  color: var(--success-ink);
}
.unb__state--restricted {
  color: var(--warning-ink);
}
.unb__state--deny {
  color: var(--text-muted-aa);
}
.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}
.link-btn:hover {
  background: var(--color-primary-light);
}
.link-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.link-btn__arrow {
  display: inline-block;
  font-size: 10px;
  transition: transform 160ms var(--ease);
}
.link-btn__arrow.is-open {
  transform: rotate(90deg);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

/* ---- 高级折叠区 ---- */
.adv {
  padding: 8px 16px 10px;
  border-bottom: 1px solid var(--divider);
  background: #fcfdff;
}
.adv__hint {
  margin: 0 0 6px;
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.5;
}
.adv__row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
}
.adv__code {
  min-width: 160px;
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.adv__usage {
  flex: 1;
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.adv__lock {
  padding: 0 6px;
  border-radius: 4px;
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 11px;
}

/* ---- 警示条 ---- */
.warn {
  min-height: 36px;
  margin: 8px 16px 0;
  padding: 6px 12px;
  border-left: 2px solid var(--color-warning);
  border-radius: 6px;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.6;
}

/* ---- 动作表 ---- */
.acts {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}
.acts__tools {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
}
.acts__all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-body);
  font-size: 13px;
  cursor: pointer;
}
.acts__all input {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: var(--color-primary);
}
.acts__count {
  color: var(--text-body);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.acts__count--muted {
  color: var(--text-muted-aa);
}
.acts__search {
  margin-left: auto;
  width: 160px;
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
  outline: none;
}
.acts__search:focus-visible {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(43, 111, 229, 0.12);
}
.acts__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.acts__table {
  min-width: 0;
}
.acts__thead {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 16px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  color: var(--text-muted-aa);
  font-size: 12px;
  font-weight: 500;
}
.acts__row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--h-action-row, 52px);
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
  transition: background 120ms var(--ease);
}
.acts__row:hover {
  background: var(--bg-hover);
}
.c-act {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
  min-width: var(--w-perm-act, 240px);
}
.c-ep {
  flex: 0 0 var(--w-perm-endpoint, 136px);
  width: var(--w-perm-endpoint, 136px);
  overflow: hidden;
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.c-risk {
  flex: 0 0 var(--w-perm-risk, 44px);
  width: var(--w-perm-risk, 44px);
  color: var(--text-muted-aa);
  font-size: 12px;
  text-align: center;
}
.c-risk.is-risk {
  color: var(--danger-ink);
  font-weight: 500;
}
.c-level {
  flex: 0 0 var(--w-perm-level, 168px);
  width: var(--w-perm-level, 168px);
}
.acts__code {
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.acts__meta {
  display: flex;
  align-items: center;
  gap: 6px;
}
.acts__name {
  color: var(--text-title);
  font-size: 13px;
}
.acts__off {
  color: var(--warning-ink);
  font-size: 11px;
}
.acts__empty {
  margin: 0;
  padding: 20px 16px;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.pill {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.pill--on {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.pill--off,
.pill--muted,
.pill--builtin {
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.pill--risk {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}

/* ---- 内联错误条 ---- */
.errbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 6px 12px;
  border-left: 2px solid var(--color-warning);
  border-radius: 6px;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.errbar--forbidden,
.errbar--network {
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.errbar .link-btn {
  margin-left: auto;
}

/* ---- 保存条（sticky，跨两栏） ---- */
.savebar {
  position: sticky;
  bottom: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 12px;
  height: var(--h-save-bar, 56px);
  padding: 0 16px;
  border-top: 1px solid var(--border);
  background: var(--bg-card);
}
.savebar__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.savebar__count {
  color: var(--text-title);
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  border-radius: 4px;
}
.savebar__count:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.savebar__slow {
  color: var(--warning-ink);
  font-size: 12px;
}
.savebar__unbound {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.savebar__right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.savebar__save {
  width: 96px;
}

/* ---- 通用按钮（本页内联，避免与全局 .btn 语义冲突） ---- */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 120ms var(--ease),
    border-color 120ms var(--ease),
    color 120ms var(--ease);
}
.btn:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.btn:active:not(:disabled) {
  background: var(--color-primary-light);
}
.btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.btn:disabled {
  color: var(--text-secondary);
  cursor: not-allowed;
  opacity: 0.6;
}
.btn--primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}
.btn--primary:hover:not(:disabled) {
  border-color: var(--color-primary-hover);
  background: var(--color-primary-hover);
}
.btn--primary:active:not(:disabled) {
  background: var(--color-primary-active);
}
.btn--primary:disabled {
  border-color: var(--border);
  background: var(--color-primary-light);
  color: var(--text-secondary);
}
.btn--text,
.btn--ghost {
  border-color: transparent;
  background: transparent;
}
.btn--danger {
  border-color: var(--color-danger);
  background: var(--color-danger);
  color: #fff;
}
.btn--danger:hover:not(:disabled) {
  background: var(--danger-ink);
  border-color: var(--danger-ink);
}

/* ---- 对话框 ---- */
.dlg__text {
  margin: 0 0 10px;
  color: var(--text-body);
  font-size: 13px;
  line-height: 1.7;
}
.dlg__list {
  margin: 0 0 10px;
  padding: 10px 12px;
  max-height: 200px;
  overflow-y: auto;
  border-radius: 6px;
  background: var(--bg-hover);
  list-style: none;
}
.dlg__list li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  font-size: 12px;
}
.dlg__list code {
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.dlg__name {
  color: var(--text-title);
  font-size: 13px;
}
.dlg__page {
  color: var(--text-muted-aa);
  font-size: 12px;
}
.dlg__hint {
  margin: 0;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.dlg__ack {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-body);
  font-size: 13px;
  cursor: pointer;
}
.dlg__ack input {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: var(--color-primary);
}

/* 端点列在 <1440 隐藏，动作码 title 承载（DESIGN §0.6） */
@media (max-width: 1439px) {
  .c-ep {
    display: none;
  }
}
</style>
