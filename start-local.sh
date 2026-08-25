#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_PORT="${APP_PORT:-9900}"
APP_URL="http://127.0.0.1:${APP_PORT}"
HEALTH_URL="${APP_URL}/milvus/health"
PID_FILE="${PID_FILE:-server.pid}"
LOG_FILE="${LOG_FILE:-server.log}"
DOCKER_WAIT_SECONDS="${DOCKER_WAIT_SECONDS:-60}"
APP_WAIT_SECONDS="${APP_WAIT_SECONDS:-240}"
COMPOSE_SERVICES=(etcd minio milvus redis postgres attu)

info() { printf '\033[0;34m%s\033[0m\n' "$*"; }
ok() { printf '\033[0;32m%s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m%s\033[0m\n' "$*"; }
fail() { printf '\033[0;31m%s\033[0m\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "未找到命令: $1"
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

load_env() {
  local inherited_dashscope_api_key="${DASHSCOPE_API_KEY-}"
  local dashscope_api_key_was_set="${DASHSCOPE_API_KEY+x}"

  # .env is optional. It is intentionally not created or modified by this script.
  if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
  fi

  # The API key must come from the caller's environment, never from this script
  # or a local .env file. Preserve an explicitly exported value across loading.
  if [ "$dashscope_api_key_was_set" = x ]; then
    export DASHSCOPE_API_KEY="$inherited_dashscope_api_key"
  else
    unset DASHSCOPE_API_KEY
  fi

  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
  export PROMETHEUS_MOCK_ENABLED="${PROMETHEUS_MOCK_ENABLED:-true}"
  export CLS_MOCK_ENABLED="${CLS_MOCK_ENABLED:-true}"
  export APP_ALERT_SIMULATE_ENABLED="${APP_ALERT_SIMULATE_ENABLED:-true}"
  export APP_SECURITY_ENABLED="${APP_SECURITY_ENABLED:-false}"
  export APP_API_TOKEN="${APP_API_TOKEN:-local-dev-api-token}"
  export APP_WEBHOOK_SECRET="${APP_WEBHOOK_SECRET:-local-dev-webhook-secret}"
  export MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
  export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"
  export POSTGRES_DB="${POSTGRES_DB:-superbizagent_v5}"
  export POSTGRES_USER="${POSTGRES_USER:-superbizagent}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-local-dev-postgres-password}"
  export POSTGRES_PORT="${POSTGRES_PORT:-5433}"
  export MILVUS_HOST="${MILVUS_HOST:-127.0.0.1}"
  export MILVUS_PORT="${MILVUS_PORT:-19530}"
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export REDIS_PORT="${REDIS_PORT:-6379}"
  export APP_INCIDENT_JDBC_URL="${APP_INCIDENT_JDBC_URL:-jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}}"
  export APP_INCIDENT_JDBC_USERNAME="${APP_INCIDENT_JDBC_USERNAME:-$POSTGRES_USER}"
  export APP_INCIDENT_JDBC_PASSWORD="${APP_INCIDENT_JDBC_PASSWORD:-$POSTGRES_PASSWORD}"
}

is_running() {
  [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

container_state() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || true
}

wait_docker() {
  require_cmd docker
  if docker info >/dev/null 2>&1; then
    return 0
  fi

  warn "Docker daemon 未就绪，等待 ${DOCKER_WAIT_SECONDS}s"
  local elapsed=0
  while [ "$elapsed" -lt "$DOCKER_WAIT_SECONDS" ]; do
    if docker info >/dev/null 2>&1; then
      ok "Docker daemon 已就绪"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  fail "Docker daemon 未就绪，请先启动 Docker Desktop"
}

wait_container() {
  local name="$1"
  local timeout="${2:-180}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local state
    state="$(container_state "$name")"
    # All dependencies waited on here define healthchecks in docker-compose.yml.
    if [ "$state" = "healthy" ]; then
      ok "${name}: ${state}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  compose_cmd logs --tail=80 "$name" || true
  fail "容器 ${name} 未在 ${timeout}s 内就绪"
}

port_in_use() {
  command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1
}

start_dependencies() {
  wait_docker
  load_env
  info "启动当前版本依赖容器: ${COMPOSE_SERVICES[*]}"
  compose_cmd up -d "${COMPOSE_SERVICES[@]}"
  wait_container milvus-etcd 120
  wait_container milvus-minio 120
  wait_container milvus-standalone 240
  wait_container session-redis 120
  wait_container superbiz-postgres 120
  ok "依赖容器已就绪"
}

wait_application() {
  local elapsed=0
  while [ "$elapsed" -lt "$APP_WAIT_SECONDS" ]; do
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
      ok "应用已就绪: ${APP_URL}"
      return 0
    fi
    if [ -f "$PID_FILE" ] && ! is_running "$(cat "$PID_FILE" 2>/dev/null || true)"; then
      fail "应用进程已退出，请查看 ${LOG_FILE}"
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  tail -n 80 "$LOG_FILE" 2>/dev/null || true
  fail "应用未在 ${APP_WAIT_SECONDS}s 内就绪"
}

start_application() {
  require_cmd mvn
  require_cmd curl
  load_env

  [ -n "${DASHSCOPE_API_KEY:-}" ] || fail "未检测到 DASHSCOPE_API_KEY，请先在当前终端环境变量中设置"

  if [ -f "$PID_FILE" ] && is_running "$(cat "$PID_FILE" 2>/dev/null || true)"; then
    ok "应用已经运行，PID: $(cat "$PID_FILE")"
    return 0
  fi
  rm -f "$PID_FILE"

  if port_in_use; then
    fail "端口 ${APP_PORT} 已被占用，请设置 APP_PORT 或先停止占用进程"
  fi

  info "后台启动 Spring Boot 应用"
  : > "$LOG_FILE"
  nohup mvn -B -ntp clean spring-boot:run </dev/null >"$LOG_FILE" 2>&1 &
  echo "$!" > "$PID_FILE"
  wait_application
}

status() {
  load_env
  if [ -f "$PID_FILE" ] && is_running "$(cat "$PID_FILE" 2>/dev/null || true)"; then
    ok "应用运行中，PID: $(cat "$PID_FILE")"
  else
    warn "应用未通过 PID 文件运行"
  fi
  if docker info >/dev/null 2>&1; then
    compose_cmd ps
  else
    warn "Docker daemon 未运行"
  fi
}

case "${1:-start}" in
  start)
    start_dependencies
    start_application
    printf 'Web UI: %s\n日志: tail -f %s\n' "$APP_URL" "$LOG_FILE"
    ;;
  deps)
    start_dependencies
    ;;
  app)
    start_application
    ;;
  status)
    status
    ;;
  logs)
    exec tail -f "$LOG_FILE"
    ;;
  *)
    cat <<'USAGE'
用法:
  ./start-local.sh          启动依赖容器和当前版本应用
  ./start-local.sh deps     只启动依赖容器
  ./start-local.sh app      只启动本机 Maven 应用
  ./start-local.sh status   查看应用和容器状态
  ./start-local.sh logs     跟踪应用日志

默认应用地址: http://127.0.0.1:9900
默认 PostgreSQL: 127.0.0.1:5433
USAGE
    exit 1
    ;;
esac
