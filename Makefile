SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help dev-up dev-down verify build lint test test-negative nacos-status clean distclean

help: ## 列出所有命令
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

dev-up: ## 一条命令拉起 Nacos + demo-service + api-gateway，并打印 READY
	@./scripts/dev-up.sh

dev-down: ## 逆序停止全部进程，并校验六个端口已释放
	@./scripts/dev-down.sh

verify: ## 体检全链路，逐项 PASS/FAIL，红灯给出修复提示
	@./scripts/verify.sh

build: ## 构建父工程与两个模块（跳过测试）
	@./scripts/build.sh

lint: ## 静态门禁：脚本语法/插值/单点实现/错误提示/文档一致性（不启动服务）
	@./scripts/tests/lint.sh

test: ## 静态门禁 + 构建 + 负向用例（需先 make dev-up）
	@./scripts/tests/lint.sh
	@./scripts/build.sh
	@./scripts/tests/verify-negative.sh

test-negative: ## 仅运行负向用例：停服务 -> verify 必红 -> 恢复 -> 全绿
	@./scripts/tests/verify-negative.sh

nacos-status: ## 查看 Nacos 进程/端口/健康状态
	@./scripts/nacos.sh status

clean: ## 清理 Maven 产物与运行态 pid（请先 make dev-down；保留 .nacos 缓存）
	@bash -c 'source ./scripts/lib.sh; \
	  for app in api-gateway demo-service nacos; do \
	    if app_alive "$$app"; then die "$$app 仍在运行：请先 make dev-down，再 make clean"; fi; \
	  done; \
	  detect_maven_repo; \
	  ( cd "$$ROOT" && mvn -q $${MAVEN_REPO_ARG:-} clean ); \
	  rm -rf "$$RUN_DIR"; \
	  echo "clean 完成（.nacos 发行包保留，make distclean 可删除）"'

distclean: clean ## 额外删除 .nacos 发行包与运行数据
	@rm -rf .nacos
	@echo "distclean 完成：下次 make dev-up 会重新下载 Nacos"
