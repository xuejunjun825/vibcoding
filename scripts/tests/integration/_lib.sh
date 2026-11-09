#!/usr/bin/env bash
# _lib.sh — M7 集成域验收脚本（s0–s7）的共享基座。Source it, do not execute it.
#
# 与 scripts/tests/quality/_lib.sh 同一套断言语义（pass/fail/expect/expect_jq/guard），
# 这里只补集成域特有的东西：
#   * 本地四合一桩的客户端（模式注入 / 计数读取 / 构建与 MR 编排）
#   * 四系统配置的创建/启用/关闭（凭据语义集中在这里，脚本不各写一份）
#   * Jenkins HMAC / GitLab Secret Token 的 webhook 调用
#   * RD 需求夹具（M7 的构建/MR/Sonar 都挂在 RD 上）
#
# 用法：source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"，末尾调用 guard <script-name>。
# 运行前提：make platform-up && make platform-seed（栈已就绪），桩由 integration-verify.sh 拉起。

# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../quality" && pwd)/_lib.sh"

STUB_URL="${STUB_URL:-http://127.0.0.1:${STUB_PORT:-19090}}"
INTEG_CREDENTIAL="${INTEG_CREDENTIAL:-S3cr3t-IT-2026}"
INTEG_SECRET="${INTEG_SECRET:-stub-webhook-secret}"
INTEG_CONFIG_READY=""

# ------------------------------------------------------------------ 通用补强

# db_exec：写语句走同一套连接参数（db_q 只约定为读，但 mysql -e 对 DML 一样适用）。
# 夹具需要"直接把某行置成某状态"时用它，失败可见（不像 db_q 那样吞 stderr）。
db_exec() {
    MYSQL_PWD="${PLATFORM_DB_PASSWORD:-}" mysql --protocol=TCP \
        -h "${PLATFORM_DB_HOST:-127.0.0.1}" -P "${PLATFORM_DB_PORT:-3306}" \
        -u "${PLATFORM_DB_USER:-root}" --default-character-set=utf8mb4 \
        "${PLATFORM_DB_NAME:-devops_platform}" -e "$1"
}

# expect_db_text：expect_db 走 expect_num，只能比数字；状态列是字符串，必须单独一条。
expect_db_text() { # expect_db_text <desc> <sql> <expected> [fix]
    local desc="$1" sql="$2" want="$3" fix="${4:-$FIXTURE_HINT}"
    local got; got="$(db_q "$sql" | head -n1)"
    if [[ "$got" == "$want" ]]; then
        pass "$desc"
    else
        fail "$desc (期望 '${want}'，实际 '${got:-<空>}')" "$fix"
    fi
}

# ------------------------------------------------------------------ 桩控制面

i_stub() { # i_stub <METHOD> <path> [body] → HTTP_CODE/HTTP_BODY（直连桩，无鉴权）
    local method="$1" path="$2" body="${3:-}"
    http_call "$method" "${STUB_URL}${path}" "$body"
}

i_stub_mode() { # i_stub_mode <logical-key|all> <ok|500|429|timeout|slow>
    i_stub POST /__stub/mode "$(jq -cn --arg k "$1" --arg v "$2" '{key:$k,value:$v}')"
}

i_stub_reset() { i_stub POST /__stub/reset '{}'; }

i_stub_count() { # i_stub_count <logical-key> → 计数（失败也计数，用来断言"重试了几次"）
    i_stub GET "/__stub/counter?key=$1"
    jget "$HTTP_BODY" '.count'
}

i_stub_ready() {
    curl -fsS --max-time 3 "${STUB_URL}/__stub/health" 2>/dev/null | grep -q '"status":"UP"'
}

i_stub_build() { # i_stub_build <job> <number> <QUEUED|RUNNING|SUCCESS|FAILURE|ABORTED>
    i_stub POST /__stub/jenkins/build "$(jq -cn --arg j "$1" --argjson n "$2" --arg s "$3" '{job:$j,number:$n,status:$s}')"
}

