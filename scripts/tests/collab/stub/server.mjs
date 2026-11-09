#!/usr/bin/env node
// collab stub — M8 协作/代码/测试治理验收用的只读外部系统替身（GitLab 只读 API + Sonar 片段）。
//
// 为什么另起一个桩而不是复用 M7 的四合一桩（19090）：
//   * 分支策略核验需要 GitLab 的「项目设置 / 保护分支 / 分支抽样」三个只读口，M7 桩没有；
//   * 端口分开（19091 vs 19090）才能让两个验收套件互不干扰地并行跑，
//     且 M7 的 integration_config 仍指向 19090，不会读到本桩的状态。
//
// 三条与 M7 桩一致的性质：
//   * 零依赖：只用 node:http，node 18+ 直接跑；
//   * 可观测：每个逻辑操作一个计数器（失败也计数），脚本用 /__stub/counter 断言"调了几次"，
//     页面渲染零外呼就是"计数器没变"这条断言；
//   * 可注入：/__stub/mode 把某个逻辑操作切成 500 / timeout，断言"不可达必须 UNVERIFIED 且落记录"。
//
// 控制面（不计数）：
//   GET  /__stub/health                 → {"status":"UP"}
//   GET  /__stub/counter?key=<k>        → {"key":k,"count":n}
//   POST /__stub/reset                  → 清计数、模式与 GitLab 状态（回到默认绿）
//   POST /__stub/mode   {key,value}     value ∈ ok|500|429|timeout
//   GET  /__stub/state                  → 当前 GitLab 状态（调试用）
//   POST /__stub/gitlab/project   {merge_method,squash_option,approvals_before_merge,only_allow_merge_if_pipeline_succeeds}
//   POST /__stub/gitlab/protected {branches:[{name,push_roles:[..]}]}
//   POST /__stub/gitlab/branches  {names:[..]}
//   POST /__stub/gitlab/mr        {project,iid,state,notes:[{id,body,file_path,line_no,resolved,system}]}
import http from 'node:http'

const PORT = Number(process.env.STUB_PORT || 19091)
const SELF = `http://127.0.0.1:${PORT}`
const DEFAULT_PROJECT = 'demo-group/demo-project'
const TOKEN_NOTE = '顺手把 glpat-' + 'AbCdEf1234567890abcd' + ' 提上去了，麻烦换掉'
const SECRET_NOTE = '连库口令 password=hunter2，别外传'

function initialState() {
  return {
    counters: Object.create(null),
    modes: Object.create(null),
    requests: [],
    gitlab: {
      project: {
        id: 11,
        name: 'demo-project',
        path_with_namespace: DEFAULT_PROJECT,
        default_branch: 'main',
        merge_method: 'merge',
        squash_option: 'never',
        approvals_before_merge: 2,
        only_allow_merge_if_pipeline_succeeds: true,
        web_url: `${SELF}/${DEFAULT_PROJECT}`,
      },
      protectedBranches: [
        { name: 'main', push_access_levels: [{ access_level: 40 }], merge_access_levels: [{ access_level: 40 }] },
        { name: 'release/*', push_access_levels: [{ access_level: 40 }], merge_access_levels: [{ access_level: 40 }] },
      ],
      branches: ['main', 'release/1.0', 'feat/gantt', 'feat/wiki'],
      mrs: new Map(),
    },
  }
}

let state = initialState()

function bump(key) {
  state.counters[key] = (state.counters[key] || 0) + 1
  state.counters.all = (state.counters.all || 0) + 1
}

function sendJson(res, code, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

function applyTrap(key, res) {
  const mode = state.modes[key]
  if (!mode || mode === 'ok') return false
  if (mode === 'timeout') {
    setTimeout(() => { try { res.destroy() } catch { /* ignore */ } }, 3000)
    return true
  }
  const code = Number(mode)
  sendJson(res, Number.isFinite(code) ? code : 500, {
    message: `stub injected ${mode} for ${key}`,
    error: mode === '429' ? 'rate limited' : 'internal error',
  })
  return true
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => { raw += chunk })
    req.on('end', () => {
      if (!raw) return resolve({})
      try { resolve(JSON.parse(raw)) } catch { resolve({}) }
    })
  })
}

function decodeProject(raw) {
  try { return decodeURIComponent(raw) } catch { return raw }
}

function mrKey(project, iid) { return `${project}#${iid}` }

