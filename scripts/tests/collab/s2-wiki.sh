#!/usr/bin/env bash
# s2-wiki.sh — AC-4..AC-7 Wiki：空间/树、版本对比与回滚（旧版 hash 不变）、编辑冲突、
# 深度 4 层与循环负向、回收站 30 天、ngram 检索、双向关联、附件锚点白名单。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

SPACE_NAME="s2空间-${RUN_TAG}"
api_call POST "${C_REQ}/wiki/spaces" "$(jq -cn --arg n "$SPACE_NAME" --argjson d "$(q_domain)" \
    '{domainId:$d,name:$n,visibility:"DOMAIN"}')"
expect "POST /wiki/spaces 建空间 200" 200
SPACE="$(jget "$HTTP_BODY" '.id')"
if [[ -n "$SPACE" && "$SPACE" != "null" ]]; then
    pass "空间 id=${SPACE} 已返回"
else
    fail "建空间没返回 id（HTTP ${HTTP_CODE}）" "Wiki 后端（T3）未实现时这里会 404：先跑 T3"
    guard s2-wiki
fi
expect_jq "默认可见范围 DOMAIN" '.visibility' DOMAIN

section "s2 · 建页 / 读详情 / 树"
api_call POST "${C_REQ}/wiki/pages" "$(jq -cn --argjson s "$SPACE" --arg t "s2根页-${RUN_TAG}" \
    --arg c "协作验收词条：这里是根页正文 v1" '{spaceId:$s,parentId:null,title:$t,contentMd:$c}')"
expect "POST /wiki/pages 建根页 200" 200
PAGE="$(jget "$HTTP_BODY" '.id')"
expect_jq "建页即落 v1" '.currentVersion' 1
api_call GET "${C_REQ}/wiki/pages/${PAGE}"
expect "GET /wiki/pages/{id} 200" 200
expect_jq "详情带 canEdit" '.page.canEdit' true
expect_jq "详情带 links 数组" '(.page.links | type)' array
api_call GET "${C_REQ}/wiki/spaces/${SPACE}/tree"
expect "GET /wiki/spaces/{id}/tree 200" 200
TREE_FILTER="[ (.nodes // .)[]? | select(.id==$PAGE) ] | length"
expect_jq "树里能查到本页" "$TREE_FILTER" 1

section "s2 · 版本：产版 / noChange / 冲突"
api_call PUT "${C_REQ}/wiki/pages/${PAGE}" "$(jq -cn --arg t "s2根页-${RUN_TAG}" \
    --arg c "协作验收词条：第二版正文，多了这一行" '{title:$t,contentMd:$c,baseVersion:1}')"
expect "保存（baseVersion=1）200" 200
expect_jq "产出版本 v2" '.versionNo' 2
expect_jq "内容有变化 → noChange=false" '.noChange' false
api_call PUT "${C_REQ}/wiki/pages/${PAGE}" "$(jq -cn --arg t "s2根页-${RUN_TAG}" \
    --arg c "协作验收词条：第二版正文，多了这一行" '{title:$t,contentMd:$c,baseVersion:2}')"
expect "同内容再存 200" 200
expect_jq "同内容不产版（noChange=true）" '.noChange' true
expect_jq "版本号仍是 2" '.versionNo' 2
api_call PUT "${C_REQ}/wiki/pages/${PAGE}" "$(jq -cn --arg t "s2根页-${RUN_TAG}" \
    --arg c "抢写" '{title:$t,contentMd:$c,baseVersion:1}')"
