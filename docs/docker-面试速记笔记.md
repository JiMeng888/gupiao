# Docker 面试速记笔记

> 面向全栈实习生面试。重点：把「Docker 是干嘛的」这类基础答扎实，把「深度问题」主动引导到能答的范围，绝不不懂装懂。

---

## 一、必会基础概念（高频，必须能答）

### ① Docker 是什么

> Docker 是一个**容器化**工具。它把「应用 + 应用需要的所有依赖（环境、库、配置）」打包成一个**镜像（Image）**，这个镜像在任何装了 Docker 的机器上都能**一模一样地跑起来**，解决「在我电脑上能跑、换台机器跑不了」的问题。

**比喻**：Docker 镜像像「集装箱」——把货物（代码）和它需要的环境打包好，上任何船（服务器）都能用，不用关心船是什么样的。

### ② 镜像（Image）vs 容器（Container）—— 面试最爱问

| | 镜像 Image | 容器 Container |
|---|---|---|
| 是什么 | 只读的**模板**（像软件安装包） | 镜像**运行起来后的实例**（像正在运行的程序） |
| 比喻 | 菜谱 | 按菜谱做出来的一盘菜 |
| 关系 | 一个镜像可启动无数容器 | 容器基于镜像运行 |

**关键类比（加分）**：Image 和 Container 的关系，就像「类」和「对象」的关系。

### ③ Docker Compose 是什么

> Compose 是**编排工具**。当应用由**多个容器**组成时，写一个 `docker-compose.yml`，就能**一条命令把整套一起启动**，而不是手动一个个 `docker run`。

**一句话**：单容器用 `docker run`，多容器协同用 `docker compose`。

### ④ 常用命令（背下来）

| 命令 | 作用 |
|---|---|
| `docker build` | 根据 Dockerfile 构建镜像 |
| `docker run` | 启动一个容器 |
| `docker ps` | 查看正在运行的容器 |
| `docker compose up -d` | 后台启动整套服务（-d 后台） |
| `docker compose down` | 停止并删除整套服务 |
| `docker images` | 查看本地镜像列表 |

### ⑤ Dockerfile 是什么

> Dockerfile 是「如何构建镜像」的**配方文件**，里面写清楚「用哪个基础镜像、复制哪些文件、装哪些依赖、怎么启动」。

---

## 二、我的项目里 Docker 具体做了什么（能讲的加分项）

**4 个容器**：

| 容器 | 作用 |
|---|---|
| MySQL 8 | 存数据，配数据卷 `mysql-data` 保证重启不丢数据 |
| Redis 7 | 分布式限流 + 会话吊销（多实例场景） |
| backend | Spring Boot 后端，`depends_on` 等 MySQL/Redis 健康才启动 |
| frontend | Nginx 托管前端静态文件 + 反向代理 |

**两个可主动提的细节**（都是文件里真实写的）：

1. **健康检查 healthcheck**：MySQL/Redis 都配了 `healthcheck`，后端 `depends_on` 用 `condition: service_healthy`，确保「数据库真的就绪了才启动后端」，而不是「容器起来了但库还没好」。

2. **一次性建表服务 db-init**：单独一个容器，启动时执行建表 SQL 后退出。这样应用账号不需要 DDL 权限（生产安全考虑）。

---

## 三、⚠️ 我现在「守不住」的点（别主动往这引）

如果被深挖，诚实说「配置照着最佳实践写的，原理还在学」：

1. `wait_timeout=28800` 和 Hikari `max-lifetime` 的关系（数据库连接池细节）
2. `depends_on` 三种 condition 的区别（`service_healthy` / `service_completed_successfully` / 默认）
3. 数据卷 volume 和 bind mount 的区别、命名卷持久化机制
4. `restart: unless-stopped` 等重启策略的语义
5. bridge 网络、容器间怎么通过服务名互访

---

## 四、关键：关于「没真正跑过 docker compose up」的标准话术

你的 README 明确写了「Docker Compose 未实际执行过」（本机无 Docker 守护进程）。被问到部署方式时，**诚实但换说法**：

> 「我的项目开发阶段主要用本地方式跑（后端 `java -jar` + 前端 `npm run dev`），Docker Compose 的配置我写好了、也确认了编排逻辑，但因为本机没有 Docker 守护进程，没有真正执行过 `docker compose up`。这是我项目里一个还没验证的部分，我如实标注在 README 里了。」

**为什么这个回答反而加分**：「诚实标注未验证」是你项目最大的优点，延续到面试里，人设是一致的。

---

## 五、最容易被追问的 3 个问题（先想好答案）

1. **「Docker 和虚拟机有什么区别？」**
   - Docker 容器共享宿主机的**内核**，更轻量、启动快（秒级）；虚拟机要虚拟整个操作系统，更重、启动慢（分钟级）。
   - 一句话：虚拟机是「完整的一套系统」，容器是「只打包应用 + 依赖」。

2. **「为什么要用容器？」**
   - 环境一致性（本地和线上一样）、快速部署、资源隔离、方便横向扩展。

3. **「你的容器之间怎么互相访问？」**
   - 通过同一个 docker 网络，用**服务名**互相访问（比如后端连 MySQL 用的是 `DB_HOST: mysql`，不是 `localhost`）。这就是为什么后端配置里 `DB_HOST` 写的是 `mysql` 而不是 `127.0.0.1`。
