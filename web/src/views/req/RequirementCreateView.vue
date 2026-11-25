<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createRequirement } from '@/api/req'
import { listSimilarRequirements } from '@/api/poolOps'
import { listDictionaryDomains } from '@/api/dictionaries'
import type { DictionaryDomain } from '@/api/dictionaries'
import type {
  CreateRequirementPayload,
  Priority,
  RequirementRow,
  SimilarRequirementItem,
} from '@/api/types'
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
const domains = ref<DictionaryDomain[]>([])

const parentId = computed(() => {
  const raw = route.query.parentId
  return raw != null ? Number(raw) : undefined
})

const requiredPermission = computed(() => (type.value === 'BUS' ? 'req:report:bus' : 'req:report:dev'))
const hasPermission = computed(() => can(requiredPermission.value))
const canSubmit = computed(
  () =>
    title.value.trim().length >= 4 &&
    domainIds.value.length > 0 &&
    domains.value.length > 0 &&
    hasPermission.value &&
    !saving.value,
)

const submitHint = computed(() => {
  if (!hasPermission.value) return `需要权限：${requiredPermission.value}`
  if (domains.value.length === 0) return '当前账号没有可提报的业务域，请联系域负责人授权后再提报'
  if (title.value.trim().length < 4) return '标题至少 4 个字'
  if (domainIds.value.length === 0) return '至少选择一个业务域'
  return ''
})

/**
 * r47 · 相似需求探测（可选能力）：
 * - 标题/首个业务域变化后 400ms 防抖再探测，避免逐字打后端
 * - 命中 0 条不渲染；403/网络失败静默（`silentForbidden` + 本地 catch），**绝不断提报**
 * - `similarSeq` 丢弃过期响应，防止慢请求覆盖新关键词的结果
 */
const SIMILAR_DEBOUNCE_MS = 400
const similarItems = ref<SimilarRequirementItem[]>([])
const similarTotal = ref(0)
const similarCollapsed = ref(false)
let similarTimer: ReturnType<typeof setTimeout> | null = null
let similarSeq = 0

function clearSimilarTimer(): void {
  if (similarTimer != null) {
    clearTimeout(similarTimer)
    similarTimer = null
  }
}

async function loadSimilar(): Promise<void> {
  const keyword = title.value.trim()
  if (keyword.length < 2) {
    similarItems.value = []
    similarTotal.value = 0
    return
  }
  const seq = ++similarSeq
  try {
    const page = await listSimilarRequirements({
      title: keyword,
      domainId: domainIds.value[0],
      limit: 5,
    })
    if (seq !== similarSeq) return
    similarItems.value = page.items ?? []
    similarTotal.value = page.total ?? similarItems.value.length
  } catch {
    if (seq !== similarSeq) return
    similarItems.value = []
    similarTotal.value = 0
  }
}

function scheduleSimilar(): void {
  clearSimilarTimer()
  similarTimer = setTimeout(() => {
    similarTimer = null
    void loadSimilar()
  }, SIMILAR_DEBOUNCE_MS)
}

watch([title, () => domainIds.value[0]], () => {
  similarCollapsed.value = false
  scheduleSimilar()
})
onBeforeUnmount(clearSimilarTimer)

function openSimilar(item: SimilarRequirementItem): void {
  void router.push(requirementPath(item.id))
}

onMounted(async () => {
  try {
    domains.value = await listDictionaryDomains()
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
          <!-- r47：相似需求提示是「只帮忙、不挡路」的可选探测：0 条不渲染，失败静默 -->
          <div v-if="similarItems.length > 0" class="similar" data-test="similar-hints">
            <header class="similar__head">
              <span class="similar__title">
                发现 {{ similarTotal }} 条相似需求
                <span class="similar__sub">先看看是否已有同样的诉求，避免重复提报</span>
              </span>
              <button type="button" class="similar__toggle" @click="similarCollapsed = !similarCollapsed">
                {{ similarCollapsed ? '展开' : '收起' }}
              </button>
            </header>
            <ul v-if="!similarCollapsed" class="similar__list">
              <li v-for="item in similarItems" :key="item.id">
                <button
                  type="button"
                  class="similar__item"
                  :title="`打开 ${item.code}`"
                  @click="openSimilar(item)"
                >
                  <span class="similar__code">{{ item.code }}</span>
                  <span class="similar__item-title">{{ item.title }}</span>
                  <StatusPill kind="requirement" :value="item.status" size="sm" />
                  <span class="similar__follow">{{ item.followCount }} 人需要</span>
                </button>
              </li>
            </ul>
            <p v-if="!similarCollapsed" class="similar__note">
              继续提报不受影响；如判定重复，提交后可在详情页合并到主需求。
            </p>
          </div>
        </el-form-item>

        <el-form-item label="业务域" required>
          <el-select
            v-model="domainIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :disabled="domains.length === 0"
            :placeholder="domains.length === 0 ? '当前账号没有可提报的业务域' : '选择所属业务域（供应链平台已划分 7 个域）'"
            style="width: 100%"
          >
            <el-option v-for="domain in domains" :key="domain.id" :label="`${domain.code} · ${domain.name}`" :value="domain.id" />
          </el-select>
          <p v-if="domains.length === 0" class="create__hint">
            域字典为空：当前账号未获任何业务域授权（字典按租户/厂商裁剪），提报已禁用，请联系域负责人授权。
          </p>
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
/* r47 相似需求提示：白底 1px 边框方角卡，行内是完整的可点目标 */
.similar {
  width: 100%;
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
}
.similar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.similar__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-title);
}
.similar__sub {
  margin-left: 6px;
  font-weight: 400;
  color: var(--text-muted-aa);
}
.similar__toggle {
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
}
.similar__toggle:hover {
  background: var(--bg-hover);
}
.similar__list {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.similar__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  border: 1px solid transparent;
  border-radius: var(--r-cell);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.similar__item:hover {
  border-color: var(--border);
  background: var(--bg-hover);
}
.similar__item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.similar__code {
  flex: 0 0 auto;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.similar__item-title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-title);
}
.similar__follow {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.similar__note {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
</style>
