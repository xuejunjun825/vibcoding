#!/usr/bin/env bash
# scripts-contract.sh — 平台脚本族的"不需要起栈"的静态契约检查。
# 能在任何机器上先跑（T0/T1 未交付时也有意义）：语法、公共库契约、入口齐备、禁用项。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/scripts/lib.sh"

TOTAL=0; PASS=0; FAIL=0; FAILED=""
ok_() { TOTAL=$((TOTAL+1)); PASS=$((PASS+1)); printf '  \033[1;32mPASS\033[0m %s\n' "$1"; }
no_() { TOTAL=$((TOTAL+1)); FAIL=$((FAIL+1)); printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"; FAILED="${FAILED}
  - $1"; }
check() { if eval "$2" >/dev/null 2>&1; then ok_ "$1"; else no_ "$1" "${3:-}"; fi; }

SECTION=0
sec() { SECTION=$((SECTION+1)); printf '\n\033[1;34m-- %s --\033[0m\n' "$1"; }

sec "1) 入口脚本齐备且可执行"
for s in platform-up platform-down platform-verify platform-test-negative platform-seed \
         platform-unit platform-perf platform-web test-all; do
    check "${s}.sh 存在且可执行" "[[ -x \"$ROOT/scripts/${s}.sh\" ]]" "chmod +x scripts/${s}.sh"
done
check "scripts/tests/lint.sh 可执行" "[[ -x \"$ROOT/scripts/tests/lint.sh\" ]]" "chmod +x scripts/tests/lint.sh"

sec "2) 全部脚本 bash -n 通过"
BAD=""
for f in "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh "$ROOT"/scripts/tests/platform/*.sh; do
    [[ -f "$f" ]] || continue
    bash -n "$f" || BAD="${BAD} ${f}"
done
check "bash -n 无语法错误" "[[ -z \"$BAD\" ]]" "逐个修语法：${BAD}"

sec "3) 公共库契约（脚本只许通过 lib.sh 触碰进程/端口/HTTP/DB）"
for fn in db_q db_count http_call api_call api_direct jget internal_call platform_login \
          req_ready deliver_ready web_up run_log_dir stop_web start_app stop_app \
          port_listening wait_until maven_build; do
    check "lib.sh 定义 ${fn}()" "grep -qE \"^${fn}\\(\\)\" \"$ROOT/scripts/lib.sh\""
done
check "平台端口变量与 r1 不冲突" "grep -q 'REQ_PORT=.*8082' \"$ROOT/scripts/lib.sh\" && grep -q 'DELIVER_PORT=.*8083' \"$ROOT/scripts/lib.sh\""
check "bash 3.2 兼容（不用 declare -A / \${x,,} 小写展开）" "! grep -rnE 'declare -A|\\\$\\{[A-Za-z_]+,,?\\}' \"$ROOT/scripts\" --include='*.sh' --exclude='scripts-contract.sh'" "宿主 bash 是 3.2，写到 bash 4 语法会在运行期炸"

sec "4) 每个平台脚本都 source lib.sh（不复制公共逻辑）"
for s in platform-up platform-down platform-verify platform-test-negative platform-seed \
         platform-unit platform-perf platform-web test-all; do
    check "${s}.sh source lib.sh" "grep -q 'source .*lib.sh' \"$ROOT/scripts/${s}.sh\"" "公共逻辑只在 lib.sh"
done

sec "5) 禁用项（Docker / Python 交付物 / 长时间 sleep 掩盖未就绪）"
check "脚本不调用 docker" "! grep -rn 'docker ' \"$ROOT/scripts\" --include='*.sh' --exclude='scripts-contract.sh'" "本产品禁止 Docker"
check "脚本不依赖 python 执行交付动作" "! grep -rn 'python3\\? ' \"$ROOT/scripts\" --include='*.sh' --exclude='scripts-contract.sh' --exclude='env-precheck.sh'" "禁止 Python 交付物（env-precheck 只做事实记录）"
check "脚本没有 sleep >= 10" "! grep -rnE 'sleep ([1-9][0-9]+)' \"$ROOT/scripts\" --include='*.sh'" "用 wait_until/wait_http_ok，不要 sleep 掩盖"

sec "6) Makefile 目标齐备"
for t in dev-up dev-down verify test-negative lint test platform-up platform-down \
         platform-verify platform-test-negative platform-seed platform-unit platform-perf platform-web; do
    check "make ${t}" "grep -qE '^${t}:' \"$ROOT/Makefile\""
done
check "make test 走 test-all.sh（唯一编排入口）" "grep -q 'scripts/test-all.sh' \"$ROOT/Makefile\""

printf '\n'
if (( FAIL > 0 )); then
    err "scripts-contract: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED" >&2
    exit 1
fi
ok "scripts-contract: ${PASS}/${TOTAL} 项通过"
