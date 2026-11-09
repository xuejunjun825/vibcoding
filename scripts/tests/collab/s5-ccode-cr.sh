#!/usr/bin/env bash
# s5-ccode-cr.sh — AC-12/AC-13 CCode：MR 评审人/行级评论（幂等落库、脱敏、分页）、
# 代码质量只读本地快照（空态给原因、不补 0），所有页面读取零外呼。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
c_stub_reset

GID="$(i_config GITLAB)" || { fail "集成配置落盘失败" "s5 依赖 stub-gitlab"; guard s5-ccode-cr; }
RD="$(i_rd "s5MR-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s5版本-${RUN_TAG}" "$RD")" || exit 1
P="demo-group/demo-project"
IID=$(( 1000 + RANDOM % 8000 ))
i_stub_mr "$P" "$IID"
info "config=${GID} requirement=${RD} release=${REL} mr=!${IID}"

section "s5 · MR 绑定与评审人"
api_call POST "${C_REQ}/requirements/${RD}/mr-sync" "$(jq -cn --arg u "${STUB_URL}/${P}/-/merge_requests/${IID}" '{mrUrl:$u}')"
expect "mr-sync 200" 200
api_call GET "${C_REQ}/requirements/${RD}/integration/mr"
expect "GET /requirements/{id}/integration/mr 200" 200
expect_jq "评审卡带评审人数组" '(.card.reviewers | type)' array
if [[ "$(jget "$HTTP_BODY" '.card.reviewers | length')" -ge 2 ]]; then
    pass "评审人 >=2（ reviewers_json 落库并回读）"
else
    fail "评审人为空" "V9 的 mr_sync.reviewers_json 必须由 MR_SYNC 写入"
fi
api_call POST "${C_REQ}/requirements/${RD}/integration/mr/refresh"
expect "refresh（拉 notes）200" 200
expect_jq "首次刷新入库 3 条行级评论（系统/非行级被过滤）" '.added' 3
expect_db "库里正好 3 条 mr_comment" \
    "SELECT COUNT(*) FROM mr_comment c JOIN mr_sync m ON m.id=c.mr_sync_id
      WHERE m.requirement_id=${RD}" eq 3 "只存 position!=null 且非 system 的 note"
expect_db "重复 note 不重复落库（(tenant,mr_sync_id,gitlab_note_id) 唯一）" \
    "SELECT COUNT(*) FROM (SELECT gitlab_note_id FROM mr_comment c JOIN mr_sync m ON m.id=c.mr_sync_id
       WHERE m.requirement_id=${RD} GROUP BY gitlab_note_id HAVING COUNT(*)>1) x" eq 0 \
    "幂等键失效会导致每次刷新都翻倍"

section "s5 · 脱敏"
if [[ "$(db_q "SELECT COUNT(*) FROM mr_comment c JOIN mr_sync m ON m.id=c.mr_sync_id WHERE m.requirement_id=${RD} AND c.body LIKE '%glpat-AbCdEf123456%'")" == "0" ]]; then
    pass "GitLab PAT 未明文落库"
else
    fail "评论里残留 glpat-AbCdEf123456" "MrCommentService.mask 必须先脱敏再入库（AC-12）"
fi
if [[ "$(db_q "SELECT COUNT(*) FROM mr_comment c JOIN mr_sync m ON m.id=c.mr_sync_id WHERE m.requirement_id=${RD} AND c.body LIKE '%hunter2%'")" == "0" ]]; then
    pass "「password=」类口令未明文落库"
else
    fail "评论里残留 password=hunter2" "脱敏要覆盖赋值式口令，不只覆盖已知 token 前缀"
fi

section "s5 · 幂等与分页"
api_call POST "${C_REQ}/requirements/${RD}/integration/mr/refresh"
expect "二次 refresh 200" 200
expect_jq "二次刷新新增 0" '.added' 0
expect_db "次数没变（仍 3 条）" \
    "SELECT COUNT(*) FROM mr_comment c JOIN mr_sync m ON m.id=c.mr_sync_id WHERE m.requirement_id=${RD}" eq 3
api_call GET "${C_REQ}/requirements/${RD}/integration/mr-comments?limit=2"
expect "第一页 200" 200
expect_jq "limit=2 → 2 条" '.items | length' 2
expect_jq "还有下一页" '.hasMore' true
CURSOR="$(jget "$HTTP_BODY" '.nextCursor')"
PAGE1_IDS="$(jget "$HTTP_BODY" '[.items[].gitlabNoteId] | join(",")')"
api_call GET "${C_REQ}/requirements/${RD}/integration/mr-comments?limit=2&cursor=${CURSOR}"
expect "第二页 200" 200
PAGE2_IDS="$(jget "$HTTP_BODY" '[.items[].gitlabNoteId] | join(",")')"
if [[ -n "$PAGE2_IDS" && -z "$(comm -12 <(tr ',' '\n' <<<"$PAGE1_IDS" | sort) <(tr ',' '\n' <<<"$PAGE2_IDS" | sort))" ]]; then
    pass "第二页按游标续读，与第一页无重叠（${PAGE1_IDS} / ${PAGE2_IDS}）"
else
    fail "游标分页重叠或空：第一页 ${PAGE1_IDS:-?} 第二页 ${PAGE2_IDS:-?}" "nextCursor 必须稳定翻页，否则前端会漏读评论"
fi

section "s5 · 代码质量：只读快照，空态不补 0"
BEFORE="$(c_stub_count all)"
api_call GET "${C_REQ}/requirements/${RD}/code-quality"
expect "GET /requirements/{id}/code-quality 200" 200
expect_jq "无快照时 card 为空（不是一堆 0）" '.card' null
if [[ "$(jget "$HTTP_BODY" '.emptyReason')" == "NO_SNAPSHOT" || "$(jget "$HTTP_BODY" '.emptyReason')" == "INTEGRATION_DISABLED" ]]; then
    pass "空态给出原因（$(jget "$HTTP_BODY" '.emptyReason')）"
else
    fail "空态没有 emptyReason" "空态必须能区分「没接 Sonar」与「还没扫过」，否则用户以为质量是 0"
fi
api_call GET "${C_REQ}/releases/${REL}/code-quality"
expect "GET /releases/{id}/code-quality 200" 200
expect_jq "版本视图返回 projects 数组（按 sonar_project_key 去重）" '(.projects | type)' array
c_zero_outbound "代码质量读快照零外呼（打开页面不打 Sonar）" all "$BEFORE"
i_config SONAR false >/dev/null 2>&1 || true
api_call GET "${C_REQ}/requirements/${RD}/code-quality"
expect "Sonar 停用后仍 200（读本地快照，不 5xx）" 200
expect_jq "停用态给出 INTEGRATION_DISABLED/NO_SNAPSHOT" '[.emptyReason // ""] | .[0] | test("INTEGRATION_DISABLED|NO_SNAPSHOT")' true

guard s5-ccode-cr
