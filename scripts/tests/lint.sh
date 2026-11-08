#!/usr/bin/env bash
# 静态门禁（回归防线）：把修复轮确认过的缺陷「类」变成可执行断言，`make lint` 调用。
# 每条断言都对应一个真实发生过的缺陷，修复前必红、修复后必绿；不启动任何服务。
#   L1 所有脚本 `bash -n` 语法通过
#   L2 脚本内禁止 `$VAR` 紧跟非 ASCII 字节：中文全角标点会被 set -u 并入变量名，
#      使错误路径提示变成 "unbound variable"（D1 的类，修复前 5 处）
#   L3 公共逻辑单点：own_jvm_pids / kill_own_jvms / chain_ok 只允许在 lib.sh 定义一次（D5）
#   L4 Nacos v3 配置 URL 只允许由 lib.sh:nacos_config_url 生成，禁止各脚本手拼（D5）
#   L5 Nacos token secret 必须可用 NACOS_TOKEN_SECRET 覆盖（D6）
#   L6 错误路径提示实测可用：下载失败必须输出「下载失败: <url>」且退出码为 1（D1）
#   L7 文档与实测基线一致：README / ARCHITECTURE 不得残留过期描述（D2/D3）
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/lib.sh"

TOTAL=0
FAILED=0

run_check() {
    local desc="$1" out
    shift
    TOTAL=$((TOTAL + 1))
    if out="$("$@" 2>&1)"; then
        ok "[$TOTAL] $desc"
    else
        err "[$TOTAL] $desc"
        printf '%s\n' "$out" | sed 's/^/    /'
        FAILED=$((FAILED + 1))
    fi
}

# ---- L1 语法 ----
syntax_ok() {
    local f
    for f in "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh; do
        bash -n "$f" || { echo "语法错误: $f"; return 1; }
    done
}

# ---- L2 裸变量名 + 全角标点（set -u 把全角字节并入变量名） ----
no_bare_var_before_wide_char() {
    local hits
    hits="$(LC_ALL=C grep -nE '\$[A-Za-z_][A-Za-z0-9_]*[^ -~]' \
        "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh || true)"
    if [[ -n "$hits" ]]; then
        echo "以下行在 \${VAR} 后紧跟非 ASCII 字节，请改为 \$ 花括号形式:"
        echo "$hits"
        return 1
    fi
}

# ---- L3 公共函数单点定义 ----
defined_once_in_lib() {
    local name="$1" hits n
    hits="$(grep -lE "^${name}\(\)" "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh 2>/dev/null || true)"
    n="$(printf '%s\n' "$hits" | grep -c . || true)"
    if [[ "$n" -ne 1 || "$hits" != "$ROOT/scripts/lib.sh" ]]; then
        echo "${name}() 应仅在 scripts/lib.sh 定义一次，实际 ${n} 处:"
        printf '%s\n' "$hits"
        return 1
    fi
}

# ---- L4 Nacos v3 配置 URL 单点生成 ----
config_url_single_source() {
    local hits
    # lint.sh 自身持有该字符串作为断言字面量，不算手拼 URL 的调用方。
    hits="$(grep -l '/nacos/v3/admin/cs/config' "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh 2>/dev/null \
        | grep -v "$ROOT/scripts/tests/lint.sh" || true)"
    if [[ "$hits" != "$ROOT/scripts/lib.sh" ]]; then
        echo "配置 URL 只允许 lib.sh:nacos_config_url 生成，实际出现于:"
        printf '%s\n' "$hits"
        return 1
    fi
}

# ---- L5 token secret 可覆盖 ----
token_secret_overridable() {
    grep -q 'NACOS_TOKEN_SECRET' "$ROOT/scripts/nacos.sh" \
        || { echo "scripts/nacos.sh 未提供 NACOS_TOKEN_SECRET 覆盖"; return 1; }
}

# ---- L6 错误提示实测（真实失败路径，不再是 unbound variable） ----
error_hint_works() {
    local tmp out rc
    tmp="$(mktemp -d)"
    if out="$(NACOS_HOME="$tmp" NACOS_DOWNLOAD_URL="http://127.0.0.1:9/nacos.tar.gz" \
        bash "$ROOT/scripts/nacos.sh" ensure 2>&1)"; then
        rc=0
    else
        rc=$?
    fi
    rm -rf "$tmp"
    if grep -q 'unbound variable' <<<"$out"; then
        echo "错误路径仍报 unbound variable:"; echo "$out"; return 1
    fi
    if (( rc != 1 )); then
        echo "期望退出码 1，实际 ${rc}"; return 1
    fi
    grep -q '下载失败:' <<<"$out" || { echo "缺少「下载失败:」提示:"; echo "$out"; return 1; }
    grep -q 'http://127.0.0.1:9/nacos.tar.gz' <<<"$out" \
        || { echo "提示未包含失败的 URL:"; echo "$out"; return 1; }
}

# ---- L7 文档与实测基线一致 ----
docs_match_measured_baseline() {
    local hits
    hits="$(grep -nE '约 25 秒|约 25s|logs/start\.out|不是幂等|约 20s|约 50 秒|返回的是 500' \
        "$ROOT/README.md" || true)"
    if [[ -n "$hits" ]]; then
        echo "README.md 残留过期描述:"; echo "$hits"; return 1
    fi
    hits="$(grep -nE 'start\.out|不是幂等|25–26s|返回 500' \
        "$ROOT/docs/teamflow/architecture/ARCHITECTURE.md" || true)"
    if [[ -n "$hits" ]]; then
        echo "ARCHITECTURE.md 残留过期描述:"; echo "$hits"; return 1
    fi
}

log "静态门禁：脚本语法 / 变量插值 / 单点实现 / 错误提示 / 文档一致性"
run_check "L1 所有脚本 bash -n 语法通过" syntax_ok
run_check "L2 无 \$VAR 紧跟全角标点（防 set -u 吞变量名）" no_bare_var_before_wide_char
run_check "L3 own_jvm_pids 仅在 lib.sh 定义" defined_once_in_lib own_jvm_pids
run_check "L3 kill_own_jvms 仅在 lib.sh 定义" defined_once_in_lib kill_own_jvms
run_check "L3 chain_ok 仅在 lib.sh 定义" defined_once_in_lib chain_ok
run_check "L4 Nacos v3 配置 URL 仅由 lib.sh 生成" config_url_single_source
run_check "L5 Nacos token secret 可用 NACOS_TOKEN_SECRET 覆盖" token_secret_overridable
run_check "L6 下载失败提示可读（下载失败: + URL，退出码 1）" error_hint_works
run_check "L7 README/ARCHITECTURE 与实测基线一致" docs_match_measured_baseline

if (( FAILED > 0 )); then
    err "lint 失败 ${FAILED}/${TOTAL} 项：按上面的行号修正后重跑 make lint"
    exit 1
fi
ok "lint 全部通过（${TOTAL} 项）"
