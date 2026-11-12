SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help dev-up dev-down verify build lint test test-negative nacos-status clean distclean \
        platform-up platform-down platform-web platform-verify platform-test-negative \
        platform-seed platform-unit platform-perf quality-verify quality-e2e integration-verify \
        collab-verify collab-perf access-verify analytics-i18n-verify platform-demo-reset

help: ## 列出所有命令
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-26s\033[0m %s\n", $$1, $$2}'

# ---- r1 baseline (unchanged contract) ----
dev-up: ## 一条命令拉起 Nacos + demo-service + api-gateway，并打印 READY
	@./scripts/dev-up.sh

dev-down: ## 逆序停止全部进程，并校验六个端口已释放
	@./scripts/dev-down.sh

verify: ## 体检全链路，逐项 PASS/FAIL，红灯给出修复提示
	@./scripts/verify.sh

build: ## 构建全部模块（跳过测试）
	@./scripts/build.sh

lint: ## 静态门禁：脚本语法/插值/单点实现/错误提示/文档一致性（不启动服务）
	@./scripts/tests/lint.sh

test: ## 全量门禁：lint + 单测 + r1 四步 + 平台栈 AC-1..AC-20 + 种子 + 负向 + 协作域 s0..s8
	@./scripts/test-all.sh
	@echo ""
	@echo "== 协作域验收（M8）：test-all 结束时已 platform-down，这里重新拉起平台栈 =="
	@./scripts/platform-up.sh
	@./scripts/collab-verify.sh
	@./scripts/platform-down.sh

test-negative: ## 仅运行 r1 负向用例：停服务 -> verify 必红 -> 恢复 -> 全绿
	@./scripts/tests/verify-negative.sh

nacos-status: ## 查看 Nacos 进程/端口/健康状态
	@./scripts/nacos.sh status

# ---- platform layer (20261108-r2) ----
platform-up: ## 幂等拉起 MySQL 库 + req(8082) + deliver(8083) + web(5173)，语义就绪后打印 READY
	@./scripts/platform-up.sh

platform-down: ## 停止平台层进程（req/deliver/web），不动 r1 的 Nacos/网关/demo
	@./scripts/platform-down.sh

platform-web: ## 只构建并启动 web 预览（5173），就绪判据为 HTML 探针
	@./scripts/platform-web.sh

platform-verify: ## 按 AC-1..AC-20 分组逐项断言（HTTP + DB 计数），末尾 PASS==TOTAL 守卫
	@./scripts/platform-verify.sh

platform-test-negative: ## 15 条负向断言：拒绝且不落库
	@./scripts/platform-test-negative.sh

platform-seed: ## 幂等种子：连跑两遍断言计数不变（AC-19）
	@./scripts/platform-seed.sh

platform-unit: ## 契约/状态机/权限矩阵/公式/ScopeSql 单元测试
	@./scripts/platform-unit.sh

platform-perf: ## AC-20 性能基线（r2 六项 + M6 质量四项，p50/p95 + EXPLAIN）
	@./scripts/platform-perf.sh

quality-verify: ## M6 质量域验收 q0..q7（PASS==TOTAL 守卫；先 make platform-up）
	@./scripts/quality-verify.sh

quality-e2e: ## M6 质量域浏览器 E2E（Playwright/Node；服务未起则 SKIP）
	@bash -c 'if ! curl -fsS -o /dev/null --max-time 3 "$${WEB_URL:-http://127.0.0.1:5173}/"; then \
	    echo "[SKIP] web 未启动（$${WEB_URL:-http://127.0.0.1:5173}/），跳过 quality-e2e：make platform-up 后再跑"; \
	    exit 0; fi; \
	  node web/e2e/quality.spec.mjs'

integration-verify: ## M7 集成对接验收 s0..s7（本地四合一桩 127.0.0.1:19090；PASS==TOTAL 守卫；先 make platform-up）
	@./scripts/integration-verify.sh

collab-verify: ## M8 协作域验收 s0..s8（本地 GitLab 桩 127.0.0.1:19091；九组各 ≥6、总计 ≥60 断言）
	@./scripts/collab-verify.sh

collab-perf: ## AC-20 五项 P95 性能基线（甘特 300 条 / Wiki 检索 / 页面树 / 价值流 / 列表首屏）
	@./scripts/collab-perf.sh

access-verify: ## 角色×页面准入：字典契约/隔离/5页×7角色矩阵/导航声明/菜单可见集（先 make platform-up）
	@./scripts/access-verify.sh

analytics-i18n-verify: ## 分析域中文化：三方一致 + 四页/导出 0 英文枚举码（先 make platform-up；--static-only 免栈）
	@./scripts/analytics-i18n-verify.sh

platform-demo-reset: ## 单事务重建演示数据（12 需求链路 + 4 条不变量自检；先 make platform-up && platform-seed）
	@./scripts/platform-demo-reset.sh

clean: ## 清理 Maven 产物与运行态 pid（请先 make dev-down；保留 .nacos 缓存）
	@bash -c 'source ./scripts/lib.sh; \
	  for app in api-gateway demo-service nacos req-service deliver-service; do \
	    if app_alive "$$app"; then die "$$app 仍在运行：请先 make dev-down / make platform-down，再 make clean"; fi; \
	  done; \
	  detect_maven_repo; \
	  ( cd "$$ROOT" && mvn -q $${MAVEN_REPO_ARG:-} clean ); \
	  rm -rf "$$RUN_DIR"; \
	  echo "clean 完成（.nacos 发行包保留，make distclean 可删除）"'

distclean: clean ## 额外删除 .nacos 发行包与运行数据
	@rm -rf .nacos
	@echo "distclean 完成：下次 make dev-up 会重新下载 Nacos"
