# Java 后端基础概念地图（面试速记）

> 面向全栈实习生面试，主攻 Java。每个概念 = 一句话 + 一个比喻 + 一个「股票分析师」项目里的真实例子。
> 覆盖 Spring Boot / MyBatis-Plus / JWT 鉴权 / 限流 / WebSocket 等高频考点。

---

## ① Spring Boot 是什么

- **一句话**：一个「约定优于配置」的 Java Web 框架，帮你把 Web 服务器、依赖注入、配置等琐事自动装配好，你专心写业务。
- **比喻**：精装修的房子，水电都通好了，你直接搬家具（写业务）住进去。
- **项目例子**：你的 `backend/` 整个就是一个 Spring Boot 应用，用 `@SpringBootApplication` 启动，`pom.xml` 里是 Spring Boot 4.1.1。

---

## ② 控制反转（IoC）与依赖注入（DI）

- **一句话**：对象不再自己 `new` 依赖，而是由 Spring 容器「注入」给你。
- **比喻**：以前你要用杯子就自己烧水造杯子；现在喊一声「我要杯子」，Spring 递给你。
- **项目例子**：
  ```java
  public JwtTokenService(JwtProperties properties) { ... }  // 构造器注入
  ```
  你不 `new JwtProperties()`，Spring 自动帮你传进来。

---

## ③ 三层架构：Controller / Service / Mapper

- **一句话**：把代码按职责分三层——Controller 接请求，Service 写业务逻辑，Mapper 操作数据库。
- **比喻**：餐厅——前台（Controller）接单，厨师（Service）做菜，仓库（Mapper）取食材。
- **项目例子**：
  - `AuthController` → 接 `/auth/login` 等请求
  - `AuthService` → 写登录、登出、刷新令牌的逻辑
  - `UserMapper` → 操作 `users` 表

---

## ④ MyBatis-Plus 是什么

- **一句话**：MyBatis 的增强版，帮你省掉大量重复的 SQL，常用增删改查一行都不用写。
- **比喻**：MyBatis 是「手写 SQL」；MyBatis-Plus 是「自带常用 SQL 模板」。
- **项目例子**：`UserMapper` 继承 `BaseMapper<User>`，就能直接用 `selectById`、`insert` 等方法，不用自己写 XML。

---

## ⑤ JWT 是什么

- **一句话**：一种「自包含」的令牌，把用户信息和过期时间编码进令牌本身，服务端不用存会话。
- **比喻**：一张自带防伪码、写了「谁、几点到期」的门票，验票时不用查后台，看票面就行。
- **项目例子**：`JwtTokenService` 签发/校验 access token 和 refresh token。

---

## ⑥ 双令牌：Access Token + Refresh Token

- **一句话**：Access 短命（30 分钟）用于日常调接口，Refresh 长命（30 天）用于无感续期。
- **比喻**：Access 是「日常通行证」，短命到处用；Refresh 是「换证凭证」，长命只在续期时用。
- **为什么**：Access 暴露频率高，短命能减小泄露风险；Refresh 让用户不用每 30 分钟重新输密码。
- **项目例子**：见 `JwtTokenService` 的 `createAccessToken` / `createRefreshToken`。

---

## ⑦ 会话族 sid —— 解决「登出立刻失效」

- **一句话**：同一次登录签发的 access 和 refresh 共享同一个 `sid`，登出拉黑 `sid`，全部一起失效。
- **为什么需要**：JWT 是无状态的，服务端「不记得」令牌，没法在到期前主动作废；引入黑名单 + sid 就补上了这个能力。
- **比喻**：一次登录 = 一个「会话户口」，登出 = 销户，这个户口下所有令牌（不管哪张还没到期）一起作废。
- **项目例子**：`JwtTokenService` 里 `sid` 声明、`TokenRevocationStore` 黑名单。

---

## ⑧ 令牌桶限流（Token Bucket）

- **一句话**：一个桶按固定速率「补充令牌」，每次请求要「拿走」一个令牌，桶空了就拒绝。
- **比喻**：食堂饭票——固定时间发几张票，没票不能打饭，防止一拥而上。
- **关键点**：允许「突发」——桶里攒的令牌可以一次性用掉，但用完就得等补充。
- **项目例子**：`InMemoryTokenBucketRateLimiter`（内存版）+ `RedisTokenBucketRateLimiter`（Redis 版），配合 `@RateLimit` 注解和 `RateLimitAspect`（AOP）。

