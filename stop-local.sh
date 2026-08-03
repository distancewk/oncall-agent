#!/usr/bin/env bash
#
# stop-local.sh — SuperBizAgent 本地停止脚本
#
# 与 start-local.sh 对应：停止本脚本启动的 Spring Boot 应用，
# 并可选择性停止本地依赖容器（etcd / minio / milvus / redis / postgres）。
#
# 用法:
#   ./stop-local.sh          停止应用 + 依赖容器（完整停止）
#   ./stop-local.sh app      只停止 Spring Boot 应用（保留依赖容器）
#   ./stop-local.sh deps     只停止依赖容器（保留应用）
#   ./stop-local.sh force    强制停止（不等优雅退出，直接 SIGKILL）
#
# 说明:
#   - 应用停止优先使用 server.pid；若 PID 文件缺失或进程已退出，
#     但端口仍被占用，会按端口兜底查找并停止占用进程。
#   - 优雅停止超时（默认 30s）后会发送 SIGKILL。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_PORT="${APP_PORT:-9900}"
PID_FILE="${PID_FILE:-server.pid}"
LOG_FILE="${LOG_FILE:-server.log}"
COMPOSE_BASE_SERVICES=(etcd minio milvus redis attu)
GRACE_TIMEOUT="${GRACE_TIMEOUT:-30}"
FORCE="${FORCE:-false}"

info()  { printf '\033[0;34m%s\033[0m\n' "$*"; }
ok()    { printf '\033[0;32m%s\033[0m\n' "$*"; }
warn()  { printf '\033[0;33m%s\033[0m\n' "$*"; }
fail()  { printf '\033[0;31m%s\033[0m\n' "$*" >&2; exit 1; }

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    fail "未找到 docker compose 或 docker-compose"
  fi
}

is_pid_running() {
  local pid="$1"
  [ -n "$pid" ] && ps -p "$pid" >/dev/null 2>&1
}

pid_on_port() {
  command -v lsof >/dev/null 2>&1 || return 0
  lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $2}' | head -n1
}

# 停止单个进程：优雅 kill，超时则 SIGKILL
kill_pid() {
  local pid="$1"
  local label="${2:-进程}"
  kill "$pid" 2>/dev/null || return 0
  local elapsed=0
  while is_pid_running "$pid" && [ "$elapsed" -lt "$GRACE_TIMEOUT" ]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  if is_pid_running "$pid"; then
    warn "${label} ${pid} 优雅停止超时，发送 SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
    sleep 1
  fi
}

stop_app() {
  local stopped=false

  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && is_pid_running "$pid"; then
      info "停止 Spring Boot 应用 (PID: $pid)"
      if [ "$FORCE" = "true" ]; then
        kill -9 "$pid" 2>/dev/null || true
      else
        kill_pid "$pid" "Spring Boot 应用"
      fi
      stopped=true
    else
      warn "PID 文件中的进程 ${pid:-空} 已不存在，清理 PID 文件"
    fi
    rm -f "$PID_FILE"
  fi

  # 端口兜底：PID 文件缺失/进程已退出，但端口仍被占用
  local port_pid
  port_pid="$(pid_on_port || true)"
  if [ -n "$port_pid" ] && is_pid_running "$port_pid"; then
    info "端口 $APP_PORT 仍被进程 $port_pid 占用，停止该进程"
    if [ "$FORCE" = "true" ]; then
      kill -9 "$port_pid" 2>/dev/null || true
    else
      kill_pid "$port_pid" "端口占用进程"
    fi
    stopped=true
  fi

  if [ "$stopped" = "true" ]; then
    ok "Spring Boot 应用已停止"
  else
    ok "没有运行中的 Spring Boot 应用"
  fi
}

stop_deps() {
  if ! docker info >/dev/null 2>&1; then
    warn "Docker daemon 未运行，跳过依赖容器停止"
    return 0
  fi
  info "停止本地依赖容器"
  compose_cmd stop "${COMPOSE_BASE_SERVICES[@]}" postgres || true
  ok "依赖容器已停止"
}

case "${1:-all}" in
  app)    stop_app ;;
  deps)   stop_deps ;;
  force)  FORCE=true; stop_app; stop_deps ;;
  all|"") stop_app; stop_deps ;;
  *)
    cat <<USAGE
用法:
  ./stop-local.sh          停止 Spring Boot 应用 + 依赖容器
  ./stop-local.sh app      只停止应用（保留 etcd/minio/milvus/redis/postgres）
  ./stop-local.sh deps     只停止依赖容器（保留应用）
  ./stop-local.sh force    强制停止（不等优雅退出，直接 SIGKILL）

环境变量:
  APP_PORT       应用端口（默认 9900）
  PID_FILE       PID 文件（默认 server.pid）
  GRACE_TIMEOUT  优雅停止等待秒数（默认 30）
  FORCE=true     等价于 force 子命令
USAGE
    exit 1
    ;;
esac
