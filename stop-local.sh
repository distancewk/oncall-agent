#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_PORT="${APP_PORT:-9900}"
PID_FILE="${PID_FILE:-server.pid}"
GRACE_TIMEOUT="${GRACE_TIMEOUT:-30}"
COMPOSE_SERVICES=(etcd minio milvus redis postgres attu)

info() { printf '\033[0;34m%s\033[0m\n' "$*"; }
ok() { printf '\033[0;32m%s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m%s\033[0m\n' "$*"; }

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    return 127
  fi
}

is_running() {
  [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

stop_process() {
  local pid="$1"
  local elapsed=0
  kill "$pid" 2>/dev/null || true
  while is_running "$pid" && [ "$elapsed" -lt "$GRACE_TIMEOUT" ]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  if is_running "$pid"; then
    warn "进程 ${pid} 未优雅退出，发送 SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
  fi
}

stop_process_tree() {
  local pid="$1"
  local child
  while read -r child; do
    [ -n "$child" ] || continue
    stop_process_tree "$child"
  done < <(pgrep -P "$pid" 2>/dev/null || true)
  stop_process "$pid"
}

stop_application() {
  if [ ! -f "$PID_FILE" ]; then
    warn "未找到 ${PID_FILE}，跳过应用停止"
    return 0
  fi

  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if is_running "$pid"; then
    info "停止 Spring Boot 应用，PID: ${pid}"
    stop_process_tree "$pid"
    ok "应用已停止"
  else
    warn "PID ${pid:-空} 已不存在"
  fi
  rm -f "$PID_FILE"
}

stop_dependencies() {
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    warn "Docker daemon 未运行，跳过依赖容器停止"
    return 0
  fi
  info "停止依赖容器（保留数据卷）"
  compose_cmd stop "${COMPOSE_SERVICES[@]}"
  ok "依赖容器已停止"
}

case "${1:-all}" in
  app)
    stop_application
    ;;
  deps)
    stop_dependencies
    ;;
  all)
    stop_application
    stop_dependencies
    ;;
  *)
    cat <<'USAGE'
用法:
  ./stop-local.sh          停止应用和依赖容器（保留数据卷）
  ./stop-local.sh app      只停止应用
  ./stop-local.sh deps     只停止依赖容器
USAGE
    exit 1
    ;;
esac
