# ---------------------------------------------------------------------------
# 后端镜像：多阶段构建
#
# 为什么用多阶段：编译需要 JDK + Maven + 完整依赖树（数百 MB），
# 运行只需要 JRE + 一个 fat jar。不分阶段的话，最终镜像会带着整个
# Maven 仓库，体积从 ~200MB 涨到 ~800MB，攻击面也大得多。
# ---------------------------------------------------------------------------

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只拷 pom.xml 并预热依赖。
# 这样只要 pom.xml 没变，改业务代码时这一层缓存就能命中，
# 不必每次重新下载全部依赖（这是最耗时的一步）。
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷源码并打包。跳过测试：测试应在 CI 的独立阶段跑，
# 而不是在镜像构建里跑（否则构建慢且失败原因混淆）。
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package


# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre-jammy

# 时区显式设置为东八区：容器默认 UTC，日志时间与 K 线时间会对不上，
# 排查「开盘前后数据异常」这类问题时会造成误导
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

WORKDIR /app

# 非 root 运行：容器逃逸时能拿到的权限更少
RUN groupadd -r app && useradd -r -g app app

COPY --from=builder /build/target/stock-analyst-backend.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# 使用 exec 形式让 JVM 成为 PID 1，能正确接收 SIGTERM。
# 配合 application.yml 里的 server.shutdown=graceful，
# 容器停止时会等在途请求处理完，而不是直接掐断。
#
# 健康检查打 /actuator/health；compose 里的 depends_on 也依赖它。
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health || exit 1"]

# 默认用 mysql profile：容器编排里一定是连真实数据库。
# 环境变量（JWT_SECRET / DB_* / LLM_* 等）由 docker-compose 注入。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-mysql}"]