deny "落后版本保存 → 409 WIKI_EDIT_CONFLICT" 409 WIKI_EDIT_CONFLICT
api_call GET "${C_REQ}/wiki/pages/${PAGE}/versions"
expect "GET versions 200" 200
expect_jq "版本列表 2 条（noChange 没有偷偷产版）" 'length' 2
api_call GET "${C_REQ}/wiki/pages/${PAGE}/versions/diff?from=1&to=2"
expect "GET diff 200" 200
ADDED="$(jget "$HTTP_BODY" '[.lines[] | select(.kind=="ADD")] | length')"
expect_num "diff 含新增行数 ≥1" "${ADDED:-0}" ge 1 "两版正文不同，LCS 必然有 ADD；为 0 说明 diff 实现没按行比"
if [[ "${ADDED:-0}" -ge 1 ]]; then
    pass "diff 含 ADD 行（行级 LCS 由服务端算）"
else
    fail "diff 没有 ADD 行" "diff 必须在服务端 WikiDiff 里算，前端不得另写一份"
fi
HASH1_BEFORE="$(db_q "SELECT content_hash FROM wiki_page_version WHERE page_id=${PAGE} AND version_no=1")"
api_call POST "${C_REQ}/wiki/pages/${PAGE}/rollback" '{"versionNo":1}'
expect "回滚到 v1 → 200" 200
expect_jq "回滚生成 v3（不覆盖历史）" '.versionNo' 3
HASH1_AFTER="$(db_q "SELECT content_hash FROM wiki_page_version WHERE page_id=${PAGE} AND version_no=1")"
if [[ -n "$HASH1_BEFORE" && "$HASH1_BEFORE" == "$HASH1_AFTER" ]]; then
    pass "旧版本 v1 的 content_hash 未变（append-only）"
else
    fail "v1 hash 变了：${HASH1_BEFORE:-?} → ${HASH1_AFTER:-?}" "回滚必须走同一保存路径产新版本，绝不能改写历史行"
fi

section "s2 · 树：深度 4 层与循环"
P2="$(api_call POST "${C_REQ}/wiki/pages" "$(jq -cn --argjson s "$SPACE" --argjson p "$PAGE" --arg t "L2-${RUN_TAG}" '{spaceId:$s,parentId:$p,title:$t,contentMd:"L2"}')"; jget "$HTTP_BODY" '.id')"
P3="$(api_call POST "${C_REQ}/wiki/pages" "$(jq -cn --argjson s "$SPACE" --argjson p "$P2" --arg t "L3-${RUN_TAG}" '{spaceId:$s,parentId:$p,title:$t,contentMd:"L3"}')"; jget "$HTTP_BODY" '.id')"
P4="$(api_call POST "${C_REQ}/wiki/pages" "$(jq -cn --argjson s "$SPACE" --argjson p "$P3" --arg t "L4-${RUN_TAG}" '{spaceId:$s,parentId:$p,title:$t,contentMd:"L4"}')"; jget "$HTTP_BODY" '.id')"
if [[ -n "$P4" && "$P4" != "null" ]]; then
    pass "第 4 层页面可建（L1..L4）"
else
    fail "第 4 层建不出来（HTTP ${HTTP_CODE}）" "上限是 4 层，不是 3 层"
fi
api_call POST "${C_REQ}/wiki/pages" "$(jq -cn --argjson s "$SPACE" --argjson p "$P4" --arg t "L5-${RUN_TAG}" '{spaceId:$s,parentId:$p,title:$t,contentMd:"L5"}')"
deny "第 5 层 → 400 WIKI_DEPTH_EXCEEDED" 400 WIKI_DEPTH_EXCEEDED
api_call POST "${C_REQ}/wiki/pages/${PAGE}/move" "$(jq -cn --argjson p "$P3" '{parentId:$p}')"
deny "把祖先移到后代下 → 409 WIKI_CYCLE" 409 WIKI_CYCLE

section "s2 · 检索（total 只计可见）"
api_call GET "${C_REQ}/wiki/search?q=%E5%8D%8F%E4%BD%9C%E9%AA%8C%E6%94%B6&page=1&size=10"
expect "GET /wiki/search 200" 200
expect_jq "检索命中本页" "[.items[] | select(.pageId==$PAGE)] | length" 1
expect_jq "带 degraded 标记（ngram 缺失时可降级）" '(.degraded|type)' boolean
if [[ "$(jget "$HTTP_BODY" '.total')" -ge 1 ]]; then
    pass "total 计数 ≥1"
