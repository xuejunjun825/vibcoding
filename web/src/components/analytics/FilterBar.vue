<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { DictionaryDomain, DictionaryVendor } from '@/api/dictionaries'
import { loadDictionaries } from '@/api/dictionaries'
import type { PresetKey } from '@/api/analytics'
import type { RequirementType } from '@/api/types'
import { PRESETS, PRESET_LABEL, useAnalyticsStore } from '@/stores/analytics'
import { REQUIREMENT_TYPES } from '@/views/req/platform'

/**
 * 全仓唯一的分析域筛选控件（TECHNICAL §5.1 / AC-7、AC-19）。
 *
 * 它不取数、不解析 URL、不持有请求：只把用户意图交给 store 的 `applyToRoute`，
 * 三页拿到的筛选与请求参数因此天然一致（一次交互 = 一次 applyToRoute = 一次导航）。
 * `fields` 决定露哪几个控件：/portal 传 domain+vendor+preset，/dashboard 再加 reqType。
 */
type FieldKey = 'domain' | 'vendor' | 'preset' | 'reqType'

const props = withDefaults(
  defineProps<{ fields?: FieldKey[]; dense?: boolean }>(),
  { fields: () => ['domain', 'vendor', 'preset', 'reqType'] as FieldKey[], dense: false },
)

const router = useRouter()
const store = useAnalyticsStore()

const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])
/** 字典是否已尝试加载：加载前不把下拉判成「不可用」，避免首帧闪烁禁用态 */
const dictLoaded = ref(false)

const has = (field: FieldKey): boolean => props.fields.includes(field)

/**
 * 字典降级（AC-19）：`loadDictionaries` 内部逐项 catch（`silentForbidden`），
 * 拿不到只会得到空数组——绝不让用户被甩到 /403，也不阻断其余数据。
 * 空数组 = 该项禁用 + 页内提示，而不是一个点了没反应的空白下拉。
 */
const domainUnavailable = computed(() => dictLoaded.value && has('domain') && domains.value.length === 0)
const vendorUnavailable = computed(() => dictLoaded.value && has('vendor') && vendors.value.length === 0)
const dictError = computed(() => {
  if (!dictLoaded.value) return ''
  const lost = [
    domainUnavailable.value ? '业务域' : '',
    vendorUnavailable.value ? '厂商' : '',
  ].filter(Boolean)
  return lost.length ? `字典不可用，相关筛选已降级：${lost.join('、')}已禁用（不影响数据读取）` : ''
})

const preset = computed<PresetKey>(() => store.filter.preset)
/** 仅自定义窗口才显示日期区间；预设窗口由 store 按 preset 重算，避免半截状态 */
const range = computed<[string, string] | null>(() =>
  store.filter.from && store.filter.to ? [store.filter.from, store.filter.to] : null,
)
const reqType = computed<string>(() => store.filter.reqType ?? '')

onMounted(async () => {
  const bundle = await loadDictionaries()
  domains.value = bundle.domains
  vendors.value = bundle.vendors
  dictLoaded.value = true
})

function optionalId(value: unknown): number | null {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

/**
 * 换域：厂商字典是租户级的，没有「厂商属于哪个域」的映射（G-2），
 * 因此无法判断旧 vendorId 是否仍在新域内 —— 宁可清掉，也不发一个可能恒空的组合。
 */
function changeDomain(value: unknown): void {
  const next = optionalId(value)
  if (next === store.filter.domainId) return
  store.applyToRoute(
    router,
    next === null ? { domainId: null } : { domainId: next, vendorId: null },
  )
}

function changeVendor(value: unknown): void {
  store.applyToRoute(router, { vendorId: optionalId(value) })
}

function changePreset(value: unknown): void {
  if (!PRESETS.includes(value as PresetKey)) return
  store.applyToRoute(router, { preset: value as PresetKey })
}

function changeRange(value: unknown): void {
  // 清空日期 = 放弃自定义窗口，退回近 30 天（否则 UI 停在空区间、请求却仍带旧日期）
  if (!Array.isArray(value) || value.length !== 2) {
    store.applyToRoute(router, { preset: '30d' })
    return
  }
  const [from, to] = value.map((item) => String(item))
  store.applyToRoute(router, { from, to, preset: 'custom' })
}

function changeReqType(value: unknown): void {
  const next = (typeof value === 'string' ? value : '') as RequirementType | ''
  store.applyToRoute(router, { reqType: next === '' ? null : next })
}

function reset(): void {
  store.resetFilter(router)
}
</script>

<template>
  <div
    class="an-filters"
    :class="{ 'an-filters--dense': props.dense }"
    role="search"
    aria-label="分析筛选"
  >
    <el-select
      v-if="has('domain')"
      :model-value="store.filter.domainId"
      :disabled="domainUnavailable"
      placeholder="业务域"
      clearable
      filterable
      size="small"
      aria-label="业务域"
      data-testid="filter-domain"
      @update:model-value="changeDomain"
    >
      <el-option v-for="item in domains" :key="item.id" :label="item.name" :value="item.id" />
    </el-select>

    <el-select
      v-if="has('vendor')"
      :model-value="store.filter.vendorId"
      :disabled="vendorUnavailable"
      placeholder="厂商"
      clearable
      filterable
      size="small"
      aria-label="厂商"
      data-testid="filter-vendor"
      @update:model-value="changeVendor"
    >
      <el-option v-for="item in vendors" :key="item.id" :label="item.name" :value="item.id" />
    </el-select>

    <template v-if="has('preset')">
      <el-select
        :model-value="preset"
        size="small"
        aria-label="时间窗"
        data-testid="filter-preset"
        @update:model-value="changePreset"
      >
        <el-option v-for="key in PRESETS" :key="key" :label="PRESET_LABEL[key]" :value="key" />
      </el-select>
      <!-- el-date-picker 不转发 data-testid（多根 + $attrs 白名单），锚点放在自控 wrapper 上 -->
      <span v-if="preset === 'custom'" class="an-date-editor" data-testid="filter-range">
        <el-date-picker
          :model-value="range"
          type="daterange"
          size="small"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
          @update:model-value="changeRange"
        />
      </span>
    </template>

    <el-select
      v-if="has('reqType')"
      :model-value="reqType"
      size="small"
      aria-label="需求类型"
      data-testid="filter-reqtype"
      @update:model-value="changeReqType"
    >
      <el-option label="全部需求类型" value="" />
      <el-option
        v-for="item in REQUIREMENT_TYPES"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>

    <el-button size="small" text data-testid="filter-reset" @click="reset">重置</el-button>

    <span v-if="dictError" class="an-hint an-filters__hint" role="status">{{ dictError }}</span>
    <span v-else-if="store.droppedKeys.length" class="an-hint an-filters__hint" role="status">
      部分筛选已重置：{{ store.droppedKeys.join('、') }}
    </span>
  </div>
</template>

<style scoped>
/* 结构类（.an-filters）在 views/analytics/shared.css，这里只放本组件的密度细节 */
.an-filters--dense {
  gap: 6px;
  padding: 6px 8px;
}

.an-filters--dense :deep(.el-select) {
  flex: 0 0 140px;
  width: 140px;
}

.an-filters__hint {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 日期区间锚点：span 包装成与 el-select 同级的定宽块，避免 inline 宽度塌陷 */
.an-date-editor {
  display: inline-flex;
  flex: 0 0 248px;
  width: 248px;
}

.an-date-editor :deep(.el-date-editor) {
  width: 100%;
}

.an-filters :deep(.el-select.is-disabled .el-select__placeholder) {
  color: var(--text-muted-aa);
}
</style>
