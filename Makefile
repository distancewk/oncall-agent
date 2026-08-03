# SuperBizAgent Makefile
# 用于自动化项目初始化和文档向量化

# 配置变量
SERVER_URL = http://localhost:9900
UPLOAD_API = $(SERVER_URL)/api/upload
UPLOAD_API_KEY ?= $(if $(APP_ADMIN_TOKEN),$(APP_ADMIN_TOKEN),$(APP_API_TOKEN))
DOCS_DIR = aiops-docs
HEALTH_CHECK_API = $(SERVER_URL)/milvus/health
DOCKER_COMPOSE_FILE = docker-compose.yml
MILVUS_CONTAINER = milvus-standalone
EVAL_SCENARIOS ?= eval/scenarios/smoke.json
EVAL_RESULTS ?= eval/results/smoke.jsonl
EVAL_METADATA ?= eval/metadata/smoke.json
EVAL_GATE_CONFIG ?= eval/gates/smoke.json
EVAL_OUTPUT ?= target/diagnosis-eval.json
EVAL_MARKDOWN_OUTPUT ?= target/diagnosis-eval.md
EVAL_LIVE_BASE_URL ?= http://localhost:9900
EVAL_LIVE_SCENARIOS ?=
EVAL_LIVE_RESULTS ?= target/diagnosis-live-results.jsonl
EVAL_LIVE_TIMEOUT_SECONDS ?= 900
EVAL_LIVE_POLL_SECONDS ?= 2

