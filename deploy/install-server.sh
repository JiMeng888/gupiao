#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 新服务器初始化（Ubuntu / Debian，x86 或 ARM 均可）
#
# 做的事（全部幂等，可重复执行）：
#   1. 装 Docker + Compose 插件
#   2. 内存 < 2 GB 时建 2 GB swap（否则首次构建容易 OOM 被杀）
#   3. 开防火墙端口（默认 8088）
#   4. 打印下一步该做什么
#
# 用法：
#   sudo bash install-server.sh              # 默认放行 8088
#   sudo bash install-server.sh 8088 22      # 自定义放行端口
#
# 不做的事：不装 MySQL、不改 SSH 配置、不动已有容器。
# ---------------------------------------------------------------------------
set -euo pipefail

if [ "$(id -u)" != "0" ]; then
  echo "请用 root 或 sudo 运行：sudo bash $0" >&2
  exit 1
fi

# 默认放行 8088。注意不能写成 `[ "$#" -eq 0 ] && PORTS=(8088)`：
# 在 set -e 下，前半句为假时整条语句返回非零，脚本会直接退出。
if [ "$#" -eq 0 ]; then
  PORTS=(8088)
else
  PORTS=("$@")
fi

echo "==> 1/4 安装 Docker ..."
if command -v docker >/dev/null 2>&1; then
  echo "    Docker 已存在：$(docker --version)"
else
  curl -fsSL https://get.docker.com | sh
fi

# 非 root 用户免 sudo 使用 docker。$SUDO_USER 是调起 sudo 的那个真实用户。
if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
  usermod -aG docker "$SUDO_USER" || true
  echo "    已把 $SUDO_USER 加入 docker 组（需重新登录生效）"
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "    未检测到 compose 插件，尝试安装 ..."
  apt-get update -qq && apt-get install -y -qq docker-compose-plugin
fi

echo "==> 2/4 检查内存与 swap ..."
mem_mb=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
swap_mb=$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo)
echo "    内存 ${mem_mb} MB / swap ${swap_mb} MB"

if [ "$mem_mb" -lt 2048 ] && [ "$swap_mb" -lt 1024 ]; then
  # 为什么需要：Maven + npm 同时跑时峰值能到 1.5 GB 以上，
  # 1 GB 机器没有 swap 会直接被 OOM killer 杀掉，表现为"构建到一半容器消失"。
  echo "    内存不足 2 GB，创建 2 GB swap ..."
  if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "    已启用 /swapfile（写入 /etc/fstab，重启后仍生效）"
  else
    echo "    /swapfile 已存在，跳过"
  fi
else
  echo "    内存充足，不创建 swap"
fi

echo "==> 3/4 配置防火墙 ..."
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp >/dev/null 2>&1 || true
  for p in "${PORTS[@]}"; do
    ufw allow "${p}/tcp" >/dev/null 2>&1 || true
    echo "    ufw 放行 ${p}/tcp"
  done
  ufw --force enable >/dev/null 2>&1 || true
  ufw status | head -20
else
  echo "    未安装 ufw（部分镜像默认没有），请用 iptables 或云厂商安全组放行：${PORTS[*]}"
fi

echo ""
echo "==> 4/4 完成。下一步："
echo "    cd deploy"
echo "    cp .env.example .env && vi .env      # 填 LLM_API_KEY"
echo "    ./start.sh --build                   # 内存 >= 2G 用 full 模式"
echo "    ./start.sh --lite --build            # 内存 1~2G 用 lite 模式"
echo ""
echo "    ⚠️ 云厂商安全组是第二层防火墙，控制台里也要放行 ${PORTS[*]}，"
echo "       否则系统内放行了、外面照样连不上。"
echo ""
echo "    部署前务必先在**这台机器上**验证行情源可达性："
echo "    curl -s -o /dev/null -w 'eastmoney: %{http_code}\\n' \\"
echo "      'https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=f43'"
