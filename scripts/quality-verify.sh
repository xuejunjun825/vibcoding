#!/usr/bin/env bash
# quality-verify.sh — M6 质量域集成/负向验收：串跑 q0..q7，汇总 PASS==TOTAL 守卫。
#
# 用法：
#   ./scripts/quality-verify.sh              # 全量 8 个脚本（需 make platform-up + platform-seed）
#   ./scripts/quality-verify.sh q0 q1        # 只跑指定脚本（q0 只需数据库，其余需要服务栈）
#
# 每个 q 脚本自己打印 `#SUMMARY <name> pass=N total=N fail=N` 并以非 0 退出表示失败；
# 这里只做汇总与「少跑一条也算失败」的守卫（TOTAL==0 一律失败）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

QUALITY_DIR="$ROOT/scripts/tests/quality"
ALL_SCRIPTS=(q0-schema q1-plan-lifecycle q2-round-exit q3-report-signoff-export \
             q4-gate-admission q5-waiver-replay q6-escape-metrics q7-strategy-env-review)

SELECTED=()
if (( $# > 0 )); then
    for want in "$@"; do
        match=""
        for s in "${ALL_SCRIPTS[@]}"; do
            if [[ "$s" == "$want" || "$s" == "${want}-"* ]]; then match="$s"; fi
        done
        if [[ -z "$match" ]]; then
            err "未知脚本: ${want}（可选: ${ALL_SCRIPTS[*]}）"
            exit 2
        fi
        SELECTED+=("$match")
    done
else
    SELECTED=("${ALL_SCRIPTS[@]}")
fi

printf '\033[1;34m质量域验收（M6）：%d 个脚本\033[0m\n' "${#SELECTED[@]}"
printf '  数据库 %s:%s/%s · req %s · deliver %s\n' \
    "$PLATFORM_DB_HOST" "$PLATFORM_DB_PORT" "$PLATFORM_DB_NAME" "$REQ_URL" "$DELIVER_URL"

TOTAL=0; PASS=0; FAILED=0
declare -a ROWS=()
FAILED_SCRIPTS=()

for name in "${SELECTED[@]}"; do
    script="$QUALITY_DIR/${name}.sh"
    if [[ ! -f "$script" ]]; then
        err "缺少脚本: ${script}"
        FAILED_SCRIPTS+=("$name(缺失)")
        continue
    fi
    printf '\n\033[1;35m══════════ %s ══════════\033[0m\n' "$name"
    start=$(date +%s)
    out="$(bash "$script" 2>&1)"; rc=$?
    secs=$(( $(date +%s) - start ))
    # 打印失败明细与结论（成功路径只留摘要，避免淹没在 8 个脚本的输出里）
    if (( rc != 0 )); then
        printf '%s\n' "$out"
    else
        printf '%s\n' "$out" | grep -E '^(  (FAIL|SKIP)|#SUMMARY|  INFO)' || true
    fi
    line="$(printf '%s\n' "$out" | grep -E '^#SUMMARY ' | tail -n1)"
    s_total=0; s_pass=0; s_fail=0
    if [[ -n "$line" ]]; then
        s_pass="$(sed -E 's/.*pass=([0-9]+).*/\1/' <<<"$line")"
        s_total="$(sed -E 's/.*total=([0-9]+).*/\1/' <<<"$line")"
        s_fail="$(sed -E 's/.*fail=([0-9]+).*/\1/' <<<"$line")"
    fi
    TOTAL=$(( TOTAL + s_total ))
    PASS=$(( PASS + s_pass ))
    FAILED=$(( FAILED + s_fail ))
    ROWS+=("$(printf '%-28s pass=%-4s total=%-4s fail=%-3s %ss %s' \
        "$name" "$s_pass" "$s_total" "$s_fail" "$secs" "$( (( rc == 0 )) && echo OK || echo FAILED )")")
    if (( rc != 0 )); then FAILED_SCRIPTS+=("$name"); fi
done

printf '\n\033[1;34m---- 汇总 ----\033[0m\n'
for row in "${ROWS[@]}"; do printf '  %s\n' "$row"; done
printf '\n  合计 pass=%d total=%d fail=%d 脚本失败=%d\n' "$PASS" "$TOTAL" "$FAILED" "${#FAILED_SCRIPTS[@]}"
printf '#SUMMARY quality-verify pass=%d total=%d fail=%d\n' "$PASS" "$TOTAL" "$FAILED"

if (( ${#FAILED_SCRIPTS[@]} > 0 )); then
    err "失败脚本: ${FAILED_SCRIPTS[*]}"
fi
if (( FAILED > 0 || TOTAL == 0 || ${#FAILED_SCRIPTS[@]} > 0 )); then
    err "质量域验收未通过：每条 FAIL 都带「修复:」提示；先 make platform-up && make platform-seed"
    exit 1
fi
ok "质量域验收全绿：${PASS}/${TOTAL}"