i_stub_sonar() { # i_stub_sonar <OK|ERROR> [missing-csv] [metrics-json]
    local metrics="${3:-}"
    [[ -n "$metrics" ]] || metrics='{}'
    i_stub POST /__stub/sonar "$(jq -cn --arg s "$1" --arg m "${2:-}" --argjson x "$metrics" \
        '{status:$s,missing:($m|split(",")|map(select(length>0))),metrics:$x}')"
}

i_stub_checksum() { # i_stub_checksum <repo> <sha256>
    i_stub POST /__stub/nexus/checksums "$(jq -cn --arg r "$1" --arg s "$2" '{repo:$r,sha256:$s}')"
}

i_stub_mr() { # i_stub_mr <project> <iid> <opened|merged|closed> [updated_at]
    i_stub POST /__stub/gitlab/mr "$(jq -cn --arg p "$1" --argjson i "$2" --arg s "$3" --arg u "${4:-}" \
        '{project:$p,iid:$i,state:$s,updated_at:$u}')"
}

# ------------------------------------------------------------------ 配置

i_cfg_name() { printf 'stub-%s' "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"; }

i_list_cfgs() { # i_list_cfgs <system> → 规范化 JSON 数组
    api_call GET "/api/req/integration/configs?system=$1"
    printf '%s' "$HTTP_BODY" | jq -c 'if type=="array" then . else (.items // .configs // .list // []) end' 2>/dev/null || printf '[]'
}

i_cfg_id() { # i_cfg_id <system> → 名为 stub-<system> 的配置 id（无则空）
    i_list_cfgs "$1" | jq -r --arg n "$(i_cfg_name "$1")" 'map(select(.name==$n)) | .[0].id // empty' 2>/dev/null
}

i_cfg_extra() { # 每系统的具名 extra（设计禁止一个自由 JSON 框）
    case "$1" in
        JENKINS) printf '{"jobName":"demo-job"}' ;;
        GITLAB)  printf '{"projectPath":"demo-group/demo-project"}' ;;
        SONAR)   printf '{"projectKey":"demo-key"}' ;;
        NEXUS)   printf '{"repository":"releases"}' ;;
        *)       printf '{}' ;;
    esac
}

i_cfg_body() { # i_cfg_body <system> <name> [enabled] [credential]
    local system="$1" name="$2" enabled="${3:-true}" credential="${4:-$INTEG_CREDENTIAL}"
    local extra; extra="$(i_cfg_extra "$system")"
    jq -cn --arg s "$system" --arg n "$name" --argjson e "$enabled" --arg c "$credential" \
        --arg b "$STUB_URL" --arg sec "$INTEG_SECRET" --argjson x "$extra" \
        '{system:$s, name:$n, baseUrl:$b, authType:"TOKEN", username:"admin",
          credential:$c, secret:$sec, extra:$x, timeoutMs:5000, enabled:$e}'
}

i_disable_system() { # i_disable_system <system> — 关掉该系统所有 stub 配置（唯一启用约束）
    local id
    for id in $(i_list_cfgs "$1" | jq -r '.[] | select(.name|startswith("stub-")) | .id' 2>/dev/null); do
        api_call PATCH "/api/req/integration/configs/${id}" '{"enabled":false}' >/dev/null 2>&1 || true
    done
}

i_config() { # i_config <system> [enabled] [credential] → id（创建或更新名为 stub-<system> 的配置）
    local system="$1" enabled="${2:-true}" credential="${3:-$INTEG_CREDENTIAL}"
    local name id body
    name="$(i_cfg_name "$system")"
    body="$(i_cfg_body "$system" "$name" "$enabled" "$credential")"
    id="$(i_cfg_id "$system")"
    if [[ -n "$id" ]]; then
        [[ "$enabled" == "true" ]] && i_disable_system_except "$system" "$id"
        api_call PATCH "/api/req/integration/configs/${id}" "$body"
    else
        [[ "$enabled" == "true" ]] && i_disable_system "$system"
        api_call POST /api/req/integration/configs "$body"
        id="$(jget "$HTTP_BODY" '.id // .config.id')"
    fi
    if [[ "$HTTP_CODE" != "200" && "$HTTP_CODE" != "201" ]]; then
        err "配置 ${system} 落盘失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    fi
    [[ -n "$id" && "$id" != "null" ]] || id="$(i_cfg_id "$system")"
    printf '%s' "$id"
}

