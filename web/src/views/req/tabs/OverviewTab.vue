<script setup lang="ts">
import { computed } from 'vue'
import type { ApprovalInstanceDetail, ApprovalTimelineEvent, RequirementDetail } from '@/api/types'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDate, formatDateTime, formatDays } from '@/utils/format'
import { childInfo, typeLabel, usePlatformDict } from '@/views/req/platform'

/**
 * P2 · 概览 tab：左字段网格，右审批进度小卡 + 最近 5 条动态。
 * 父需求的人天来自子需求汇总，只读并带锁标记（DESIGN §5 P2）。
 */
const props = defineProps<{
  detail: RequirementDetail
  approval: ApprovalInstanceDetail | null
  approvalLoading: boolean
  timeline: ApprovalTimelineEvent[]
}>()

const emit = defineEmits<{ (event: 'open-approval'): void }>()

const { domainName, vendorName, userName } = usePlatformDict()

const rollup = computed(() => childInfo(props.detail))
const isParent = computed(() => rollup.value.childCount > 0)
const pendingTasks = computed(() => (props.approval?.tasks ?? []).filter((task) => task.status === 'PENDING'))
const recent = computed(() => props.timeline.slice(0, 5))
const currentNode = computed(() => props.approval?.instance.currentNode ?? '')
const approvalStatus = computed(() => props.approval?.instance.status ?? '')
</script>

<template>
  <div class="overview">
    <div class="overview__grid">
      <section class="surface surface--pad overview__fields">
        <h3 class="overview__title">基本信息</h3>
        <dl class="grid">
          <div>
            <dt>需求类型</dt>
            <dd>{{ typeLabel(detail.type) }}</dd>
          </div>
          <div>
            <dt>业务域</dt>
            <dd>{{ detail.domainName ?? domainName(detail.domainId) }}</dd>
          </div>
          <div>
            <dt>厂商</dt>
            <dd>{{ detail.vendorName ?? vendorName(detail.vendorId) }}</dd>
          </div>
          <div>
            <dt>负责人</dt>
            <dd>{{ detail.ownerName ?? userName(detail.ownerId) }}</dd>
          </div>
          <div>
            <dt>期望交付</dt>
            <dd>{{ formatDate(detail.expectedDeliveryDate) }}</dd>
          </div>
          <div>
            <dt>所属版本</dt>
            <dd>{{ detail.releaseName ?? (detail.releaseId ? `版本 #${detail.releaseId}` : '未排期') }}</dd>
          </div>
          <div>
            <dt>预估人天</dt>
            <dd class="num">
              {{ formatDays(detail.estimateDays) }}
              <span v-if="isParent" class="lock" title="父需求人天由子需求汇总，只读">🔒 汇总</span>
            </dd>
          </div>
          <div>
            <dt>实际人天</dt>
            <dd class="num">
              {{ formatDays(detail.actualDays) }}
              <span v-if="isParent" class="lock" title="父需求人天由子需求汇总，只读">🔒 汇总</span>
            </dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(detail.createdAt) }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd>{{ formatDateTime(detail.updatedAt) }}</dd>
          </div>
        </dl>
        <p v-if="detail.description" class="overview__desc">{{ detail.description }}</p>
      </section>

      <aside class="overview__side">
        <section class="surface surface--pad">
          <header class="overview__card-head">
            <h3 class="overview__title">审批进度</h3>
            <button type="button" class="overview__link" @click="emit('open-approval')">查看审批 →</button>
          </header>
          <div v-if="approvalLoading" class="skeleton skeleton__row" />
          <template v-else-if="approval">
            <div class="overview__approval">
              <StatusPill kind="approval" :value="approvalStatus" />
              <span class="overview__node">当前节点：{{ currentNode || '—' }}</span>
              <span class="overview__node">第 {{ approval.instance.definitionVersion }} 版流程</span>
            </div>
            <ul v-if="pendingTasks.length" class="overview__tasks">
              <li v-for="task in pendingTasks" :key="task.id">
                <span>{{ task.nodeName }}</span>
                <span class="overview__assignee">{{ task.assignee?.name ?? '待指派' }}</span>
              </li>
            </ul>
            <p v-else class="overview__muted">没有待处理的审批任务</p>
          </template>
          <p v-else class="overview__muted">尚未发起审批；提交后这里显示当前节点与待办人。</p>
        </section>

        <section class="surface surface--pad">
          <h3 class="overview__title">最近动态</h3>
          <ApprovalTimeline
            v-if="recent.length"
            :events="recent"
            dense
            :active="approvalStatus === 'PENDING'"
            pending-label="等待后续审批节点"
          />
          <p v-else class="overview__muted">还没有流转记录。</p>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.overview__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}
.overview__side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.overview__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.overview__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.overview__card-head .overview__title {
  margin: 0;
}
.overview__link {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.overview__link:hover {
  text-decoration: underline;
}
.overview__link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px 20px;
  margin: 0;
}
.grid div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.grid dt {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.grid dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-title);
}
.num {
  font-variant-numeric: tabular-nums;
}
.lock {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.overview__desc {
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--divider);
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body);
  white-space: pre-wrap;
}
.overview__approval {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.overview__node {
  font-size: 12px;
  color: var(--text-body);
}
.overview__tasks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.overview__tasks li {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-body);
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-page);
}
.overview__assignee {
  color: var(--text-title);
}
.overview__muted {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
@media (max-width: 1100px) {
  .overview__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
