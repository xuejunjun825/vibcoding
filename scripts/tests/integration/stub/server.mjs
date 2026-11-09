#!/usr/bin/env node
// server.mjs — M7 集成对接的本地四合一桩（Jenkins / GitLab / Sonar / Nexus）。
//
// 只被 scripts/tests/integration/*.sh 与 make integration-verify 拉起，永远不进产品路径：
// req-service 的适配器不知道桩的存在，它看到的只是一个普通的 127.0.0.1:19090。
//
// 设计要点
//   * 零依赖：只用 node:http，仓库里不新增 npm 包。
//   * 可观测：每个逻辑操作都计数（失败也计数），脚本用 /__stub/counter 断言"重试了几次"。
//   * 可注入：/__stub/mode 把某个逻辑操作切成 500 / 429 / timeout / slow，断言降级与重试。
//   * 可编排：/__stub/jenkins/build 推进构建状态，/__stub/sonar 改质量门，/__stub/nexus/checksums 改校验和。
//
// 启动：STUB_PORT=19090 node scripts/tests/integration/stub/server.mjs
// 就绪：GET /__stub/health → {"status":"UP",...}

import http from 'node:http'

const PORT = Number(process.env.STUB_PORT || 19090)
const HOST = process.env.STUB_HOST || '127.0.0.1'
const SELF = `http://${HOST}:${PORT}`
const VERBOSE = process.env.STUB_VERBOSE === '1'

const SHA_RELEASES = 'a1'.repeat(32)
const SHA_PROD = 'a1'.repeat(32)
const SHA_SNAPSHOTS = 'b2'.repeat(32)

const BASE_METRICS = {
  coverage: '82.5',
  bugs: '0',
  vulnerabilities: '1',
  code_smells: '7',
  duplicated_lines_density: '1.2',
  reliability_rating: '1.0',
  security_rating: '1.0',
}

function initialState() {
  return {
    modes: {},
    counters: {},
    requests: [],
    builds: new Map(),
    buildSeq: 0,
    mrs: new Map(),
    checksums: { releases: SHA_RELEASES, prod: SHA_PROD, snapshots: SHA_SNAPSHOTS },
    sonar: { status: 'OK', metrics: { ...BASE_METRICS }, missing: [] },
  }
}

let state = initialState()

function resetState() {
  state = initialState()
}

function bump(key) {
  state.counters[key] = (state.counters[key] || 0) + 1
}

function log(...args) {
  if (VERBOSE) console.log('[stub]', ...args)
}

function sendJson(res, code, payload, headers = {}) {
  const body = Buffer.from(JSON.stringify(payload))
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    ...headers,
  })
  res.end(body)
}

function sendText(res, code, text, headers = {}) {
  const body = Buffer.from(String(text))
  res.writeHead(code, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': body.length,
    ...headers,
  })
  res.end(body)
}

// 返回 true 表示已由故障注入接管，调用方直接 return。
function applyTrap(key, res) {
  const mode = state.modes[key] || state.modes.all
  if (!mode || mode === 'ok') return false
  if (mode === '500') {
    sendJson(res, 500, { error: 'stub injected 500', key })
    return true
  }
  if (mode === '429') {
    sendJson(res, 429, { message: 'stub injected 429', key }, { 'Retry-After': '1' })
    return true
  }
  if (mode === 'timeout') {
    // 挂住 10s，客户端的显式超时必须先生效；10s 后再兜底回包，避免把连接挂死。
    const timer = setTimeout(() => {
      if (!res.writableEnded) sendJson(res, 504, { error: 'stub timeout', key })
    }, 10000)
    res.on('close', () => clearTimeout(timer))
    return true
  }
  if (mode === 'slow') {
    const timer = setTimeout(() => {
      if (!res.writableEnded) sendJson(res, 200, { ok: true, slow: true, key })
    }, 2000)
    res.on('close', () => clearTimeout(timer))
    return true
  }
  // 未知 mode 不拦（宁可放过，也不要制造一个永远失败的环境）
  return false
}