else
    fail "total=0 但 items 有命中" "total 与 items 必须是同一次可见性过滤的结果"
fi

section "s2 · 回收站 / 恢复 / 清理"
api_call DELETE "${C_REQ}/wiki/pages/${P2}"
expect "软删子树根 L2 → 200" 200
expect_db "子树一起软删（L2/L3/L4 三行 DELETED）" \
    "SELECT COUNT(*) FROM wiki_page WHERE tenant_id=${TENANT_ID} AND id IN (${P2},${P3},${P4}) AND status='DELETED'" \
    eq 3 "软删必须整棵子树同 deleted_at/deleted_root_id，只删根会留下孤儿"
expect_db "deleted_root_id 指向 L2 根" \
    "SELECT COUNT(*) FROM wiki_page WHERE id=${P3} AND deleted_root_id=${P2}" eq 1 \
    "恢复要能按根恢复整棵子树：deleted_root_id 必须写根 id"
api_call GET "${C_REQ}/wiki/trash?spaceId=${SPACE}"
expect "GET /wiki/trash 200" 200
expect_jq "回收站里以根行呈现（不逐条列子树）" '(.items // .) | length' 1
api_call POST "${C_REQ}/wiki/pages/${P2}/restore"
expect "恢复 → 200" 200
expect_db "恢复后三行都不是 DELETED" \
    "SELECT COUNT(*) FROM wiki_page WHERE tenant_id=${TENANT_ID} AND id IN (${P2},${P3},${P4}) AND status<>'DELETED'" \
    eq 3 "恢复只恢复根会让子树永久消失在树里"
api_call DELETE "${C_REQ}/wiki/pages/${P4}"
expect "再次软删 L4 → 200" 200
api_call DELETE "${C_REQ}/wiki/pages/${P4}/purge"
expect "管理员物理清理 → 200" 200
expect_db "purge 后行已不存在" "SELECT COUNT(*) FROM wiki_page WHERE id=${P4}" eq 0 \
    "purge 是管理员动作，必须真删（版本一起级联）"

section "s2 · 双向关联与附件锚点"
api_call POST "${C_REQ}/wiki/pages/${PAGE}/links" "$(jq -cn --argjson id "$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")" \
    '{bizType:"REQUIREMENT",bizId:$id}')"
expect "挂需求关联 → 200" 200
api_call GET "${C_REQ}/wiki/links?bizType=REQUIREMENT&bizId=$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
expect "GET /wiki/links 200" 200
expect_jq "对端能看到本页（双向关联）" "[.items[]? | select(.pageId==$PAGE)] | length" 1
TMPFILE="$(mktemp)"; printf 'wiki attachment %s\n' "$RUN_TAG" >"$TMPFILE"
RESPFILE="$(mktemp)"
HTTP_CODE="$(curl -s -o "$RESPFILE" -w '%{http_code}' -X POST "${GATEWAY_URL}${C_REQ}/attachments?bizType=WIKI_PAGE&bizId=${PAGE}" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" -F "file=@${TMPFILE};filename=wiki-${RUN_TAG}.txt" 2>/dev/null)" || true
HTTP_BODY="$(cat "$RESPFILE" 2>/dev/null || true)"
rm -f "$TMPFILE" "$RESPFILE"
expect "WIKI_PAGE 附件可上传（bizType 白名单已扩展；与 platform-verify 一致为 201）" 201
api_call GET "${C_REQ}/wiki/pages/${PAGE}"
expect_jq "页面详情带 attachments[]" '(.page.attachments|type)' array

BEFORE="$(c_stub_count all)"
api_call GET "${C_REQ}/wiki/pages/${PAGE}"
c_zero_outbound "Wiki 详情零外呼" all "$BEFORE"

guard s2-wiki
