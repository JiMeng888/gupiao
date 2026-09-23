#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 一键停止服务（保留数据卷 mysql-data / redis-data，数据不丢失）
#
# 用法：
#   ./stop.sh            # 停止全部容器
#   ./stop.sh --volumes  # 停止并删除数据卷（会清空数据库！慎用）
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--volumes" ]; then
  echo "警告：将删除数据卷 mysql-data / redis-data，数据库数据会清空！"
  read -r -p "确认继续？(输入 yes 继续) " ans
  if [ "$ans" != "yes" ]; then
    echo "已取消。"
    exit 0
  fi
  docker compose down -v
  echo "已停止并删除数据卷。"
else
  docker compose down
  echo "已停止服务（数据卷已保留）。"
fi
