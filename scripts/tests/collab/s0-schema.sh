#!/usr/bin/env bash
# s0-schema.sh — AC-15/AC-16 迁移基座：11 张新表、判空加列、两个索引、回填校验列全 0、
# 每租户种子（7 阶段 / 5 指标 / 1 空间 / 3 页面 / 4 版本 / 1 策略 / 2 执行器）、
# 以及权限矩阵 42→56 的编译期事实（新 14 码必须都在册且 PLATFORM_ADMIN 拥有）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

section "s0 · 11 张新表"
TABLES="'wiki_space','wiki_page','wiki_page_version','wiki_comment','wiki_page_link','stage_definition','branch_policy','branch_policy_check','mr_comment','test_case_review','test_executor'"
expect_db "11 张新表齐全" \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ($TABLES)" \
    eq 11 "V9 只建了部分表：重跑 make platform-up（Flyway 会重新应用失败迁移）"

section "s0 · 判空加列"
expect_db "mr_sync 补齐 4 列（reviewers_json / review_state / changed_files / comments_count）" \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='mr_sync' AND column_name IN ('reviewers_json','review_state','changed_files','comments_count')" \
    eq 4 "V9 的 mr_sync 加列段没跑完：检查 ALTER 是否被 IF NOT EXISTS 跳过"
for t in requirement release sprint task; do
    expect_db "${t}.actual_start_date 存在" \
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
          AND table_name='${t}' AND column_name='actual_start_date'" \
        eq 1 "甘特浅色实际条没有数据来源：V9 的 actual_* 加列段缺失"
done
expect_db "test_plan.data_note 存在" \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='test_plan' AND column_name='data_note'" eq 1 "V9 未加 data_note：CTest 测试数据说明无落点"
expect_db "test_execution.executor_id 存在（V1 建表时已有，V9 不重复 ALTER）" \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='test_execution' AND column_name='executor_id'" eq 1 "执行器上报无法归属：检查 V1 建表"

section "s0 · 两个索引"
expect_db "wiki_page.ft_wiki 是 FULLTEXT（ngram 分词器的前提）" \
    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='wiki_page' AND index_name='ft_wiki' AND index_type='FULLTEXT'" \
    eq 1 "中文全文检索会退化成全表 LIKE：补 CREATE FULLTEXT INDEX ft_wiki ... WITH PARSER ngram"
expect_db "audit_log.idx_audit_req 存在（价值流窗口函数的唯一索引依赖）" \
    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='audit_log' AND index_name='idx_audit_req'" \
    eq 1 "价值流按需求取审计会全表扫：补 idx_audit_req(tenant_id,biz_type,biz_id,created_at)"

section "s0 · 回填校验列（期望全 0）"
expect_db "有 IN_DEV 审计但 actual_start_date 仍为空的需求 = 0" \
    "SELECT COUNT(*) FROM requirement r WHERE r.tenant_id=${TENANT_ID}
       AND r.actual_start_date IS NULL AND EXISTS (
         SELECT 1 FROM audit_log a WHERE a.tenant_id=r.tenant_id AND a.biz_type='REQUIREMENT'
           AND a.biz_id=r.id AND a.action='STATE_CHANGE'
           AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'\$.to'))='IN_DEV')" \
    eq 0 "回填口径与 ActualPeriodRecorder 不一致：同一状态要有同一个实际期"
expect_db "有终态审计但 actual_end_date 仍为空的需求 = 0" \
    "SELECT COUNT(*) FROM requirement r WHERE r.tenant_id=${TENANT_ID}
       AND r.actual_end_date IS NULL AND EXISTS (
         SELECT 1 FROM audit_log a WHERE a.tenant_id=r.tenant_id AND a.biz_type='REQUIREMENT'
           AND a.biz_id=r.id AND a.action='STATE_CHANGE'
           AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'\$.to')) IN ('DELIVERED','DONE','ACCEPTED_CLOSED','CLOSED'))" \
    eq 0 "交付类终态没有写 actual_end_date：甘特实际条会缺尾"
expect_db "FLOW_* 指标的 datasource 必须是 req.audit = 0 例外" \
    "SELECT COUNT(*) FROM metric_definition WHERE code LIKE 'FLOW\\_%' AND version=1 AND datasource<>'req.audit'" \
    eq 0 "价值流指标必须与看板同源（audit_log），否则字典页读数与看板不相等"
expect_db "undeleted 页面都有 current_version 版本行 = 0 例外" \
    "SELECT COUNT(*) FROM wiki_page p WHERE p.tenant_id=${TENANT_ID} AND p.status<>'DELETED'
       AND p.current_version > (SELECT COALESCE(MAX(v.version_no),0) FROM wiki_page_version v WHERE v.page_id=p.id)" \
    eq 0 "版本指针指向不存在的版本：回滚/对比会 500"

