#!/usr/bin/env bash
#
# 后端冒烟测试：覆盖 8 大模块的关键路径与错误分支。
#
# 设计原则：
#   1. 只依赖 curl + grep/sed，不依赖 jq / python，任何开发机都能跑。
#   2. 每一项都断言「状态码 + 关键字段」，不是只看 HTTP 200 就算过。
#   3. 末尾打印 PASS/FAIL 汇总并以退出码反映结果，可直接接进 CI。
#
# 用法：
#   bash scripts/smoke-test.sh                      # 默认 http://localhost:8080
#   BASE=http://localhost:18080 bash scripts/smoke-test.sh
#
#   # 公网受限（CI 沙箱、内网、上游被墙）时跳过必须访问真实行情/资讯源的断言。
#   # 被跳过的项会计入 SKIP 并在汇总里单独列出，**不会**被当成通过。
#   SKIP_UPSTREAM=1 BASE=http://localhost:8080 bash scripts/smoke-test.sh
#
# 注意：脚本会注册一个随机手机号的测试用户并写入持仓数据，
#       因此只应指向 dev / 测试环境，不要对着生产库跑。

set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
PASS=0
FAIL=0
SKIP=0

# 需要真实上游数据的断言在 SKIP_UPSTREAM=1 时被跳过。
# 判据是「响应体里必须有某个来自上游的具体值」——上游不可达时这些值必然缺失，
# 失败原因在上游而不在代码，混在一起会让真正的回归被噪声淹没。
SKIP_UPSTREAM="${SKIP_UPSTREAM:-0}"

# 临时文件放在脚本同级，避免 Windows 下 /tmp 与 MSYS 路径转换打架
WORKDIR="$(dirname "$0")/.smoke-tmp"
mkdir -p "$WORKDIR"
trap 'rm -rf "$WORKDIR"' EXIT

c_green=$'\033[32m'; c_red=$'\033[31m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
c_yellow=$'\033[33m'

# check <描述> <期望状态码> <实际状态码> <响应文件> <必须包含的片段(可空)>
check() {
  local name="$1" want="$2" got="$3" body="$4" needle="${5:-}"
  local ok=1
  [ "$want" = "$got" ] || ok=0
  if [ -n "$needle" ] && ! grep -q "$needle" "$body" 2>/dev/null; then ok=0; fi
  if [ "$ok" = "1" ]; then
    PASS=$((PASS + 1))
    printf '  %sPASS%s %-52s %s\n' "$c_green" "$c_off" "$name" "$c_dim$got$c_off"
  else
    FAIL=$((FAIL + 1))
    printf '  %sFAIL%s %-52s 期望 %s 实际 %s\n' "$c_red" "$c_off" "$name" "$want" "$got"
    printf '       %s%s%s\n' "$c_dim" "$(head -c 200 "$body" 2>/dev/null)" "$c_off"
  fi
}

section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# skip <描述> <原因>  —— 计入 SKIP 计数，明确区别于 PASS
skip() {
  local name="$1" why="${2:-上游不可达}"
  SKIP=$((SKIP + 1))
  printf '  %sSKIP%s %-52s %s%s%s\n' "$c_yellow" "$c_off" "$name" "$c_dim" "$why" "$c_off"
}

# upstream_required <描述> <期望状态码> <实际状态码> <响应文件> <必须包含的片段>
#   SKIP_UPSTREAM=1 时记为 SKIP，否则等同于 check。
#   用于「断言值来自真实上游接口」的用例，避免公网受限时误报回归。
upstream_required() {
  if [ "$SKIP_UPSTREAM" = "1" ]; then
    skip "$1" "SKIP_UPSTREAM=1"
  else
    check "$@"
  fi
}

# req <method> <url> [json_body] [header...]
#   header 传「名字: 值」即可，内部补 -H；
#   若直接 args+=("$@") 而不补 -H，curl 会把 "Authorization: Bearer xxx"
#   当成第二个 URL 去请求（"authorization" 协议不存在）——表现为真实请求
#   没带上令牌，同时 -w 多打印一个 000，状态码变成 "401000000" 这种脏值。
req() {
  local method="$1" url="$2"; shift 2
  local body="${1:-}"; shift || true
  local args=(-s -o "$WORKDIR/resp.json" -w '%{http_code}' -X "$method" "$url")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local h
  for h in "$@"; do args+=(-H "$h"); done
  curl "${args[@]}" 2>/dev/null || echo 000
}

