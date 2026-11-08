#!/usr/bin/env bash
# Pre-flight checks. Fails BEFORE any process is started, with an actionable message.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

FAILED=0
fail() { err "$*"; FAILED=$((FAILED + 1)); }

log "环境前置检查"

# ---- 0. Tooling the checks depend on (without it every port probe silently passes) ----
for tool in lsof curl; do
    if command -v "$tool" >/dev/null 2>&1; then
        ok "命令可用: ${tool}"
    else
        fail "缺少命令: ${tool}（端口/健康探测会静默失效，产生假绿灯）修复: macOS 自带；Linux 请 brew install ${tool}"
    fi
done

# ---- 1. JDK ----
if ! command -v java >/dev/null 2>&1; then
    fail "未找到 java。修复: brew install openjdk@25 && export JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null || echo /opt/homebrew/opt/openjdk@25)"
else
    RAW="$(java -version 2>&1 | head -n1)"
    MAJOR="$(printf '%s' "$RAW" | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [[ "$MAJOR" =~ ^[0-9]+$ ]] && (( MAJOR >= 25 )); then
        ok "JDK ${MAJOR} (${RAW})"
    else
        fail "JDK 版本不足: ${RAW}（本工程锁定 JDK 25）"
        cat >&2 <<'EOF'
      修复（二选一）:
      A. 保持本工程版本矩阵（推荐）:
         brew install openjdk@25
         export JAVA_HOME=$(/usr/libexec/java_home -v 25)
         export PATH="$JAVA_HOME/bin:$PATH"
      B. 必须使用 JDK 21/24 时，整套降级（不要只改 JDK）:
         brew install openjdk@21
         export JAVA_HOME=$(/usr/libexec/java_home -v 21)
         export PATH="$JAVA_HOME/bin:$PATH"
         Boot 3.5.16 + Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0 + Nacos 2.5.5
         并同步修改 pom.xml、scripts/nacos.sh 的 NACOS_VERSION、README 版本矩阵与 ARCHITECTURE.md；
         网关 artefact 名在 Cloud 2025.0 下不同，README 命令数放宽到 ≤9 条。
EOF
    fi
fi

# ---- 2. Maven ----
if command -v mvn >/dev/null 2>&1; then
    ok "Maven $(mvn -v 2>/dev/null | awk 'NR==1{print $3}')"
else
    fail "未找到 mvn。修复: brew install maven"
fi

# ---- 3. Ports (must be usable before dev-up) ----
# dev-up must be re-entrant in ANY state (TECHNICAL §6.2 S4: 环境已在运行 -> 再跑
# dev-up 不得失败）。所以“端口被占用”本身不是错误：只有**外部进程**占用才拦冷启动。
# “本项目自己的” = PID 记录在 .run/*.pid（start_app / nacos.sh start 写入）；当 ps 可读时
# 还要求命令行落在本项目路径下，避免 pid 文件过期 + PID 复用把外部进程洗成“自己人”。
own_pids() {
    local f p
    for f in "$RUN_DIR"/*.pid; do
        [[ -f "$f" ]] || continue
        p="$(tr -d '[:space:]' < "$f")"
        if [[ "$p" =~ ^[0-9]+$ ]]; then printf '%s\n' "$p"; fi
    done
    return 0
}

own_pid_set=" $(own_pids | sort -u | tr '\n' ' ')"
owns_pid() {
    local pid="$1" cmd
    [[ "$own_pid_set" == *" ${pid} "* ]] || return 1
    cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [[ -z "$cmd" ]] && return 0   # ps 被沙箱拒绝时退回 pid 文件这一份证据
    case "$cmd" in
        *"$ROOT/api-gateway/target/"*|*"$ROOT/demo-service/target/"*|*"$NACOS_HOME/"*) return 0 ;;
    esac
    return 1
}

for spec in "${GATEWAY_PORT}:api-gateway" "${DEMO_PORT}:demo-service" \
            "${NACOS_HTTP_PORT}:nacos-http" "${NACOS_CONSOLE_PORT}:nacos-console" \
            "${NACOS_GRPC_PORT}:nacos-grpc" "${NACOS_GRPC_TLS_PORT}:nacos-grpc-tls"; do
    port="${spec%%:*}"; name="${spec#*:}"
    pids="$(port_pids "$port")"
    if [[ -z "$pids" ]]; then
        ok "端口 ${port} (${name}) 空闲"
        continue
    fi
    external=""
    for p in $pids; do
        owns_pid "$p" || external+=" ${p}"
    done
    if [[ -n "$external" ]]; then
        fail "端口 ${port} (${name}) 被外部进程占用，PID:${external}"
        err "  修复: 先执行 make dev-down；仍占用则 kill${external}，或改用 GATEWAY_PORT/DEMO_PORT/NACOS_HTTP_PORT 覆盖"
    else
        ok "端口 ${port} (${name}) 已由本项目持有 (PID ${pids})，dev-up 可重入"
    fi
done

# ---- 4. Disk >= 1.5GB ----
AVAIL_KB="$(df -k "$ROOT" | awk 'NR==2{print $4}')"
if [[ "$AVAIL_KB" =~ ^[0-9]+$ ]] && (( AVAIL_KB >= 1536 * 1024 )); then
    ok "磁盘可用 $(( AVAIL_KB / 1024 / 1024 ))GB (>= 1.5GB)"
else
    fail "磁盘可用空间不足 1.5GB（当前 $(( ${AVAIL_KB:-0} / 1024 / 1024 ))GB）；Nacos 首次下载约 233MB"
fi

# ---- 5. Network (warning only: cached deps / cached Nacos still work offline) ----
if [[ "${PRECHECK_SKIP_NETWORK:-0}" != "1" ]]; then
    if curl -fsSI --max-time 8 https://repo1.maven.org/maven2/ >/dev/null 2>&1; then
        ok "Maven Central 可达"
    else
        warn "Maven Central 不可达；若依赖已在本机 ~/.m2 缓存仍可构建，否则请配置 ~/.m2/settings.xml 镜像"
    fi
fi

# ---- 6. Python (fact only, NOT a deliverable / NOT a gate) ----
if command -v python3 >/dev/null 2>&1; then
    ok "python3 $(python3 --version 2>&1 | awk '{print $2}')（仅事实记录；本需求无 Python 交付物，不参与验收）"
fi

if (( FAILED > 0 )); then
    err "前置检查未通过（${FAILED} 项），未启动任何进程"
    exit 1
fi
ok "前置检查全部通过"
