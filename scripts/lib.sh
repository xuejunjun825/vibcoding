#!/usr/bin/env bash
# Shared library for all dev-environment scripts. Source it, do not execute it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT

# ---- Configurable defaults (override via environment or .env) ----
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
DEMO_PORT="${DEMO_PORT:-8081}"
NACOS_HTTP_PORT="${NACOS_HTTP_PORT:-8848}"
NACOS_CONSOLE_PORT="${NACOS_CONSOLE_PORT:-8849}"
NACOS_GRPC_PORT="${NACOS_GRPC_PORT:-9848}"
NACOS_GRPC_TLS_PORT="${NACOS_GRPC_TLS_PORT:-9849}"
NACOS_VERSION="${NACOS_VERSION:-3.3.0}"
NACOS_HOME="${NACOS_HOME:-$ROOT/.nacos}"
DATA_ID="demo-service.yml"
GROUP="DEFAULT_GROUP"
CONFIG_SEED="$ROOT/config/nacos/demo-service.yml"

# .env (gitignored) MUST be sourced before any derived value: sourcing it after
# the URLs were computed left `GATEWAY_PORT=9090` in .env with GATEWAY_URL still
# pointing at :8080, so callers probed one port while the app bound another.
if [[ -f "$ROOT/.env" ]]; then
    # shellcheck disable=SC1091
    set -a; source "$ROOT/.env"; set +a
fi

# ---- Derived values (recomputed AFTER .env on purpose) ----
NACOS_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:${NACOS_HTTP_PORT}}"
NACOS_BASE="http://127.0.0.1:${NACOS_HTTP_PORT}"
GATEWAY_URL="http://127.0.0.1:${GATEWAY_PORT}"
DEMO_URL="http://127.0.0.1:${DEMO_PORT}"

# ---- Platform layer (20261108-r2): req 8082 / deliver 8083 / web 5173 ----
REQ_PORT="${REQ_PORT:-8082}"
DELIVER_PORT="${DELIVER_PORT:-8083}"
WEB_PORT="${WEB_PORT:-5173}"
REQ_URL="http://127.0.0.1:${REQ_PORT}"
DELIVER_URL="http://127.0.0.1:${DELIVER_PORT}"
WEB_URL="http://127.0.0.1:${WEB_PORT}"
PLATFORM_DB_URL="${PLATFORM_DB_URL:-jdbc:mysql://127.0.0.1:3306/devops_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8}"
PLATFORM_DB_USER="${PLATFORM_DB_USER:-root}"
PLATFORM_DB_PASSWORD="${PLATFORM_DB_PASSWORD:-}"
PLATFORM_INTERNAL_TOKEN="${PLATFORM_INTERNAL_TOKEN:-dev-internal-token}"
PLATFORM_JWT_SECRET="${PLATFORM_JWT_SECRET:-dev-only-jwt-secret-please-change-32bytes-min}"
PLATFORM_MASTER_KEY="${PLATFORM_MASTER_KEY:-ZGV2LW9ubHktbWFzdGVyLWtleS0zMi1ieXRlcy1sb25nISE=}"
PLATFORM_SEED_ENABLED="${PLATFORM_SEED_ENABLED:-false}"

_platform_db_parse() {
    # jdbc:mysql://host:port/db?query  ->  PLATFORM_DB_HOST/PORT/NAME
    local rest="${PLATFORM_DB_URL#jdbc:mysql://}"
    local hp="${rest%%/*}" dbq="${rest#*/}"
    PLATFORM_DB_HOST="${hp%%:*}"
    PLATFORM_DB_PORT="${hp##*:}"
    [[ "$PLATFORM_DB_PORT" == "$PLATFORM_DB_HOST" ]] && PLATFORM_DB_PORT=3306
    PLATFORM_DB_NAME="${dbq%%\?*}"
}
_platform_db_parse

RUN_DIR="$ROOT/.run"
LOG_DIR="$ROOT/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