function defaultNotes() {
  return [
    { id: 9001, body: '这里的窗口计算建议抽出来复用', author: 'lisi', file_path: 'req-service/GanttService.java', line_no: 42, resolved: false, system: false },
    { id: 9002, body: TOKEN_NOTE, author: 'wangwu', file_path: 'req-service/GanttQuery.java', line_no: 108, resolved: false, system: false },
    { id: 9003, body: SECRET_NOTE, author: 'zhaoliu', file_path: 'deliver-service/FlowQuery.java', line_no: 77, resolved: true, system: false },
    { id: 9004, body: 'added 1 commit', author: 'gitlab', file_path: null, line_no: null, resolved: false, system: true },
    { id: 9005, body: '整体思路没问题', author: 'lisi', file_path: null, line_no: null, resolved: false, system: false },
  ]
}

function ensureMr(project, iid, patch) {
  const key = mrKey(project, iid)
  if (!state.gitlab.mrs.has(key)) {
    state.gitlab.mrs.set(key, {
      id: 50000 + iid,
      iid,
      project_id: 11,
      title: `stub MR !${iid}`,
      state: 'opened',
      source_branch: 'feat/gantt',
      target_branch: 'main',
      web_url: `${SELF}/${project}/-/merge_requests/${iid}`,
      additions: 128,
      deletions: 12,
      changes_count: '7',
      user_notes_count: 3,
      updated_at: '2026-11-09T05:00:00.000Z',
      merged_at: null,
      detailed_merge_status: 'mergeable',
      author: { id: 21, name: '李四', username: 'lisi' },
      reviewers: [
        { id: 21, name: '李四', username: 'lisi', state: 'approved' },
        { id: 22, name: '王五', username: 'wangwu', state: 'unreviewed' },
      ],
      approved_by: [{ user: { id: 21, username: 'lisi' } }],
      notes: defaultNotes(),
    })
  }
  const mr = state.gitlab.mrs.get(key)
  if (patch) Object.assign(mr, patch)
  return mr
}

function noteJson(note) {
  const out = {
    id: note.id,
    body: note.body,
    system: !!note.system,
    resolved: !!note.resolved,
    created_at: note.created_at || '2026-11-09T05:10:00.000Z',
    author: { id: 21, name: note.author || 'lisi', username: note.author || 'lisi' },
  }
  if (note.file_path) {
    out.position = {
      new_path: note.file_path,
      old_path: note.file_path,
      new_line: note.line_no,
      old_line: note.line_no,
    }
  }
  return out
}

function gitlabRoute(req, res, path, url) {
  const decoded = decodeProject(path)
  const notesMatch = decoded.match(/^\/api\/v4\/projects\/(.+)\/merge_requests\/(\d+)\/notes$/)
  if (notesMatch) {
    bump('gitlab.notes')
    if (applyTrap('gitlab.notes', res)) return
    const mr = ensureMr(decodeProject(notesMatch[1]), Number(notesMatch[2]))
    return sendJson(res, 200, mr.notes.map(noteJson))
  }
  const mrMatch = decoded.match(/^\/api\/v4\/projects\/(.+)\/merge_requests\/(\d+)$/)
  if (mrMatch) {
    bump('gitlab.mr')
    if (applyTrap('gitlab.mr', res)) return
    const mr = ensureMr(decodeProject(mrMatch[1]), Number(mrMatch[2]))
    const { notes, ...rest } = mr
    return sendJson(res, 200, rest)
  }
  const listMatch = decoded.match(/^\/api\/v4\/projects\/(.+)\/merge_requests$/)
  if (listMatch) {
    bump('gitlab.list')
    if (applyTrap('gitlab.list', res)) return
    const project = decodeProject(listMatch[1])
    const items = []
    for (const [key, mr] of state.gitlab.mrs) {
      if (!key.startsWith(`${project}#`)) continue
      const { notes, ...rest } = mr
      items.push(rest)
    }
    return sendJson(res, 200, items)
  }
  const protectedMatch = decoded.match(/^\/api\/v4\/projects\/(.+)\/protected_branches$/)
  if (protectedMatch) {
    bump('gitlab.protected')
    if (applyTrap('gitlab.protected', res)) return
    return sendJson(res, 200, state.gitlab.protectedBranches)
  }
  const branchesMatch = decoded.match(/^\/api\/v4\/projects\/(.+)\/repository\/branches$/)
  if (branchesMatch) {
    bump('gitlab.branches')
    if (applyTrap('gitlab.branches', res)) return
    const perPage = Number(url.searchParams.get('per_page') || 20)
    return sendJson(res, 200, state.gitlab.branches.slice(0, perPage).map((name) => ({ name, default: name === 'main' })))
  }
  // 项目名经 URL 编码后是 demo-group%2Fdemo-project，decodeProject 之后变成两段路径：
  // 前面的 merge_requests/protected_branches/repository 路由都先匹配，这里才敢用贪婪匹配。
  const projectMatch = decoded.match(/^\/api\/v4\/projects\/(.+)$/)
  if (projectMatch) {
    bump('gitlab.project')
    if (applyTrap('gitlab.project', res)) return
    return sendJson(res, 200, state.gitlab.project)
  }
  if (decoded === '/api/v4/user') {
    bump('gitlab.project')
    return sendJson(res, 200, { id: 1, username: 'stub-bot', name: 'Stub Bot' })
  }
  if (decoded === '/api/v4/version') {
    bump('gitlab.project')
    return sendJson(res, 200, { version: '17.0.0-stub', revision: 'stub' })
  }
  bump('unknown')
  return sendJson(res, 404, { message: `stub 未实现: GET ${path}` })
}

