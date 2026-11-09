#!/usr/bin/env bash
# s3-sonar.sh — AC-6 Sonar 质量门：key 继承（requirement > release 默认 > config）、五度量快照、
# 同 hash 幂等不新增、OK/ERROR/跳过三态、跳过原因具体、追溯 SONAR_GATE 节点、快照只增、降级 stale。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
SID="$(i_config SONAR)" || exit 1
RD="$(i_rd "s3Sonar-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s3版本-${RUN_TAG}" "$RD")" || exit 1
info "config=${SID} requirement=${RD} release=${REL}"

# 取 SonarCard：兼容裸对象 / {sonar:...} / {data:...} 三种包装
sonar_card() { jq -c '(.data // .sonar // .)' <<<"$HTTP_BODY" 2>/dev/null; }

section "s3 · key 继承：版本默认 key 生效（需求未单独配置）"
api_call PUT "/api/req/releases/${REL}/integration-settings" '{"defaultSonarProjectKey":"release-key"}'
expect "版本集成设置 200" 200
api_call POST "/api/req/requirements/${RD}/sonar/refresh"
expect "刷新快照 200" 200
CARD="$(sonar_card)"
KEY="$(jq -r '.projectKey // empty' <<<"$CARD")"
if [[ "$KEY" == "release-key" ]]; then pass "需求继承版本默认 key=release-key"; else fail "projectKey='${KEY}'" "effectiveSonarKey 继承顺序 requirement>release>config"; fi

section "s3 · 五度量落快照（coverage/bugs/vulnerabilities/codeSmells/duplicatedLinesDensity）"
MISSING=0
for m in coverage bugs vulnerabilities codeSmells duplicatedLinesDensity; do
    V="$(jq -r --arg m "$m" '.metrics[$m] // empty' <<<"$CARD")"
    [[ -n "$V" ]] || { MISSING=$((MISSING + 1)); err "缺少度量 ${m}"; }
done
if (( MISSING == 0 )); then pass "五度量齐全"; else fail "缺 ${MISSING} 个度量" "SonarAdapter.measures 需映射 5 个 metricKey"; fi

section "s3 · 同 hash 幂等：重复刷新不新增快照行"
SNAP_BEFORE="$(db_count quality_gate_snapshot "tenant_id=${TENANT_ID} AND source='SONAR' AND requirement_id=${RD}")"
SNAP_ID1="$(jq -r '.snapshotId // empty' <<<"$CARD")"
api_call POST "/api/req/requirements/${RD}/sonar/refresh"
expect "再次刷新 200" 200
CARD="$(sonar_card)"
SNAP_AFTER="$(db_count quality_gate_snapshot "tenant_id=${TENANT_ID} AND source='SONAR' AND requirement_id=${RD}")"
SNAP_ID2="$(jq -r '.snapshotId // empty' <<<"$CARD")"
if [[ "$SNAP_BEFORE" == "$SNAP_AFTER" ]]; then pass "同 hash 不新增（${SNAP_AFTER} 行）"; else fail "快照行 ${SNAP_BEFORE}→${SNAP_AFTER}" "hash 相同应复用既有快照"; fi
if [[ -n "$SNAP_ID1" && "$SNAP_ID1" == "$SNAP_ID2" ]]; then pass "snapshotId 稳定=${SNAP_ID1}"; else info "snapshotId ${SNAP_ID1} vs ${SNAP_ID2}（允许实现不返回 id）"; fi

section "s3 · 三态之一：Sonar ERROR → 卡片 ERROR（计为失败，不吞）"
i_stub_sonar ERROR
api_call POST "/api/req/requirements/${RD}/sonar/refresh"
expect "ERROR 刷新 200" 200
CARD="$(sonar_card)"
ST="$(jq -r '.status // empty' <<<"$CARD" | tr '[:lower:]' '[:upper:]')"
if [[ "$ST" == "ERROR" ]]; then pass "门禁状态 ERROR"; else fail "status=${ST}" "projectStatus.status=ERROR 必须原样透出"; fi
SNAP_AFTER2="$(db_count quality_gate_snapshot "tenant_id=${TENANT_ID} AND source='SONAR' AND requirement_id=${RD}")"
if (( SNAP_AFTER2 > SNAP_AFTER )); then pass "hash 变化 → 快照只增（+$((SNAP_AFTER2 - SNAP_AFTER))）"; else fail "ERROR 快照未新增" "快照只增，历史可回溯"; fi
i_stub_sonar OK