i_disable_system_except() { # 关掉同系统其它 stub 配置，避免 uk_cfg_active 冲突
    local system="$1" keep="$2" id
    for id in $(i_list_cfgs "$system" | jq -r '.[] | select(.name|startswith("stub-")) | select(.enabled==true) | .id' 2>/dev/null); do
        [[ "$id" == "$keep" ]] && continue
        api_call PATCH "/api/req/integration/configs/${id}" '{"enabled":false}' >/dev/null 2>&1 || true
    done
}

i_cleanup() { # trap EXIT 调用：只关本套脚本创建的配置，不动别人的数据
    local system
    for system in JENKINS GITLAB SONAR NEXUS; do
        i_disable_system "$system"
    done
}

i_on_exit() { # trap EXIT：清理不能吞掉脚本真实退出码（set -u 早退时 guard 还没执行）
    local rc=$?
    i_cleanup || true
    exit "$rc"
}

i_cfg_store_state() { # i_cfg_store_state <system> → DB 里该配置的 system/enabled/credential_last4/credential_enc 是否为空
    db_q "SELECT CONCAT(system,'|',enabled,'|',COALESCE(credential_last4,''),'|',credential_enc IS NOT NULL)
           FROM integration_config
          WHERE tenant_id=${TENANT_ID} AND name='$(i_cfg_name "$1")' ORDER BY id DESC LIMIT 1"
}

# ------------------------------------------------------------------ 需求 / 版本 / 发布单夹具

i_rd() { # i_rd <title> → RD 需求 id（RD 必须有 PDR 父，状态机 merge 只允许 TEST_PASSED→MERGED）
    local title="$1" pdr
    pdr="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND type='PDR' ORDER BY id LIMIT 1")"
    [[ -n "$pdr" ]] || pdr="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
    api_call POST /api/req/requirements "$(jq -cn --arg t "$title" --argjson p "${pdr:-0}" \
        --argjson d "$(q_domain)" '{type:"RD",title:$t,parentId:$p,domainIds:[$d],priority:"P2",
          expectedDeliveryDate:"2026-12-31",description:"M7 集成对接夹具"}')"
    local id; id="$(jget "$HTTP_BODY" '.id')"
    if [[ -z "$id" || "$id" == "null" ]]; then
        err "创建 RD 失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    fi
    printf '%s' "$id"
}

i_rd_status() { # i_rd_status <id> <status> — 集成测试夹具：直接置状态（正常路径由状态机驱动）
    db_exec "UPDATE requirement SET status='$2', updated_at=NOW(3) WHERE id=$1 AND tenant_id=${TENANT_ID}" >/dev/null
}

i_release_with() { # i_release_with <name> <requirementId> → releaseId（版本里带上需求）
    local name="$1" req="$2" rel
    rel="$(q_create_release "$name")" || return 1
    api_call POST "/api/req/releases/${rel}/requirements" "{\"requirementIds\":[${req}]}" >/dev/null 2>&1 || true
    printf '%s' "$rel"
}

i_order_for() { # i_order_for <releaseId> <requirementId> [env] → deployOrderId
    q_create_deploy_order "$1" "$2" "${3:-PROD}"
}

# ------------------------------------------------------------------ webhook

i_jenkins_body() { # i_jenkins_body <buildId> <status> [consoleUrl] [stagesJson]
    local build="$1" status="$2" console="${3:-${STUB_URL}/job/demo-job/${1}/console}" stages="${4:-}"
    [[ -n "$stages" ]] || stages='[{"name":"compile","status":"SUCCESS","durationMs":1200},{"name":"unit-test","status":"SUCCESS","durationMs":800}]'
    jq -cn --argjson b "$build" --arg s "$status" --arg u "$console" --argjson st "$stages" \
        '{buildId:$b,buildNumber:$b,jobName:"demo-job",status:$s,consoleUrl:$u,stages:$st,durationMs:2000}'
}