async function control(req, res, path, url, body) {
  if (path === '/__stub/health') {
    return sendJson(res, 200, { status: 'UP', service: 'collab-stub', port: PORT, project: DEFAULT_PROJECT })
  }
  if (path === '/__stub/reset' && req.method === 'POST') {
    state = initialState()
    return sendJson(res, 200, { reset: true })
  }
  if (path === '/__stub/counter' && req.method === 'GET') {
    const key = url.searchParams.get('key') || 'all'
    return sendJson(res, 200, { key, count: state.counters[key] || 0 })
  }
  if (path === '/__stub/counters') {
    return sendJson(res, 200, state.counters)
  }
  if (path === '/__stub/mode' && req.method === 'POST') {
    state.modes[body.key || 'all'] = body.value || 'ok'
    return sendJson(res, 200, { key: body.key || 'all', value: body.value || 'ok' })
  }
  if (path === '/__stub/state') {
    return sendJson(res, 200, {
      project: state.gitlab.project,
      protectedBranches: state.gitlab.protectedBranches,
      branches: state.gitlab.branches,
      mrs: [...state.gitlab.mrs.values()].map(({ notes, ...rest }) => rest),
      counters: state.counters,
      modes: state.modes,
    })
  }
  if (path === '/__stub/gitlab/project' && req.method === 'POST') {
    Object.assign(state.gitlab.project, body || {})
    return sendJson(res, 200, state.gitlab.project)
  }
  if (path === '/__stub/gitlab/protected' && req.method === 'POST') {
    state.gitlab.protectedBranches = (body.branches || []).map((item) => ({
      name: item.name,
      push_access_levels: (item.push_roles || ['Maintainer']).map((role) => ({ access_level: role === 'Owner' ? 50 : role === 'Maintainer' ? 40 : 30, access_level_description: role })),
      merge_access_levels: [{ access_level: 40, access_level_description: 'Maintainer' }],
    }))
    return sendJson(res, 200, state.gitlab.protectedBranches)
  }
  if (path === '/__stub/gitlab/branches' && req.method === 'POST') {
    if (Array.isArray(body.names)) state.gitlab.branches = body.names
    return sendJson(res, 200, { count: state.gitlab.branches.length })
  }
  if (path === '/__stub/gitlab/mr' && req.method === 'POST') {
    const project = body.project || DEFAULT_PROJECT
    const iid = Number(body.iid || 1)
    const mr = ensureMr(project, iid, body.patch || {})
    if (Array.isArray(body.notes)) {
      mr.notes = body.notes.map((note, index) => ({ id: note.id || 9000 + index, ...note }))
    }
    return sendJson(res, 200, { project, iid, notes: mr.notes.length })
  }
  return sendJson(res, 404, { message: `未知控制面路径: ${path}` })
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, SELF)
  const path = url.pathname
  state.requests.push({ method: req.method, path })
  if (state.requests.length > 500) state.requests.shift()
  readBody(req).then((body) => {
    if (path.startsWith('/__stub/')) {
      control(req, res, path, url, body).catch((error) => sendJson(res, 500, { message: String(error) }))
      return
    }
    if (path.startsWith('/api/v4/')) {
      try {
        gitlabRoute(req, res, path, url)
      } catch (error) {
        sendJson(res, 500, { message: String(error) })
      }
      return
    }
    bump('unknown')
    sendJson(res, 404, { message: `stub 未实现: ${req.method} ${path}` })
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`collab stub listening on ${SELF}`)
})
