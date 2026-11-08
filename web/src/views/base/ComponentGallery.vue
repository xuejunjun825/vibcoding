<script setup lang="ts">
import { ref } from 'vue'
import {
  ApprovalTimeline,
  CaseVersionSwitcher,
  ConfirmDialog,
  EmptyState,
  ErrorState,
  GateChecklist,
  ImportWizard,
  MetricCard,
  PassRateRing,
  PriorityDot,
  ScopeChip,
  StatusPill,
  TraceMatrix,
} from '@/components'
import type { ApprovalTimelineEvent, GateItem, MetricDefinition, TraceRow } from '@/api/types'

/**
 * 组件总览（仅开发期路由 /__gallery）：共享组件的外观与三态自检页。
 * 交付物本身不含这个页面（vite build 时 DEV=false，路由不会注册）。
 */
const confirmOpen = ref(false)
const importOpen = ref(false)
const version = ref(2)

const gateItems: GateItem[] = [
  { code: 'CASE_EXISTS', name: '存在关联测试计划', passed: true, detail: '计划 TP-2026-011，10 个用例' },
  { code: 'ALL_PASSED', name: '关联用例全部通过', passed: false, detail: '2 个用例失败', fixHint: '到测试计划执行页重跑失败用例' },
  { code: 'EXECUTED', name: '计划已全部执行', passed: false, detail: '还有 1 个用例未执行', fixHint: '执行完剩余用例后重新提交' },
  { code: 'NO_OPEN_HIGH', name: '无未关闭高优缺陷', passed: true, detail: '当前 0 条' },
  { code: 'MANUAL_CONFIRM', name: '人工确认记录', passed: true, detail: '产品经理 李敏 于 11-07 确认', forced: true },
]

const timeline: ApprovalTimelineEvent[] = [
  { action: 'submit', operator: '王强', comment: '业务需求提报：华东仓入库复核', at: '2026-11-06T09:12:00+08:00' },
  { action: 'approve', operator: '李敏', comment: '同意，按既定节奏排期。', at: '2026-11-06T14:20:00+08:00' },
  { action: 'add_sign', operator: '李敏', comment: '加签：财务预算超出 50 万，需要张涛复核。', at: '2026-11-06T14:22:00+08:00', origin: 'ADD_SIGN' },
  { action: 'approve', operator: '张涛', comment: '预算已确认。', at: '2026-11-07T10:05:00+08:00' },
  { action: 'urge', operator: '王强', comment: '催办：等待交付中心确认排期', at: '2026-11-07T16:40:00+08:00', origin: 'URGE' },
]

const traceRows: TraceRow[] = [
  {
    requirementId: 1,
    requirementCode: 'RD-2026-0012',
    title: '入库复核支持批次混扫',
    complete: false,
    gaps: ['mr', 'release'],
    cells: {
      task: { state: 'COMPLETE', label: '3 个任务' },
      mr: { state: 'GAP', label: '缺少 MR' },
      execution: { state: 'COMPLETE', label: '12 次执行' },
      defect: { state: 'COMPLETE', label: '2 个缺陷已关闭' },
      release: { state: 'GAP', label: '未进发布单' },
      version: { state: 'COMPLETE', label: 'v3' },
    },
  },
  {
    requirementId: 2,
    requirementCode: 'RD-2026-0015',
    title: '出库波次合并策略',
    complete: true,
    gaps: [],
    cells: {
      task: { state: 'COMPLETE' },
      mr: { state: 'COMPLETE' },
      execution: { state: 'COMPLETE' },
      defect: { state: 'COMPLETE' },
      release: { state: 'COMPLETE' },
      version: { state: 'COMPLETE' },
    },
  },
  {
    requirementId: 3,
    requirementCode: 'PDR-2026-0004',
    title: '供应商门户对账单导出',
    complete: false,
    gaps: ['execution'],
    cells: {
      task: { state: 'COMPLETE' },
      mr: { state: 'COMPLETE' },
      execution: { state: 'GAP' },
      defect: { state: 'NA' },
      release: { state: 'GAP' },
      version: { state: 'COMPLETE' },
    },
  },
]

const density: MetricDefinition = {
  code: 'defect_density',
  name: '缺陷密度',
  formula: '缺陷数 ÷ 需求数',
  caliberNote: '按统计区间内新建缺陷计数，需求数取同区间进入开发的需求',
  version: 3,
  ownerName: '质量组',
  updatedAt: '2026-10-28',
}

const versions = [
  { versionNo: 1, createdAt: '2026-09-12', createdBy: '陈晨' },
  { versionNo: 2, createdAt: '2026-10-02', createdBy: '陈晨' },
  { versionNo: 3, createdAt: '2026-11-01', createdBy: '陈晨', current: true },
]

const showConfirm = ref(false)
const lastResult = ref('')

