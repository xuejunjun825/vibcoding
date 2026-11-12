<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchPortalConfig, savePortalConfig, REPORT_ERROR_TEXT } from '@/api/analytics'
import type { PortalCardRef } from '@/api/analytics'
import { PORTAL_ROLES } from '@/components/analytics/portalRoles'
import { ApiError } from '@/api/client'

/**
 * 卡片配置抽屉（AC-20 / P1）。
 *
 * 只有管理员（PORTAL_CONFIG）能看到入口；抽屉按角色读 `GET /portal/config?roleCode=`，
 * 勾选后 `PUT` 保存（user_id=0 的角色级配置，重复保存结果一致即幂等）。
 * 后端 `resolveConfigRole` 接受大写角色码，故这里直接用 PORTAL_ROLES。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'saved', roleCode: string): void
}>()

const roleCode = ref(PORTAL_ROLES[0].code)
const cards = ref<PortalCardRef[]>([])
const selected = ref<string[]>([])
const maxCards = ref(4)
const loading = ref(false)
const saving = ref(false)
const errorText = ref('')

const roleLabel = computed(() => PORTAL_ROLES.find((role) => role.code === roleCode.value)?.label ?? roleCode.value)
/** 超出上限时后端会整单拒绝；前端先挡住，避免一次注定失败的请求 */
const overLimit = computed(() => selected.value.length > maxCards.value)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const config = await fetchPortalConfig(roleCode.value)
    cards.value = config.cards ?? []
    selected.value = [...(config.cardKeys ?? [])]
    maxCards.value = config.maxCards ?? 4
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    errorText.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
    cards.value = []
    selected.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void load()
  },
)

watch(roleCode, () => {
  if (props.modelValue) void load()
})

async function save(): Promise<void> {
  if (overLimit.value) {
    errorText.value = `最多只能选 ${maxCards.value} 张卡片，请先取消 ${selected.value.length - maxCards.value} 张`
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    await savePortalConfig({ roleCode: roleCode.value, cardKeys: [...selected.value] })
    ElMessage.success(`已保存「${roleLabel.value}」的卡片配置`)
    emit('saved', roleCode.value)
    emit('update:modelValue', false)
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    if (error?.isForbidden) errorText.value = '你没有卡片配置权限（需 PORTAL_CONFIG），本次修改未生效'
    else errorText.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="props.modelValue"
    title="卡片配置"
    size="420px"
    data-testid="card-config-drawer"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="cc">
      <div class="cc__row">
        <span class="cc__label">角色</span>
        <el-select :model-value="roleCode" filterable data-testid="config-role-select" @update:model-value="(value: string) => (roleCode = value)">
          <el-option v-for="role in PORTAL_ROLES" :key="role.code" :label="`${role.label}（${role.code}）`" :value="role.code" />
        </el-select>
      </div>
      <p class="an-hint">
        当前角色「{{ roleLabel }}」最多 {{ maxCards }} 张卡片，已选 {{ selected.length }} 张。取消勾选即从该角色工作台移除。
      </p>

      <div v-loading="loading" class="cc__list">
        <el-checkbox-group v-model="selected">
          <el-checkbox v-for="card in cards" :key="card.key" :value="card.key" class="cc__item" :data-testid="`config-card-${card.key}`">
            {{ card.title }}
          </el-checkbox>
        </el-checkbox-group>
        <p v-if="!loading && !cards.length" class="an-hint">该角色暂无可配置卡片。</p>
      </div>

      <el-alert v-if="errorText" type="error" :closable="false" show-icon :title="errorText" />
    </div>

    <template #footer>
      <el-button size="small" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" :disabled="loading || overLimit" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.cc {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cc__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cc__row :deep(.el-select) {
  flex: 1;
}

.cc__label {
  flex: none;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-muted-aa, #6b7280);
}

.cc__list {
  min-height: 120px;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 10px 12px;
}

.cc__item {
  display: flex;
  height: 36px;
  align-items: center;
}
</style>