if [[ -t 1 ]]; then
    C_BLUE=$'\033[1;34m'; C_GREEN=$'\033[1;32m'; C_YELLOW=$'\033[1;33m'
    C_RED=$'\033[1;31m'; C_RESET=$'\033[0m'
else
    C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_RESET=""
fi

log()  { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()  { printf '%s err%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

port_pids() {
    # lsof exits 1 when nothing listens; keep callers under `set -e` alive.
    lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/ *$//' || true
}

port_listening() {
    [[ -n "$(port_pids "$1")" ]]
}

app_pid() {
    local f="$RUN_DIR/$1.pid"
    [[ -f "$f" ]] && tr -d '[:space:]' < "$f" || true
}

app_alive() {
    local name="$1" pid marker evidence
    pid="$(app_pid "$name")"
    [[ -n "$pid" ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    # Guard against PID reuse after `kill -9` left a stale .run/<name>.pid: a
    # recycled pid belongs to a foreign process, so kill -0 alone would make
    # start_app skip the restart. `ps` is the cheapest witness of *what* the pid
    # is; the sandbox can deny it, in which case lsof still exposes the jar/cwd
    # paths the pid has open. Only when both are unavailable do we fall back to
    # the conservative kill -0 answer.
    marker="$ROOT/$name/target/"
    [[ "$name" == "nacos" ]] && marker="$NACOS_HOME/"
    evidence="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ -z "$evidence" ]]; then
        evidence="$(lsof -nP -p "$pid" 2>/dev/null || true)"
    fi
    if [[ -n "$evidence" && "$evidence" != *"$marker"* ]]; then
        return 1
    fi
    return 0
}

http_ok() {
    curl -fsS --max-time 4 -o /dev/null "$1" 2>/dev/null
}

http_body() {
    curl -fsS --max-time 6 "$1" 2>/dev/null || true
}

app_health_up() {
    local port="$1"
    http_body "http://127.0.0.1:${port}/actuator/health" \
        | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
}

wait_until() {
    local desc="$1" timeout="$2"; shift 2
    local i=0
    while ! "$@" >/dev/null 2>&1; do
        i=$((i + 1))
        if (( i >= timeout )); then
            err "超时 ${timeout}s: ${desc}"
            return 1
        fi
        sleep 1
    done
    ok "$desc"
}

wait_http_ok() {
    wait_until "$2" "$3" http_ok "$1"
}

wait_app_health() {
    local name="$1" port="$2"
    wait_until "${name} /actuator/health = UP" 90 app_health_up "$port"
}

nacos_health_path() {
    # Nacos 3.3 answers 404 on both v1 console health endpoints (measured
    # 2026-11-08), so the v3 admin core-state endpoint is tried first and the
    # v1 metrics endpoint stays as the fallback for older distributions.
    # Every candidate must return a healthy BODY, not just HTTP 200: a foreign
    # process squatting on 8848 must not be able to turn this check green.
    local p body
    for p in "/nacos/v3/admin/core/state" \
             "/nacos/v1/console/health/readiness" \
             "/nacos/v1/console/health/liveness" \
             "/nacos/v1/ns/operator/metrics"; do
        body="$(http_body "${NACOS_BASE}${p}")"
        if [[ "$p" == "/nacos/v3/admin/core/state" ]]; then
            if grep -Eq '"code"[[:space:]]*:[[:space:]]*0' <<<"$body"; then
                printf '%s' "$p"
                return 0
            fi
        else
            if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$body"; then
                printf '%s' "$p"
                return 0
            fi
        fi
    done
    return 1
}

nacos_healthy() {
    nacos_health_path >/dev/null
}

nacos_config_base() {
    # Single source of truth for the v3 admin config endpoint path.
    printf '%s/nacos/v3/admin/cs/config' "$NACOS_BASE"
}

nacos_config_url() {
    # GET form: v3 accepts dataId/groupName as query parameters.
    # Nacos 3.x renamed the parameter group -> groupName and removed the v1 API.
    printf '%s?dataId=%s&groupName=%s' \
        "$(nacos_config_base)" "${1:-$DATA_ID}" "${2:-$GROUP}"
}

publish_nacos_config() {
    [[ -f "$CONFIG_SEED" ]] || die "缺少种子配置: $CONFIG_SEED"
    local body rc
    # POST form: dataId/groupName go in the body; sending them in both the query
    # string and the body makes Nacos join the values ("a.yml,a.yml") and 400.
    body="$(curl -sS --max-time 10 -w '\n%{http_code}' -X POST "$(nacos_config_base)" \
        --data-urlencode "dataId=${DATA_ID}" \
        --data-urlencode "groupName=${GROUP}" \
        --data-urlencode "type=yaml" \
        --data-urlencode "content@${CONFIG_SEED}" 2>&1)" || true
    rc="$(printf '%s' "$body" | tail -n1)"
    if [[ "$rc" != "200" ]]; then
        err "Nacos 配置发布失败 (HTTP ${rc})"
        err "响应: $(printf '%s' "$body" | sed '$d')"
        err "修复: 确认 Nacos 已就绪且控制台端口为 ${NACOS_CONSOLE_PORT}；若开启了鉴权，请按 README 关闭或提供 token"
        return 1
    fi
    ok "Nacos 配置已发布: ${DATA_ID} (group=${GROUP})"
}

nacos_has_instance() {
    http_body "${NACOS_BASE}/nacos/v3/admin/ns/instance/list?serviceName=demo-service&groupName=${GROUP}&healthyOnly=true" \
        | grep -Eq '"port"[[:space:]]*:[[:space:]]*'"${DEMO_PORT}"
}

# Sets CHAIN_CODE/CHAIN_BODY from one gateway call. Shared by dev-up (smoke)
# and verify (#14) so the URL and the three-field contract exist in one place.
gateway_chain() {
    local out
    out="$(curl -sS --max-time 6 -w '\n%{http_code}' "${GATEWAY_URL}/api/demo/hello" 2>/dev/null || true)"
    CHAIN_CODE="$(printf '%s' "$out" | tail -n1)"
    CHAIN_BODY="$(printf '%s' "$out" | sed '$d')"
}

chain_ok() {
    gateway_chain
    [[ "$CHAIN_CODE" == "200" ]] || return 1
    grep -Eq '"instancePort"[[:space:]]*:[[:space:]]*'"${DEMO_PORT}"'([^0-9]|$)' <<<"$CHAIN_BODY" || return 1
    grep -Eq '"configSource"[[:space:]]*:[[:space:]]*"nacos"' <<<"$CHAIN_BODY" || return 1
}

# Own-process detection. A Nacos JVM that lost its listening ports still holds
# the embedded Derby lock, and the next start then dies with
#   "Another instance of Derby may have already booted the database"
# Two independent sources on purpose: `ps` can be denied by a sandbox while
# `lsof` (already required by the port checks) still works.
#   1) `ps` command lines naming the Nacos home or one of our module jars;
#   2) `lsof` holders of the Derby lock file / data dir (alive but mute JVM).
own_jvm_pids() {
    {
        local ps_out="" pid cmd f
        ps_out="$(ps -axo pid=,command= 2>/dev/null || true)"
        if [[ -n "$ps_out" ]]; then
            while read -r pid cmd; do
                [[ -n "${pid:-}" ]] || continue
                case "$cmd" in
                    *"$NACOS_HOME/"*|*"$ROOT/api-gateway/target/"*|*"$ROOT/demo-service/target/"*)
                        printf '%s\n' "$pid" ;;
                esac
            done <<< "$ps_out"
        fi
        for f in "$NACOS_HOME/data/derby-data/db.lck" "$NACOS_HOME/data/derby-data"; do
            [[ -e "$f" ]] || continue
            lsof -nP -t -- "$f" 2>/dev/null || true
        done
    } | sort -u | tr '\n' ' ' | sed 's/ *$//'
}

kill_own_jvms() {
    local label="$1" pids p i
    pids="$(own_jvm_pids)"
    [[ -n "${pids// /}" ]] || return 0
    warn "发现本项目残留 JVM（${label}）: ${pids}"
    for p in $pids; do kill "$p" 2>/dev/null || true; done
    for i in $(seq 1 10); do
        pids="$(own_jvm_pids)"
        [[ -z "${pids// /}" ]] && break
        sleep 1
    done
    if [[ -n "${pids// /}" ]]; then
        warn "SIGTERM 未生效，发送 SIGKILL: ${pids}"
        for p in $pids; do kill -9 "$p" 2>/dev/null || true; done
        sleep 1
    fi
    ok "残留 JVM 已清理（${label}）"
}

maven_build() {
    need_cmd mvn
    detect_maven_repo
    log "构建两个模块 (mvn -q -DskipTests package)"
    ( cd "$ROOT" && mvn -q ${MAVEN_REPO_ARG:-} -DskipTests package )
    ok "构建完成"
}

# Some CI/sandbox environments deny writes to ~/.m2. Fall back to an in-workspace
# repository (gitignored) instead of failing with "Operation not permitted".
detect_maven_repo() {
    MAVEN_REPO_ARG=""
    mkdir -p "$HOME/.m2" 2>/dev/null || true
    if ! ( : > "$HOME/.m2/.dsh-write-probe" ) 2>/dev/null; then
        MAVEN_REPO_ARG="-Dmaven.repo.local=$ROOT/.m2/repository"
        mkdir -p "$ROOT/.m2/repository"
        warn "~/.m2 不可写（沙箱限制），本次构建使用 $ROOT/.m2/repository"
    else
        rm -f "$HOME/.m2/.dsh-write-probe"
    fi
}

app_jar() {
    local module="$1" jar
    # `find` exits 1 when the target dir is missing; without `|| true` the
    # pipefail + set -e pair aborted the caller here, so the actionable
    # message below never printed (measured: bare exit 1, no output).
    jar="$(find "$ROOT/$module/target" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -n1 || true)"
    [[ -n "$jar" ]] || die "找不到 $module 的可执行 jar，请先执行 make build"
    printf '%s' "$jar"
}

start_app() {
    local name="$1" port="$2" module="$3"
    if app_alive "$name"; then
        ok "$name 已在运行 (pid $(app_pid "$name"), port ${port})"
        return 0
    fi
    # app_alive() rejected the recorded pid (gone, or recycled by a foreign
    # process): drop it so the pid file cannot point at someone else's JVM.
    rm -f "$RUN_DIR/${name}.pid"
    local jar; jar="$(app_jar "$module")"
    log "启动 ${name} (port ${port})"
    (
        cd "$ROOT"
        NACOS_SERVER_ADDR="$NACOS_ADDR" \
        nohup java -jar "$jar" --server.port="$port" >> "$LOG_DIR/${name}.log" 2>&1 &
        echo $! > "$RUN_DIR/${name}.pid"
    )
    sleep 1
    if ! app_alive "$name"; then
        err "$name 启动即退出，最近日志:"
        tail -n 20 "$LOG_DIR/${name}.log" >&2 || true
        return 1
    fi
    ok "$name 已启动 (pid $(app_pid "$name"))"
}

stop_app() {
    local name="$1" pid i
    pid="$(app_pid "$name")"
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$RUN_DIR/${name}.pid"
        ok "$name 未运行"
        return 0
    fi
    log "停止 ${name} (pid ${pid})"
    kill "$pid" 2>/dev/null || true
    for i in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
        warn "$name 未在 20s 内退出，发送 SIGKILL"
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$RUN_DIR/${name}.pid"
    ok "$name 已停止"
}

port_free_after() {
    local port="$1" i
    for i in $(seq 1 "$2"); do
        port_listening "$port" || return 0
        sleep 1
    done
    return 1
}

# ===================== platform layer (20261108-r2) =====================
# MySQL CLI helpers. The DB name defaults to devops_platform (see §2.4); every
# assertion in platform-verify cross-checks HTTP against a real count, so these
# must fail loudly rather than return an empty string.
db_q() {
    MYSQL_PWD="${PLATFORM_DB_PASSWORD:-}" mysql --protocol=TCP \
        -h "$PLATFORM_DB_HOST" -P "$PLATFORM_DB_PORT" \
        -u "$PLATFORM_DB_USER" -N -B --default-character-set=utf8mb4 \
        "$PLATFORM_DB_NAME" -e "$1" 2>/dev/null
}

db_admin_q() {
    MYSQL_PWD="${PLATFORM_DB_PASSWORD:-}" mysql --protocol=TCP \
        -h "$PLATFORM_DB_HOST" -P "$PLATFORM_DB_PORT" \
        -u "$PLATFORM_DB_USER" -N -B --default-character-set=utf8mb4 \
        -e "$1" 2>/dev/null
}

db_up() {
    [[ "$(db_admin_q 'SELECT 1' 2>/dev/null)" == "1" ]]
}

db_exists() {
    [[ "$(db_admin_q "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${PLATFORM_DB_NAME}'")" == "1" ]]
}

db_count() {
    local table="$1" where="${2:-}"
    local sql="SELECT COUNT(*) FROM ${table}"
    [[ -n "$where" ]] && sql="${sql} WHERE ${where}"
    db_q "$sql"
}

# HTTP helpers: every call records both code and body so a check can assert the
# status AND the payload (a 200 with the wrong body is still a failure).
http_call() {
    local method="$1" url="$2" body="${3:-}"; shift 3 || true
    local args=(-sS --max-time 25 -X "$method" "$url")
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
    args+=("$@")
    local out
    out="$(curl "${args[@]}" -w '\n%{http_code}' 2>&1)" || true
    HTTP_BODY="$(printf '%s' "$out" | sed '$d')"
    HTTP_CODE="$(printf '%s' "$out" | tail -n1)"
}

# api_call: through the gateway with the current bearer token + tenant header.
api_call() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-H 'Accept: application/json')
    [[ -n "${API_TOKEN:-}" ]] && args+=(-H "Authorization: Bearer ${API_TOKEN}")
    [[ -n "${API_TENANT:-}" ]] && args+=(-H "X-Tenant-Id: ${API_TENANT}")
    http_call "$method" "${GATEWAY_URL}${path}" "$body" "${args[@]}"
}

# api_direct: straight to a service (internal endpoints bypass the gateway).
api_direct() {
    local method="$1" base="$2" path="$3" body="${4:-}"; shift 4 || true
    http_call "$method" "${base}${path}" "$body" "$@"
}

jget() {
    printf '%s' "$1" | jq -r "$2" 2>/dev/null || true
}

jlen() {
    printf '%s' "$1" | jq -r "$2 | length" 2>/dev/null || true
}

json_ok() {
    printf '%s' "$1" | jq -e . >/dev/null 2>&1
}

# Poll a URL until its HTTP status is one of `want` (space-separated).
wait_http_code() {
    local url="$1" want="$2" timeout="$3" code=""
    local i=0
    while (( i < timeout )); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 "$url" 2>/dev/null || true)"
        [[ " $want " == *" $code "* ]] && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

wait_http_json() {
    local url="$1" timeout="$2" body=""
    local i=0
    while (( i < timeout )); do
        body="$(curl -fsS --max-time 4 "$url" 2>/dev/null || true)"
        if json_ok "$body"; then
            printf '%s' "$body"
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}

web_up() {
    curl -fsS --max-time 4 "${WEB_URL}/" 2>/dev/null | grep -qi '<!doctype html>'
}

# The run's scratch dir (logs/teamflow/<runId>/). Checkers keep conclusions and
# non-derivable payloads there; it is archived by the host at run end.
run_log_dir() {
    local d
    d="$(ls -dt "$ROOT"/logs/teamflow/*/ 2>/dev/null | head -n 1 || true)"
    if [[ -n "$d" ]]; then
        printf '%s' "${d%/}"
    else
        printf '%s' "$LOG_DIR"
    fi
}

# Login through the gateway and keep TOKEN/TENANT (the two headers every
# protected call needs). Fails loudly: a silent empty token would make the next
# ten assertions fail for the wrong reason.
platform_login() {
    local tenant="$1" user="$2" pass="$3"
    api_call POST /api/req/auth/login \
        "{\"tenantCode\":\"${tenant}\",\"username\":\"${user}\",\"password\":\"${pass}\"}"
    [[ "$HTTP_CODE" == "200" ]] || {
        err "登录失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    }
    API_TOKEN="$(jget "$HTTP_BODY" '.token')"
    # X-Tenant-Id 是数字 tenantId（TECHNICAL §2.2），不是登录用的 tenant code：
    # 网关的 JwtGatewayFilter 会把它按 Long 解析并与 JWT 的 tenantId 比对，传 code 直接 403。
    local tid; tid="$(jget "$HTTP_BODY" '.tenantId')"
    API_TENANT="${tid:-$tenant}"
    [[ -n "$API_TOKEN" && "$API_TOKEN" != "null" ]] || {
        err "登录响应缺 token: $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    }
    ok "登录成功: ${tenant}/${user}"
}

# Internal calls (health/seed/gates) require X-Internal-Token.
internal_call() {
    local method="$1" base="$2" path="$3" body="${4:-}"
    http_call "$method" "${base}${path}" "$body" \
        -H "X-Internal-Token: ${PLATFORM_INTERNAL_TOKEN}"
}

# Strict readiness for req-service: status=UP + db=up + flyway set.
req_ready() {
    local body
    body="$(curl -fsS --max-time 4 "${REQ_URL}/internal/health" 2>/dev/null || true)"
    json_ok "$body" || return 1
    [[ "$(jget "$body" '.status')" == "UP" ]] || return 1
    [[ "$(jget "$body" '.db')" == "up" ]] || return 1
    [[ -n "$(jget "$body" '.flyway')" && "$(jget "$body" '.flyway')" != "null" ]]
}

deliver_ready() {
    local body
    body="$(curl -fsS --max-time 4 "${DELIVER_URL}/internal/health" 2>/dev/null || true)"
    json_ok "$body" || return 1
    [[ "$(jget "$body" '.status')" == "UP" ]] || return 1
    [[ "$(jget "$body" '.db')" == "up" ]]
}

# vite preview is a Node process, so it has no module target/ marker for
# app_alive(). It gets its own stop path that only kills a process whose open
# files prove it belongs to this workspace's web/ tree.
stop_web() {
    local pid pids i owned
    pid="$(app_pid web)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        log "停止 web (pid ${pid})"
        kill "$pid" 2>/dev/null || true
        for i in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$RUN_DIR/web.pid"
    pids="$(port_pids "$WEB_PORT")"
    if [[ -n "${pids// /}" ]]; then
        for pid in $pids; do
            owned="$(lsof -nP -p "$pid" 2>/dev/null | grep -F "$ROOT/web/" || true)"
            if [[ -n "$owned" ]]; then
                kill "$pid" 2>/dev/null || true
            else
                warn "端口 ${WEB_PORT} 的进程 ${pid} 不属于本工作区，未终止"
            fi
        done
        port_free_after "$WEB_PORT" 5 || warn "端口 ${WEB_PORT} 仍被占用"
    fi
    ok "web 已停止"
}