async function fakeImport(file: File) {
  await new Promise((resolve) => setTimeout(resolve, 400))
  return {
    ok: Math.max(1, Math.round(file.size / 1000)),
    failed: 2,
    errors: [
      { row: 3, message: '模块路径超过 3 层' },
      { row: 7, message: '前置条件为空' },
    ],
  }
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">组件总览</h1>
        <p class="page-head__sub">共享组件的默认态与边界态自检（仅开发期可见）</p>
      </div>
    </header>

    <section class="surface surface--pad">
      <h2 class="gallery__h2">状态标识 / 优先级 / 数据范围</h2>
      <div class="gallery__row">
        <StatusPill kind="requirement" value="APPROVING" />
        <StatusPill kind="requirement" value="POOLED" />
        <StatusPill kind="requirement" value="IN_DEV" />
        <StatusPill kind="defect" value="PENDING_VERIFY" />
        <StatusPill kind="approval" value="PENDING" />
        <StatusPill kind="execution" value="NOT_RUN" />
        <StatusPill kind="execution" value="PASS" />
        <StatusPill kind="execution" value="FAIL" />
        <StatusPill kind="execution" value="BLOCKED" />
        <StatusPill kind="release" value="LOCKED" />
        <StatusPill kind="deploy" value="ROLLED_BACK" />
      </div>
      <div class="gallery__row">
        <PriorityDot value="P0" />
        <PriorityDot value="P1" />
        <PriorityDot value="P2" />
        <PriorityDot value="P3" />
        <ScopeChip scope="华东 · 2 个域" />
        <ScopeChip tone="warning" label="厂商范围" scope="仅本厂商数据" />
      </div>
    </section>

    <section class="surface surface--pad">
      <h2 class="gallery__h2">门禁 / 准入清单</h2>
      <GateChecklist :items="gateItems" mode="admission" rejected :allow-force="true" checked-at="2026-11-07T18:20:00+08:00" @force="showConfirm = true" />
    </section>

    <div class="gallery__grid2">
      <section class="surface surface--pad">
        <h2 class="gallery__h2">审批时间线</h2>
        <ApprovalTimeline :events="timeline" active />
      </section>

      <section class="surface surface--pad">
        <h2 class="gallery__h2">通过率 / 指标</h2>
        <div class="gallery__row">
          <PassRateRing :passed="18" :planned="20" :coverage="0.9" />
          <PassRateRing :passed="0" :planned="0" :coverage="0" />
        </div>
        <div class="gallery__row">
          <MetricCard label="缺陷密度" :value="0.42" :digits="2" unit="条/需求" :definition="density" drillable @drill="lastResult = 'drill: defect_density'" />
          <MetricCard label="重开率" :value="0.083" :digits="1" hint="含被退回重开的缺陷" :definition="density" />
        </div>
        <p v-if="lastResult" class="gallery__out">{{ lastResult }}</p>
      </section>
    </div>

    <section class="surface surface--pad">
      <h2 class="gallery__h2">用例版本切换（含只读 banner）</h2>
      <CaseVersionSwitcher v-model="version" :versions="versions" />
    </section>

    <section class="surface surface--pad">
      <h2 class="gallery__h2">追溯矩阵</h2>
      <TraceMatrix :rows="traceRows" @open="lastResult = `open #${$event}`" />
    </section>

    <div class="gallery__grid2">
      <section class="surface surface--pad">
        <h2 class="gallery__h2">空态</h2>
        <EmptyState title="还没有需求" description="提报第一条业务需求后，这里会显示需求池。" action-text="提报需求" />
        <EmptyState variant="filtered" title="当前筛选无结果" description="试试清除「P0 · 待评审」筛选。" action-text="清除筛选" />
        <EmptyState variant="forbidden" title="无权查看该单据" description="当前角色没有该需求所在域的访问范围。" />
      </section>
      <section class="surface surface--pad">
        <h2 class="gallery__h2">错误态 / 对话框</h2>
        <ErrorState message="加载需求池失败，请稍后重试" code="INTERNAL_ERROR" :status="500" @retry="lastResult = 'retry'" />
        <div class="gallery__row gallery__row--gap">
          <el-button @click="confirmOpen = true">危险操作确认</el-button>
          <el-button @click="importOpen = true">导入向导</el-button>
        </div>
      </section>
    </div>

    <ConfirmDialog
      v-model="confirmOpen"
      title="强制放行发布单"
      description="强制放行会跳过未通过的门禁项，并通知域负责人。"
      :impact="['该发布单将带着 2 项未通过准入进入执行', '执行结果仍会记录，但不可撤销', '域负责人会收到站内通知与审计留痕']"
      require-ack
      ack-label="我已知悉将通知域负责人"
      confirm-text="强制放行"
      @confirm="lastResult = `force: ${$event.reason}`"
    />

    <ImportWizard v-model="importOpen" :validate="fakeImport" />
  </div>
</template>

<style scoped>
.surface--pad {
  padding: 16px;
  margin-bottom: 12px;
}
.gallery__h2 {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.gallery__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.gallery__row + .gallery__row {
  margin-top: 12px;
}
.gallery__row--gap {
  margin-top: 12px;
}
.gallery__grid2 {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
.gallery__grid2 .surface--pad {
  margin-bottom: 0;
}
.gallery__out {
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted-aa);
}
</style>
