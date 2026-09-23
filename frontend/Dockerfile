# ---------------------------------------------------------------------------
# 前端镜像：Node 构建 → Nginx 托管
#
# 前端构建产物是纯静态文件，运行时完全不需要 Node。
# 最终镜像基于 nginx:alpine（约 50MB），而不是 node（约 400MB）。
# ---------------------------------------------------------------------------

# ---------- 阶段 1：构建 ----------
FROM node:22-alpine AS builder

WORKDIR /build

# 同理，先拷依赖清单再装，利用层缓存避免每次重新下载
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY . .

# 生产构建。注意 .env.production 里刻意不配后端地址：
# 生产环境前端只发同源相对请求，由 Nginx 反向代理到后端，
# 因此前端镜像不需要知道后端域名。
RUN npm run build


# ---------- 阶段 2：运行 ----------
FROM nginx:1.27-alpine

# 时区同后端，保证 Nginx 访问日志与后端日志时间可比对
ENV TZ=Asia/Shanghai

RUN rm -f /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html
# nginx.conf 放在 frontend/ 而不是 deploy/：
# Docker 构建上下文是 frontend/ 目录，COPY 不能引用上下文之外的路径
# （写成 ../deploy/nginx.conf 会直接构建失败）。
COPY nginx.conf /etc/nginx/conf.d/app.conf

EXPOSE 80

# 用 curl 而非 wget：nginx:alpine 里的 wget 是 BusyBox 版，
# 解析 localhost 时优先连 IPv6 ::1，而 nginx 只监听 IPv4 80，
# 会导致健康检查一直 "Connection refused"（容器被误判为 unhealthy）。
# curl 能正确回退到 IPv4，且 nginx:alpine 自带 curl。
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost/ > /dev/null || exit 1"]

CMD ["nginx", "-g", "daemon off;"]