---

## ⑨ AOP 面向切面编程

- **一句话**：把「横切」的通用逻辑（日志、限流、事务）抽出来，不散落在每个方法里。
- **比喻**：安检门——不管进哪个房间（方法），都要过这道门（切面）。
- **项目例子**：`RateLimitAspect` 就是切面，用 `@Around` 拦截带 `@RateLimit` 的方法，统一做限流判断。

---

## ⑩ WebSocket 实时推送

- **一句话**：HTTP 是「一问一答」、每次都要重新握手；WebSocket 是「长连接」，一次建立后双方随时互相推。
- **比喻**：HTTP 是「打电话，说完挂断」；WebSocket 是「开着对讲机，随时喊话」。
- **项目例子**：`/ws/quote` 每 3 秒向浏览器推送行情，`quoteSocket` store 负责管理连接。

---

## ⑪ 统一异常处理（Global Exception Handler）

- **一句话**：用 `@RestControllerAdvice` 集中捕获所有异常，统一转成 `{code, message, data}` 结构。
- **比喻**：一个总客服，不管哪个部门出错，都由它统一回复。
- **项目例子**：`GlobalExceptionHandler` 把 `BizException` 转成业务码（如 `AUTH_EXPIRED`），前端据此决定「静默刷新」还是「跳登录」。

---

## ⑫ 数据库多环境（Profile）

- **一句话**：同一套代码，用不同配置文件适配不同环境（开发用 H2，生产用 MySQL）。
- **比喻**：同一件衣服，不同场合换不同配饰。
- **项目例子**：`application-dev.yml`（H2 内存库）+ `application-mysql.yml`（MySQL 8），启动时用 `--spring.profiles.active=mysql` 切换。

---

## 总表

| # | 概念 | 一句话 | 项目例子 |
|---|---|---|---|
| ① | Spring Boot | 自动装配的 Web 框架 | `backend/` 整个应用 |
| ② | IoC / DI | 对象由容器注入 | `JwtTokenService` 构造器注入 |
| ③ | 三层架构 | Controller/Service/Mapper | `AuthController` → `AuthService` → `UserMapper` |
| ④ | MyBatis-Plus | 省掉重复 SQL | `UserMapper extends BaseMapper` |
| ⑤ | JWT | 自包含令牌 | `JwtTokenService` |
| ⑥ | 双令牌 | Access 短 + Refresh 长 | access 30min / refresh 30d |
| ⑦ | 会话族 sid | 登出全灭 | `sid` 声明 + 黑名单 |
| ⑧ | 令牌桶限流 | 拿令牌才放行 | `InMemoryTokenBucketRateLimiter` |
| ⑨ | AOP | 横切逻辑抽出来 | `RateLimitAspect` |
| ⑩ | WebSocket | 长连接推送 | `/ws/quote` 每 3 秒推行情 |
| ⑪ | 全局异常 | 统一转结构 | `GlobalExceptionHandler` |
| ⑫ | Profile | 多环境配置 | dev(H2) / mysql |

---

## ⚠️ 后端最容易踩的坑（重点背）

**1. JWT「登出立刻失效」的坑**：
> JWT 无状态，服务端「不记得」令牌，所以签出去后无法主动作废。解决方案：给同一次登录的 access 和 refresh 盖同一个 `sid`，登出时拉黑 `sid`，过滤器每次请求查黑名单，命中就拒绝。这样没到期的 access token 也能立刻失效。
> —— 如果只拉黑 refresh、不拉黑 access，就会「登出只吊销一半」，access 还能用满 30 分钟。

**2. 限流默认关闭的坑**：
> `enabled` 默认 `false` 会让限流静默失效，接口全都不设防还查不出问题。应该默认开启。

**3. 降级数据要标注的坑**：
> 上游行情不可达时降级到 mock 数据，但界面必须明确标注「演示数据」，绝不能把模拟价格伪装成真实行情。
