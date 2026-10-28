#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 一键停止服务（保留数据卷，数据不丢失）
#
# 用法：
#   ./stop.sh              # 停止全部容器（自动识别 full / lite 编排）
#   ./stop.sh --lite       # 明确指定 lite 编排
#   ./stop.sh --volumes    # 停止并删除数据卷（会清空数据库！慎用）
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE_FILE=docker-compose.yml
WITH_VOLUMES=no
for arg in "$@"; do
  case "$arg" in
    --lite) COMPOSE_FILE=docker-compose.lite.yml ;;
    --volumes) WITH_VOLUMES=yes ;;
    *) echo "未知参数: $arg" >&2; exit 1 ;;
  esac
done

# 没显式指定时自动识别：lite 编排里没有 mysql 容器。
# 不做这层判断的话，lite 部署下跑 ./stop.sh 会因为找不到 mysql 服务
# 而只停掉一部分，留下半死不活的容器。
if [ "$COMPOSE_FILE" = "docker-compose.yml" ] && [ "$WITH_VOLUMES" = "no" ]; then
  if ! docker ps -a --format '{{.Names}}' | grep -qx 'stocklab-mysql'; then
    if docker ps -a --format '{{.Names}}' | grep -qx 'stocklab-backend'; then
      COMPOSE_FILE=docker-compose.lite.yml
    fi
  fi
fi

COMPOSE="docker compose -f $COMPOSE_FILE"

if [ "$WITH_VOLUMES" = "yes" ]; then
  echo "警告：将删除数据卷（MySQL 数据 / H2 库文件），数据会清空！"
  read -r -p "确认继续？(输入 yes 继续) " ans
  if [ "$ans" != "yes" ]; then
    echo "已取消。"
    exit 0
  fi
  $COMPOSE down -v
  echo "已停止并删除数据卷。"
else
  $COMPOSE down
  echo "已停止服务（数据卷已保留）。"
fi