echo "冒烟测试目标：$BASE"
# 注意：这里刻意把输出写进文件而不是 /dev/null。
# Git Bash 下若设置了 MSYS_NO_PATHCONV=1，curl.exe 会收到字面量 "/dev/null"
# 并按「当前盘符根目录下的 dev/null」去写，直接失败，从而把「服务正常」
# 误判成「服务未启动」。
HEALTH_CODE=$(curl -s -o "$WORKDIR/health.json" -w '%{http_code}' --max-time 5 \
  "$BASE/actuator/health" 2>/dev/null)
if [ "$HEALTH_CODE" != "200" ]; then
  echo "后端未就绪（健康检查返回 $HEALTH_CODE）。请先启动服务（默认端口 8080 / dev profile）。"
  exit 2
fi
check "健康检查" 200 "$HEALTH_CODE" "$WORKDIR/health.json" '"status":"UP"'

# ------------------------------------------------------------------
section "行情：指数 / 报价 / K 线 / 搜索 / 数据源状态"

check "GET /quote/indices" 200 "$(req GET "$API/quote/indices")" "$WORKDIR/resp.json" '"code"'
check "GET /quote/600519" 200 "$(req GET "$API/quote/600519")" "$WORKDIR/resp.json" '"price"'
check "GET /quote/600519/bars" 200 "$(req GET "$API/quote/600519/bars?period=daily&limit=5")" \
  "$WORKDIR/resp.json" '"close"'
check "GET /quote/carousel" 200 "$(req GET "$API/quote/carousel?count=4")" "$WORKDIR/resp.json"

# 市场榜单：三个榜都要有数据且形状正确（榜单头部是新股属正常市场行为）
check "GET /quote/movers 涨幅榜" 200 "$(req GET "$API/quote/movers?metric=changePct&order=desc&limit=10")"   "$WORKDIR/resp.json" '"changePct"'
check "GET /quote/movers 跌幅榜" 200 "$(req GET "$API/quote/movers?metric=changePct&order=asc&limit=10")"   "$WORKDIR/resp.json" '"changePct"'
check "GET /quote/movers 成交额榜" 200 "$(req GET "$API/quote/movers?metric=amount&order=desc&limit=10")"   "$WORKDIR/resp.json" '"amount"'
check "GET /quote/movers 非法维度 -> 400" 400 "$(req GET "$API/quote/movers?metric=turnover")" "$WORKDIR/resp.json"

# 中文必须百分号编码：Tomcat 会拒绝请求行里的裸非 ASCII 字节（400）
# 期望值 "600519" 来自真实搜索接口，属上游数据 → 允许跳过
upstream_required "GET /quote/search 中文(编码)" 200 \
  "$(req GET "$API/quote/search?q=%E8%8C%85%E5%8F%B0")" "$WORKDIR/resp.json" '600519'
check "GET /quote/search 空关键字 -> 400" 400 "$(req GET "$API/quote/search?q=")" "$WORKDIR/resp.json"

# 数据源状态必须自洽：degraded 为 true 时不能同时宣称是真实行情
code=$(req GET "$API/quote/source")
check "GET /quote/source" 200 "$code" "$WORKDIR/resp.json" '"provider"'
if grep -q '"degraded":true' "$WORKDIR/resp.json"; then
  if grep -q '请勿据此做出投资决策' "$WORKDIR/resp.json"; then
    PASS=$((PASS + 1)); printf '  %sPASS%s %-52s %s已降级且如实告知%s\n' "$c_green" "$c_off" "数据源降级时给出明确告警" "$c_dim" "$c_off"
  else
    FAIL=$((FAIL + 1)); printf '  %sFAIL%s %s\n' "$c_red" "$c_off" "已降级但未给出告警文案"
  fi
fi

# ------------------------------------------------------------------
section "技术指标"

check "GET /indicator 四指标" 200 \
  "$(req GET "$API/indicator/600519?indicators=MACD,RSI,BOLL,KDJ&limit=120")" \
  "$WORKDIR/resp.json" '"macd"'
check "GET /indicator 非法指标名 -> 400" 400 \
  "$(req GET "$API/indicator/600519?indicators=NOPE")" "$WORKDIR/resp.json" 'INVALID_INDICATOR'
check "GET /indicator limit 越界 -> 400" 400 \
  "$(req GET "$API/indicator/600519?limit=99999")" "$WORKDIR/resp.json"
