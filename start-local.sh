#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_PORT="${APP_PORT:-9900}"
APP_URL="http://localhost:${APP_PORT}"
HEALTH_URL="${APP_URL}/milvus/health"
ATTU_URL="${ATTU_URL:-http://localhost:8000}"
LOG_FILE="${LOG_FILE:-server.log}"
PID_FILE="${PID_FILE:-server.pid}"
DOCKER_WAIT_SECONDS="${DOCKER_WAIT_SECONDS:-90}"
COMPOSE_BASE_SERVICES=(etcd minio milvus redis attu)
APP_INCIDENT_JDBC_URL_WAS_SET="${APP_INCIDENT_JDBC_URL+x}"

info() {
  printf '\033[0;34m%s\033[0m\n' "$*"
}

ok() {
  printf '\033[0;32m%s\033[0m\n' "$*"
}

warn() {
  printf '\033[0;33m%s\033[0m\n' "$*"
}

fail() {
  printf '\033[0;31m%s\033[0m\n' "$*" >&2
  exit 1
}

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    fail "未找到 docker compose 或 docker-compose"
  fi
}

wait_docker_daemon() {
  require_cmd docker
  if docker info >/dev/null 2>&1; then
    return 0
  fi

  warn "Docker daemon 未就绪，尝试等待 Docker Desktop 启动"
  if command -v open >/dev/null 2>&1; then
    open -a Docker >/dev/null 2>&1 || true
  fi

  local elapsed=0
  while [ "$elapsed" -lt "$DOCKER_WAIT_SECONDS" ]; do
    if docker info >/dev/null 2>&1; then
      ok "Docker daemon 已就绪"
      return 0
    fi
    printf '  等待 Docker daemon... [%ss/%ss]\r' "$elapsed" "$DOCKER_WAIT_SECONDS"
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf '\n'
  fail "Docker daemon 未就绪，请先启动 Docker Desktop 后重试"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "未找到命令: $1"
}

container_health() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || true
}

wait_container() {
  local name="$1"
  local timeout="${2:-180}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local status
    status="$(container_health "$name")"
    if [ "$status" = "healthy" ] || [ "$status" = "running" ]; then
      ok "  ${name}: ${status}"
      return 0
    fi
    printf '  等待 %s 就绪... [%ss/%ss]\r' "$name" "$elapsed" "$timeout"
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf '\n'
  fail "容器 ${name} 未在 ${timeout}s 内就绪"
}

wait_http() {
  local url="$1"
  local timeout="${2:-180}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      ok "应用已就绪: ${APP_URL}"
      return 0
    fi
    if [ -f "$PID_FILE" ]; then
      local pid
      pid="$(cat "$PID_FILE" 2>/dev/null || true)"
      if [ -n "$pid" ] && ! is_pid_running "$pid"; then
        fail "应用进程 ${pid} 已退出，请查看 ${LOG_FILE}"
      fi
    fi
    printf '  等待应用启动... [%ss/%ss]\r' "$elapsed" "$timeout"
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf '\n'
  fail "应用未在 ${timeout}s 内就绪，请查看 ${LOG_FILE}"
}

is_pid_running() {
  local pid="$1"
  [ -n "$pid" ] && ps -p "$pid" >/dev/null 2>&1
}

set_default_env() {
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
  export PROMETHEUS_MOCK_ENABLED="${PROMETHEUS_MOCK_ENABLED:-true}"
  export CLS_MOCK_ENABLED="${CLS_MOCK_ENABLED:-true}"
  export APP_ALERT_SIMULATE_ENABLED="${APP_ALERT_SIMULATE_ENABLED:-true}"
  export MILVUS_HOST="${MILVUS_HOST:-localhost}"
  export MILVUS_PORT="${MILVUS_PORT:-19530}"
  export REDIS_HOST="${REDIS_HOST:-localhost}"
  export REDIS_PORT="${REDIS_PORT:-6379}"
  export APP_INCIDENT_JDBC_URL="${APP_INCIDENT_JDBC_URL:-jdbc:postgresql://localhost:5432/superbizagent}"
  export APP_INCIDENT_JDBC_USERNAME="${APP_INCIDENT_JDBC_USERNAME:-superbizagent}"
  export APP_INCIDENT_JDBC_PASSWORD="${APP_INCIDENT_JDBC_PASSWORD:-${POSTGRES_PASSWORD:-superbizagent}}"
  export POSTGRES_DB="${POSTGRES_DB:-$(incident_db_name)}"
  export POSTGRES_USER="${POSTGRES_USER:-$APP_INCIDENT_JDBC_USERNAME}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$APP_INCIDENT_JDBC_PASSWORD}"
  export MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
  export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"
  export APP_SECURITY_ENABLED="${APP_SECURITY_ENABLED:-false}"
  export APP_API_TOKEN="${APP_API_TOKEN:-local-dev-api-token}"
  export APP_WEBHOOK_SECRET="${APP_WEBHOOK_SECRET:-local-dev-webhook-secret}"
  export APP_WEBHOOK_SIGNING_SECRET="${APP_WEBHOOK_SIGNING_SECRET:-local-dev-webhook-signing-secret}"
  export APP_SECURITY_SESSION_SIGNING_SECRET="${APP_SECURITY_SESSION_SIGNING_SECRET:-local-dev-session-signing-secret}"
}