i_jenkins_webhook() { # i_jenkins_webhook <configId> <buildId> <status> [console] [tsOffset] [secret]
    local cfg="$1" build="$2" status="$3" console="${4:-}" offset="${5:-0}" secret="${6:-$INTEG_SECRET}"
    local body ts sig
    body="$(i_jenkins_body "$build" "$status" "$console")"
    ts=$(( $(date +%s) + offset ))
    sig="$(printf '%s' "${ts}.${body}" | openssl dgst -sha256 -hmac "$secret" -hex | awk '{print $NF}')"
    http_call POST "${GATEWAY_URL}/api/integration/webhooks/jenkins/${cfg}" "$body" \
        -H "X-Jenkins-Signature: sha256=${sig}" \
        -H "X-Jenkins-Timestamp: ${ts}" \
        -H "X-Jenkins-Event: build"
}

i_jenkins_webhook_raw() { # i_jenkins_webhook_raw <configId> <body> <signature> <timestamp>
    http_call POST "${GATEWAY_URL}/api/integration/webhooks/jenkins/${1}" "$2" \
        -H "X-Jenkins-Signature: $3" -H "X-Jenkins-Timestamp: $4" -H "X-Jenkins-Event: build"
}

i_gitlab_payload() { # i_gitlab_payload <project> <iid> <state> [updated_at] [title]
    local project="$1" iid="$2" state="$3" updated="${4:-$(date -u +%Y-%m-%dT%H:%M:%S.000Z)}" title="${5:-stub MR !$2}"
    jq -cn --arg p "$project" --argjson i "$iid" --arg s "$state" --arg u "$updated" --arg t "$title" '
        {object_kind:"merge_request", event_type:"merge_request",
         project:{id:11, path_with_namespace:$p, web_url:("'"${STUB_URL}"'/"+$p)},
         object_attributes:{iid:$i, title:$t, state:$s, source_branch:"feat/stub", target_branch:"main",
           url:("'"${STUB_URL}"'/"+$p+"/-/merge_requests/"+($i|tostring)),
           updated_at:$u, merged_at:(if $s=="merged" then $u else null end),
           action:(if $s=="merged" then "merge" elif $s=="closed" then "close" else "update" end),
           detailed_merge_status:"mergeable"},
         labels:[],
         changes:{state:{previous:"opened", current:$s}}}'
}

i_gitlab_webhook() { # i_gitlab_webhook <configId> <project> <iid> <state> [event] [updated_at] [token]
    local cfg="$1" project="$2" iid="$3" state="$4" event="${5:-Merge Request Hook}" updated="${6:-}" token="${7:-$INTEG_SECRET}"
    local body
    body="$(i_gitlab_payload "$project" "$iid" "$state" "$updated")"
    http_call POST "${GATEWAY_URL}/api/integration/webhooks/gitlab/${cfg}" "$body" \
        -H "X-Gitlab-Token: ${token}" -H "X-Gitlab-Event: ${event}"
}

# ------------------------------------------------------------------ 轮询

i_wait_build() { # i_wait_build <buildId> <期望状态> [tries]
    local id="$1" want="$2" tries="${3:-15}" i
    for ((i = 0; i < tries; i++)); do
        api_call GET "/api/req/builds/${id}"
        [[ "$(jget "$HTTP_BODY" '.status')" == "$want" ]] && return 0
        sleep 1
    done
    return 1
}

i_wait_http() { # i_wait_http <tries> <jq-filter> <expected> <method> <path> [body]
    local tries="$1" filter="$2" want="$3" method="$4" path="$5" body="${6:-}" i
    for ((i = 0; i < tries; i++)); do
        api_call "$method" "$path" "$body"
        [[ "$(jget "$HTTP_BODY" "$filter")" == "$want" ]] && return 0
        sleep 1
    done
    return 1
}

i_json() { # i_json <jq-args...> — 读 STUB/HTTP 之外的 JSON 时统一入口
    jq -cn "$@"
}
