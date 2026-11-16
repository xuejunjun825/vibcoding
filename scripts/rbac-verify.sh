#!/usr/bin/env bash
# rbac-verify.sh — r18 三层授权（FUNCTION / MENU / ACTION）门禁聚合器
#
# 分段：
#   s0 迁移与计数        s1 注册表三向比对     s2 种子逐格等价     s3 三序判定
#   s4 缓存与 epoch      s5 角色 CRUD/防锁死   s6 人员与组织       s7 负向 9 例
#   s8 审计与 reset      s9 动作声明覆盖率     s2r 收尾：套件结束后库仍=预设（复用 s2）
#
# 每段自报 `sN <pass> <total>` 到 ${RBAC_COUNTS}；本脚本负责
#   1) 每段下限（少于下限说明断言被删，门禁在缩水）
#   2) 总数 >= 60（AC-21）
#   3) PASS == TOTAL（全绿才算过）
# s2r 是门禁级兜底：s3/s4/s7 都靠 trap 还原授权，任何一段漏还原（典型是备份口径少 30 行）
# 都会让 s2r 红，而不是留给下一次运行去发现。它复用 s2 的脚本与阈值，只增断言不改判据。
# 单个分段可单独跑：`bash scripts/rbac-verify.sh s3`（此时不校验总数下限，只给提示）；
# `s2r` 也可单独跑：`bash scripts/rbac-verify.sh s2r`。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

RBAC_DIR="$ROOT/scripts/tests/rbac"
RBAC_COUNTS="$(mktemp -t rbac-counts-XXXXXX)"
export RBAC_COUNTS

SEGMENTS=(s0 s1 s2 s3 s4 s5 s6 s7 s8 s9 s2r)
# 每段下限 = TECHNICAL §10.2 的断言数；删断言必须先改这里并说明理由，否则门禁静默缩水。
segment_min() {
    case "$1" in
        s0) echo 8 ;;   # 4 表列/行数/36/72/1848/660-396-792
        s1) echo 8 ;;   # DB=MenuRegistry=nav.ts=ActionRegistry + 命名 + 归属
        s2) echo 8 ;;   # 1848 行 + 推导等价 + 规范化哈希
        s3) echo 6 ;;   # 三序、并集、RESTRICTED、未注册码
        s4) echo 5 ;;   # 撤销即时、恢复、epoch 单调、TTL<=60
        s5) echo 8 ;;   # CRUD/不可改码/内置不可删停/停用失权/防锁死
        s6) echo 6 ;;   # 角色差集/域差集/非法角色 400/组织 409/迁移审计
        s7) echo 9 ;;   # AC-18 九例
        s8) echo 6 ;;   # 四类审计 + 无差异不写 + reset 幂等
        s9) echo 5 ;;   # 未保护写端点=0/白名单/只读/默认 fail-fast
        s2r) echo 8 ;;  # 与 s2 同一套 1848/168/660/396/792/hash：套件结束库=预设
        *)  echo 0 ;;
    esac
}
segment_file() {
    case "$1" in
        s0) echo "s0-schema-migration.sh" ;;
        s1) echo "s1-registry-three-way.mjs" ;;
        s2) echo "s2-seed-equivalence.sh" ;;
        s3) echo "s3-decision-order.sh" ;;
        s4) echo "s4-cache-epoch.sh" ;;
        s5) echo "s5-role-lockout.sh" ;;
        s6) echo "s6-user-org.sh" ;;
        s7) echo "s7-negative-nine.sh" ;;
        s8) echo "s8-audit-reset.sh" ;;
        s9) echo "s9-declaration-coverage.mjs" ;;
        s2r) echo "s2-seed-equivalence.sh" ;;
        *)  echo "" ;;
    esac
}

SELECTED=("${SEGMENTS[@]}")
if (( $# > 0 )) && [[ "$1" != "all" ]]; then
    SELECTED=()
    for arg in "$@"; do
        case "$arg" in
            s[0-9]|s[0-9]r) SELECTED+=("$arg") ;;
            *) die "未知分段: ${arg}（可用: ${SEGMENTS[*]} / all）" ;;
        esac
    done
