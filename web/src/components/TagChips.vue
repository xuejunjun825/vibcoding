<script setup lang="ts">
import { computed } from 'vue'
import { tagSourceLabel, type RequirementTag } from '@/api/req'

/**
 * 需求标签 chip（DESIGN §3.4）。
 *
 * - 列表 `max=3` → 超出折成 `+N`，`title` 列出被折起的全部标签；
 * - 详情不传 `max` → 不限枚数，自然换行；
 * - 来源（RULE / MANUAL）**不染色**，只进 title：自动化打的标签不是一种状态。
 */
const props = withDefaults(defineProps<{ tags: RequirementTag[]; max?: number }>(), { max: 0 })

const visible = computed(() =>
  props.max > 0 && props.tags.length > props.max ? props.tags.slice(0, props.max) : props.tags,
)
const folded = computed(() =>
  props.max > 0 && props.tags.length > props.max ? props.tags.slice(props.max) : [],
)
const foldedTitle = computed(() => folded.value.map((item) => item.tag).join('、'))

function titleOf(tag: RequirementTag): string {
  return `${tag.tag} · ${tagSourceLabel(tag.source)}`
}
</script>

<template>
  <span v-if="tags.length" class="tag-chips" aria-label="需求标签">
    <span v-for="tag in visible" :key="tag.tag" class="tag-chip" :title="titleOf(tag)">{{ tag.tag }}</span>
    <span v-if="folded.length" class="tag-chip tag-chip--more" :title="foldedTitle">+{{ folded.length }}</span>
  </span>
</template>

<style scoped>
.tag-chips {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  height: 18px;
  max-width: 12em;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-chip--more {
  color: var(--text-secondary);
  cursor: help;
}
</style>
