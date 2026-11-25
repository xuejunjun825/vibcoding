<script setup lang="ts">
/**
 * 需求详情「评审」tab（r49）。
 *
 * 薄包装：真正的评审单交互在 `PdrReviewPanel.vue`（状态徽标 / 评审人 / 多条意见 / 转任务 / 附件）。
 * 这层只做两件事：
 * 1. **只有 PDR 需求才有评审**（RD 需求走的是 `req.action.start_dev` 那条链，没有评审单），
 *    非 PDR 类型直接给一句说明，避免用户以为「评审丢了」；
 * 2. 把详情页的 `detail` 传下去、把 `refresh` 透上去（写操作后由详情页统一重拉，保证其它 tab 同步）。
 */
import { computed } from 'vue'
import type { RequirementDetail } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import PdrReviewPanel from '@/views/req/PdrReviewPanel.vue'

const props = withDefaults(
  defineProps<{ detail: RequirementDetail; loading?: boolean }>(),
  { loading: false },
)

const emit = defineEmits<{ (event: 'refresh'): void }>()

const isPdr = computed(() => props.detail.type === 'PDR')
</script>

<template>
  <div class="pdr-tab">
    <PdrReviewPanel v-if="isPdr" :detail="props.detail" :loading="props.loading" @refresh="emit('refresh')" />
    <div v-else class="surface surface--pad">
      <EmptyState
        variant="empty"
        title="这个需求不走 PDR 评审"
        description="评审只针对产品需求（PDR）：RD 需求由承接厂商提工时评估后直接进入开发，没有评审单。"
        icon="file"
      />
    </div>
  </div>
</template>

<style scoped>
.pdr-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