section "s0 · 每租户种子"
expect_db "7 个阶段定义（REPORT…DELIVERY）" \
    "SELECT COUNT(DISTINCT code) FROM stage_definition WHERE tenant_id=${TENANT_ID}" eq 7 \
    "价值流看板项数不足：V9 种子段缺失或租户未覆盖"
expect_db "5 个 FLOW_* 指标定义" \
    "SELECT COUNT(*) FROM metric_definition WHERE tenant_id=${TENANT_ID} AND code LIKE 'FLOW\\_%'" eq 5 \
    "指标字典缺 FLOW 行：字典页与看板必然对不上"
expect_db "1 个 DOMAIN 可见性种子 Wiki 空间（按名字锁定，测试库可重复跑）" \
    "SELECT COUNT(*) FROM wiki_space WHERE tenant_id=${TENANT_ID}
       AND name='研发协作 Wiki' AND visibility='DOMAIN'" eq 1 \
    "Wiki 无种子空间：页面创建会 404 WIKI_SPACE_NOT_FOUND"
expect_db "3 个种子页面（含一个 depth=2 子页；只数种子空间）" \
    "SELECT COUNT(*) FROM wiki_page p JOIN wiki_space s ON s.id=p.space_id
      WHERE p.tenant_id=${TENANT_ID} AND s.name='研发协作 Wiki'" eq 3 \
    "Wiki 树种子缺失：树/检索用例无夹具"
expect_db "4 条种子版本（根页 v1/v2 + 子页 v1 + 另一根 v1；只数种子空间）" \
    "SELECT COUNT(*) FROM wiki_page_version v
       JOIN wiki_page p ON p.id=v.page_id AND p.tenant_id=${TENANT_ID}
       JOIN wiki_space s ON s.id=p.space_id
      WHERE v.tenant_id=${TENANT_ID} AND s.name='研发协作 Wiki'" eq 4 \
    "版本历史种子与页面不匹配：版本对比/回滚无法验"
expect_db "种子分支策略 1 条且没有任何核验记录（派生 UNVERIFIED）" \
    "SELECT COUNT(*) FROM branch_policy b WHERE b.tenant_id=${TENANT_ID}
       AND b.gitlab_project='demo/supply-chain-platform'
       AND NOT EXISTS (SELECT 1 FROM branch_policy_check c WHERE c.policy_id=b.id)" eq 1 \
    "核验态不能靠列存：种子策略必须无 check 行，页面才显示未核验"
expect_db "2 个种子执行器（一台从未上报 / 一台停用；按 code 锁定）" \
    "SELECT COUNT(*) FROM test_executor WHERE tenant_id=${TENANT_ID}
       AND code IN ('STUB-LOCAL','LEGACY-OFF')" eq 2 "执行器台账为空：健康派生无夹具"

section "s0 · 权限矩阵 42→56"
api_call GET "${C_REQ}/admin/permissions"
expect "GET /admin/permissions 200" 200
expect_jq "功能码 56 个" '.functions | length' 56
expect_jq "角色 10 个" '.roles | length' 10
NEW_CODES="WIKI_VIEW WIKI_EDIT WIKI_ADMIN GANTT_VIEW GANTT_EDIT FLOW_VIEW FLOW_CONFIG CCODE_POLICY_VIEW CCODE_POLICY_EDIT CASE_REVIEW_VIEW CASE_REVIEW_SUBMIT CASE_REVIEW_DECIDE TEST_EXECUTOR_VIEW TEST_EXECUTOR_MANAGE"
MISSING=""
for code in $NEW_CODES; do
    [[ "$(jget "$HTTP_BODY" "[.functions[] | select(.code==\"$code\")] | length")" == "1" ]] || MISSING="${MISSING} ${code}"
done
if [[ -z "$MISSING" ]]; then
    pass "14 个新权限码全部在册"
else
    fail "权限码缺失:${MISSING}" "PermissionMatrix.FUNCTIONS 只能追加：管理页只是投影，缺码=后端不拦截"
fi
NOT_ADMIN=""
for code in $NEW_CODES; do
    [[ "$(jget "$HTTP_BODY" "[.functions[] | select(.code==\"$code\") | .allow[] | select(.==\"PLATFORM_ADMIN\")] | length")" == "1" ]] \
        || NOT_ADMIN="${NOT_ADMIN} ${code}"
done
if [[ -z "$NOT_ADMIN" ]]; then
    pass "PLATFORM_ADMIN 拥有全部 14 个新码"
else
    fail "PLATFORM_ADMIN 缺码:${NOT_ADMIN}" "平台管理员必须在 allow 里，否则新功能无人可配"
fi

guard s0-schema
