<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { decomposeRequirement } from '@/api/req'
import type { CreateRequirementPayload, Priority, RequirementDetail, RequirementRow } from '@/api/types'
import PriorityDot from '@/components/PriorityDot.vue'
import { messageOf, PRIORITIES, REQUIREMENT_TYPES } from '@/views/req/platform'

/**
 * 拆解子需求（P2 · 子需求 tab）：父需求只读继承域；子类型只允许 PDR / RD。
 */
const props = defineProps<{ modelValue: boolean; parent: RequirementDetail | null }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'created', row: RequirementRow | null): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const title = ref('')
const type = ref<'PDR' | 'RD'>('RD')
const priority = ref<Priority>('P2')
const estimateDays = ref<number | undefined>()
const description = ref('')
const saving = ref(false)
const formError = ref('')

const childTypes = REQUIREMENT_TYPES.filter((item) => item.value !== 'BUS')

watch(open, (value) => {
  if (!value) return
  title.value = ''
  type.value = props.parent?.type === 'BUS' ? 'PDR' : 'RD'
  priority.value = props.parent?.priority ?? 'P2'
  estimateDays.value = undefined
  description.value = ''
  formError.value = ''
})

async function submit(): Promise<void> {
  if (!props.parent) return
  if (title.value.trim().length < 4) {
    formError.value = '标题至少 4 个字，便于在需求池中识别'
    return
  }
  saving.value = true
  formError.value = ''
  const payload: CreateRequirementPayload = {
    type: type.value,
    title: title.value.trim(),
    priority: priority.value,
    description: description.value.trim(),
    domainIds: props.parent.domainId != null ? [props.parent.domainId] : [],
    parentId: props.parent.id,
  }
  try {
    const created = await decomposeRequirement(props.parent.id, payload)
    ElMessage.success('子需求已创建')
    open.value = false
    emit('created', (created as unknown as RequirementRow) ?? null)
  } catch (error) {
    formError.value = messageOf(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="拆解子需求" width="520px" append-to-body>
    <p class="parent-hint">
      父需求：<span class="mono">{{ parent?.code }}</span> {{ parent?.title }}
      <span class="parent-hint__domain">域继承自父需求，不可修改</span>
    </p>
    <el-form label-position="top" size="small" @submit.prevent>
      <el-form-item label="标题" required>
        <el-input v-model="title" maxlength="120" show-word-limit placeholder="子需求的交付内容" />
      </el-form-item>
      <div class="form-row">
        <el-form-item label="类型" required>
          <el-select v-model="type" style="width: 140px">
            <el-option v-for="item in childTypes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="priority" style="width: 140px">
            <el-option v-for="item in PRIORITIES" :key="item" :label="item" :value="item">
              <span class="option-priority"><PriorityDot :value="item" /> {{ item }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="预估人天">
          <el-input-number v-model="estimateDays" :min="0.5" :step="0.5" :precision="1" controls-position="right" />
        </el-form-item>
      </div>
      <el-form-item label="描述">
        <el-input v-model="description" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="验收要点 / 边界 / 依赖" />
      </el-form-item>
    </el-form>
    <p v-if="formError" class="dialog-error">{{ formError }}</p>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">创建子需求</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.parent-hint {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-radius: var(--r-ctl);
  background: var(--bg-page);
  color: var(--text-body);
  font-size: 12px;
  line-height: 1.6;
}
.parent-hint__domain {
  display: block;
  color: var(--text-muted-aa);
}
.mono {
  font-family: var(--font-mono);
}
.form-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.option-priority {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
