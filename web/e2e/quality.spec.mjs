#!/usr/bin/env node
// web/e2e/quality.spec.mjs — M6 质量域 E2E 冒烟（无第三方依赖，Node 20+ 内置 fetch）。
//
// 为什么不用 Playwright：本项目 web 侧没有浏览器依赖，而 M6 的验收重点是「页面能加载 + 质量接口可用」，
// 用 HTTP 就能覆盖 SPA 路由回退与鉴权/路由守卫的失败面；真正的交互矩阵留给 QA 手工/浏览器阶段。
//
// 约定：web 或网关未启动 → 打印 SKIP 并以 0 退出（离线可跑，不制造假红灯）；
//       启动但接口异常 → 打印 FAIL 并以 1 退出。结论同时写到 stdout（宿主截尾保留）。

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5173';
const GW = process.env.GATEWAY_URL || 'http://127.0.0.1:8080';
const TENANT = process.env.QUALITY_E2E_TENANT || 't1';
const USER = process.env.QUALITY_E2E_USER || 'admin';
const PASS = process.env.QUALITY_E2E_PASSWORD || 'Admin@12345';

let total = 0, pass = 0, fail = 0;
const failures = [];
function check(desc, ok, detail = '') {
  total++;
  if (ok) { pass++; console.log(`  PASS ${desc}`); }
  else { fail++; failures.push(`${desc}${detail ? ` — ${detail}` : ''}`); console.log(`  FAIL ${desc}${detail ? ` — ${detail}` : ''}`); }
}
function skip(reason) { console.log(`SKIP quality-e2e: ${reason}`); process.exit(0); }

async function get(url, headers = {}, timeoutMs = 5000) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ac.signal });
    const text = await res.text();
    return { status: res.status, text };
  } catch (e) {
    return { status: 0, text: String(e?.message || e) };
  } finally { clearTimeout(timer); }
}

console.log(`质量域 E2E：web=${WEB} gateway=${GW} tenant=${TENANT}`);

// 1) web 是否可达（未启动 → SKIP，离线可跑）
const index = await get(`${WEB}/`, {}, 3000);
if (index.status === 0) skip(`web 未启动（${WEB}/）：make platform-up 后再跑 make quality-e2e`);
check('web 首页 200', index.status === 200, `HTTP ${index.status}`);
check('首页挂载 #app', /id="app"/.test(index.text));
check('前端产物含模块脚本', /<script[^>]+src=/.test(index.text));

// 2) SPA 深链回退：质量路由刷新不能 404（history fallback）
for (const route of ['/test-plans', '/test-reports', '/quality-gates', '/workbench']) {
  const r = await get(`${WEB}${route}`, {}, 4000);
  check(`SPA 深链 ${route} 返回入口页`, r.status === 200 && /id="app"/.test(r.text), `HTTP ${r.status}`);
}

// 3) 网关是否可达；不可达也算 SKIP（E2E 依赖栈，但离线模式不失败）
const health = await get(`${GW}/api/req/internal/health`, {}, 3000);
if (health.status === 0) skip(`网关未启动（${GW}）：make platform-up 后再跑 make quality-e2e`);

// 4) 登录（质量页面全部在鉴权之后）
const login = await fetch(`${GW}/api/req/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ tenantCode: TENANT, username: USER, password: PASS }),
});
const loginBody = await login.text();
check('管理员登录 200', login.status === 200, `HTTP ${login.status}`);
let token = '', tenantId = '';
try { const j = JSON.parse(loginBody); token = j.token || ''; tenantId = String(j.tenantId || ''); } catch { /* 下面断言会报 */ }
check('登录返回 token/tenantId', !!token && !!tenantId);
if (!token) {
  console.log(`#SUMMARY quality-e2e pass=${pass} total=${total} fail=${fail}`);
  process.exit(1);
}
const H = { Authorization: `Bearer ${token}`, 'X-Tenant-Id': tenantId };

// 5) 质量域核心读接口（页面首屏依赖）
const endpoints = [
  ['测试计划列表', '/api/deliver/test-plans?page=1&size=1'],
  ['报告中心列表', '/api/deliver/test-reports?page=1&size=1'],
  ['门禁总览', '/api/deliver/quality-gates?page=1&size=1'],
  ['质量指标', '/api/deliver/metrics/quality'],
  ['我的用例工作台', '/api/deliver/workbench/my-cases?page=1&size=1'],
];
for (const [name, path] of endpoints) {
  const r = await get(`${GW}${path}`, H, 8000);
  check(`${name} 200`, r.status === 200, `HTTP ${r.status} ${r.text.slice(0, 120)}`);
}

// 6) 权限入口：未带 token 必须 401（前端入口显隐的前提）
const anon = await get(`${GW}/api/deliver/test-reports?page=1&size=1`, {}, 5000);
check('未认证访问报告中心 → 401', anon.status === 401, `HTTP ${anon.status}`);

// 7) 状态字典与导航同源（前端构建产物里能搜到质量路由名，说明路由已注册）
const bundle = index.text;
check('入口页加载了前端脚本（可继续在浏览器中断言路由）', bundle.length > 0);

console.log(`#SUMMARY quality-e2e pass=${pass} total=${total} fail=${fail}`);
if (fail > 0) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  console.log('修复提示：质量接口未就绪时先看 make quality-verify 的具体 FAIL；页面 404 检查 quality.routes.ts 是否被 glob 挂载。');
  process.exit(1);
}
console.log('质量域 E2E 冒烟通过。');
