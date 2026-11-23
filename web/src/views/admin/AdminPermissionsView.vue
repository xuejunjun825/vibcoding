<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listPermissions } from '@/api/admin'
import type { PermissionMatrixResponse } from '@/api/types'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/admin/admin-shared.css'

/**
 * 权限总览（DESIGN §3.3 / AC-12）。
 * 只读视图，数据来自服务端 /admin/permissions —— 它是 FUNCTION 授权行的投影，与
 * 「角色与权限」同源（在授权页改档后刷新本页即见新值）：
 * ✓ 允许 / ⚪ 受限（带范围）/ — 不渲染。前端不复制任何一格判定，也不提供任何写控件
 * （该控制器没有写映射，s9 断言）；改档位请到「角色与权限」。
 */
const router = useRouter()
const matrix = ref<PermissionMatrixResponse | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    matrix.value = await listPermissions()
  } catch (err) {
    error.value = {
      message: (err as Error).message || '权限总览加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/** 写入口唯一收敛点：总览只读，授权跳角色列表（AC-12） */
function goRoles(): void {
  void router.push('/admin/roles')
}

interface RoleColumn {
  code: string
  name: string
}

const roles = computed<RoleColumn[]>(() =>
  (matrix.value?.roles ?? []).map((role) =>
    typeof role === 'string' ? { code: role, name: role } : { code: role.code, name: role.name || role.code },
  ),
)

const functions = computed(() => {
  const list = matrix.value?.functions ?? []
  const text = keyword.value.trim().toLowerCase()
  return text ? list.filter((cell) => cell.code.toLowerCase().includes(text)) : list
})

function cellOf(code: string, role: string): 'allow' | 'restricted' | 'deny' {
  const cell = (matrix.value?.functions ?? []).find((item) => item.code === code)
  if (!cell) return 'deny'
  if ((cell.allow ?? []).includes(role)) return 'allow'
  if ((cell.restricted ?? []).includes(role)) return 'restricted'
  return 'deny'
}

const stats = computed(() => {
  const list = matrix.value?.functions ?? []
  let allow = 0
  let restricted = 0
  for (const cell of list) {
    allow += cell.allow?.length ?? 0
    restricted += cell.restricted?.length ?? 0
  }
  return { functions: list.length, roles: roles.value.length, allow, restricted }
})

onMounted(load)
</script>

<template>
  <div class="adm-pages">
    <!-- 只读条（DESIGN §3.3）：40 高，明确「这里不能改」，并给出唯一写入口 -->
    <div class="perm-readonly">
      <span class="perm-readonly__text">
        只读视图 · 编辑权限请到「角色与权限」
      </span>
      <el-button size="small" @click="goRoles">去角色授权</el-button>
    </div>

    <div class="adm-toolbar">
      <el-input v-model="keyword" placeholder="搜索功能码" clearable style="width: 220px" />
      <div class="adm-toolbar__spacer" />
      <el-button link :loading="loading" @click="load">刷新</el-button>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <template v-else>
      <section class="surface surface--pad">
        <div class="perm-summary">
          <div>
            <span class="t-block-title">{{ stats.roles }} 角色 × {{ stats.functions }} 功能码</span>
            <p class="adm-hint">
              总览与运行时同源（FUNCTION 授权行）：网关快速拒绝、服务内逐请求复核、前端置灰都读它。
              换档位后在「角色与权限」保存，回本页刷新即可见新值；权限秒级生效（每请求读库）。
            </p>
          </div>
          <div class="adm-legend">
            <span><span class="adm-cell adm-cell--allow">✓ 允许</span></span>
            <span><span class="adm-cell adm-cell--restricted">⚪ 受限</span></span>
            <span><span class="adm-cell adm-cell--deny">— 不渲染</span></span>
          </div>
        </div>
        <div class="perm-stats">
          <span>允许格 {{ stats.allow }}</span>
          <span>受限格 {{ stats.restricted }}</span>
          <span>禁止格 {{ Math.max(stats.roles * stats.functions - stats.allow - stats.restricted, 0) }}</span>
        </div>
      </section>

      <section class="surface">
        <div v-if="loading && !matrix" class="adm-hint" style="padding: 16px">加载总览…</div>
        <div v-else class="adm-matrix-wrap">
          <table class="adm-matrix">
            <thead>
              <tr>
                <th scope="col">功能码</th>
                <th v-for="role in roles" :key="role.code" scope="col" :title="role.code">{{ role.name }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cell in functions" :key="cell.code">
                <th scope="row">{{ cell.code }}</th>
                <td v-for="role in roles" :key="role.code">
                  <span
                    class="adm-cell"
                    :class="`adm-cell--${cellOf(cell.code, role.code)}`"
                    :title="`${cell.code} × ${role.code}：${cellOf(cell.code, role.code) === 'allow' ? '允许' : cellOf(cell.code, role.code) === 'restricted' ? '受限' : '不渲染'}`"
                  >
                    {{ cellOf(cell.code, role.code) === 'allow' ? '✓' : cellOf(cell.code, role.code) === 'restricted' ? '⚪' : '—' }}
                  </span>
                </td>
              </tr>
              <tr v-if="!functions.length">
                <td :colspan="roles.length + 1" class="adm-hint" style="padding: 16px">没有匹配的功能码</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <p class="perm-foot">
        恢复预设：<code>make platform-demo-reset</code>（11 个内置角色回到 1881 行预设授权）
      </p>
    </template>
  </div>
</template>

<style scoped>
.adm-pages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 只读条（DESIGN §3.3）：40 高，#F7F9FC 底 + 下边 1px 发丝线，刻意不描四边 ——
   它是一条提示带而不是卡片，不做浮起感，避免与下方内容抢层级 */
.perm-readonly {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 40px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-hover);
}

.perm-readonly__text {
  font-size: 12px;
  color: var(--text-muted-aa);
}

.perm-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.perm-summary .adm-hint {
  max-width: 640px;
  margin-top: 4px;
}

.perm-stats {
  display: flex;
  gap: 18px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
}

/* 只读：单元格不出现「可编辑」暗示（hover 只提亮，见 admin-shared.css） */
.adm-matrix .adm-cell {
  cursor: default;
}

.perm-foot {
  margin: 0;
  font-size: 12px;
  color: var(--text-weak);
}

.perm-foot code {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 1px 4px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
}
</style>
