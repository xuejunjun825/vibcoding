#!/usr/bin/env bash
# _lib.sh — M8 协作/代码/测试治理验收脚本（s0–s8）的共享基座。Source it, do not execute it.
#
# 复用两套既有基座，不另起一套语义：
#   * 断言语义（pass/fail/expect/expect_jq/expect_db/deny/guard）来自 quality/_lib.sh；
#   * 桩客户端与 integration_config 夹具（i_config / i_cleanup / i_rd …）来自 integration/_lib.sh。
# 本文件只补协作域自己的东西：
#   * 甘特读/改期、分支策略、MR 评论、代码质量、价值流、Wiki、用例评审、执行器的调用捷径；
#   * 「页面渲染零外呼」的断言原语 c_zero_outbound（前后计数相等才算通过）。
#
# 用法：source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"，末尾调用 guard <script-name>。
# 运行前提：make platform-up && make platform-seed；桩由 collab-verify.sh 在 127.0.0.1:19091 拉起。

# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../integration" && pwd)/_lib.sh"

# 桩端口必须与 collab-verify.sh 一致；integration/_lib.sh 在 source 时按 STUB_PORT 算出 STUB_URL。
export STUB_PORT="${STUB_PORT:-19091}"
STUB_URL="${STUB_URL:-http://127.0.0.1:${STUB_PORT}}"

C_REQ="/api/req"
C_DELIVER="/api/deliver"

# ------------------------------------------------------------------ 桩控制面

c_stub_reset() { i_stub POST /__stub/reset '{}' >/dev/null 2>&1 || true; }
c_stub_count() { i_stub_count "${1:-all}"; }

c_stub_mode() { # c_stub_mode <logical-key> <ok|500|429|timeout>
    i_stub POST /__stub/mode "$(jq -cn --arg k "$1" --arg v "$2" '{key:$k,value:$v}')" >/dev/null 2>&1 || true
}

c_stub_gl_project() { # c_stub_gl_project <jq-json> — 改 GitLab 项目设置（造 DRIFT）
    i_stub POST /__stub/gitlab/project "$1" >/dev/null 2>&1 || true
}

c_stub_gl_protected() { # c_stub_gl_protected <jq-json> — 改保护分支（branches:[{name,push_roles}]）
    i_stub POST /__stub/gitlab/protected "$1" >/dev/null 2>&1 || true
}

c_stub_gl_branches() { # c_stub_gl_branches <jq-json> — 改分支列表（names:[..]）
    i_stub POST /__stub/gitlab/branches "$1" >/dev/null 2>&1 || true
}

c_stub_gl_mr() { # c_stub_gl_mr <project> <iid> <patch-json> [notes-json]
    local project="$1" iid="$2" patch="${3:-{\}}" notes="${4:-}"
    local body
    if [[ -n "$notes" ]]; then
        body="$(jq -cn --arg p "$project" --argjson i "$iid" --argjson patch "$patch" --argjson notes "$notes" \
            '{project:$p,iid:$i,patch:$patch,notes:$notes}')"
    else
        body="$(jq -cn --arg p "$project" --argjson i "$iid" --argjson patch "$patch" \
            '{project:$p,iid:$i,patch:$patch}')"
    fi
    i_stub POST /__stub/gitlab/mr "$body" >/dev/null 2>&1 || true
}

# c_zero_outbound <desc> <counter-key> <before> — 只读请求不得外呼（AC-11/AC-13 的可断言形式）
c_zero_outbound() {
    local desc="$1" key="$2" before="$3" after
    after="$(c_stub_count "$key")"
    if [[ "${after:-x}" == "${before:-y}" ]]; then
        pass "${desc}（${key} 计数保持 ${after}）"
    else
        fail "${desc}（${key} ${before} → ${after}：只读页面在渲染时外呼了外部系统）" \
            "只读接口必须读本地快照；外呼只允许发生在「刷新 / 核验」按钮"
    fi
}

# ------------------------------------------------------------------ 甘特

c_gantt() { # c_gantt <query-string>
    api_call GET "${C_REQ}/gantt/rows?${1}"
}

c_gantt_patch() { # c_gantt_patch <TYPE> <id> <json-body>
    api_call PATCH "${C_REQ}/gantt/rows/$1/$2" "$3"
}

c_gantt_version() { # c_gantt_version <TYPE> <id> <query> → 行上的乐观锁版本（没有该行则为空）
    c_gantt "$3"
    jget "$HTTP_BODY" "[.rows[] | select(.type==\"$1\" and .id==$2)][0].version // empty"
}

# ------------------------------------------------------------------ 夹具

c_release() { # c_release <name> <startDate> <endDate> → releaseId（窗口可控，逾期/临期用例需要）
    local name="$1" start="$2" end="$3"
    api_call POST "${C_REQ}/releases" "$(jq -cn --arg n "$name" --arg s "$start" --arg e "$end" \
        --argjson d "$(q_domain)" \
        '{name:$n,domainId:$d,startDate:$s,endDate:$e,plannedReleaseDate:$e,gateMode:"STRICT"}')"
    local id; id="$(jget "$HTTP_BODY" '.id')"
    if [[ -z "$id" || "$id" == "null" ]]; then
        err "创建版本失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    fi
    printf '%s' "$id"
}

c_requirement() { # c_requirement <title> <releaseId> [ownerId] → requirementId（RD，挂在版本下）
    local title="$1" release="$2" owner="${3:-}" pdr
    pdr="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND type='PDR' ORDER BY id LIMIT 1")"
    [[ -n "$pdr" ]] || pdr="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
    api_call POST "${C_REQ}/requirements" "$(jq -cn --arg t "$title" --argjson p "${pdr:-0}" \
        --argjson d "$(q_domain)" --argjson r "$release" --arg owner "$owner" \
        '{type:"RD",title:$t,parentId:$p,domainIds:[$d],priority:"P2",
          expectedDeliveryDate:"2026-12-31",description:"M8 协作域验收夹具",releaseId:$r}
         + (if $owner == "" then {} else {ownerUserId:($owner|tonumber)} end)')"
    local id; id="$(jget "$HTTP_BODY" '.id')"
    if [[ -z "$id" || "$id" == "null" ]]; then
        err "创建需求失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    fi
    # releaseId 不在创建体里生效：需求与版本的关联走独立端点（V1 既有契约）。
    api_call POST "${C_REQ}/releases/${release}/requirements" "{\"requirementIds\":[${id}]}" >/dev/null 2>&1 || true
    printf '%s' "$id"
}

c_audit_count() { # c_audit_count <bizType> <bizId> <action>
    db_q "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${TENANT_ID}
            AND biz_type='$1' AND biz_id=$2 AND action='$3'"
}
