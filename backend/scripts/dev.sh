#!/usr/bin/env bash
# RAG-Wiki 本地中间件管理脚本（bash）
# 用法：bash scripts/dev.sh <命令>，命令列表见 usage
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# 载入 .env，保证探活用的账号密码与容器启动时一致
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-13306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-rag_wiki}"
MYSQL_USER="${MYSQL_USER:-rag_wiki}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rag_wiki}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root123456}"
REDIS_PASSWORD="${REDIS_PASSWORD:-redis123456}"
ES_URIS="${ES_URIS:-http://localhost:19200}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-rag-wiki-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-rag-wiki-redis}"
ES_CONTAINER="${ES_CONTAINER:-rag-wiki-es}"

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"
HEALTH_INTERVAL=5

usage() {
  cat <<'EOF'
用法：bash scripts/dev.sh <命令>

命令：
  up        启动全部中间件，三个服务全部 healthy 后才返回
  down      停止全部中间件（保留数据卷）
  restart   重启全部中间件
  status    查看容器状态
  health    逐项探活 MySQL / Redis / ES
  logs      跟踪日志（Ctrl-C 退出）
  reset     停止并删除数据卷（需二次确认，数据不可恢复）
EOF
}

compose() {
  docker compose -f "$ROOT/docker-compose.yml" "$@"
}

container_health() {
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo "missing"
}

cmd_up() {
  echo "启动中间件 ..."
  compose up -d
  wait_healthy
}

wait_healthy() {
  local elapsed=0
  local detail=""
  while [ "$elapsed" -lt "$HEALTH_TIMEOUT" ]; do
    local all_ok=1
    detail=""
    for name in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ES_CONTAINER"; do
      local state
      state="$(container_health "$name")"
      detail="${detail}  ${name}=${state}"
      [ "$state" = "healthy" ] || all_ok=0
    done
    if [ "$all_ok" -eq 1 ]; then
      echo "全部 healthy（约 ${elapsed}s）："
      echo "$detail"
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
    elapsed=$((elapsed + HEALTH_INTERVAL))
  done
  echo "等待超时（${HEALTH_TIMEOUT}s），当前状态：${detail}" >&2
  echo "排查：bash scripts/dev.sh logs" >&2
  return 1
}

cmd_down() {
  compose down
}

cmd_restart() {
  compose restart
  wait_healthy
}

cmd_status() {
  compose ps
}

cmd_logs() {
  compose logs -f --tail=100
}

cmd_health() {
  local rc=0

  echo "== MySQL =="
  # 密码走 MYSQL_PWD 而不是命令行，避免 mysqladmin 的安全警告污染输出
  if ! docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
      mysqladmin ping -h 127.0.0.1 -u root --silent >/dev/null 2>&1; then
    echo "[FAIL] MySQL 探活失败，容器 ${MYSQL_CONTAINER} 是否在运行？"
    rc=1
  else
    local version charset tz dbexists userok
    version="$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -u root -N -B -e 'SELECT VERSION()' 2>/dev/null)"
    charset="$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -u root -N -B -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='${MYSQL_DATABASE}'" 2>/dev/null)"
    tz="$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -u root -N -B -e 'SELECT @@global.time_zone' 2>/dev/null)"
    dbexists="$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -u root -N -B -e "SHOW DATABASES LIKE '${MYSQL_DATABASE}'" 2>/dev/null)"
    userok="$(docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" mysql -u "$MYSQL_USER" -N -B -e 'SELECT 1' "$MYSQL_DATABASE" 2>/dev/null)"
    echo "[OK]   版本 ${version}"
    echo "[OK]   字符集/排序规则 ${charset}"
    echo "[OK]   时区 ${tz}"
    if [ -n "$dbexists" ]; then echo "[OK]   库 ${MYSQL_DATABASE} 存在"; else echo "[FAIL] 库 ${MYSQL_DATABASE} 不存在"; rc=1; fi
    if [ "$userok" = "1" ]; then echo "[OK]   应用账号 ${MYSQL_USER} 可登录"; else echo "[FAIL] 应用账号 ${MYSQL_USER} 无法登录"; rc=1; fi
  fi

  echo
  echo "== Redis =="
  if docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q PONG; then
    echo "[OK]   PONG"
    local aof
    aof="$(docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" config get appendonly 2>/dev/null | tail -1)"
    echo "[OK]   appendonly=${aof}"
    docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" set rag-wiki:health ok >/dev/null 2>&1
    local back
    back="$(docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" get rag-wiki:health 2>/dev/null)"
    if [ "$back" = "ok" ]; then echo "[OK]   set/get 一致"; else echo "[FAIL] set/get 不一致：${back}"; rc=1; fi
  else
    echo "[FAIL] Redis 探活失败，容器 ${REDIS_CONTAINER} 是否在运行？"
    rc=1
  fi

  echo
  echo "== Elasticsearch =="
  if ! curl -fs "$ES_URIS" >/dev/null 2>&1; then
    echo "[FAIL] ES 探活失败，容器 ${ES_CONTAINER} 是否在运行？"
    rc=1
  else
    local info health
    info="$(curl -fs "$ES_URIS" 2>/dev/null)"
    health="$(curl -fs "$ES_URIS/_cluster/health" 2>/dev/null)"
    echo "[OK]   版本 $(printf '%s' "$info" | grep -o '"number"[^,]*' | head -1 | cut -d'"' -f4)"
    echo "[OK]   集群状态 $(printf '%s' "$health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)，节点数 $(printf '%s' "$health" | grep -o '"number_of_nodes":[0-9]*' | cut -d: -f2)"
    local plugins
    plugins="$(docker exec "$ES_CONTAINER" bin/elasticsearch-plugin list 2>/dev/null || true)"
    if [ -z "$plugins" ]; then echo "[OK]   无额外插件（中文分析用内置 cjk，见 docs/backend/总体方案.md）"; else echo "[OK]   已装插件：${plugins}"; fi
  fi

  echo
  if [ "$rc" -eq 0 ]; then echo "health 全部通过"; else echo "health 存在失败项"; fi
  return "$rc"
}

cmd_reset() {
  echo "⚠️  即将停止全部中间件并删除数据卷："
  echo "    mysql-data / redis-data / es-data"
  echo "    数据库、Redis 数据、ES 索引将全部丢失，不可恢复。"
  printf '确认请输入项目名 rag-wiki：'
  local answer
  read -r answer
  if [ "$answer" != "rag-wiki" ]; then
    echo "输入不匹配，已取消。"
    return 1
  fi
  compose down -v
  echo "数据卷已删除。重新启动：bash scripts/dev.sh up"
}

main() {
  case "${1:-}" in
    up) cmd_up ;;
    down) cmd_down ;;
    restart) cmd_restart ;;
    status) cmd_status ;;
    health) cmd_health ;;
    logs) cmd_logs ;;
    reset) cmd_reset ;;
    "" | -h | --help | help) usage ;;
    *) echo "未知命令：$1" >&2; echo; usage; exit 2 ;;
  esac
}

main "$@"
