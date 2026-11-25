/**
 * 评审意见 → 任务（r49，交互稿第 4 屏的「转为任务」弹层）。
 *
 * 交互稿要求「标题缺省截断、说明自动带来源」，这两条**和后端默认值同源**：
 * 后端 `PdrViews.ToTask` 的 title 缺省取意见前 40 字、description 缺省带来源
 * （见 `PdrReviewService.toTask`），前端预填是为了让用户看到即将提交什么、可再改，
 * 而不是把规则复制到前端（真值仍在后端：不填也成立）。
 *
 * 契约：`POST /pdr-reviews/{reviewId}/opinions/{opinionId}/to-task`（201，
 * `ToTaskResult{taskId,taskCode,opinion}`；重复转任务 409 `OPINION_ALREADY_TASKED` 带 taskId）。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pdrOpinionToTask } from '@/api/pdr'
import type { PdrOpinion, PdrToTaskResult } from '@/api/types.pdr'
import { usePlatformDict } from '@/views/req/platform'
import { pdrCategoryLabel } from '@/api/pdrCategories'
import { PDR_TASK_TITLE_LIMIT, PDR_DECISION_HINTS, pdrErrorInfo, pdrTaskDescription, pdrTaskTitle } from '@/views/req/pdr'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    reviewId: number
    requirementId: number
    requirementCode?: string
    opinion: PdrOpinion | null
    /** 默认负责人：当前评审人（意见作者），没有就用当前登录人 */
    currentUserId?: number | null
  }>(),
  { requirementCode: '', currentUserId: null },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'tasked', result: PdrToTaskResult): void
}>()

const dict = usePlatformDict()
const title = ref('')
const description = ref('')
const assigneeId = ref<number | null>(null)
const dueDate = ref<string>('')
const estimateHours = ref<number | null>(null)
const submitting = ref(false)
/** 错误既可能是校验文案，也可能是后端 409；后者可能带 taskId，需要给「已存在的任务」入口 */
const formError = ref('')
const errorTaskId = ref<number | null>(null)

const users = computed(() => dict.users.value)
const subject = computed(() =>
  [props.requirementCode, `源自评审 #${props.reviewId}`].filter(Boolean).join(' · '),
)

function defaultDueDate(): string {
  const date = new Date()
  date.setDate(date.getDate() + 14)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open || !props.opinion) return
    const opinion = props.opinion
    title.value = pdrTaskTitle(opinion.comment)
    description.value = pdrTaskDescription(props.reviewId, opinion)
    // 默认负责人：意见作者（评审人）；作者恰好是当前登录人时也用它，保证不会空
    assigneeId.value = opinion.reviewerId ?? props.currentUserId ?? null
    dueDate.value = defaultDueDate()
    estimateHours.value = null
    formError.value = ''
    errorTaskId.value = null
    void dict.ensure()
  },
)

async function submit(): Promise<void> {
  if (!props.opinion) return
  if (title.value.trim().length === 0) {
    formError.value = '请填写任务标题'
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    const result = await pdrOpinionToTask(props.reviewId, props.opinion.id, {
      title: title.value.trim(),
      description: description.value.trim() || undefined,
      assigneeId: assigneeId.value ?? undefined,
      dueDate: dueDate.value || undefined,
      estimateHours: estimateHours.value ?? undefined,
    })
    ElMessage.success(`已转为任务 ${result.taskCode}`)
    emit('tasked', result)
    emit('update:modelValue', false)
  } catch (error) {
    const info = pdrErrorInfo(error)
    formError.value = info.text
    errorTaskId.value = info.taskId
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    title="转为任务"
    width="580px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="to-task">
      <p v-if="subject" class="to-task__subject">
        关联需求 <span class="t-code">{{ subject }}</span>
      </p>

      <div v-if="props.opinion" class="to-task__source">
        <span class="to-task__chip">{{ pdrCategoryLabel(props.opinion.category) }}</span>
        <span class="to-task__chip">{{ PDR_DECISION_HINTS[props.opinion.decision] ?? props.opinion.decision }}</span>
        <span class="to-task__chip to-task__chip--muted">{{ props.opinion.reviewerName }}</span>
      </div>

      <el-form label-width="88px" label-position="right" @submit.prevent>
        <el-form-item label="任务标题" required>
          <el-input
            v-model="title"
            :maxlength="PDR_TASK_TITLE_LIMIT * 2"
            show-word-limit
            placeholder="默认取意见正文前 40 字"
          />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="assigneeId" filterable clearable placeholder="不指定则落在承接厂商成员" style="width: 100%">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker
            v-model="dueDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="默认两周后"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="预估工时">
          <el-input-number v-model="estimateHours" :min="0" :step="0.5" :precision="1" controls-position="right" />
          <span class="to-task__unit">人天（可空）</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="description" type="textarea" :rows="4" :maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>

      <p v-if="formError" class="to-task__error" role="alert">
        {{ formError }}
        <a v-if="errorTaskId" class="to-task__link" :href="`/requirements/${props.requirementId}?tab=tasks`">
          查看任务 #{{ errorTaskId }}
        </a>
      </p>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">
        创建任务
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.to-task {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.to-task__subject {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.to-task__source {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.to-task__chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 10px;
  background: var(--color-info-bg);
  color: var(--info-ink);
  font-size: 12px;
}
.to-task__chip--muted {
  background: var(--bg-hover);
  color: var(--text-secondary);
}
.to-task__unit {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.to-task__error {
  margin: 0;
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}
.to-task__link {
  margin-left: 6px;
  color: var(--color-primary);
  text-decoration: none;
}
.to-task__link:hover {
  text-decoration: underline;
}
</style>
