<script setup lang="ts">
import { computed } from 'vue'
import type { PortalCardRef } from '@/api/analytics'

/**
 * pin 上限出口（AC-15 / D6）：409 `PORTAL_CARD_LIMIT` 的专属弹层。
 *
 * 只认 `cardLimitCards(error)` 解析出的清单（当前 4 张），不渲染第 5 张：
 * 这里再 `slice(0, 4)` 一次是兜底，即使调用方误传满 5 条也不会出现第 5 行。
 * 本轮后端无 unpin（G-1），所以只给「打开」与「去工作台移除」的链接，不伪造移除按钮。
 */
const props = defineProps<{ modelValue: boolean; cards: PortalCardRef[] }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>()

const LIMIT = 4

const shown = computed<PortalCardRef[]>(() => props.cards.slice(0, LIMIT))

function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    title="工作台卡片已满"
    width="460px"
    :close-on-click-modal="false"
    data-testid="pin-limit-dialog"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <p class="pin__lead">
      工作台最多 4 张卡片，本次未添加第 5 张。当前已有
      <strong class="pin__count">{{ shown.length }}</strong>
      张：
    </p>

    <ul v-if="shown.length" class="pin__list">
      <li v-for="(card, index) in shown" :key="card.key" class="pin__item">
        <router-link v-if="card.reportId" class="pin__row" :to="`/reports/${card.reportId}`">
          <span class="pin__index">{{ index + 1 }}</span>
          <span class="pin__title" :title="card.title">{{ card.title }}</span>
          <span class="pin__id">#{{ card.reportId }}</span>
          <span class="pin__open">打开</span>
        </router-link>
        <div v-else class="pin__row pin__row--static">
          <span class="pin__index">{{ index + 1 }}</span>
          <span class="pin__title" :title="card.title">{{ card.title }}</span>
          <span class="pin__id">模板卡</span>
        </div>
      </li>
    </ul>
    <p v-else class="pin__hint">服务端未返回卡片清单，请稍后重试。</p>

    <p class="pin__hint pin__note" data-testid="pin-limit-note">本轮暂不支持移除，下轮支持移除。</p>

    <template #footer>
      <router-link to="/portal" class="pin__portal" data-testid="pin-limit-portal">去工作台移除</router-link>
      <el-button size="small" type="primary" @click="close">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pin__lead {
  margin: 0 0 8px;
  color: var(--text-body);
  font-size: 13px;
  line-height: 20px;
}

.pin__count {
  color: var(--color-primary);
}

.pin__list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  list-style: none;
}

.pin__item {
  border-bottom: 1px solid var(--border);
}

.pin__item:last-child {
  border-bottom: 0;
}

.pin__row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 12px;
  border-radius: var(--r-ctl);
  color: inherit;
  font-size: 13px;
  text-decoration: none;
  transition: background var(--t-color) var(--ease);
}

a.pin__row:hover {
  background: var(--bg-hover);
}

a.pin__row:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.pin__index {
  flex: none;
  width: 16px;
  color: var(--text-muted-aa);
  font-size: 12px;
  text-align: right;
}

.pin__title {
  overflow: hidden;
  flex: 1;
  min-width: 0;
  color: var(--text-title);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pin__id {
  flex: none;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.pin__open {
  flex: none;
  color: var(--color-primary);
  font-size: 12px;
}

.pin__hint {
  margin: 8px 0 0;
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 18px;
}

.pin__note {
  margin-top: 10px;
  padding: 6px 8px;
  background: var(--color-warning-bg);
  border-radius: var(--r-ctl);
  color: var(--warning-ink);
}

.pin__portal {
  margin-right: 8px;
  color: var(--color-primary);
  font-size: 12px;
  text-decoration: none;
}

.pin__portal:hover {
  text-decoration: underline;
}

.pin__portal:focus-visible {
  outline: none;
  border-radius: 2px;
  box-shadow: var(--focus-ring);
}
</style>
