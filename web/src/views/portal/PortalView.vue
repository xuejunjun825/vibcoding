<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import DataStamp from '@/components/analytics/DataStamp.vue'
import FilterBar from '@/components/analytics/FilterBar.vue'
import CardConfigDrawer from '@/components/portal/CardConfigDrawer.vue'
import PortalSection from '@/components/portal/PortalSection.vue'
import ErrorState from '@/components/ErrorState.vue'
import { listPortal } from '@/api/analytics'
import type { Portal } from '@/api/analytics'
import { useAnalyticsFeed } from '@/composables/useAnalyticsFeed'
import { usePermission } from '@/composables/usePermission'
import { useAnalyticsStore } from '@/stores/analytics'
import '@/views/analytics/shared.css'

/**
 * /portal 岗位工作台（AC-3/4/5/20）。
 *
 * 五区直接按后端 `sections` 数组顺序 v-for —— 不排序、不按角色映射模板，
 * 所以模板里看不到任何 `template === '...'` 分支。
 */
const route = useRoute()
const store = useAnalyticsStore()
const { can } = usePermission()

store.syncFromQuery(route)
watch(
  () => route.query,
  () => store.syncFromQuery(route),
)

const feed = useAnalyticsFeed<Portal>((signal) => listPortal(store.query, signal), { immediate: true })

const configOpen = ref(false)

const portal = computed(() => feed.data.value)
const readOnly = computed(() => portal.value?.todoMode === 'READONLY')
const sections = computed(() => portal.value?.sections ?? [])
const todoSources = computed(() => portal.value?.todoSources ?? [])
const canConfig = computed(() => can('PORTAL_CONFIG'))
/** 后端总是返回 todo 区；只有它缺失时才由本页兜底「暂无待办」，避免与区内空态重复 */
const hasTodoSection = computed(() => sections.value.some((section) => section.key === 'todo'))
/** 首屏骨架：有数据后重取时保留旧内容（只转圈），不让整页闪白 */
const firstLoad = computed(() => feed.status.value === 'loading' && !portal.value)
</script>

<template>
  <div class="an-page" data-page="portal" data-testid="portal-page">
    <header class="an-head">
      <div>
        <h1 class="an-head__title">{{ portal?.templateLabel || '工作台' }}</h1>
        <p class="an-head__sub">
          数据按你的可见范围（业务域 / 厂商）聚合
          <el-tag v-if="readOnly" size="small" type="info" effect="plain" class="an-head__pill" data-testid="portal-readonly">只读</el-tag>
        </p>
      </div>
      <div class="an-head__actions">
        <DataStamp v-if="portal" :data-as-of="portal.dataAsOf" :cache="portal.cache" :loading="feed.status.value === 'loading'" />
        <el-button v-if="canConfig" size="small" data-testid="portal-config-entry" @click="configOpen = true">卡片配置</el-button>
        <el-button size="small" :loading="feed.status.value === 'loading'" @click="feed.reload()">刷新</el-button>
      </div>
    </header>

    <div class="an-filters">
      <FilterBar :fields="['domain', 'vendor', 'preset']" />
    </div>
    <p class="an-hint ps-scope">筛选作用于关键指标、最近报表与个人卡；待办与快捷入口是行动清单，不随筛选变化。</p>

    <ErrorState
      v-if="feed.status.value === 'error'"
      :message="feed.error.value?.message"
      :code="feed.error.value?.code"
      :status="feed.error.value?.status"
      @retry="feed.reload()"
    />

    <div v-else-if="firstLoad" class="an-sections" aria-busy="true">
      <div v-for="index in 3" :key="index" class="ps-skeleton" />
    </div>

    <div v-else class="an-sections">
      <p v-if="!todoSources.length && !hasTodoSection" class="an-hint an-sections__empty">暂无待办</p>
      <PortalSection
        v-for="section in sections"
        :key="section.key"
        :section="section"
        :todo-mode="portal?.todoMode ?? 'FULL'"
        :data-as-of="portal?.dataAsOf ?? null"
        :cache="portal?.cache ?? null"
        @retry="feed.reload()"
      />
    </div>

    <CardConfigDrawer v-model="configOpen" @saved="feed.reload()" />
  </div>
</template>

<style scoped>
.an-head__pill {
  margin-left: 8px;
}

.an-sections {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

/* 筛选作用半径说明（B1）：一行小字，避免「改了筛选待办没变」被当成缺陷 */
.ps-scope {
  margin: -2px 0 10px;
  font-size: 12px;
}

.an-sections__empty {
  margin: 0;
}

/* 首屏骨架：占位高度与真实区一致，避免加载完成后整页跳动 */
.ps-skeleton {
  height: 132px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: linear-gradient(90deg, #f7f8fa 25%, #f0f2f5 37%, #f7f8fa 63%);
  background-size: 400% 100%;
  animation: ps-skeleton-shimmer 1.4s ease infinite;
}

@keyframes ps-skeleton-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ps-skeleton {
    animation: none;
  }
}
</style>
