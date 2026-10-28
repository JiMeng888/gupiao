#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 一键启动：MySQL 8 + Redis 7 + 后端 + 前端(Nginx)
#
# 用法：
#   ./start.sh            # 首次启动会自动生成 .env（随机密钥）+ 构建镜像 + 拉起
#   ./start.sh --build    # 强制重新构建镜像（改了代码后）
#   ./start.sh --no-build # 只拉起，不构建
#   ./start.sh --lite     # 低配/免费主机：只起「后端(H2 文件库) + 前端」两个容器
#
# 启动完成后浏览器打开 http://localhost:${FRONTEND_PORT}（默认 8088）
# ---------------------------------------------------------------------------
set -euo pipefail

# 切到脚本所在目录（deploy/），保证相对路径稳定
cd "$(dirname "$0")"

BUILD=auto
COMPOSE_FILE=docker-compose.yml
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=yes ;;
    --no-build) BUILD=no ;;
    --lite) COMPOSE_FILE=docker-compose.lite.yml ;;
    --full) COMPOSE_FILE=docker-compose.yml ;;
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

# ---------- 2. 解析端口 ----------
# 从这里读 .env，而不是在脚本里写死。
# 曾经踩过的坑：健康检查写死 localhost:8080，而 compose 映射的是
# ${BACKEND_PORT:-18080}:8080，于是「服务其实已经起来了，脚本永远报
# 健康检查未通过」。
# 优先级：shell 环境变量 > .env > 默认值（与 docker compose 的取值顺序一致）
env_get() {
  sed -n "s/^$1=//p" .env | tail -1 | tr -d '\r"'"'"
}
BACKEND_PORT="${BACKEND_PORT:-$(env_get BACKEND_PORT)}"
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-$(env_get FRONTEND_PORT)}"
FRONTEND_PORT="${FRONTEND_PORT:-8088}"

# ---------- 3. 检查 Docker ----------
if ! command -v docker >/dev/null 2>&1; then
  echo "错误：未检测到 docker，请先安装 Docker（curl -fsSL https://get.docker.com | sh）。" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "错误：未检测到 docker compose 插件（docker compose version 失败）。" >&2
  exit 1
fi

COMPOSE="docker compose -f $COMPOSE_FILE"
echo "==> 编排文件：$COMPOSE_FILE"

# ---------- 4. 拉起服务 ----------
if [ "$BUILD" = "yes" ]; then
  echo "==> 强制重新构建并启动 ..."
  $COMPOSE up -d --build
elif [ "$BUILD" = "no" ]; then
  echo "==> 启动服务（不构建）..."
  $COMPOSE up -d
else
  # auto：镜像齐全才跳过构建。
  # 注意不能靠 `docker images | grep stocklab` 判断——compose v2 默认把镜像
  # 命名成 <目录名>-<服务名>（这里是 deploy-backend / deploy-frontend），
  # 里面根本没有 "stocklab"，那样判断会导致每次都重建（在 2 核小机上要等好几分钟）。
  # 正确做法是让 compose 自己报出它要用的镜像名，再逐个检查是否存在。
  images=$($COMPOSE config --images 2>/dev/null || true)
  missing=0
  for img in $images; do
    if ! docker image inspect "$img" >/dev/null 2>&1; then
      missing=1
    fi
  done
  if [ -z "$images" ] || [ "$missing" = "1" ]; then
    echo "==> 镜像不齐，构建后启动 ..."
    $COMPOSE up -d --build
  else
    echo "==> 镜像已存在，直接启动 ..."
    $COMPOSE up -d
  fi
fi

# ---------- 5. 健康检查 ----------
# 直接问容器自身的健康状态，比 curl 宿主端口更可靠：
# 后端端口在 lite 编排里只绑了 127.0.0.1，且端口可能被 .env 改掉。
echo ""
echo "==> 等待服务就绪 ..."
max_wait=180
elapsed=0
ready=0
while [ "$elapsed" -lt "$max_wait" ]; do
  status=$(docker inspect -f '{{.State.Health.Status}}' stocklab-backend 2>/dev/null || echo "starting")
  if [ "$status" = "healthy" ]; then
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
  echo "  前端入口：http://localhost:${FRONTEND_PORT}"
  echo "  后端健康：http://localhost:${BACKEND_PORT}/actuator/health"
  if [ "$COMPOSE_FILE" = "docker-compose.lite.yml" ]; then
    echo "  运行模式：lite（H2 文件库，数据在 backend-data 卷）"
    echo "  备份命令：docker cp stocklab-backend:/app/data/stockanalyst.mv.db ./backup.mv.db"
  fi
  echo "  停止服务：./stop.sh"
else
  echo "  容器已启动，但后端健康检查在 ${max_wait}s 内未通过。"
  echo "  查日志：docker compose -f ${COMPOSE_FILE} logs -f backend"
  echo "  常见原因：JWT_SECRET 少于 32 字符、内存不足被 OOM、H2 目录无写权限"
fi
echo "=================================================="
