<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createRequirement } from '@/api/req'
import { listDomains } from '@/api/admin'
import type { DomainRow } from '@/api/admin'
import type { CreateRequirementPayload, Priority, RequirementRow } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { messageOf, PRIORITIES, REQUIREMENT_TYPES, requirementPath, typeLabel } from '@/views/req/platform'

/**
 * 提报需求（P1 主按钮落点）。类型决定所需权限（功能码来自 contract 权限矩阵）。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()

const type = ref<'BUS' | 'PDR' | 'RD'>('BUS')
const title = ref('')
const description = ref('')
const domainIds = ref<number[]>([])
const priority = ref<Priority>('P2')
const expectedDeliveryDate = ref('')
const saving = ref(false)
const formError = ref('')
const domains = ref<DomainRow[]>([])

const parentId = computed(() => {
  const raw = route.query.parentId
  return raw != null ? Number(raw) : undefined
})

const requiredPermission = computed(() => (type.value === 'BUS' ? 'req:report:bus' : 'req:report:dev'))
const hasPermission = computed(() => can(requiredPermission.value))
const canSubmit = computed(
  () => title.value.trim().length >= 4 && domainIds.value.length > 0 && hasPermission.value && !saving.value,
)

const submitHint = computed(() => {
  if (!hasPermission.value) return `需要权限：${requiredPermission.value}`
  if (title.value.trim().length < 4) return '标题至少 4 个字'
  if (domainIds.value.length === 0) return '至少选择一个业务域'
  return ''
})

onMounted(async () => {
  try {
    domains.value = await listDomains()
  } catch {
    domains.value = []
  }
  if (parentId.value != null) {
    const parentType = String(route.query.parentType ?? '')
    type.value = parentType === 'BUS' ? 'PDR' : 'RD'
  }
})

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  saving.value = true
  formError.value = ''
  const payload: CreateRequirementPayload = {
    type: type.value,
    title: title.value.trim(),
    description: description.value.trim(),
    domainIds: domainIds.value,
    priority: priority.value,
    expectedDeliveryDate: expectedDeliveryDate.value || undefined,
    parentId: parentId.value,
  }
  try {
    const created = (await createRequirement(payload)) as unknown as RequirementRow
    ElMessage.success(`已提报 ${created.code ?? ''}`)
    if (created.id != null) await router.push(requirementPath(created.id))
    else await router.push('/requirements')
  } catch (error) {
    formError.value = messageOf(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="create">
    <header class="page-head">
      <div class="page-head__left">
        <RouterLink to="/requirements" class="create__back">← 需求池</RouterLink>
        <h1 class="page-head__title">提报需求</h1>
      </div>
      <div class="page-head__actions">
        <el-button size="small" @click="router.push('/requirements')">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!canSubmit"
          :title="submitHint"
          :loading="saving"
          @click="submit"
        >
          提交
        </el-button>
      </div>
    </header>

    <div v-if="parentId != null" class="create__parent">
      从需求 #{{ parentId }} 拆解创建；域与父需求保持一致才能进入同一版本。
    </div>

    <section class="surface surface--pad create__panel">
      <el-form label-position="top" size="small" @submit.prevent>
        <el-form-item label="需求类型" required>
          <div class="type-cards">
            <button
              v-for="item in REQUIREMENT_TYPES"
              :key="item.value"
              type="button"
              class="type-card"
              :class="{ 'is-active': type === item.value }"
              :aria-pressed="type === item.value"
              @click="type = item.value"
            >
              <span class="type-card__name">{{ item.label }}</span>
              <span class="type-card__desc">{{ item.description }}</span>
            </button>
          </div>
        </el-form-item>

        <el-form-item label="标题" required>
          <el-input v-model="title" maxlength="120" show-word-limit placeholder="一句话说清要交付什么（≥4 字）" />
        </el-form-item>

        <el-form-item label="业务域" required>
          <el-select
            v-model="domainIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择所属业务域（供应链平台已划分 7 个域）"
            style="width: 100%"
          >
            <el-option v-for="domain in domains" :key="domain.id" :label="`${domain.code} · ${domain.name}`" :value="domain.id" />
          </el-select>
          <p v-if="domains.length === 0" class="create__hint">域列表暂不可用（管理端接口未就绪），仍可提交后再分配。</p>
        </el-form-item>

        <div class="create__row">
          <el-form-item label="优先级">
            <el-radio-group v-model="priority">
              <el-radio-button v-for="item in PRIORITIES" :key="item" :value="item">{{ item }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="期望交付">
            <el-date-picker
              v-model="expectedDeliveryDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              :clearable="true"
            />
          </el-form-item>
        </div>

        <el-form-item label="描述">
          <el-input
            v-model="description"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
            placeholder="背景 / 目标 / 验收要点 / 依赖与风险"
          />
        </el-form-item>
      </el-form>

      <p v-if="formError" class="create__error">{{ formError }}</p>
    </section>

    <section class="surface surface--pad create__preview">
      <header class="create__preview-head">
        <h2 class="create__preview-title">提交后</h2>
        <StatusPill kind="requirement" value="SUBMITTED" />
      </header>
      <ul>
        <li>进入 {{ typeLabel(type) }} 状态机，按已发布的审批流进入审批中心（无审批流时直接待评审）。</li>
        <li>审批通过后进入需求池，可被纳入版本计划与发布单。</li>
        <li>域 × 厂商隔离从创建起生效：厂商账号只能看到本厂商承接的需求。</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.create {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 920px;
}
.create__back {
  font-size: 12px;
  color: var(--text-muted-aa);
  text-decoration: none;
  margin-right: 8px;
}
.create__back:hover {
  color: var(--color-primary);
}
.create__parent {
  padding: 8px 12px;
  border: 1px solid var(--color-primary-light);
  border-radius: var(--r-ctl);
  background: var(--color-primary-light);
  color: var(--text-body);
  font-size: 12px;
}
.create__panel {
  padding: 20px 20px 8px;
}
.type-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
}
.type-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 68px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.type-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.type-card.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  box-shadow: inset 0 0 0 1px var(--color-primary);
}
.type-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.type-card__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.type-card__desc {
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.5;
}
.create__row {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}
.create__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--warning-ink);
}
.create__error {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-left: 2px solid var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.create__preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.create__preview-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.create__preview ul {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-body);
  line-height: 1.9;
}
</style>
