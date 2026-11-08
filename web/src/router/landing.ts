import type { Router } from 'vue-router'

/**
 * 登录后的落点：
 * 优先用服务端下发的 defaultRoute（auth.homeRoute）与 ?redirect=；
 * 若该模块在当前部署里尚未启用（路由没注册），退到第一个可用入口，
 * 而不是把用户扔进 404 —— 前端基座要能独立跑起来。
 */
const FALLBACKS = ['/requirements', '/approvals', '/notifications']

export function landingRoute(router: Router, preferred?: string): string {
  const candidates = [preferred, ...FALLBACKS].filter((item): item is string => Boolean(item))
  for (const candidate of candidates) {
    const resolved = router.resolve(candidate)
    const missing = resolved.matched.some((record) => record.name === 'catch-all')
    if (resolved.matched.length > 0 && !missing) return candidate
  }
  return '/notifications'
}