check "GET /quote/bars 非法周期 -> 400" 400 \
  "$(req GET "$API/quote/600519/bars?period=bogus")" "$WORKDIR/resp.json" 'INVALID_PERIOD'

# ------------------------------------------------------------------
section "资讯"

check "GET /news/channels" 200 "$(req GET "$API/news/channels")" "$WORKDIR/resp.json" '"all"'
# 期望值 "title" 来自真实资讯 feed → 允许跳过
upstream_required "GET /news/list 默认频道" 200 "$(req GET "$API/news/list?limit=5")" "$WORKDIR/resp.json" '"title"'
check "GET /news/list 非法频道 -> 400" 400 \
  "$(req GET "$API/news/list?channel=bogus")" "$WORKDIR/resp.json" 'INVALID_PARAMS'

# ------------------------------------------------------------------
section "基本面"

check "GET /fundamental/600519" 200 "$(req GET "$API/fundamental/600519")" "$WORKDIR/resp.json"
check "GET /fundamental/600519/dupont" 200 \
  "$(req GET "$API/fundamental/600519/dupont")" "$WORKDIR/resp.json" '"roe"'
check "GET /fundamental/compare 超 6 只 -> 400" 400 \
  "$(req GET "$API/fundamental/compare?codes=600519,000858,300750,601318,000001,600036,000651")" \
  "$WORKDIR/resp.json" 'INVALID_PARAMS'

# ------------------------------------------------------------------
section "鉴权：注册 / 登录 / 刷新 / 登出 / 会话吊销"

# 生成一个 11 位的测试手机号。
#
# 这里踩过一个坑，值得记下来：原来的写法是
#     PHONE="139$(date +%H%M%S)$((RANDOM % 100))"
# 看起来是 11 位，但当 RANDOM%100 落在 0~9 时实际只有 10 位，
# 会被 RegisterRequest 上的 @Pattern("^1\d{10}$") 挡掉返回 400——
# 于是「注册未返回令牌」导致整个鉴权段（9 条断言）被跳过，
# 汇总从 46 条变成 37 条，看起来像代码坏了，其实只是随机数没补零。
# 概率约 10%，属于最难查的那种偶发假失败。
#
# 现在改成「前缀 3 位 + MMSS 4 位 + 4 位零填充随机」，固定 11 位，
# 且同一分钟内的取值空间从 100 提到 10000，重跑撞号的概率也一并降下来。
new_test_phone() {
  printf '%s%s%04d' "$1" "$(date +%M%S)" "$((RANDOM % 10000))"
}

PHONE="$(new_test_phone 139)"
BODY="{\"phone\":\"$PHONE\",\"password\":\"Passw0rd!\"}"