function readBody(req) {
  return new Promise((resolve) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size <= 2 * 1024 * 1024) chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', () => resolve(''))
  })
}

function parseJson(text) {
  if (!text) return {}
  try {
    const v = JSON.parse(text)
    return v && typeof v === 'object' ? v : {}
  } catch {
    return {}
  }
}

function classify(method, path, url) {
  if (path.startsWith('/__stub/')) return { key: 'control', handler: 'control' }

  // Jenkins
  if (path === '/api/json' || path === '/me/api/json') return { key: 'jenkins.test', handler: 'jenkinsInfo' }
  if (/^\/job\/.+\/(buildWithParameters|build)\/?$/.test(path) && method === 'POST') {
    return { key: 'jenkins.submit', handler: 'jenkinsSubmit' }
  }
  if (/^\/queue\/item\/\d+\/api\/json$/.test(path)) return { key: 'jenkins.status', handler: 'jenkinsQueue' }
  if (/^\/job\/.+\/\d+\/api\/json$/.test(path)) return { key: 'jenkins.status', handler: 'jenkinsBuild' }
  if (/^\/job\/.+\/\d+\/consoleText$/.test(path)) return { key: 'jenkins.status', handler: 'jenkinsConsole' }
  if (path.startsWith('/blue/rest/')) return { key: 'jenkins.status', handler: 'jenkinsBlue' }

  // GitLab
  if (/^\/api\/v4\/projects\/.+\/merge_requests\/\d+\/approvals$/.test(path)) {
    return { key: 'gitlab.mr', handler: 'gitlabApprovals' }
  }
  if (/^\/api\/v4\/projects\/.+\/merge_requests\/\d+$/.test(path)) {
    return { key: 'gitlab.mr', handler: 'gitlabMr' }
  }
  if (/^\/api\/v4\/projects\/.+\/merge_requests\/?$/.test(path)) {
    return { key: 'gitlab.list', handler: 'gitlabMrList' }
  }
  if (/^\/api\/v4\/projects\/[^/]+$/.test(path) && path.split('/').length === 5) {
    return { key: 'gitlab.test', handler: 'gitlabProject' }
  }
  if (path === '/api/v4/user' || path === '/api/v4/version') {
    return { key: 'gitlab.test', handler: 'gitlabMeta' }
  }

  // Sonar
  if (path.startsWith('/api/qualitygates/')) return { key: 'sonar.gate', handler: 'sonarGate' }
  if (path.startsWith('/api/measures/')) return { key: 'sonar.measures', handler: 'sonarMeasures' }
  if (path.startsWith('/api/authentication/')) return { key: 'sonar.test', handler: 'sonarValidate' }
  if (path.startsWith('/api/server/') || path.startsWith('/api/system/')) {
    return { key: 'sonar.test', handler: 'sonarMeta' }
  }

  // Nexus
  if (path.startsWith('/service/rest/v1/search')) return { key: 'nexus.search', handler: 'nexusSearch' }
  if (path.startsWith('/service/rest/v1/status')) return { key: 'nexus.test', handler: 'nexusStatus' }
  if (path.startsWith('/repository/')) return { key: 'nexus.checksum', handler: 'nexusDownload' }

  return { key: 'unknown', handler: 'notFound', url }
}

// ------------------------------------------------------------------ Jenkins

function newBuild(job, number, status = 'QUEUED') {
  return {
    job,
    number,
    status,
    stages: [
      { name: 'compile', status: 'SUCCESS', durationMs: 1200 },
      { name: 'unit-test', status: status === 'RUNNING' ? 'RUNNING' : 'SUCCESS', durationMs: 800 },
      { name: 'package', status: status === 'QUEUED' || status === 'RUNNING' ? 'PENDING' : 'SUCCESS', durationMs: 600 },
    ],
    consoleUrl: `${SELF}/job/${encodeURIComponent(job)}/${number}/console`,
    startedAt: status === 'QUEUED' ? null : new Date().toISOString(),
    finishedAt: null,
  }
}

