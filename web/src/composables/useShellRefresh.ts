import { inject, type InjectionKey } from 'vue'

/** AppShell 提供的角标/登录态刷新入口：页面完成审批等动作后调用，侧栏角标立刻跟上 */
export const SHELL_REFRESH_KEY: InjectionKey<() => void> = Symbol('shell:refresh')

export function useShellRefresh(): { refresh: () => void } {
  const refresh = inject(SHELL_REFRESH_KEY, () => {})
  return { refresh }
}
