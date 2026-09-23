#!/usr/bin/env bash
#
# Maven 包装脚本。
#
# 为什么需要它：在 Git Bash 下，若环境变量 MSYS_NO_PATHCONV=1（本项目所在机器
# 的默认设置），Maven 自带的 bin/mvn 脚本会把 unix 风格路径（/d/code/...）
# 原样交给 java.exe，Windows 的 java 无法解析，报：
#     ���ڵ���: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher
# 本脚本绕过 mvn 脚本，直接用 Windows 风格路径调用 classworlds 启动器。
#
# 用法（与 mvn 完全一致，参数原样透传）：
#   ./tools/mvn.sh -f backend/pom.xml -o -Dmaven.test.skip=true package
#   ./tools/mvn.sh -f backend/pom.xml test
#
# 在非 MSYS/Cygwin 环境（Linux、macOS、WSL 原生 bash）下，本脚本直接转发给 mvn。

set -euo pipefail

# 注意：必须用 case 做模式匹配。
# 写成 [ "$(uname -s)" != "MINGW64_NT"* ] 是错的——通配符写在引号内会被
# 当成字面量星号，条件恒为真，脚本会静默转交给那个本来就坏掉的 mvn。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    : # Windows 下的 bash，继续走下面的绕过逻辑
    ;;
  *)
    # Linux / macOS / WSL 原生 bash：系统 mvn 正常工作，无需本包装
    exec mvn "$@"
    ;;
esac

# ------------------------------------------------------------------
# 定位 Maven 安装目录：依次尝试 MAVEN_HOME / M2_HOME / PATH 上的 mvn / 常见路径
detect_maven_home() {
  local candidates=()
  [ -n "${MAVEN_HOME:-}" ] && candidates+=("$MAVEN_HOME")
  [ -n "${M2_HOME:-}" ] && candidates+=("$M2_HOME")

  local mvn_path
  mvn_path="$(command -v mvn 2>/dev/null || true)"
  if [ -n "$mvn_path" ]; then
    candidates+=("$(cd "$(dirname "$mvn_path")/.." && pwd)")
  fi

  candidates+=(
    "/c/Program Files/apache-maven"
    "/d/code/maven3.9.11"
    "/opt/maven"
    "/usr/local/maven"
  )
  # 通配展开常见安装位置（如 apache-maven-3.9.x）
  local g
  for g in "/c/Program Files"/apache-maven-* /d/code/maven*/ /opt/apache-maven-*; do
    [ -d "$g" ] && candidates+=("$g")
  done

  local c unix
  for c in "${candidates[@]}"; do
    [ -z "$c" ] && continue
    unix="$(cygpath -u "$c" 2>/dev/null || echo "$c")"
    if compgen -G "$unix/boot/plexus-classworlds-*.jar" >/dev/null 2>&1; then
      echo "$unix"
      return 0
    fi
  done
  return 1
}

if ! MAVEN_HOME_UNIX="$(detect_maven_home)"; then
  cat >&2 <<'MSG'
未能自动定位 Maven 安装目录。
请设置环境变量 MAVEN_HOME 指向 Maven 解压目录后重试，例如：
  export MAVEN_HOME='D:\code\maven3.9.11'
MSG
  exit 1
fi

MAVEN_HOME_WIN="$(cygpath -w "$MAVEN_HOME_UNIX")"
CLASSWORLDS_JAR="$(compgen -G "$MAVEN_HOME_UNIX/boot/plexus-classworlds-*.jar" | head -1)"
CLASSWORLDS_NAME="$(basename "$CLASSWORLDS_JAR")"

# multiModuleProjectDirectory 必须是 Windows 风格；pwd -W 仅在 MSYS 下可用
PROJECT_DIR="$(pwd -W 2>/dev/null || pwd)"

# 关键：阻止 MSYS 把 -classpath 的值按 unix 路径重写
export MSYS_NO_PATHCONV=1

exec java \
  -classpath "${MAVEN_HOME_WIN}\\boot\\${CLASSWORLDS_NAME}" \
  "-Dclassworlds.conf=${MAVEN_HOME_WIN}\\bin\\m2.conf" \
  "-Dmaven.home=${MAVEN_HOME_WIN}" \
  "-Dmaven.multiModuleProjectDirectory=${PROJECT_DIR}" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
