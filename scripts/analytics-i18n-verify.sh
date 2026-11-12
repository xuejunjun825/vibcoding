#!/usr/bin/env bash
# analytics-i18n-verify.sh — 分析域中文化门禁（r14 AC-11：make analytics-i18n-verify）
#
# 两段，各自打印断言明细并吐一行 `COUNT <stage> <pass> <total>`：
#   static（R1–R7）三方逐字符串一致：AnalyticsLabels.ROWS ↔ web/src/stores/dict.ts
#                   ↔ sys_dict 种子（RoleCatalog.DICTS + SeedService 内联数组）
#   live  （A1–A9）真实浏览器四页 + 导出：/dashboard /portal /reports /reports/:id，
#                   交叉表 th / 图表 canvas 文字 / 缓存徽标 / portal 口径 / 范围 chips
#                   0 命中英文枚举码，且页面表头与 CSV/XLSX 导出逐列相等。
#
# 聚合器只做编排（与 access-verify 同约定）：求和后做 PASS==TOTAL、两段齐全、
# 分段下限三道守卫；失败一律打印「哪一项 + 怎么修」。
#
# 用法：
#   bash scripts/analytics-i18n-verify.sh               # 静态 + 现场（需 make platform-up）
#   bash scripts/analytics-i18n-verify.sh --static-only # 只跑静态（免栈，改字典后秒级自查）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ANALYTICS_DIR="$ROOT/scripts/tests/analytics"
STATIC_JS="$ANALYTICS_DIR/i18n-labels-consistency.mjs"
LIVE_JS="$ANALYTICS_DIR/i18n-live.mjs"
TENANT_CODE="${I18N_TENANT:-t1}"
ADMIN_USER="${I18N_USER:-admin}"
ADMIN_PASS="${I18N_PASS:-Admin@12345}"

STATIC_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --static-only) STATIC_ONLY=1 ;;
        -h|--help)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "未知参数: ${arg}（可用: --static-only）" ;;
    esac
done

[[ -f "$STATIC_JS" ]] || die "缺少静态门禁脚本: ${STATIC_JS}"
[[ -f "$LIVE_JS" ]] || die "缺少现场门禁脚本: ${LIVE_JS}"

PASS=0
TOTAL=0
FAILED_STAGES=()
MISSING_STAGES=()

# 每段的下限：断言被删掉时总数守卫只看到「少了」，这里直接指出「哪段少了」。
stage_min() {
    case "$1" in
        static) printf 25 ;;
        live) printf 20 ;;
    esac
}

# 跑一段：子进程自己打印明细，这里只从它的 stdout 里摘 COUNT 行（不落盘、不 dump）。
run_stage() {
    local stage="$1"
    shift
    local out rc line p t
    out="$("$@" 2>&1)"
    rc=$?
    printf '%s\n' "$out"
    line="$(printf '%s\n' "$out" | grep -E "^COUNT ${stage} [0-9]+ [0-9]+$" | tail -n1 || true)"
    if [[ -z "$line" ]]; then
        MISSING_STAGES+=("$stage")
        return 1
    fi
    read -r _ _ p t <<<"$line"
    PASS=$((PASS + p))
    TOTAL=$((TOTAL + t))
    printf '  → %s: %s/%s\n' "$stage" "$p" "$t"
    ((rc == 0)) || FAILED_STAGES+=("$stage")
    return 0
}

# ---- 现场段就绪判据：一个带登录态的 dashboard 读不到，就别把「栈没起」误报成「中文没改」 ----
probe_platform() {
    api_call GET /api/deliver/analytics/dashboard >/dev/null 2>&1 || return 1
    [[ "${HTTP_CODE:-}" == "200" ]] || return 1
    curl -fsS -o /dev/null --max-time 5 "${WEB_URL}/" 2>/dev/null || return 1
    return 0
}

printf '\n\033[1;34m===== 分析域中文化门禁（r14）=====\033[0m\n'

log "段 1/2 静态三方一致（R1–R7：注册表 ↔ dict.ts ↔ sys_dict 种子）"
run_stage static node "$STATIC_JS" || true

if ((STATIC_ONLY == 0)); then
    log "段 2/2 现场渲染与导出（A1–A9：/dashboard /portal /reports /reports/:id + CSV/XLSX）"
    if ! platform_login "$TENANT_CODE" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1 || ! probe_platform; then
        warn "平台栈未就绪，尝试 make platform-up（幂等，可能需要几分钟）"
        bash "$ROOT/scripts/platform-up.sh" || {
            err "平台栈起不来：先看 make platform-up 的报错（常见：MySQL 未起、8083 被占）"
            err "修复后重跑 make analytics-i18n-verify；只想查字典一致性可加 --static-only"
            exit 1
        }
        platform_login "$TENANT_CODE" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1 || {
            err "栈起来了但登录失败（${TENANT_CODE}/${ADMIN_USER}）：先 make platform-seed"
            exit 1
        }
        probe_platform || {
            err "栈起来了但 /api/deliver/analytics/dashboard 不是 200：先 make platform-seed，再看 deliver-service 日志"
            exit 1
        }
    fi
    run_stage live node "$LIVE_JS" || true
else
    warn "--static-only：跳过现场段（四页渲染与导出仍需 make analytics-i18n-verify 全量）"
fi

# ---- 汇总守卫 ----
printf '\n'
if (( ${#MISSING_STAGES[@]} > 0 )); then
    err "以下段没有产出计数（脚本中途失败、没跑完）: ${MISSING_STAGES[*]}"
    fi
if (( ${#FAILED_STAGES[@]} > 0 )); then
    err "失败段: ${FAILED_STAGES[*]}"
fi

if ((STATIC_ONLY == 0)); then
    min_total=$(( 25 + 20 ))
    if ((TOTAL < min_total)); then
        err "断言总数 ${TOTAL} < ${min_total}：有段没跑起来，或断言被删（R1–R7/A1–A9 一条都不许减少）"
        exit 1
    fi
fi
if ((PASS != TOTAL)); then
    err "分析域中文化门禁未通过：PASS ${PASS} != TOTAL ${TOTAL}"
    err "看上面每个 FAIL 行：static 段给 code/label 差异，live 段给首列下标与命中文本"
    exit 1
fi
if (( ${#MISSING_STAGES[@]} > 0 || ${#FAILED_STAGES[@]} > 0 )); then
    exit 1
fi

if ((STATIC_ONLY == 1)); then
    ok "分析域中文化（静态）通过：${PASS}/${TOTAL} 项（注册表 ↔ dict.ts ↔ sys_dict）"
else
    ok "分析域中文化门禁通过：${PASS}/${TOTAL} 项（三方一致 + 四页/导出 0 英文枚举码）"
fi