incident_db_name() {
  local url="${APP_INCIDENT_JDBC_URL#jdbc:postgresql://}"
  url="${url%%\?*}"
  printf '%s\n' "${url##*/}"
}

incident_db_host() {
  local url="${APP_INCIDENT_JDBC_URL#jdbc:postgresql://}"
  url="${url%%/*}"
  printf '%s\n' "${url%%:*}"
}

incident_db_port() {
  local url="${APP_INCIDENT_JDBC_URL#jdbc:postgresql://}"
  local host_port="${url%%/*}"
  if [ "$host_port" = "${host_port%:*}" ]; then
    printf '5432\n'
  else
    printf '%s\n' "${host_port##*:}"
  fi
}

local_postgres_ready() {
  command -v psql >/dev/null 2>&1 || return 1
  PGPASSWORD="$APP_INCIDENT_JDBC_PASSWORD" psql \
    -h "$(incident_db_host)" \
    -p "$(incident_db_port)" \
    -U "$APP_INCIDENT_JDBC_USERNAME" \
    -d "$(incident_db_name)" \
    -c 'select 1' >/dev/null 2>&1
}

port_in_use() {
  lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1
}

postgres_port_in_use() {
  lsof -nP -iTCP:"$(incident_db_port)" -sTCP:LISTEN >/dev/null 2>&1
}

use_docker_postgres() {
  if [ -z "$APP_INCIDENT_JDBC_URL_WAS_SET" ]; then
    export POSTGRES_PORT="${POSTGRES_PORT:-5433}"
    export APP_INCIDENT_JDBC_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}"
  else
    export POSTGRES_PORT="${POSTGRES_PORT:-$(incident_db_port)}"
  fi
}

start_deps() {
  wait_docker_daemon
  set_default_env

  local services=("${COMPOSE_BASE_SERVICES[@]}")
  if local_postgres_ready; then
    ok "检测到本机 PostgreSQL 可用，跳过 Docker postgres"
  elif [ "$(incident_db_host)" = "localhost" ] || [ "$(incident_db_host)" = "127.0.0.1" ]; then
    if [ -n "$APP_INCIDENT_JDBC_URL_WAS_SET" ] && postgres_port_in_use; then
      fail "localhost:$(incident_db_port) 已有 PostgreSQL 监听，但当前账号/密码不可用；请修正 APP_INCIDENT_JDBC_* 或停掉该 PostgreSQL 后重试"
    fi
    use_docker_postgres
    if postgres_port_in_use; then
      fail "Docker postgres 需要映射的 localhost:$(incident_db_port) 已被占用；请设置 POSTGRES_PORT 后重试"
    fi
    warn "本机 PostgreSQL 不可用，将启动 Docker postgres 并映射到 localhost:$(incident_db_port)"
    services+=(postgres)
  else
    fail "配置的 PostgreSQL 不可用: $(incident_db_host):$(incident_db_port)/$(incident_db_name)"
  fi

  info "启动本地依赖容器: ${services[*]}"
  compose_cmd up -d "${services[@]}"

  info "等待依赖容器就绪"
  wait_container milvus-etcd 120
  wait_container milvus-minio 120
  wait_container milvus-standalone 240
  wait_container session-redis 120
  if [[ " ${services[*]} " == *" postgres "* ]]; then
    wait_container superbiz-postgres 120
  fi
}

stop_deps() {
  wait_docker_daemon
  set_default_env
  info "停止本地依赖容器"
  compose_cmd stop "${COMPOSE_BASE_SERVICES[@]}" postgres
}

start_app() {
  require_cmd mvn
  require_cmd curl

  if [ -f "$PID_FILE" ]; then
    local old_pid
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if is_pid_running "$old_pid"; then
      ok "应用已经在运行，PID: ${old_pid}"
      return 0
    fi
    warn "清理过期 PID 文件: ${PID_FILE}"
    rm -f "$PID_FILE"
  fi

  if port_in_use; then
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
      ok "端口 ${APP_PORT} 已有当前应用在运行: ${APP_URL}"
      return 0
    fi
    fail "端口 ${APP_PORT} 已被占用，但健康检查未通过。请先释放该端口。"
  fi

  set_default_env

  if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
    warn "DASHSCOPE_API_KEY 未设置：应用可启动，但聊天、向量化和 AI 诊断会在调用模型时失败。"
    export DASHSCOPE_API_KEY="local-dev-placeholder"
  fi

  info "后台启动 Spring Boot 应用"
  : > "$LOG_FILE"
  nohup mvn spring-boot:run > "$LOG_FILE" 2>&1 &
  echo "$!" > "$PID_FILE"
  ok "启动命令已执行，PID: $(cat "$PID_FILE")"
  wait_http "$HEALTH_URL" 240
}

