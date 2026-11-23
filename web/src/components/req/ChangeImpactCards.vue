<script setup lang="ts">
import { computed } from 'vue'
import type { ChangeImpact } from '@/api/req'
import { IMPACT_MISSING, formatDaysDelta, formatDaysValue, formatRatio, isMissing, staleVerdict } from '@/views/req/change'

/**
 * 影响评估四卡（DESIGN §3.2 ④ / §3.3）：
 * 影响是决策依据，所以缺数据只写「无法评估」——绝不出现 0、— 或「无影响」，
 * 否则读者会把「没查到」当成「没影响」。数据未到位前连 0 都不显示（走骨架）。
 */
const props = withDefaults(
  defineProps<{
    impact: ChangeImpact | null
    mode?: 'preview' | 'readonly'
    loading?: boolean
  }>(),
  { mode: 'readonly', loading: false },
)

const days = computed(() => props.impact?.days ?? null)
const verdict = computed(() => staleVerdict(days.value))
const children = computed(() => props.impact?.children?.count ?? null)
const release = computed(() => props.impact?.release ?? null)
const targetRelease = computed(() => props.impact?.targetRelease ?? null)
const postLock = computed(() => props.impact?.postLock ?? null)
const lockedIds = computed<number[]>(() => postLock.value?.lockedReleaseIds ?? [])
/** 数组缺失返回 null（= 无法评估），空数组才是「0 张」——两者不能混为一谈 */
const orders = computed<string[] | null>(() => {
  const list = props.impact?.admission?.passedOrderCodes
  return Array.isArray(list) ? list : null
})

function releaseName(item: { id?: number | null; name?: string | null; code?: string | null } | null): string {
  if (!item) return '未关联版本'
  if (item.name) return item.name
  if (item.code) return item.code
  if (item.id !== null && item.id !== undefined) return `版本 #${item.id}`
  return '未关联版本'
}

const releaseLocked = computed(() => postLock.value?.value === true || release.value?.locked === true)

const daysHeadline = computed(() => {
  if (!days.value) return IMPACT_MISSING
  const before = days.value.before
  const after = days.value.after
  if (isMissing(before) || isMissing(after)) return IMPACT_MISSING
  return `${formatDaysValue(before)} → ${formatDaysValue(after)}`
})

const orderText = computed(() => {
  const list = orders.value
  if (!props.impact || list === null) return IMPACT_MISSING
  return `${list.length} 张`
})
</script>

<template>
  <div class="impact" :class="[`impact--${props.mode}`]" data-test="change-impact">
    <template v-if="props.loading">
      <div v-for="index in 4" :key="index" class="impact__card impact__card--skeleton skeleton" />
    </template>
    <template v-else>
      <article class="impact__card" data-test="impact-children">
        <p class="impact__label">子需求</p>
        <p class="impact__value mono">{{ isMissing(children) ? IMPACT_MISSING : `${children} 个` }}</p>
        <p class="impact__sub">
          {{ children ? '父需求人天由子需求汇总，不在此处改' : '没有子需求，可单独改人天' }}
        </p>
      </article>

      <article class="impact__card" data-test="impact-release">
        <p class="impact__label">{{ targetRelease ? '版本（将移入）' : '当前版本' }}</p>
        <p class="impact__value">
          {{ releaseName(targetRelease ?? release) }}
          <span v-if="(targetRelease ?? release)?.status" class="impact__chip mono">
            {{ (targetRelease ?? release)?.status }}
          </span>
        </p>
        <p class="impact__sub" :class="{ 'is-warning': releaseLocked }">
          <template v-if="releaseLocked">
            已锁定<template v-if="lockedIds.length">（#{{ lockedIds.join(' / #') }}）</template> · 应用将额外写
            CHANGE_AFTER_RELEASE_LOCK
          </template>
          <template v-else-if="postLock">未锁定 · 应用直接改需求字段</template>
          <template v-else>{{ IMPACT_MISSING }}</template>
        </p>
      </article>

      <article class="impact__card" data-test="impact-days">
        <p class="impact__label">人天预测</p>
        <p class="impact__value mono">{{ daysHeadline }}</p>
        <p class="impact__sub">
          <span class="mono">{{ formatDaysDelta(days?.delta) }}</span>
          <span class="impact__sep" aria-hidden="true">·</span>
          <span>对已批评估 <span class="mono">{{ formatRatio(days?.deltaRatio) }}</span></span>
          <span class="impact__sep" aria-hidden="true">·</span>
          <span :class="`impact__verdict impact__verdict--${verdict.tone}`">{{ verdict.text }}</span>
        </p>
      </article>

      <article class="impact__card" data-test="impact-orders">
        <p class="impact__label">受影响发布单</p>
        <p class="impact__value mono">{{ orderText }}</p>
        <p class="impact__sub">
          <template v-if="orders && orders.length">
            已通过准入：<span class="mono">{{ orders.slice(0, 2).join('、') }}</span>
            <template v-if="orders.length > 2"> 等 {{ orders.length }} 张</template>
          </template>
          <template v-else-if="orders">没有已通过准入的发布单</template>
          <template v-else>{{ IMPACT_MISSING }}</template>
        </p>
      </article>
    </template>
  </div>
</template>

<style scoped>
.impact {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--change-row-gap);
}

.impact__card {
  min-height: 120px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  box-sizing: border-box;
}

.impact__card--skeleton {
  background: var(--bg-hover);
  border-color: transparent;
}

.impact__label {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}

.impact__value {
  margin: 6px 0 0;
  font-size: 16px;
  line-height: 28px;
  font-weight: 600;
  color: var(--text-title);
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

.impact__chip {
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
  font-size: 11px;
  font-weight: 500;
  line-height: 18px;
}

.impact__sub {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
  word-break: break-word;
}

.impact__sub.is-warning {
  color: var(--warning-ink);
}

.impact__sep {
  margin: 0 4px;
  color: var(--text-weak);
}

.impact__verdict--warning {
  color: var(--warning-ink);
  font-weight: 500;
}

.impact__verdict--neutral {
  color: var(--text-muted-aa);
}

.impact--readonly .impact__card {
  background: var(--bg-card);
}

@media (max-width: 1280px) {
  .impact {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
