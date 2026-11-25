<script setup lang="ts">
/**
 * 评审单详情 `/pdr-reviews/:id`（r49）。
 *
 * 后端**没有** `GET /pdr-reviews/{id}`：评审单只能从需求角度读（`GET /requirements/{id}/pdr-review`）。
 * 所以这个页面的定位规则是：
 * 1. 带 `?requirementId=` 时直接按需求读详情（列表页点进来就是这条路径）；
 * 2. 只带评审单号时，先按「PDR 需求」扫一页（30 条，并发 6）找 `review.id === :id`，
 *    找到就 `router.replace` 补上 `requirementId`；扫不到就请用户按需求编号定位。
 * 这样 `/pdr-reviews/1024` 这种深链至少不会白屏，也不会假装后端有这个端点。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRequirement, listRequirements } from '@/api/req'
import { getPdrReview, PDR_LIST_SCAN } from '@/api/pdr'
import type { RequirementDetail } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import { messageOf, normalizeRequirementDetail, usePlatformDict } from '@/views/req/platform'
import PdrReviewPanel from '@/views/req/PdrReviewPanel.vue'

const route = useRoute()
const router = useRouter()
const dict = usePlatformDict()

const reviewId = computed(() => Number(route.params.id))
const queryRequirementId = computed(() => {
  const raw = route.query.requirementId
  const value = Array.isArray(raw) ? raw[0] : raw
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
})

const detail = ref<RequirementDetail | null>(null)
const loading = ref(false)
const locating = ref(false)
const error = ref('')
const locateKeyword = ref('')
const locateTried = ref(false)

const review = computed(() => detail.value?.pdrReview?.review ?? null)
const opinions = computed(() => detail.value?.pdrReview?.opinions ?? [])

async function loadDetail(requirementId: number): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const raw = await getRequirement(requirementId)
    detail.value = { ...normalizeRequirementDetail(raw), estimate: raw.estimate ?? null } as RequirementDetail
  } catch (err) {
    error.value = messageOf(err)
    detail.value = null
  } finally {
    loading.value = false
  }
}

/** 只有评审单号时：扫一页 PDR 需求，把 reviewId → requirementId 的映射补出来 */
async function locate(): Promise<void> {
  if (queryRequirementId.value != null) {
    await loadDetail(queryRequirementId.value)
    return
  }
  if (!Number.isFinite(reviewId.value) || reviewId.value <= 0) {
    error.value = '评审单号不合法'
    return
  }
  locating.value = true
  locateTried.value = true
  try {
    const page = await listRequirements({ type: 'PDR', view: 'flat', page: 1, size: PDR_LIST_SCAN.limit })
    const candidates = page.items
    let found: number | null = null
    let cursor = 0
    await Promise.all(
      Array.from({ length: Math.min(PDR_LIST_SCAN.concurrency, candidates.length) }, async () => {
        while (cursor < candidates.length && found == null) {
          const index = cursor
          cursor += 1
          const row = candidates[index]
          try {
            const info = await getPdrReview(row.id, { silentForbidden: true })
            if (info.review?.id === reviewId.value) found = row.id
          } catch {
            // 单条读不到不影响其它候选
          }
        }
      }),
    )
    if (found != null) {
      await router.replace({ path: `/pdr-reviews/${reviewId.value}`, query: { requirementId: String(found) } })
      return
    }
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    locating.value = false
  }
}

/** 扫不到时：按需求编号/标题搜一条 PDR 需求，落到它的评审 tab */
async function locateByRequirement(): Promise<void> {
  const keyword = locateKeyword.value.trim()
  if (!keyword) return
  locating.value = true
  error.value = ''
  try {
    const page = await listRequirements({ type: 'PDR', keyword, view: 'flat', page: 1, size: 1 })
    const hit = page.items[0]
    if (!hit) {
      error.value = `没找到匹配「${keyword}」的 PDR 需求`
      return
    }
    await router.replace({ path: `/pdr-reviews/${reviewId.value}`, query: { requirementId: String(hit.id) } })
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    locating.value = false
  }
}

