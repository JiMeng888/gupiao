#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 一键启动：MySQL 8 + Redis 7 + 后端 + 前端(Nginx)
#
# 用法：
#   ./start.sh            # 首次启动会自动生成 .env（随机密钥）+ 构建镜像 + 拉起
#   ./start.sh --build    # 强制重新构建镜像（改了代码后）
#   ./start.sh --no-build # 只拉起，不构建
#
# 启动完成后浏览器打开 http://localhost:8088
# ---------------------------------------------------------------------------
set -euo pipefail

# 切到脚本所在目录（deploy/），保证相对路径稳定
cd "$(dirname "$0")"

BUILD=auto
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=yes ;;
    --no-build) BUILD=no ;;
    *) echo "未知参数: $arg" >&2; exit 1 ;;
  esac
done

# ---------- 1. 准备 .env（幂等：已有 .env 则跳过） ----------
if [ ! -f .env ]; then
  echo "==> 未找到 .env，从模板生成（随机 JWT_SECRET + 库密码）..."
  cp .env.example .env

  # 随机 hex：优先 /dev/urandom（macOS 无 openssl rand -hex 也能跑）
  gen_hex() {
    local n="$1"
    if command -v openssl >/dev/null 2>&1; then
      openssl rand -hex "$n"
    else
      head -c "$((n*2))" /dev/urandom | od -An -tx1 | tr -d ' \n'
    fi
  }

  # JWT_SECRET：64 字符 hex（32 字节），满足「至少 32 字符」
  secret=$(gen_hex 32)
  # MYSQL_ROOT_PASSWORD：32 字符 hex
  pw=$(gen_hex 16)

  # 用 sed 替换模板里的空值 / 占位符（Linux/macOS 通用写法）
  sed -i.bak "s|^JWT_SECRET=.*|JWT_SECRET=${secret}|" .env
  sed -i.bak "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${pw}|" .env
  rm -f .env.bak
else
  echo "==> 使用已有 .env"
fi

# ---------- 2. 检查 Docker ----------
if ! command -v docker >/dev/null 2>&1; then
  echo "错误：未检测到 docker，请先安装 Docker Desktop。" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "错误：未检测到 docker compose 插件（docker compose version 失败）。" >&2
  exit 1
fi

# ---------- 3. 拉起服务 ----------
echo "==> 启动服务 ..."
if [ "$BUILD" = "yes" ]; then
  docker compose up -d --build
elif [ "$BUILD" = "no" ]; then
  docker compose up -d
else
  # auto：镜像不存在才构建
  if docker compose config --images 2>/dev/null | grep -q "stocklab" || [ ! -z "$(docker images -q stocklab-backend 2>/dev/null)" ]; then
    docker compose up -d
  else
    docker compose up -d --build
  fi
fi

# ---------- 4. 健康检查 ----------
echo ""
echo "==> 等待服务就绪 ..."
max_wait=120
elapsed=0
ready=0
while [ "$elapsed" -lt "$max_wait" ]; do
  if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done

echo ""
echo "=================================================="
if [ "$ready" = "1" ]; then
  echo "  启动成功！"
  echo "  前端入口：http://localhost:8088"
  echo "  后端健康：http://localhost:8080/actuator/health"
  echo "  停止服务：./stop.sh"
else
  echo "  容器已启动，但后端健康检查尚未通过。"
  echo "  请查看日志：docker compose logs -f backend"
fi
echo "=================================================="
