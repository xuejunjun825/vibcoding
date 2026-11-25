/**
 * 发起 PDR 评审（r49，交互稿第 1 屏的「发起评审」弹窗）。
 *
 * 评审人来源是**项目组成员**（`GET /projects/{id}/members`）：
 * - 项目组为空（或需求没挂项目组）时不阻断，只提示「将回退为 提报人 + 域负责人」，
 *   提交空数组让后端按 `PdrReviewService` 的回退规则补人（`ReviewView.fallback=true` 会回显）；
 * - 成员没有中文名（`ProjectViews.MemberView` 只有 username），这里用人员字典按 userId 补名字，
 *   字典拿不到就显示 username —— 不为了一个名字让整个弹窗失败。
 *
 * 契约：`POST /requirements/{id}/pdr-review`（body `StartReview{reviewerIds,note,dueAt,mode}`，201）。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listProjectMembers, startPdrReview } from '@/api/pdr'
import type { PdrProjectMember } from '@/api/types.pdr'
import { usePlatformDict } from '@/views/req/platform'
import { PDR_MODE_OPTIONS, pdrErrorInfo, pdrRoleLabel } from '@/views/req/pdr'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    requirementId: number
    requirementCode?: string
    /** 需求所属项目组；为空时直接走「提报人 + 域负责人」回退 */
    projectId?: number | null
  }>(),
  { requirementCode: '', projectId: null },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'started'): void
}>()

const dict = usePlatformDict()
const reviewerIds = ref<number[]>([])
const mode = ref<'MODE_ALL' | 'MODE_ANY'>('MODE_ALL')
const dueAt = ref<string>(defaultDueAt())
const note = ref('')
const members = ref<PdrProjectMember[]>([])
const membersLoading = ref(false)
const submitting = ref(false)
const formError = ref('')

const hasProject = computed(() => props.projectId != null)
const groupEmpty = computed(() => hasProject.value && !membersLoading.value && members.value.length === 0)

function defaultDueAt(): string {
  const date = new Date()
  date.setDate(date.getDate() + 3)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

/** 成员下拉文案：中文名（字典有则用）· 项目组角色 */
function memberLabel(member: PdrProjectMember): string {
  const cn = dict.userName(member.userId)
  const name = cn.startsWith('用户 #') ? member.username : `${cn}（${member.username}）`
  return `${name} · ${pdrRoleLabel(member.roleInProject)}`
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    formError.value = ''
    reviewerIds.value = []
    mode.value = 'MODE_ALL'
    dueAt.value = defaultDueAt()
    note.value = ''
    members.value = []
    void dict.ensure()
    if (props.projectId == null) return
    membersLoading.value = true
    try {
      // 可选探测：读不到成员不该阻断「发起评审」（后端仍会按项目组回退补人）
      members.value = await listProjectMembers(props.projectId, { silentForbidden: true })
    } catch {
      members.value = []
    } finally {
      membersLoading.value = false
    }
  },
)

async function submit(): Promise<void> {
  if (!dueAt.value) {
    formError.value = '请选择评审截止时间'
    return
  }
  if (mode.value === 'MODE_ANY' && reviewerIds.value.length === 0) {
    formError.value = '或签（任一通过）至少要选一位评审人'
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    await startPdrReview(props.requirementId, {
      reviewerIds: reviewerIds.value,
      mode: mode.value,
      dueAt: dueAt.value,
      note: note.value.trim() || undefined,
    })
    ElMessage.success('已发起 PDR 评审')
    emit('started')
    emit('update:modelValue', false)
  } catch (error) {
    formError.value = pdrErrorInfo(error).text
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    title="发起 PDR 评审"
    width="600px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="start-review">
      <p v-if="props.requirementCode" class="start-review__subject">
        需求 <span class="t-code">{{ props.requirementCode }}</span>
      </p>

      <el-form label-width="96px" label-position="right" @submit.prevent>
        <el-form-item label="评审人">
          <el-select
            v-model="reviewerIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :loading="membersLoading"
            placeholder="从项目组成员里多选；不选则自动补齐"
            style="width: 100%"
          >
            <el-option
              v-for="member in members"
              :key="member.userId"
              :label="memberLabel(member)"
              :value="member.userId"
            />
          </el-select>
          <p v-if="!hasProject" class="start-review__hint" data-test="pdr-no-project">
            该需求还没挂项目组：将回退为「提报人 + 域负责人」作为评审人。
          </p>
          <p v-else-if="groupEmpty" class="start-review__hint" data-test="pdr-empty-group">
            项目组还没有成员：将回退为「提报人 + 域负责人」作为评审人。
          </p>
          <p v-else class="start-review__hint">
            评审人只能从「项目组成员」里选；不选时后端按项目组自动补齐，仍为空则回退「提报人 + 域负责人」。
          </p>
        </el-form-item>

        <el-form-item label="评审模式">
          <el-radio-group v-model="mode">
            <el-radio v-for="option in PDR_MODE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
          <p class="start-review__hint">
            {{ PDR_MODE_OPTIONS.find((item) => item.value === mode)?.hint }}
          </p>
        </el-form-item>

        <el-form-item label="截止时间">
          <el-date-picker
            v-model="dueAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="评审截止时间"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="评审说明">
          <el-input
            v-model="note"
            type="textarea"
            :rows="3"
            :maxlength="500"
            show-word-limit
            placeholder="这次评审希望重点看什么（可选）"
          />
        </el-form-item>
      </el-form>

      <p class="start-review__hint">
        评审材料（规格说明、差异样例等）在评审单下方上传，支持 PDF / DOCX / XLSX / PNG / JPG / ZIP / CSV / MD，单个 ≤ 20MB。
      </p>
      <p v-if="formError" class="start-review__error" role="alert">{{ formError }}</p>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">
        发起评审
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.start-review {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.start-review__subject {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.start-review__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.start-review__error {
  margin: 0;
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}
</style>