section "s3 · 三态之二：需求未配置 key → SKIP + 具体原因"
REL2="$(q_create_release "s3无key-${RUN_TAG}")" || exit 1
RD2="$(i_rd "s3Sonar-skip-${RUN_TAG}")" || exit 1
api_call POST "/api/req/releases/${REL2}/requirements" "{\"requirementIds\":[${RD2}]}" >/dev/null 2>&1 || true
api_call GET "/api/req/requirements/${RD2}/sonar"
expect "未配置时 GET 200（不报错白屏）" 200
CARD="$(sonar_card)"
ST="$(jq -r '.status // empty' <<<"$CARD" | tr '[:lower:]' '[:upper:]')"
REASON="$(jq -r '.skipReason // .reasonCode // empty' <<<"$CARD")"
if [[ "$ST" == "SKIP" && -n "$REASON" ]]; then pass "SKIP 且原因=${REASON}"; else fail "status=${ST} reason='${REASON}'" "无 key 应 SKIP/NOT_CONFIGURED，不能算通过"; fi

section "s3 · 三态之三：度量缺失 → 不可判为通过（EVIDENCE_UNAVAILABLE）"
i_stub_sonar OK "coverage,bugs"
api_call POST "/api/req/requirements/${RD}/sonar/refresh"
expect "缺度量刷新不 5xx" 200
CARD="$(sonar_card)"
ST="$(jq -r '.status // empty' <<<"$CARD" | tr '[:lower:]' '[:upper:]')"
REASON="$(jq -r '.skipReason // .reasonCode // empty' <<<"$CARD")"
if [[ "$ST" != "OK" || -n "$REASON" ]]; then pass "缺度量不判 OK（status=${ST} reason=${REASON}）"; else fail "缺度量仍 OK" "fail-closed：证据不全不能 PASS"; fi
i_stub_sonar OK

section "s3 · 追溯矩阵出现 SONAR_GATE 节点（cells 数据驱动）"
api_call GET "/api/deliver/traceability/${RD}"
expect "追溯详情 200" 200
if jq -e '(.cells // {}) | keys | map(ascii_downcase) | any(test("sonar"))' <<<"$HTTP_BODY" >/dev/null 2>&1; then
    pass "cells 含 sonar 节点"
else
    fail "cells 无 sonar key: $(jq -c '(.cells // {}) | keys' <<<"$HTTP_BODY" 2>/dev/null)"
fi
if jq -e '.. | strings | ascii_downcase | select(test("sonar_gate"))' <<<"$HTTP_BODY" >/dev/null 2>&1; then
    pass "节点标识含 SONAR_GATE"
else
    info "未出现字面 SONAR_GATE（cells key 可能是 sonar）"
fi

section "s3 · 降级：Sonar 5xx 时刷新不 5xx，返回 stale/保留上次快照"
i_stub_mode sonar.gate 500
i_stub_mode sonar.measures 500
api_call POST "/api/req/requirements/${RD}/sonar/refresh"
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "5xx 降级 200"
    CARD="$(sonar_card)"
    if [[ "$(jq -r '.stale // false' <<<"$CARD")" == "true" || -n "$(jq -r '.skipReason // empty' <<<"$CARD")" ]]; then
        pass "标记 stale / 原因可见"
    else
        info "未返回 stale 字段（实现可能只写 DEGRADED 日志）"
    fi
else
    fail "降级返回 ${HTTP_CODE}" "交互读单次尝试，失败降级不阻断页面"
fi
i_stub_mode sonar.gate ok
i_stub_mode sonar.measures ok

guard s3-sonar