function buildKey(job, number) {
  return `${job}#${number}`
}

function latestBuild() {
  let found = null
  for (const b of state.builds.values()) {
    if (!found || b.number >= found.number) found = b
  }
  return found
}

function findByQueueOrNumber(id) {
  for (const b of state.builds.values()) {
    if (b.number === id) return b
  }
  return null
}

function jenkinsStatusPayload(b) {
  const building = b.status === 'QUEUED' || b.status === 'RUNNING'
  const result =
    b.status === 'SUCCESS' ? 'SUCCESS' : b.status === 'FAILURE' ? 'FAILURE' : b.status === 'ABORTED' ? 'ABORTED' : null
  const duration = building ? 0 : 12_000
  return {
    _class: 'org.jenkinsci.plugins.workflow.job.WorkflowRun',
    number: b.number,
    queueId: b.number,
    displayName: `${b.job} #${b.number}`,
    fullDisplayName: `${b.job} #${b.number}`,
    building,
    result,
    duration,
    timestamp: Date.parse(b.startedAt || new Date().toISOString()),
    url: `${SELF}/job/${encodeURIComponent(b.job)}/${b.number}/`,
    consoleUrl: b.consoleUrl,
    stages: b.stages,
    artifacts: [],
  }
}

function jenkinsSubmit(req, res, path) {
  const job = decodeURIComponent(path.replace(/^\/job\//, '').replace(/\/(buildWithParameters|build)\/?$/, ''))
  const number = ++state.buildSeq
  const b = newBuild(job, number, 'QUEUED')
  state.builds.set(buildKey(job, number), b)
  state.lastSubmitted = buildKey(job, number)
  sendJson(
    res,
    201,
    { queueId: number, buildNumber: number, number, id: number, job },
    { Location: `${SELF}/queue/item/${number}/` },
  )
}

function jenkinsQueue(req, res, path) {
  const id = Number(path.match(/^\/queue\/item\/(\d+)\/api\/json$/)[1])
  const b = findByQueueOrNumber(id)
  if (!b) return sendJson(res, 404, { error: 'queue item not found', id })
  const executable = b.status === 'QUEUED' ? null : { number: b.number, url: `${SELF}/job/${encodeURIComponent(b.job)}/${b.number}/` }
  sendJson(res, 200, {
    id,
    queueId: id,
    why: b.status === 'QUEUED' ? 'waiting for an executor' : null,
    cancelled: false,
    executable,
  })
}

function jenkinsBuild(req, res, path) {
  const m = path.match(/^\/job\/(.+)\/(\d+)\/api\/json$/)
  const job = decodeURIComponent(m[1])
  const number = Number(m[2])
  const b = state.builds.get(buildKey(job, number))
  if (!b) return sendJson(res, 404, { error: 'build not found', job, number })
  sendJson(res, 200, jenkinsStatusPayload(b))
}

function jenkinsConsole(req, res, path) {
  const m = path.match(/^\/job\/(.+)\/(\d+)\/consoleText$/)
  const job = decodeURIComponent(m[1])
  const number = Number(m[2])
  const b = state.builds.get(buildKey(job, number))
  const status = b ? b.status : 'UNKNOWN'
  sendText(res, 200, `Started by user stub\nBuilding ${job} #${number}\nFinished: ${status}\n`)
}

function jenkinsBlue(req, res) {
  const builds = [...state.builds.values()].slice(-10)
  sendJson(res, 200, {
    items: builds.map((b) => ({
      id: String(b.number),
      state: b.status === 'RUNNING' ? 'RUNNING' : b.status === 'QUEUED' ? 'QUEUED' : 'FINISHED',
      result: b.status,
      stages: b.stages,
    })),
  })
}

// ------------------------------------------------------------------ GitLab

function mrKey(project, iid) {
  return `${project}#${iid}`
}

function ensureMr(project, iid) {
  const key = mrKey(project, iid)
  if (!state.mrs.has(key)) {
    state.mrs.set(key, {
      id: iid,
      iid,
      project_id: 1000 + iid,
      title: `stub MR !${iid}`,
      state: 'opened',
      source_branch: `feat/stub-${iid}`,
      target_branch: 'main',
      web_url: `${SELF}/${project}/-/merge_requests/${iid}`,
      additions: 12,
      deletions: 3,
      updated_at: '2026-11-09T05:00:00.000Z',
      merged_at: null,
      detailed_merge_status: 'mergeable',
      author: { username: 'stub-author' },
      approved_by: [{ user: { username: 'stub-reviewer' } }],
    })
  }
  return state.mrs.get(key)
}

function decodeProject(raw) {
  try {
    return decodeURIComponent(raw)
  } catch {
    return raw
  }
}

function gitlabMr(req, res, path) {
  const m = path.match(/^\/api\/v4\/projects\/(.+)\/merge_requests\/(\d+)$/)
  const project = decodeProject(m[1])
  const iid = Number(m[2])
  sendJson(res, 200, ensureMr(project, iid))
}

function gitlabApprovals(req, res, path) {
  const m = path.match(/^\/api\/v4\/projects\/(.+)\/merge_requests\/(\d+)\/approvals$/)
  const project = decodeProject(m[1])
  const iid = Number(m[2])
  const mr = ensureMr(project, iid)
  sendJson(res, 200, {
    id: iid,
    iid,
    project_id: mr.project_id,
    approved_by: mr.approved_by,
    approvals_required: 1,
    approvals_left: mr.approved_by.length > 0 ? 0 : 1,
  })
}

function gitlabMrList(req, res, path, url) {
  const m = path.match(/^\/api\/v4\/projects\/(.+)\/merge_requests\/?$/)
  const project = decodeProject(m[1])
  const wanted = (url.searchParams.get('state') || 'all').toLowerCase()
  const perPage = Number(url.searchParams.get('per_page') || 20)
  const items = []
  for (const mr of state.mrs.values()) {
    if (!mr.web_url.startsWith(`${SELF}/${project}`)) continue
    if (wanted !== 'all' && mr.state !== wanted) continue
    items.push(mr)
  }
  sendJson(res, 200, items.slice(0, perPage))
}

function gitlabProject(req, res, path) {
  const project = decodeProject(path.replace(/^\/api\/v4\/projects\//, ''))
  sendJson(res, 200, {
    id: 11,
    name: project.split('/').pop(),
    path_with_namespace: project,
    default_branch: 'main',
    web_url: `${SELF}/${project}`,
  })
}

function gitlabMeta(req, res, path) {
  if (path === '/api/v4/user') return sendJson(res, 200, { id: 1, username: 'stub-bot', name: 'Stub Bot' })
  sendJson(res, 200, { version: '17.0.0-stub', revision: 'stub', enterprise: false })
}

// ------------------------------------------------------------------ Sonar

function sonarGate(req, res, url) {
  const projectKey = url.searchParams.get('projectKey') || url.searchParams.get('componentKey') || 'unknown'
  const status = state.sonar.status
  const metrics = state.sonar.metrics
  const conditions = [
    { metricKey: 'coverage', comparator: 'LT', errorThreshold: '80', actualValue: metrics.coverage, status: 'OK' },
    { metricKey: 'bugs', comparator: 'GT', errorThreshold: '0', actualValue: metrics.bugs, status: status === 'ERROR' ? 'ERROR' : 'OK' },
    { metricKey: 'vulnerabilities', comparator: 'GT', errorThreshold: '0', actualValue: metrics.vulnerabilities, status: 'OK' },
  ]
  if (status === 'ERROR') conditions[0].status = 'ERROR'
  sendJson(res, 200, { projectStatus: { status, conditions } })
}

function sonarMeasures(req, res, url) {
  const component = url.searchParams.get('component') || url.searchParams.get('projectKey') || 'unknown'
  const keys = (url.searchParams.get('metricKeys') || Object.keys(state.sonar.metrics).join(','))
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  const missing = new Set(state.sonar.missing || [])
  const measures = keys
    .filter((k) => !missing.has(k) && state.sonar.metrics[k] !== undefined)
    .map((k) => ({ metric: k, value: String(state.sonar.metrics[k]), bestValue: false }))
  sendJson(res, 200, { component: { key: component, name: component, qualifier: 'TRK', measures } })
}

function sonarValidate(req, res) {
  sendJson(res, 200, { valid: true })
}

function sonarMeta(req, res) {
  sendJson(res, 200, { version: '10.4-stub', status: 'UP' })
}

// ------------------------------------------------------------------ Nexus

function nexusItems(repository, version) {
  const sha = state.checksums[repository] || state.checksums.releases
  const base = `${SELF}/repository/${repository}`
  const rows = [
    { suffix: 'jar', version: '1.2.3', packaging: 'jar', size: 4096 },
    { suffix: 'pom', version: '1.2.3', packaging: 'pom', size: 1024 },
    { suffix: 'jar', version: '1.2.2', packaging: 'jar', size: 4000 },
  ]
  return rows.map((row, idx) => {
    const artifact = 'demo-app'
    const path = `com/example/${artifact}/${row.version}/${artifact}-${row.version}.${row.suffix}`
    return {
      id: `${repository}-${idx + 1}`,
      repository,
      format: 'maven2',
      group: 'com.example',
      groupId: 'com.example',
      name: artifact,
      artifactName: artifact,
      version: row.version,
      packaging: row.packaging,
      classifier: null,
      sha256: sha,
      sizeBytes: row.size,
      contentLength: row.size,
      downloadUrl: `${base}/${path}`,
      assets: [
        {
          id: `${repository}-asset-${idx + 1}`,
          repository,
          format: 'maven2',
          path,
          downloadUrl: `${base}/${path}`,
          contentLength: row.size,
          checksum: { sha1: 'c'.repeat(40), sha256: sha },
        },
      ],
    }
  })
}

// 固定版本之外的坐标：桩按"仓库校验和"合成一条，让脚本每次运行都能用新版本号
// （artifact_record 以坐标为幂等键，固定 1.2.3 会让第二次运行正确地 409）。
function nexusSynthetic(repository, version) {
  const sha = state.checksums[repository] || state.checksums.releases
  const base = `${SELF}/repository/${repository}`
  const path = `com/example/demo-app/${version}/demo-app-${version}.jar`
  return {
    id: `${repository}-synth-${version}`,
    repository,
    format: 'maven2',
    group: 'com.example',
    groupId: 'com.example',
    name: 'demo-app',
    artifactName: 'demo-app',
    version,
    packaging: 'jar',
    classifier: null,
    sha256: sha,
    sizeBytes: 4096,
    contentLength: 4096,
    downloadUrl: `${base}/${path}`,
    assets: [
      {
        id: `${repository}-synth-asset-${version}`,
        repository,
        format: 'maven2',
        path,
        downloadUrl: `${base}/${path}`,
        contentLength: 4096,
        checksum: { sha1: 'c'.repeat(40), sha256: sha },
      },
    ],
  }
}

function nexusSearch(req, res, url) {
  const repository = url.searchParams.get('repository') || 'releases'
  const version = url.searchParams.get('version') || ''
  const q = (url.searchParams.get('q') || url.searchParams.get('name') || '').toLowerCase()
  let items = nexusItems(repository, version)
  if (version) items = items.filter((i) => i.version === version)
  if (version && items.length === 0) items = [nexusSynthetic(repository, version)]
  if (q) items = items.filter((i) => `${i.group}/${i.name}/${i.version}`.toLowerCase().includes(q))
  const limit = Number(url.searchParams.get('limit') || 50)
  const page = items.slice(0, Number.isFinite(limit) && limit > 0 ? Math.min(limit, 50) : 50)
  sendJson(res, 200, {
    items: page,
    continuationToken: null,
    totalCount: page.length,
  })
}

function nexusStatus(req, res) {
  sendJson(res, 200, { healthy: true, version: '3.68.1-stub', available: true })
}

function nexusDownload(req, res, path, method) {
  const repo = path.split('/')[2] || 'releases'
  const sha = state.checksums[repo] || state.checksums.releases
  const body = Buffer.from('stub artifact bytes')
  const headers = {
    'Content-Type': 'application/java-archive',
    'Content-Length': method === 'HEAD' ? 0 : body.length,
    'X-Checksum-Sha256': sha,
    'X-Checksum-Sha1': 'c'.repeat(40),
    'X-Checksum-Md5': 'd'.repeat(32),
    'Accept-Ranges': 'bytes',
  }
  if (method === 'HEAD') {
    res.writeHead(200, headers)
    return res.end()
  }
  res.writeHead(200, headers)
  res.end(body)
}

// ------------------------------------------------------------------ control plane

function handleControl(req, res, path, body, url) {
  const payload = parseJson(body)
  if (path === '/__stub/health' && req.method === 'GET') {
    return sendJson(res, 200, {
      status: 'UP',
      stub: 'm7-integration',
      builds: state.builds.size,
      mrs: state.mrs.size,
      counters: state.counters,
    })
  }
  if (path === '/__stub/reset' && req.method === 'POST') {
    resetState()
    return sendJson(res, 200, { reset: true })
  }
  if (path === '/__stub/counters/reset' && req.method === 'POST') {
    state.counters = {}
    return sendJson(res, 200, { reset: true })
  }
  if (path === '/__stub/counters' && req.method === 'GET') {
    return sendJson(res, 200, { counters: state.counters })
  }
  if (path === '/__stub/counter' && req.method === 'GET') {
    const key = url.searchParams.get('key') || 'all'
    return sendJson(res, 200, { key, count: state.counters[key] || 0 })
  }
  if (path === '/__stub/requests' && req.method === 'GET') {
    return sendJson(res, 200, { requests: state.requests })
  }
  if (path === '/__stub/mode' && req.method === 'POST') {
    const key = payload.key || 'all'
    const value = payload.value || 'ok'
    state.modes[key] = value
    return sendJson(res, 200, { modes: state.modes })
  }
  if (path === '/__stub/modes' && req.method === 'GET') {
    return sendJson(res, 200, { modes: state.modes })
  }
  if (path === '/__stub/jenkins/build' && req.method === 'POST') {
    const number = Number(payload.number) || state.buildSeq + 1
    state.buildSeq = Math.max(state.buildSeq, number)
    const job = payload.job || state.lastJob || 'demo-job'
    state.lastJob = job
    const key = buildKey(job, number)
    const b = state.builds.get(key) || newBuild(job, number, payload.status || 'QUEUED')
    b.status = payload.status || b.status
    if (Array.isArray(payload.stages)) b.stages = payload.stages
    if (b.status !== 'QUEUED') b.startedAt = b.startedAt || new Date().toISOString()
    if (['SUCCESS', 'FAILURE', 'ABORTED'].includes(b.status)) {
      b.finishedAt = new Date().toISOString()
      b.stages = b.stages.map((s) => ({
        ...s,
        status: s.status === 'PENDING' || s.status === 'RUNNING' ? (b.status === 'FAILURE' ? 'FAILURE' : 'SUCCESS') : s.status,
      }))
    }
    state.builds.set(key, b)
    return sendJson(res, 200, { job, number, status: b.status, key })
  }
  if (path === '/__stub/nexus/checksums' && req.method === 'POST') {
    if (payload.repo) state.checksums[payload.repo] = payload.sha256
    if (payload.sha256 && !payload.repo) state.checksums.releases = payload.sha256
    return sendJson(res, 200, { checksums: state.checksums })
  }
  if (path === '/__stub/sonar' && req.method === 'POST') {
    if (payload.status) state.sonar.status = payload.status
    if (payload.metrics) Object.assign(state.sonar.metrics, payload.metrics)
    if (Array.isArray(payload.missing)) state.sonar.missing = payload.missing
    return sendJson(res, 200, { sonar: state.sonar })
  }
  if (path === '/__stub/gitlab/mr' && req.method === 'POST') {
    const project = payload.project || 'demo-group/demo-project'
    const iid = Number(payload.iid || 1)
    const mr = ensureMr(project, iid)
    for (const key of ['state', 'title', 'source_branch', 'target_branch', 'updated_at', 'merged_at', 'additions', 'deletions']) {
      if (payload[key] !== undefined) mr[key] = payload[key]
    }
    if (Array.isArray(payload.approved_by)) mr.approved_by = payload.approved_by
    if (payload.state === 'merged' && !mr.merged_at) mr.merged_at = new Date().toISOString()
    state.mrs.set(mrKey(project, iid), mr)
    return sendJson(res, 200, { project, iid, mr })
  }
  if (path === '/__stub/state' && req.method === 'GET') {
    return sendJson(res, 200, {
      modes: state.modes,
      counters: state.counters,
      checksums: state.checksums,
      sonar: state.sonar,
      builds: [...state.builds.values()],
      mrs: [...state.mrs.values()],
    })
  }
  return sendJson(res, 404, { error: 'unknown control endpoint', path })
}

// ------------------------------------------------------------------ server

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', SELF)
  const path = url.pathname
  const method = req.method || 'GET'
  const body = await readBody(req)

  if (path.startsWith('/__stub/')) {
    log('control', method, path)
    return handleControl(req, res, path, body, url)
  }

  const route = classify(method, path, url)
  bump(route.key)
  state.requests.push({ method, path: req.url, key: route.key, at: new Date().toISOString() })
  if (state.requests.length > 200) state.requests.shift()
  log(method, path, '->', route.key)

  if (applyTrap(route.key, res)) return

  switch (route.handler) {
    case 'jenkinsInfo':
      return sendJson(res, 200, { _class: 'hudson.model.Hudson', mode: 'NORMAL', nodeDescription: 'stub', jobs: [] })
    case 'jenkinsSubmit':
      return jenkinsSubmit(req, res, path)
    case 'jenkinsQueue':
      return jenkinsQueue(req, res, path)
    case 'jenkinsBuild':
      return jenkinsBuild(req, res, path)
    case 'jenkinsConsole':
      return jenkinsConsole(req, res, path)
    case 'jenkinsBlue':
      return jenkinsBlue(req, res)
    case 'gitlabMr':
      return gitlabMr(req, res, path)
    case 'gitlabApprovals':
      return gitlabApprovals(req, res, path)
    case 'gitlabMrList':
      return gitlabMrList(req, res, path, url)
    case 'gitlabProject':
      return gitlabProject(req, res, path)
    case 'gitlabMeta':
      return gitlabMeta(req, res, path)
    case 'sonarGate':
      return sonarGate(req, res, url)
    case 'sonarMeasures':
      return sonarMeasures(req, res, url)
    case 'sonarValidate':
      return sonarValidate(req, res)
    case 'sonarMeta':
      return sonarMeta(req, res)
    case 'nexusSearch':
      return nexusSearch(req, res, url)
    case 'nexusStatus':
      return nexusStatus(req, res)
    case 'nexusDownload':
      return nexusDownload(req, res, path, method)
    default:
      return sendJson(res, 404, { error: 'stub: no route', method, path })
  }
})

server.on('clientError', (err, socket) => {
  if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\n\r\n')
})

server.listen(PORT, HOST, () => {
  console.log(`STUB READY ${SELF} (jenkins/gitlab/sonar/nexus)`)
})

function shutdown(signal) {
  console.log(`STUB STOP ${signal}`)
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 2000).unref()
}

process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGTERM', () => shutdown('SIGTERM'))

server.on('error', (err) => {
  console.error(`STUB ERROR ${err.code || ''} ${err.message}`)
  process.exit(2)
})