fi
ALL_SELECTED=0
(( ${#SELECTED[@]} == ${#SEGMENTS[@]} )) && ALL_SELECTED=1

log "rbac-verify：分段 ${SELECTED[*]}（每段下限见 TECHNICAL §10.2）"

# ---------------------------------------------------------------- 预检
# s9 是纯静态段（读源码，不需要服务在跑），所以单独跑它时不必先 make platform-up；
# 其余分段会打真库/真接口，缺环境就别假装跑过——直接说清楚要起什么。
NEED_STACK=0
for seg in "${SELECTED[@]}"; do
    [[ "$seg" == "s9" ]] || NEED_STACK=1
done
command -v jq >/dev/null 2>&1 || die "缺少 jq（断言解析 JSON 用）"
if (( NEED_STACK )); then
    db_up || die "MySQL 不可达（${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT}/${PLATFORM_DB_NAME}）；先 make platform-up"
    req_ready || die "req-service 未就绪（${REQ_URL}/internal/health）；先 make platform-up"
fi

# ---------------------------------------------------------------- 执行
RUN_FAILED=0
for seg in "${SELECTED[@]}"; do
    file="$RBAC_DIR/$(segment_file "$seg")"
    if [[ ! -f "$file" ]]; then
        err "缺少分段脚本: scripts/tests/rbac/$(segment_file "$seg")"
        RUN_FAILED=1
        continue
    fi
    printf '\n\033[1;36m==== %s · %s ====\033[0m\n' "$seg" "$(segment_file "$seg")"
    # RBAC_SEGMENT 让复用同一脚本的段（s2 / s2r）在自己的 report_counts 里报对段名：
    # 计数按段名归集，报错段名会让 s2r 的计数覆盖掉 s2 的计数，总数静默少一整段。
    if [[ "$file" == *.mjs ]]; then
        RBAC_SEGMENT="$seg" node "$file" || RUN_FAILED=1
    else
        RBAC_SEGMENT="$seg" bash "$file" || RUN_FAILED=1
    fi
done

# ---------------------------------------------------------------- 汇总
sum_for() { # sum_for <col> <seg> → 该段上报的 pass/total；未上报给空
    awk -v s="$1" -v c="$2" '$1==s {print $c}' "$RBAC_COUNTS" | tail -n1
}

TOTAL=0
PASS=0
MISSING=""
BELOW=""
for seg in "${SELECTED[@]}"; do
    p="$(sum_for "$seg" 2)"
    t="$(sum_for "$seg" 3)"
    if [[ -z "${p}" || -z "${t}" ]]; then
        MISSING="${MISSING} ${seg}"
        continue
    fi
    TOTAL=$((TOTAL + t))
    PASS=$((PASS + p))
    min="$(segment_min "$seg")"
    if (( t < min )); then
        BELOW="${BELOW}
  - ${seg}: ${t} 条 < 下限 ${min} 条"
    fi
    printf '  %-3s %3d/%-3d（下限 %s）\n' "$seg" "$p" "$t" "$min"
done

if [[ -n "$MISSING" ]]; then
    err "以下分段没有上报计数（脚本未跑完或 report_counts 缺失）:${MISSING}"
    RUN_FAILED=1
fi
if [[ -n "$BELOW" ]]; then
    err "分段断言数低于下限（门禁被削弱，不是通过）:${BELOW}"
    RUN_FAILED=1
fi
if (( ALL_SELECTED )) && (( TOTAL < 60 )); then
    err "断言总数 ${TOTAL} < 60（AC-21 下限）"
    RUN_FAILED=1
fi
if (( PASS != TOTAL )); then
    err "rbac-verify 未全绿：PASS=${PASS} / TOTAL=${TOTAL}"
    RUN_FAILED=1
fi
if (( RUN_FAILED != 0 )); then
    err "rbac-verify 失败：先按上面每段的 FAIL 行修正（修复提示已在段内给出）"
    exit 1
fi

ok "rbac-verify: ${PASS}/${TOTAL} 全部通过（${#SELECTED[@]} 段，下限已核对）"