function reload(): void {
  if (queryRequirementId.value != null) void loadDetail(queryRequirementId.value)
  else void locate()
}

function openRequirement(): void {
  if (!detail.value) return
  void router.push(`/requirements/${detail.value.id}`)
}

watch(
  () => route.fullPath,
  () => {
    void dict.ensure()
    reload()
  },
)

onMounted(() => {
  void dict.ensure()
  void locate()
})
</script>

<template>
  <div class="pdr-detail">
    <nav class="pdr-detail__crumb">
      <router-link to="/pdr-reviews">评审中心</router-link>
      <span class="t-aux">/</span>
      <span class="t-aux">评审单 #{{ Number.isFinite(reviewId) ? reviewId : '—' }}</span>
    </nav>

    <ErrorState v-if="error" title="评审单加载失败" :message="error" @retry="reload" />

    <div v-else-if="locating" class="pdr-detail__locating surface surface--pad">
      <div class="skeleton skeleton__row" />
      <div class="skeleton skeleton--sm skeleton__row" />
      <p class="t-aux">正在按评审单号定位对应需求…</p>
    </div>

    <div v-else-if="queryRequirementId == null" class="surface surface--pad">
      <EmptyState
        variant="empty"
        title="没定位到这个评审单"
        description="评审单只能从需求维度读取：按需求编号（如 PDR-0102）搜一下，会自动跳到它的评审详情。"
        icon="search"
      />
      <div class="pdr-detail__locate">
        <el-input
          v-model="locateKeyword"
          size="small"
          placeholder="需求编号 / 标题关键字"
          style="width: 240px"
          @keyup.enter="locateByRequirement"
        />
        <el-button size="small" type="primary" :loading="locating" @click="locateByRequirement">
          定位
        </el-button>
      </div>
      <p v-if="locateTried" class="t-aux pdr-detail__hint">
        评审单 #{{ reviewId }} 不在最近 30 条 PDR 需求里，可能是更早的需求。
      </p>
    </div>

    <template v-else>
      <header class="page-head">
        <div class="page-head__left">
          <h1 class="t-page-title">
            <span class="t-code pdr-detail__code">{{ detail?.code ?? `需求 #${queryRequirementId}` }}</span>
            {{ detail?.title ?? '' }}
          </h1>
          <p class="t-aux">
            <template v-if="review">
              <StatusPill kind="pdrReview" :value="review.status" size="sm" />
              <span class="pdr-detail__meta">发起人 {{ dict.userName(review.initiatorId) }}</span>
              <span v-if="review.createdAt" class="pdr-detail__meta">发起于 {{ formatDateTime(review.createdAt) }}</span>
              <span class="pdr-detail__meta">{{ opinions.length }} 条意见</span>
            </template>
            <template v-else-if="!loading">这个需求还没有发起评审</template>
          </p>
        </div>
        <div class="page-head__actions">
          <el-button size="small" @click="openRequirement">打开需求详情</el-button>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </header>

      <div v-if="loading && !detail" class="surface surface--pad">
        <div v-for="index in 4" :key="index" class="skeleton skeleton__row" />
      </div>
      <PdrReviewPanel v-else-if="detail" :detail="detail" :loading="loading" @refresh="reload" />
    </template>
  </div>
</template>

<style scoped>
.pdr-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pdr-detail__crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.pdr-detail__crumb a {
  color: var(--text-secondary);
  text-decoration: none;
}
.pdr-detail__crumb a:hover {
  color: var(--color-primary);
}
.pdr-detail__locating {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pdr-detail__locate {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin-top: 8px;
}
.pdr-detail__hint {
  text-align: center;
}
.pdr-detail__code {
  margin-right: 8px;
  color: var(--text-secondary);
}
.pdr-detail__meta {
  margin-left: 10px;
}
</style>
