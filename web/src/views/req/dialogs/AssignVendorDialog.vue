/**
 * 指派 / 更换承接厂商（r49，交互稿第 2 屏）。
 *
 * 背景：厂商指派原本内联在 `RequirementDetailView.vue` 里（`assignOpen/assignVendorId/assignReason`），
 * 本轮要加「已有通过评估时警告评估将失效（STALE）」与「研发已开始则禁用」，
 * 内联弹窗已经撑不住了，所以抽成组件——**同一个弹窗**同时服务「首次指派」与「更换厂商」，
 * 不新建第二个入口（交付清单第 5 条：若已有类似弹窗则改造，别新建重复）。
 *
 * 契约：
 * - `POST /requirements/{id}/assign-vendor`（body `{vendorId, ownerUserId?, reason?}`，
 *   见 `api/req.ts:assignVendor`）；
 * - 换厂商后端写 `VENDOR_CHANGED` 审计并把 APPROVED 评估置 STALE（`409 VENDOR_LOCKED` 表示窗口已关）；
 * - 研发已开始（PDR `IN_DEV` 之后）后端不给 `assign` 动作，这里只在弹窗内说清「为什么点不了」。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { assignVendor } from '@/api/req'
import type { DictionaryVendor } from '@/api/dictionaries'
import type { EstimateBlock } from '@/api/types'
import { usePlatformDict } from '@/views/req/platform'
import { VENDOR_LOCKED_HINT, VENDOR_SWAP_STALE_WARNING, pdrErrorInfo, vendorEditLocked } from '@/views/req/pdr'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    requirementId: number
    requirementCode?: string
    /** 需求状态（PDR `PENDING_ANALYSIS/SCHEDULED/IN_DEV/...`），决定是否允许换厂商 */
    status?: string
    /** 当前承接厂商（为空 = 首次指派） */
    vendorId?: number | null
    /** 当前负责人（预填「厂商负责人」） */
    ownerId?: number | null
    /** 工时评估块：`APPROVED` 时换厂商会让它失效，必须先告诉用户 */
    estimate?: EstimateBlock | null
  }>(),
  { requirementCode: '', status: '', vendorId: null, ownerId: null, estimate: null },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'assigned', vendorId: number): void
}>()

const dict = usePlatformDict()
const formError = ref('')
const submitting = ref(false)
const vendorId = ref<number | null>(props.vendorId)
const ownerId = ref<number | null>(props.ownerId)
const reason = ref('')
/** 厂商选项来自只读字典（`/dictionaries/vendors`），取不到就退化空表并提示 */
const vendors = ref<DictionaryVendor[]>([])
/** 人员字典同理：`usePlatformDict` 返回的是 ref，模板里要通过 computed 取 */
const users = computed(() => dict.users.value)

const hasVendor = computed(() => props.vendorId != null)
const isChange = computed(() => hasVendor.value && vendorId.value !== props.vendorId)
const locked = computed(() => vendorEditLocked(props.status))
const approvedEstimate = computed(() => props.estimate?.status === 'APPROVED')
const showStaleWarning = computed(() => isChange.value && approvedEstimate.value)
const dialogTitle = computed(() => (hasVendor.value ? '更换承接厂商' : '指派承接厂商'))
const confirmText = computed(() => (isChange.value ? '确认更换' : '确认指派'))

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    formError.value = ''
    vendorId.value = props.vendorId
    ownerId.value = props.ownerId
    reason.value = ''
    void dict.ensure().then(() => {
      vendors.value = dict.vendors.value
    })
  },
)

/** 已有厂商时下拉里的「解除指派」不可用——换厂商必须选新的，清空会让后续评估失去归属 */
function validate(): string {
  if (vendorId.value == null) return '请选择承接厂商'
  if (reason.value.trim().length === 0) return '请填写原因（会写入审计日志并通知新旧厂商）'
  return ''
}

async function submit(): Promise<void> {
  const invalid = validate()
  if (invalid) {
    formError.value = invalid
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    await assignVendor(props.requirementId, {
      vendorId: vendorId.value as number,
      ownerUserId: ownerId.value ?? undefined,
      reason: reason.value.trim(),
    })
    ElMessage.success(isChange.value ? '已更换承接厂商，原评估已失效，需新厂商重新评估' : '已指派承接厂商')
    emit('assigned', vendorId.value as number)
    emit('update:modelValue', false)
  } catch (error) {
    // 409 VENDOR_LOCKED / 403 都在这里落成行内提示，不关弹窗（用户能看清原因）
    formError.value = pdrErrorInfo(error).text
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="dialogTitle"
    width="520px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="assign">
      <p v-if="props.requirementCode" class="assign__subject">
        需求 <span class="t-code">{{ props.requirementCode }}</span>
      </p>

      <el-form label-width="96px" label-position="right" @submit.prevent>
        <el-form-item label="承接厂商" required>
          <el-select
            v-model="vendorId"
            placeholder="选择厂商"
            filterable
            style="width: 100%"
            :disabled="locked"
          >
            <el-option
              v-for="vendor in vendors"
              :key="vendor.id"
              :label="vendor.name"
              :value="vendor.id"
            />
          </el-select>
          <p v-if="vendors.length === 0" class="assign__hint">
            厂商字典为空（可能无权限或未配置），可直接联系管理员补数据。
          </p>
        </el-form-item>
        <el-form-item label="厂商负责人">
          <el-select
            v-model="ownerId"
            placeholder="不指定则沿用当前负责人"
            clearable
            filterable
            style="width: 100%"
            :disabled="locked"
          >
            <el-option
              v-for="user in users"
              :key="user.id"
              :label="user.name"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            :maxlength="200"
            show-word-limit
            placeholder="为什么换这家厂商（写审计 + 通知新旧厂商）"
            :disabled="locked"
          />
        </el-form-item>
      </el-form>

      <!-- 黄条：换厂商会让已通过的评估失效（交互稿第 2 屏原文口径） -->
      <p v-if="showStaleWarning" class="assign__warn" data-test="assign-stale-warning">
        {{ VENDOR_SWAP_STALE_WARNING }}
      </p>
      <p v-if="locked" class="assign__locked" data-test="assign-locked">
        {{ VENDOR_LOCKED_HINT }}
      </p>
      <p v-if="formError" class="assign__error" role="alert">{{ formError }}</p>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="submitting || locked"
        @click="submit"
      >
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.assign {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.assign__subject {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.assign__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.5;
}
.assign__warn {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.6;
}
.assign__locked {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.assign__error {
  margin: 0;
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}
</style>
