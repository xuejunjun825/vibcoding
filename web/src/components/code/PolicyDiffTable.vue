<script setup lang="ts">
/**
 * 核验差异表：固定四列「检查项 / 期望 / 实际 / 来源」。
 * 不一致行左侧 2px 红条 + 浅红底；全部一致时收成一行结论，不铺四行噪音。
 */
import { computed } from 'vue'
import type { PolicyDiffItem } from '@/api/code'

const props = withDefaults(
  defineProps<{
    items?: PolicyDiffItem[] | null
    result?: string | null
  }>(),
  { items: () => [], result: null },
)

const rows = computed(() => props.items ?? [])
const allMatch = computed(() => rows.value.length > 0 && rows.value.every((item) => item.ok))
const driftCount = computed(() => rows.value.filter((item) => !item.ok).length)
</script>

<template>
  <div class="policy-diff">
    <p v-if="allMatch" class="policy-diff__all">
      <span class="policy-diff__check" aria-hidden="true">✓</span>
      {{ rows.length }} 项全部一致
      <span class="policy-diff__hint">（最近一次核验逐项比对通过）</span>
    </p>
    <template v-else-if="rows.length">
      <table class="policy-diff__table">
        <colgroup>
          <col class="policy-diff__col-item" />
          <col class="policy-diff__col-value" />
          <col class="policy-diff__col-value" />
          <col class="policy-diff__col-source" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">检查项</th>
            <th scope="col">期望</th>
            <th scope="col">实际</th>
            <th scope="col">来源</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(item, index) in rows"
            :key="`${item.item}-${index}`"
            :class="{ 'is-drift': !item.ok }"
          >
            <td class="policy-diff__item">
              <span class="policy-diff__mark" :class="item.ok ? 'is-ok' : 'is-bad'" aria-hidden="true">
                {{ item.ok ? '✓' : '✗' }}
              </span>
              {{ item.item }}
            </td>
            <td class="policy-diff__mono">{{ item.expected ?? '—' }}</td>
            <td class="policy-diff__mono" :class="{ 'is-drift-text': !item.ok }">{{ item.actual ?? '—' }}</td>
            <td class="policy-diff__source">{{ item.source || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="driftCount" class="policy-diff__foot">
        {{ driftCount }} 项与期望不一致：按 GitLab 实际配置为准整改，或调整规则后重新核验。
      </p>
    </template>
    <p v-else class="policy-diff__empty">最近一次核验没有返回逐项证据，请重新核验。</p>
  </div>
</template>

<style scoped>
.policy-diff {
  padding: 8px 12px 4px 20px;
  font-size: 12px;
  color: var(--text-body, #4e5969);
}

.policy-diff__all {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0;
  color: var(--success-ink, #157a38);
}

.policy-diff__check {
  font-weight: 700;
}

.policy-diff__hint {
  color: var(--text-muted-aa, #6b7280);
}

.policy-diff__table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.policy-diff__col-item {
  width: 180px;
}

.policy-diff__col-value {
  width: 280px;
}

.policy-diff__col-source {
  width: auto;
}

.policy-diff__table th {
  padding: 4px 8px;
  text-align: left;
  font-weight: 500;
  color: var(--text-muted-aa, #6b7280);
  border-bottom: 1px solid var(--divider, #f2f3f5);
}

.policy-diff__table td {
  padding: 5px 8px;
  border-bottom: 1px solid var(--divider, #f2f3f5);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.policy-diff__table tr.is-drift td {
  background: #fffbfb;
}

.policy-diff__table tr.is-drift td:first-child {
  box-shadow: inset 2px 0 0 var(--color-danger, #d9363e);
}

.policy-diff__item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.policy-diff__mark {
  font-weight: 700;
}

.policy-diff__mark.is-ok {
  color: var(--color-success, #1f9d45);
}

.policy-diff__mark.is-bad {
  color: var(--color-danger, #d9363e);
}

.policy-diff__mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

.policy-diff__mono.is-drift-text {
  color: var(--danger-ink, #b3262e);
}

.policy-diff__source {
  color: var(--text-muted-aa, #6b7280);
}

.policy-diff__foot {
  margin: 6px 0 2px;
  color: var(--warning-ink, #9a5b00);
}

.policy-diff__empty {
  margin: 4px 0;
  color: var(--text-muted-aa, #6b7280);
}
</style>