check "POST /auth/register" 200 "$(req POST "$API/auth/register" "$BODY")" "$WORKDIR/resp.json" 'accessToken'
cp "$WORKDIR/resp.json" "$WORKDIR/reg.json"
AT=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$WORKDIR/reg.json" | tr -d '\r')
RT=$(sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p' "$WORKDIR/reg.json" | tr -d '\r')

if [ -z "$AT" ] || [ -z "$RT" ]; then
  FAIL=$((FAIL + 1)); printf '  %sFAIL%s 注册未返回令牌，后续鉴权用例跳过\n' "$c_red" "$c_off"
else
  check "POST /auth/register 重复手机号 -> 409" 409 "$(req POST "$API/auth/register" "$BODY")" "$WORKDIR/resp.json" 'PHONE_EXISTS'
  check "POST /auth/register 密码过短 -> 400" 400 \
    "$(req POST "$API/auth/register" '{"phone":"13900000000","password":"123"}')" "$WORKDIR/resp.json"
  check "POST /auth/login" 200 "$(req POST "$API/auth/login" "$BODY")" "$WORKDIR/resp.json" 'accessToken'
  check "GET /auth/me 带令牌" 200 "$(req GET "$API/auth/me" "" "Authorization: Bearer $AT")" "$WORKDIR/resp.json" "\"phone\":\"$PHONE\""
  check "GET /auth/me 伪造令牌 -> 401" 401 \
    "$(req GET "$API/auth/me" "" "Authorization: Bearer not-a-real-token")" "$WORKDIR/resp.json" 'AUTH_INVALID'
  check "GET /auth/me 无令牌 -> 401" 401 "$(req GET "$API/auth/me")" "$WORKDIR/resp.json" 'AUTH_REQUIRED'

  # 用 Access Token 冒充 Refresh Token 必须被拒（令牌类型校验）
  check "POST /auth/refresh 用 access 冒充 -> 401" 401 \
    "$(req POST "$API/auth/refresh" "{\"refreshToken\":\"$AT\"}")" "$WORKDIR/resp.json" 'AUTH_INVALID'

  # 登出必须真正吊销会话：仅凭 Authorization 头（不带 body）也要生效
  check "POST /auth/logout 仅带 Authorization 头" 200 \
    "$(req POST "$API/auth/logout" "" "Authorization: Bearer $AT")" "$WORKDIR/resp.json" '"ok":true'
  check "登出后原 access token 立即失效 -> 401" 401 \
    "$(req GET "$API/auth/me" "" "Authorization: Bearer $AT")" "$WORKDIR/resp.json" 'AUTH_INVALID'
  check "登出后原 refresh token 立即失效 -> 401" 401 \
    "$(req POST "$API/auth/refresh" "{\"refreshToken\":\"$RT\"}")" "$WORKDIR/resp.json" 'AUTH_INVALID'
  check "重复登出仍返回 200（幂等）" 200 \
    "$(req POST "$API/auth/logout" "" "Authorization: Bearer $AT")" "$WORKDIR/resp.json" '"ok":true'

  # ------------------------------------------------------------------
  # 只用 refreshToken 登出（不带 Authorization 头）的回归用例。
  #
  # 覆盖的是 AuthService.logout 里一个真实修过的缺陷：原实现只在
  # sessionId 参数非空时吊销会话，而忽略 refreshToken 自带的 sid。
  # 于是这条路径会「只黑掉一张令牌」——refresh 不能用了，但那张
  # 尚未过期的 Access Token 还能继续用到自然过期（最多 30 分钟）。
  #
  # 单测已经覆盖了逻辑层，这里再走一遍真实 HTTP 栈，是为了确认
  # 过滤器/控制器的取值方式不会让修复在集成层面失效。
  # 需要一对全新令牌（上面的 AT/RT 已在上一个用例里被吊销）。
  # ------------------------------------------------------------------
  check "POST /auth/login（为登出回归用例取新令牌对）" 200 \
    "$(req POST "$API/auth/login" "$BODY")" "$WORKDIR/resp.json" 'accessToken'
  AT2B=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$WORKDIR/resp.json" | tr -d '\r')
  RT2B=$(sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p' "$WORKDIR/resp.json" | tr -d '\r')

  if [ -z "$AT2B" ] || [ -z "$RT2B" ]; then
    FAIL=$((FAIL + 1)); printf '  %sFAIL%s 登出回归用例未能取到新令牌对\n' "$c_red" "$c_off"
  else
    check "POST /auth/logout 仅带 refreshToken（无 Authorization 头）" 200 \
      "$(req POST "$API/auth/logout" "{\"refreshToken\":\"$RT2B\"}")" "$WORKDIR/resp.json" '"ok":true'
    check "仅凭 refreshToken 登出后 access token 也失效 -> 401" 401 \
      "$(req GET "$API/auth/me" "" "Authorization: Bearer $AT2B")" "$WORKDIR/resp.json" 'AUTH_INVALID'
    check "仅凭 refreshToken 登出后 refresh token 也失效 -> 401" 401 \
      "$(req POST "$API/auth/refresh" "{\"refreshToken\":\"$RT2B\"}")" "$WORKDIR/resp.json" 'AUTH_INVALID'
  fi
fi

# ------------------------------------------------------------------
section "自选股与持仓（需登录）"

PHONE2="$(new_test_phone 138)"
req POST "$API/auth/register" "{\"phone\":\"$PHONE2\",\"password\":\"Passw0rd!\"}" >/dev/null
AT2=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$WORKDIR/resp.json" | tr -d '\r')
AUTH2="Authorization: Bearer $AT2"

if [ -z "$AT2" ]; then
  FAIL=$((FAIL + 1)); printf '  %sFAIL%s 第二个测试用户注册失败，跳过自选/持仓用例\n' "$c_red" "$c_off"
else
  check "POST /watchlist/items 首次新增（懒创建分组）" 200 \
    "$(req POST "$API/watchlist/items" '{"code":"600519"}' "$AUTH2")" "$WORKDIR/resp.json"
  check "POST /watchlist/items 重复新增 -> 409" 409 \
    "$(req POST "$API/watchlist/items" '{"code":"600519"}' "$AUTH2")" "$WORKDIR/resp.json" 'DUPLICATE_ITEM'
  check "POST /watchlist/items 非法代码 -> 400" 400 \
    "$(req POST "$API/watchlist/items" '{"code":"NOTACODE"}' "$AUTH2")" "$WORKDIR/resp.json"
  check "GET /watchlist/items 列表含实时行情" 200 \
    "$(req GET "$API/watchlist/items" "" "$AUTH2")" "$WORKDIR/resp.json" '600519'

  check "POST /holdings/buy 首笔买入" 200 \
    "$(req POST "$API/holdings/buy" '{"code":"600519","shares":100,"price":1200}' "$AUTH2")" "$WORKDIR/resp.json"
  check "POST /holdings/buy 补仓" 200 \
    "$(req POST "$API/holdings/buy" '{"code":"600519","shares":100,"price":1300}' "$AUTH2")" "$WORKDIR/resp.json"
  # 加权平均成本必须是 (100*1200 + 100*1300) / 200 = 1250
  if grep -q '"avgCost":1250' "$WORKDIR/resp.json"; then
    PASS=$((PASS + 1)); printf '  %sPASS%s %-52s %s\n' "$c_green" "$c_off" "补仓后加权平均成本 = 1250" "$c_dim已验证$c_off"
  else
    FAIL=$((FAIL + 1)); printf '  %sFAIL%s 补仓加权平均成本不正确：%s\n' "$c_red" "$c_off" "$(head -c 200 "$WORKDIR/resp.json")"
  fi
  check "POST /holdings/sell 卖出 50 股" 200 \
    "$(req POST "$API/holdings/sell" '{"code":"600519","shares":50,"price":1500}' "$AUTH2")" "$WORKDIR/resp.json"
  check "POST /holdings/sell 超量卖出 -> 400" 400 \
    "$(req POST "$API/holdings/sell" '{"code":"600519","shares":99999,"price":1500}' "$AUTH2")" "$WORKDIR/resp.json" 'INSUFFICIENT_SHARES'
  check "GET /holdings/summary" 200 "$(req GET "$API/holdings/summary" "" "$AUTH2")" "$WORKDIR/resp.json"
fi

# ------------------------------------------------------------------
section "AI 分析"

code=$(req GET "$API/analysis/600519")
check "GET /analysis/600519" 200 "$code" "$WORKDIR/resp.json" '"thesis"'
# 未配置大模型 Key 时必须如实标注降级，不能假装是模型结论
if grep -q '"model":"rule-based"' "$WORKDIR/resp.json"; then
  if grep -q '"notice"' "$WORKDIR/resp.json" && grep -q '"dataBasis"' "$WORKDIR/resp.json"; then
    PASS=$((PASS + 1)); printf '  %sPASS%s %-52s %s\n' "$c_green" "$c_off" "降级为规则引擎时标注 model + notice" "$c_dim已验证$c_off"
  else
    FAIL=$((FAIL + 1)); printf '  %sFAIL%s 降级报告缺少 notice / dataBasis\n' "$c_red" "$c_off"
  fi
fi
check "GET /analysis/600519/history" 200 "$(req GET "$API/analysis/600519/history?limit=3")" "$WORKDIR/resp.json"
check "GET /analysis 非法代码 -> 404" 404 "$(req GET "$API/analysis/ZZZZZZ")" "$WORKDIR/resp.json"

# ------------------------------------------------------------------
section "WebSocket 统计"

check "GET /ws/stats" 200 "$(req GET "$BASE/ws/stats")" "$WORKDIR/resp.json" '"connections"'

# ------------------------------------------------------------------
printf '\n\033[1m================ 汇总 ================\033[0m\n'
if [ "$SKIP" -gt 0 ]; then
  printf '通过 %s%d%s 项，失败 %s%d%s 项，跳过 %s%d%s 项\n' \
    "$c_green" "$PASS" "$c_off" "$c_red" "$FAIL" "$c_off" "$c_yellow" "$SKIP" "$c_off"
  printf '%s注意：跳过的 %d 项依赖真实上游接口，本次未被验证，不计入「通过」。%s\n' \
    "$c_yellow" "$SKIP" "$c_off"
else
  printf '通过 %s%d%s 项，失败 %s%d%s 项\n' "$c_green" "$PASS" "$c_off" "$c_red" "$FAIL" "$c_off"
fi
if [ "$FAIL" -gt 0 ]; then
  echo "存在失败项——请检查后端日志确认是环境问题（如上游不可达）还是代码问题。"
  exit 1
fi
echo "全部通过。"
exit 0
