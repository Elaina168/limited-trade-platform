# 只打包本机已验证的JAR，构建过程不重新下载Maven依赖或运行全量测试。
FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

WORKDIR /app
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs \
    && chown 10001:10001 /app/logs

COPY --chown=10001:10001 target/limited-trade-platform-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=0755 docker/app-healthcheck.sh /usr/local/bin/app-healthcheck

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["/usr/local/bin/app-healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
