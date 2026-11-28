FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /build
COPY . .
ARG APP_GIT_SHA
ARG APP_RELEASE_LABEL=compose
ARG APP_REQUIRED_MIGRATION=018_product_search_sync
RUN --mount=type=cache,target=/root/.m2/repository \
    set -eu; sha="$APP_GIT_SHA"; \
    if [ -z "$sha" ] && [ -f .git/HEAD ]; then \
      head=$(cat .git/HEAD); \
      case "$head" in \
        'ref: '*) ref=${head#ref: }; \
          if [ -f ".git/$ref" ]; then sha=$(cat ".git/$ref"); \
          else sha=$(awk -v ref="$ref" '$2 == ref {print $1}' .git/packed-refs); fi ;; \
        *) sha=$head ;; \
      esac; \
    fi; \
    mvn -B -ntp -Dmaven.test.skip=true "-Dbuild.git.commit=${sha:-unknown}" \
      "-Dbuild.release.label=$APP_RELEASE_LABEL" "-Dbuild.required.migration=$APP_REQUIRED_MIGRATION" package

FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

WORKDIR /app
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs \
    && chown 10001:10001 /app/logs

COPY --from=build --chown=10001:10001 /build/target/limited-trade-platform-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=0755 docker/app-healthcheck.sh /usr/local/bin/app-healthcheck
COPY --chmod=0755 docker/app-entrypoint.sh /usr/local/bin/app-entrypoint

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=6 \
    CMD ["/usr/local/bin/app-healthcheck"]
ENTRYPOINT ["/usr/local/bin/app-entrypoint"]
