#!/usr/bin/env bash
# platform-unit.sh — 单元门禁：契约、状态机、权限矩阵、公式、ScopeSql。
# `mvn test` 自身在断言失败时非 0；这里额外守住「0 个测试也算绿」的假绿。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STAGE="前置检查"
on_err() {
    local rc=$?
    err "platform-unit 失败于阶段: ${STAGE} (exit ${rc})"
    err "修复: 上面是第一个失败用例；按 类名#方法 定位，先看断言消息再看被测实现"
    exit "$rc"
}
trap on_err ERR

STAGE="前置检查"
need_cmd mvn
for m in platform-contract platform-common req-service deliver-service; do
    [[ -f "$ROOT/$m/pom.xml" ]] || die "缺少 $m/pom.xml：请先完成 T0（工程骨架）"
done
detect_maven_repo

STAGE="mvn test"
log "运行 platform-contract / platform-common / req-service / deliver-service 的单元测试"
( cd "$ROOT" && mvn -q ${MAVEN_REPO_ARG:-} \
    -pl platform-contract,platform-common,req-service,deliver-service test )

STAGE="汇总 surefire"
TOTAL=0
FAILED=0
for f in "$ROOT"/{platform-contract,platform-common,req-service,deliver-service}/target/surefire-reports/TEST-*.xml; do
    [[ -f "$f" ]] || continue
    t="$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]*\)".*/\1/p' "$f" | head -n1)"
    fl="$(sed -n 's/.*<testsuite[^>]* failures="\([0-9]*\)".*/\1/p' "$f" | head -n1)"
    er="$(sed -n 's/.*<testsuite[^>]* errors="\([0-9]*\)".*/\1/p' "$f" | head -n1)"
    TOTAL=$((TOTAL + ${t:-0}))
    FAILED=$((FAILED + ${fl:-0} + ${er:-0}))
done

if (( TOTAL == 0 )); then
    err "没有发现任何测试用例（surefire 报告为空）——0 个测试不是绿色的证据"
    err "修复: 检查模块是否被 -pl 选中、src/test/java 是否存在，以及 surefire 是否被 skip"
    exit 1
fi
if (( FAILED > 0 )); then
    err "单元测试失败: ${FAILED} / ${TOTAL}"
    exit 1
fi
ok "platform-unit: ${TOTAL} 个单元测试全部通过"