run_app_foreground() {
  require_cmd mvn
  set_default_env

  if port_in_use; then
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
      ok "端口 ${APP_PORT} 已有当前应用在运行: ${APP_URL}"
      return 0
    fi
    fail "端口 ${APP_PORT} 已被占用，但健康检查未通过。请先释放该端口。"
  fi

  if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
    warn "DASHSCOPE_API_KEY 未设置：应用可启动，但聊天、向量化和 AI 诊断会在调用模型时失败。"
    export DASHSCOPE_API_KEY="local-dev-placeholder"
  fi

  info "以前台方式运行 Spring Boot 应用，按 Ctrl+C 停止"
  exec mvn spring-boot:run
}

stop_app() {
  if [ ! -f "$PID_FILE" ]; then
    warn "未找到 ${PID_FILE}，跳过应用停止"
    return 0
  fi

  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if is_pid_running "$pid"; then
    info "停止应用进程: ${pid}"
    kill "$pid"
    local elapsed=0
    while is_pid_running "$pid" && [ "$elapsed" -lt 30 ]; do
      sleep 1
      elapsed=$((elapsed + 1))
    done
    if is_pid_running "$pid"; then
      fail "应用进程 ${pid} 未能正常停止"
    fi
    ok "应用已停止"
  else
    warn "PID ${pid:-空} 不存在，清理 PID 文件"
  fi
  rm -f "$PID_FILE"
}

status() {
  set_default_env
  info "应用状态"
  if [ -f "$PID_FILE" ] && is_pid_running "$(cat "$PID_FILE" 2>/dev/null || true)"; then
    ok "  Spring Boot: running, PID $(cat "$PID_FILE")"
  elif curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    ok "  Spring Boot: running, ${APP_URL}"
  else
    warn "  Spring Boot: not ready"
  fi
  info "容器状态"
  compose_cmd ps
}

health() {
  require_cmd curl
  info "应用健康检查"
  curl -fsS "$HEALTH_URL"
  printf '\n'
}

doctor() {
  info "本地环境检查"
  require_cmd mvn
  require_cmd curl
  wait_docker_daemon
  set_default_env

  if local_postgres_ready; then
    ok "  PostgreSQL: local ready ($(incident_db_host):$(incident_db_port)/$(incident_db_name))"
  else
    warn "  PostgreSQL: local not ready，将由 Docker postgres 兜底"
  fi
  ok "  Maven: $(mvn -v | head -n 1)"
  ok "  Docker: $(docker version --format '{{.Server.Version}}')"
}

open_ui() {
  if command -v open >/dev/null 2>&1; then
    open "$APP_URL"
  else
    printf 'Web UI: %s\n' "$APP_URL"
  fi
}

clean_local_files() {
  info "清理本地运行文件"
  rm -f "$PID_FILE" "$LOG_FILE"
  ok "已清理 ${PID_FILE} 和 ${LOG_FILE}"
}

case "${1:-start}" in
  start)
    start_deps
    start_app
    ok "启动完成"
    printf 'Web UI: %s\n' "$APP_URL"
    printf 'Attu:   %s\n' "$ATTU_URL"
    printf '日志:   tail -f %s\n' "$LOG_FILE"
    ;;
  stop)
    stop_app
    ;;
  deps-stop)
    stop_deps
    ;;
  restart)
    stop_app
    start_deps
    start_app
    ;;
  run)
    start_deps
    run_app_foreground
    ;;
  status)
    status
    ;;
  health)
    health
    ;;
  doctor)
    doctor
    ;;
  open)
    open_ui
    ;;
  clean)
    clean_local_files
    ;;
  logs)
    tail -f "$LOG_FILE"
    ;;
  *)
    cat <<USAGE
用法:
  ./start-local.sh          一键启动依赖容器和本地应用
  ./start-local.sh start    同上
  ./start-local.sh stop     停止本脚本启动的 Spring Boot 应用
  ./start-local.sh deps-stop 停止本地依赖容器
  ./start-local.sh restart  重启本地应用
  ./start-local.sh run      前台运行应用，适合需要保持终端占用的场景
  ./start-local.sh status   查看应用和容器状态
  ./start-local.sh health   调用应用健康检查
  ./start-local.sh doctor   检查本地命令、Docker、PostgreSQL
  ./start-local.sh open     打开 Web UI
  ./start-local.sh clean    清理本地日志和 PID 文件
  ./start-local.sh logs     跟踪应用日志
USAGE
    exit 1
    ;;
esac