# 颜色输出
GREEN = \033[0;32m
YELLOW = \033[0;33m
RED = \033[0;31m
NC = \033[0m # No Color

.PHONY: help init start stop restart check upload eval eval-live eval-release eval-test vector-inventory vector-migration-plan clean up down status wait docker-build

# 默认目标：显示帮助信息
help:
	@echo "$(GREEN)SuperBizAgent Makefile$(NC)"
	@echo ""
	@echo "可用命令："
	@echo "  $(YELLOW)make init$(NC)    - 🚀 一键初始化（启动Docker → 启动服务 → 上传文档）"
	@echo "  $(YELLOW)make up$(NC)      - 启动 Docker Compose（应用 + Milvus 向量数据库）"
	@echo "  $(YELLOW)make down$(NC)    - 停止 Docker Compose"
	@echo "  $(YELLOW)make status$(NC)  - 查看 Docker 容器状态"
	@echo "  $(YELLOW)make docker-build$(NC) - 构建 Docker 镜像"
	@echo "  $(YELLOW)make start$(NC)   - 启动 Spring Boot 服务（后台运行）"
	@echo "  $(YELLOW)make stop$(NC)    - 停止 Spring Boot 服务"
	@echo "  $(YELLOW)make restart$(NC) - 重启 Spring Boot 服务"
	@echo "  $(YELLOW)make check$(NC)   - 检查服务器是否运行"
	@echo "  $(YELLOW)make upload$(NC)  - 上传 aiops-docs 目录下的所有文档"
	@echo "  $(YELLOW)make eval$(NC)    - 运行诊断离线评测 smoke 基线"
	@echo "  $(YELLOW)make eval-live EVAL_LIVE_SCENARIOS=...$(NC) - 从运行中的应用采集真实诊断结果"
	@echo "  $(YELLOW)make eval-release$(NC) - 使用真实数据集运行发布质量门禁"
	@echo "  $(YELLOW)make vector-inventory API_TOKEN=...$(NC) - 导出只读 Milvus 向量租户清单"
	@echo "  $(YELLOW)make vector-migration-plan INVENTORY=... MAPPING=...$(NC) - 生成不写 Milvus 的迁移计划"
	@echo "  $(YELLOW)make eval-test$(NC) - 运行评测契约单元测试"
	@echo "  $(YELLOW)make clean$(NC)   - 清理临时文件"
	@echo ""
	@echo "使用示例："
	@echo "  1. 一键初始化: make init"
	@echo "  2. 手动启动: make up && make start && make upload"
	@echo "  3. 停止服务: make stop && make down"

# 运行确定性的诊断评测契约，不调用外部模型
eval:
	@python3 tools/diagnosis_eval.py \
		--scenarios $(EVAL_SCENARIOS) \
		--results $(EVAL_RESULTS) \
		--metadata $(EVAL_METADATA) \
		--gate-config $(EVAL_GATE_CONFIG) \
		--output $(EVAL_OUTPUT) \
		--markdown-output $(EVAL_MARKDOWN_OUTPUT)

# 从运行中的应用采集真实诊断结果，不负责替代专家标注或发布门禁。
eval-live:
	@test -n "$(EVAL_LIVE_SCENARIOS)" || (echo "请设置 EVAL_LIVE_SCENARIOS=脱敏评测场景文件"; exit 2)
	@python3 tools/diagnosis_live_adapter.py \
		--base-url $(EVAL_LIVE_BASE_URL) \
		--scenarios $(EVAL_LIVE_SCENARIOS) \
		--output $(EVAL_LIVE_RESULTS) \
		--api-token "$${APP_API_TOKEN}" \
		--webhook-secret "$${APP_WEBHOOK_SIGNING_SECRET:-$${APP_WEBHOOK_SECRET}}" \
		--timeout-seconds $(EVAL_LIVE_TIMEOUT_SECONDS) \
		--poll-seconds $(EVAL_LIVE_POLL_SECONDS)

# 发布门禁必须使用已审批的脱敏真实事故集，不接受 smoke/fixture 元数据。
eval-release:
	@python3 tools/diagnosis_eval.py \
		--scenarios $(EVAL_SCENARIOS) \
		--results $(EVAL_RESULTS) \
		--metadata $(EVAL_METADATA) \
		--gate-config $(EVAL_GATE_CONFIG) \
		--output $(EVAL_OUTPUT) \
		--markdown-output $(EVAL_MARKDOWN_OUTPUT) \
		--require-approved-dataset

eval-test:
	@python3 -m unittest tools/test_diagnosis_eval.py tools/test_diagnosis_live_adapter.py tools/test_milvus_vector_migration_plan.py

vector-inventory:
	@test -n "$(API_TOKEN)" || (echo "请设置 API_TOKEN"; exit 2)
	@python3 tools/milvus_vector_inventory.py \
		--base-url $(SERVER_URL) \
		--api-token "$(API_TOKEN)" \
		--output target/milvus-vector-inventory.json

vector-migration-plan:
	@test -n "$(INVENTORY)" || (echo "请设置 INVENTORY"; exit 2)
	@test -n "$(MAPPING)" || (echo "请设置 MAPPING"; exit 2)
	@python3 tools/milvus_vector_migration_plan.py \
		--inventory "$(INVENTORY)" \
		--mapping "$(MAPPING)" \
		--output "$${OUTPUT:-target/milvus-vector-migration-plan.json}" \
		--batch-size "$${BATCH_SIZE:-100}" \
		--resume-from "$${RESUME_FROM:-0}" \
		$(if $(CHECKPOINT),--checkpoint "$(CHECKPOINT)")

# 一键初始化：启动Docker → 启动服务 → 检查服务 → 上传文档
init:
	@echo "$(GREEN)🚀 开始一键初始化 SuperBizAgent...$(NC)"
	@echo ""
	@echo "$(YELLOW)步骤 1/4: 启动 Docker Compose（Milvus 向量数据库）$(NC)"
	@$(MAKE) up
	@echo ""
	@echo "$(YELLOW)步骤 2/4: 启动 Spring Boot 服务$(NC)"
	@$(MAKE) start
	@echo ""
	@echo "$(YELLOW)步骤 3/4: 等待服务就绪$(NC)"
	@$(MAKE) wait
	@echo ""
	@echo "$(YELLOW)步骤 4/4: 上传 AIOps 文档到向量数据库$(NC)"
	@$(MAKE) upload
	@echo ""
	@echo "$(GREEN)═══════════════════════════════════════════════════════$(NC)"
	@echo "$(GREEN)✅ 初始化完成！所有文档已成功向量化存储到数据库$(NC)"
	@echo "$(GREEN)═══════════════════════════════════════════════════════$(NC)"
	@echo ""
	@echo "$(GREEN)🌐 服务访问地址:$(NC)"
	@echo "   API 服务: $(SERVER_URL)"
	@echo "   Attu (Web UI): http://localhost:8000"
	@echo ""
	@echo "$(YELLOW)💡 提示: 服务正在后台运行，查看日志: tail -f server.log$(NC)"

# 启动 Spring Boot 服务（后台运行）
start:
	@echo "$(YELLOW)🚀 启动 Spring Boot 服务...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "$(GREEN)✅ 服务已经在运行中 ($(SERVER_URL))$(NC)"; \
	else \
		echo "$(YELLOW)📦 正在启动服务（后台运行）...$(NC)"; \
		nohup mvn spring-boot:run > server.log 2>&1 & \
		echo $$! > server.pid; \
		echo "$(GREEN)✅ 服务启动命令已执行$(NC)"; \
		echo "$(YELLOW)   PID: $$(cat server.pid)$(NC)"; \
		echo "$(YELLOW)   日志文件: server.log$(NC)"; \
	fi

# 等待服务器就绪（最多等待 60 秒）
wait:
	@echo "$(YELLOW)⏳ 等待服务器就绪...$(NC)"
	@max_attempts=60; \
	attempt=0; \
	while [ $$attempt -lt $$max_attempts ]; do \
		if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
			echo "$(GREEN)✅ 服务器已就绪！($(SERVER_URL))$(NC)"; \
			exit 0; \
		fi; \
		attempt=$$((attempt + 1)); \
		printf "$(YELLOW)   等待中... [$$attempt/$$max_attempts]$(NC)\r"; \
		sleep 1; \
	done; \
	echo ""; \
	echo "$(RED)❌ 服务器启动超时！$(NC)"; \
	echo "$(YELLOW)请检查日志: tail -f server.log$(NC)"; \
	exit 1

# 检查服务器是否运行
check:
	@echo "$(YELLOW)🔍 检查服务器状态...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "$(GREEN)✅ 服务器运行正常 ($(SERVER_URL))$(NC)"; \
	else \
		echo "$(RED)❌ 服务器未运行或无法连接！$(NC)"; \
		echo "$(YELLOW)请先启动项目: mvn spring-boot:run$(NC)"; \
		exit 1; \
	fi

# 上传所有文档
upload:
	@echo "$(YELLOW)📤 开始上传 $(DOCS_DIR) 目录下的文档...$(NC)"
	@if [ ! -d "$(DOCS_DIR)" ]; then \
		echo "$(RED)❌ 目录 $(DOCS_DIR) 不存在！$(NC)"; \
		exit 1; \
	fi
	@count=0; \
	success=0; \
	failed=0; \
	for file in $(DOCS_DIR)/*.md; do \
		if [ -f "$$file" ]; then \
			count=$$((count + 1)); \
			filename=$$(basename "$$file"); \
			echo "$(YELLOW)  [$$count] 上传文件: $$filename$(NC)"; \
			response=$$(curl -s -w "\n%{http_code}" -X POST $(UPLOAD_API) \
				-F "file=@$$file" \
				-H "Accept: application/json" \
				-H "X-API-Key: $(UPLOAD_API_KEY)"); \
			http_code=$$(echo "$$response" | tail -n1); \
			body=$$(echo "$$response" | sed '$$d'); \
			if [ "$$http_code" = "200" ]; then \
				echo "$(GREEN)      ✅ 成功: $$filename$(NC)"; \
				success=$$((success + 1)); \
			else \
				echo "$(RED)      ❌ 失败: $$filename (HTTP $$http_code)$(NC)"; \
				echo "$$body" | head -n 3; \
				failed=$$((failed + 1)); \
			fi; \
			sleep 1; \
		fi; \
	done; \
	echo ""; \
	echo "$(GREEN)📊 上传统计:$(NC)"; \
	echo "   总计: $$count 个文件"; \
	echo "   $(GREEN)成功: $$success$(NC)"; \
	if [ $$failed -gt 0 ]; then \
		echo "   $(RED)失败: $$failed$(NC)"; \
	fi

# 停止 Spring Boot 服务
stop:
	@echo "$(YELLOW)🛑 停止 Spring Boot 服务...$(NC)"
	@if [ -f server.pid ]; then \
		pid=$$(cat server.pid); \
		if ps -p $$pid > /dev/null 2>&1; then \
			kill $$pid; \
			echo "$(GREEN)✅ 服务已停止 (PID: $$pid)$(NC)"; \
		else \
			echo "$(YELLOW)⚠️  进程不存在 (PID: $$pid)$(NC)"; \
		fi; \
		rm -f server.pid; \
	else \
		echo "$(YELLOW)⚠️  未找到 server.pid 文件$(NC)"; \
		pkill -f "spring-boot:run" && echo "$(GREEN)✅ 已停止所有 spring-boot 进程$(NC)" || echo "$(YELLOW)⚠️  没有运行中的 spring-boot 进程$(NC)"; \
	fi

# 重启 Spring Boot 服务
restart:
	@echo "$(YELLOW)🔄 重启 Spring Boot 服务...$(NC)"
	@echo ""
	@echo "$(YELLOW)步骤 1/2: 停止服务$(NC)"
	@$(MAKE) stop
	@echo ""
	@echo "$(YELLOW)步骤 2/2: 启动服务$(NC)"
	@$(MAKE) start
	@echo ""
	@$(MAKE) wait
	@echo ""
	@echo "$(GREEN)✅ 服务重启完成！$(NC)"

# 清理临时文件
clean:
	@echo "$(YELLOW)🧹 清理临时文件...$(NC)"
	@rm -rf uploads/*.tmp
	@rm -f server.pid server.log
	@echo "$(GREEN)✅ 清理完成$(NC)"

# 显示文档列表
list-docs:
	@echo "$(YELLOW)📚 $(DOCS_DIR) 目录下的文档:$(NC)"
	@if [ -d "$(DOCS_DIR)" ]; then \
		ls -lh $(DOCS_DIR)/*.md 2>/dev/null || echo "$(RED)没有找到 .md 文件$(NC)"; \
	else \
		echo "$(RED)目录 $(DOCS_DIR) 不存在$(NC)"; \
	fi

# 测试单个文件上传
test-upload:
	@echo "$(YELLOW)🧪 测试上传单个文件...$(NC)"
	@if [ -f "$(DOCS_DIR)/cpu_high_usage.md" ]; then \
		curl -X POST $(UPLOAD_API) \
			-F "file=@$(DOCS_DIR)/cpu_high_usage.md" \
			-H "Accept: application/json" \
			-H "X-API-Key: $(UPLOAD_API_KEY)" | jq .; \
	else \
		echo "$(RED)测试文件不存在$(NC)"; \
	fi

# 启动 Docker Compose（智能检测，避免重复启动）
up:
	@echo "$(YELLOW)🐳 检查 Docker 容器状态...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)❌ Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)$(NC)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
		echo "$(GREEN)✅ Milvus 容器已经在运行中$(NC)"; \
		echo "$(YELLOW)📋 当前运行的容器:$(NC)"; \
		docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
	else \
		echo "$(YELLOW)🚀 启动 Docker Compose...$(NC)"; \
		docker-compose -f $(DOCKER_COMPOSE_FILE) up -d; \
		echo ""; \
		echo "$(YELLOW)⏳ 等待容器启动...$(NC)"; \
		sleep 5; \
		if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
			echo "$(GREEN)✅ Docker Compose 启动成功！$(NC)"; \
			echo ""; \
			echo "$(GREEN)📋 运行中的容器:$(NC)"; \
			docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
			echo ""; \
			echo "$(GREEN)🌐 服务访问地址:$(NC)"; \
			echo "   Milvus: localhost:19530"; \
			echo "   Attu (Web UI): http://localhost:8000"; \
			echo "   MinIO: http://localhost:9001 (admin/minioadmin)"; \
		else \
			echo "$(RED)❌ 容器启动失败，请检查日志: docker-compose -f $(DOCKER_COMPOSE_FILE) logs$(NC)"; \
			exit 1; \
		fi; \
	fi

# 停止 Docker Compose
down:
	@echo "$(YELLOW)🛑 停止 Docker Compose...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)❌ Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)$(NC)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "milvus"; then \
		docker-compose -f $(DOCKER_COMPOSE_FILE) down; \
		echo "$(GREEN)✅ Docker Compose 已停止$(NC)"; \
	else \
		echo "$(YELLOW)⚠️  没有运行中的 Milvus 容器$(NC)"; \
	fi

# 查看 Docker 容器状态
status:
	@echo "$(YELLOW)📊 Docker 容器状态:$(NC)"
	@echo ""
	@if docker ps -a --format '{{.Names}}' | grep -q "milvus"; then \
		docker ps -a --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
		echo ""; \
		running=$$(docker ps --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		total=$$(docker ps -a --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		echo "$(GREEN)运行中: $$running / $$total$(NC)"; \
	else \
		echo "$(YELLOW)⚠️  没有找到 Milvus 相关容器$(NC)"; \
		echo "$(YELLOW)提示: 运行 'make up' 启动容器$(NC)"; \
	fi

# 构建 Docker 镜像
docker-build:
	@echo "$(YELLOW)🔨 构建 Docker 镜像...$(NC)"
	@docker build -t super-biz-agent:latest .
	@echo "$(GREEN)✅ Docker 镜像构建完成: super-biz-agent:latest$(NC)"
