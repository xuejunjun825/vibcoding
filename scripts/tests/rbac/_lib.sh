#!/usr/bin/env bash
# _lib.sh — s3~s8 共用的「改授权 → 打探针 → 还原」助手（**不是段脚本**，不被 rbac-verify.sh 直接执行）
#
# 用法：source 本文件之前先 source scripts/lib.sh（拿 api_call/platform_login/HTTP_CODE）。
# 探针固定为「空 body 打真实写端点」：被拦截是 403，放行则落到参数校验 400——
# 「放行」的证据不需要真的建出数据，段脚本可以反复跑而不污染库。
#
# 可覆盖（跨段复用同一探针口径）：RBAC_PROBE_METHOD / RBAC_PROBE_PATH / RBAC_PROBE_BODY
#                                    RBAC_PAGE / RBAC_ACTION

RBAC_PROBE_METHOD="${RBAC_PROBE_METHOD:-POST}"
RBAC_PROBE_PATH="${RBAC_PROBE_PATH:-/api/deliver/test-plans}"
RBAC_PROBE_BODY="${RBAC_PROBE_BODY:-{\}}"
RBAC_PAGE="${RBAC_PAGE:-test.plans}"
RBAC_ACTION="${RBAC_ACTION:-test.action.plan_create}"
RBAC_ADMIN_USER="${RBAC_ADMIN_USER:-admin}"
RBAC_ADMIN_PASS="${RBAC_ADMIN_PASS:-Admin@12345}"

login_as() { # login_as <user> <pass> → 设置 API_TOKEN/API_TENANT
    platform_login t1 "$1" "$2" >/dev/null || return 1
    [[ -n "${API_TOKEN:-}" ]]
}

admin_login() { login_as "$RBAC_ADMIN_USER" "$RBAC_ADMIN_PASS"; }

probe_status() { # 以当前 API_TOKEN 打探针，回显状态码
    api_call "$RBAC_PROBE_METHOD" "$RBAC_PROBE_PATH" "$RBAC_PROBE_BODY" >/dev/null
    printf '%s' "${HTTP_CODE:-000}"
}

probe_settle() { # probe_settle <403|allow> [超时秒=70] → 轮询探针直到进入期望态，回显最终状态码
    # 为什么需要轮询：授权行由 req-service 写入，探针打的是 deliver-service —— 两个 JVM 各有
    # 一份按租户的快照（platform.rbac.cache-ttl-seconds 默认 45s），invalidate(tenantId) 只对
    # 写请求所在的进程 epoch+1。同进程的「下一次请求即生效」由 s4 的 epoch 断言覆盖；跨进程的
    # 传播上界就是 TTL，这里等它，不把「还没传播到」误判成「缓存没有失效」。
    local want="$1" limit="${2:-70}" waited=0 got
    while true; do
        got="$(probe_status)" || true
        if [[ "${want}" == "403" ]]; then
            if [[ "${got}" == "403" ]]; then
                printf '%s' "${got}"
                return 0
            fi
        else
            if [[ "${got}" != "403" && "${got}" != "000" ]]; then
                printf '%s' "${got}"
                return 0
            fi
        fi
        if [[ "${waited}" -ge "${limit}" ]]; then
            # 超时不算「脚本出错」：回显最后状态码并正常返回，由调用方的 if 判 FAIL 并给出原因。
            # 这里若 return 1，调用点 `CODE1="$(probe_settle 403)"` 在 set -e 下会**静默中止整段**
            # 脚本（没有 FAIL 行、没有 report_counts），失败原因反而丢失。
            printf '%s' "${got}"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
}

grants_of() { # grants_of <roleId> → 扁平 [{subjectType,subjectCode,access}]
    api_call GET "/api/req/admin/roles/$1/permissions"
    [[ "${HTTP_CODE:-}" == "200" ]] || return 1
    printf '%s' "$HTTP_BODY" | jq -c '[.modules[]? | .pages[]? as $p
        | ($p.functionCodes[]? | {subjectType:"FUNCTION", subjectCode:.code, access:.access}),
          ($p | {subjectType:"MENU", subjectCode:$p.code, access:$p.access}),
          ($p.actions[]? | {subjectType:"ACTION", subjectCode:.code, access:.access})]'
}

set_cell() { # set_cell <grants> <TYPE> <code> <access>
    printf '%s' "$1" | jq -c --arg t "$2" --arg c "$3" --arg a "$4" \
        'map(if .subjectType == $t and .subjectCode == $c then .access = $a else . end)'
}

get_cell() { # get_cell <grants> <TYPE> <code> → 当前档位（缺行回显 MISSING）
    printf '%s' "$1" | jq -r --arg t "$2" --arg c "$3" \
        '[.[] | select(.subjectType == $t and .subjectCode == $c) | .access] | first // "MISSING"'
}

put_grants() { # put_grants <roleId> <grants-json> [confirmHighRisk] → 回显状态码
    local body
    body="$(jq -n --argjson g "$2" --argjson ok "${3:-true}" '{grants: $g, confirmHighRisk: $ok}')"
    api_call PUT "/api/req/admin/roles/$1/permissions" "$body" >/dev/null
    printf '%s' "${HTTP_CODE:-000}"
}

put_grants_code() { # put_grants_code <roleId> <grants-json> [confirmHighRisk] → 回显 "<状态码>|<业务码>"
    # 状态码与响应体必须从同一次请求取：调用点常写成 X="$(put_grants ...)"，
    # 子 shell 内更新的 HTTP_BODY 不会回传，父 shell 读到的是上一条请求的旧 body。
    local body
    body="$(jq -n --argjson g "$2" --argjson ok "${3:-true}" '{grants: $g, confirmHighRisk: $ok}')"
    api_call PUT "/api/req/admin/roles/$1/permissions" "$body" >/dev/null
    printf '%s|%s' "${HTTP_CODE:-000}" \
        "$(printf '%s' "${HTTP_BODY:-}" | jq -r '.code // empty' 2>/dev/null)"
}

role_id_of() { # role_id_of <code>
    api_call GET /api/req/admin/roles
    printf '%s' "$HTTP_BODY" | jq -r --arg c "$1" '.[]? | select(.code == $c) | .id' | head -n1
}

role_row_of() { # role_row_of <code> → 整行 JSON（用户数/内置/状态）
    api_call GET /api/req/admin/roles
    printf '%s' "$HTTP_BODY" | jq -c --arg c "$1" '.[]? | select(.code == $c)' | head -n1
}

user_id_of() { # user_id_of <username>
    api_call GET "/api/req/admin/users?keyword=$1&size=10"
    printf '%s' "$HTTP_BODY" | jq -r --arg u "$1" '(.items // .records // [])[]? | select(.username == $u) | .id' | head -n1
}

epoch_now() { # epoch_now → /auth/me/permissions 的 epoch（失败回显 0）
    api_call GET /api/req/auth/me/permissions
    printf '%s' "$HTTP_BODY" | jq -r '.epoch // 0' 2>/dev/null | head -n1
}

audit_count() { # audit_count <action 前缀> [biz_type]
    local where="action LIKE '${1}%'"
    [[ -n "${2:-}" ]] && where="${where} AND biz_type = '${2}'"
    db_q "SELECT COUNT(*) FROM audit_log WHERE ${where}" | head -n1
}
