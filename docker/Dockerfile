# syntax=docker/dockerfile:1
###############################################################################
# HormigaMessanger — container image (JVM, Quarkus fast-jar)
#
# Build from the REPO ROOT (build context = project root, this file lives in docker/):
#   docker build -f docker/Dockerfile -t hormiga-messenger:local .
# or via the compose in this folder:
#   docker compose -f docker/docker-compose.yml up -d --build
#
# Two stages: (1) build with JDK 25 + the Maven wrapper; (2) slim JRE 25 runtime.
# The runtime bakes in the MEMORY GUARDS (MALLOC_ARENA_MAX + bounded heap/direct/metaspace) —
# without them the JVM's virtual memory balloons to ~24 GB. See docker/README.md.
###############################################################################

# ---- stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Dependency layer first so it caches while only source changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B -ntp dependency:go-offline

# Source + package (tests run in CI, not in the image build — they need Docker/testcontainers).
COPY src/ src/
RUN ./mvnw -q -B -ntp clean package -DskipTests

# ---- stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# curl only, for the container HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV LANGUAGE='en_US:en' \
    # glibc arena cap — THE fix for the runaway virtual-memory footprint.
    MALLOC_ARENA_MAX=2 \
    # Bounded JVM footprint → VIRT settles ~3.2 GB (vs ~24 GB unbounded). Override at run time if needed.
    JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 \
-Djava.util.logging.manager=org.jboss.logmanager.LogManager \
-XX:+UseG1GC \
-Xmx2g \
-XX:MaxDirectMemorySize=1g \
-XX:MaxMetaspaceSize=256m \
-XX:ReservedCodeCacheSize=256m" \
    # Deploys usually mount a volume here; the app also honours LOG_FILE.
    LOG_FILE=/var/log/messenger/messenger.log

WORKDIR /deployments

# Four fast-jar layers so library layers stay cached when only app code changes.
COPY --from=build /build/target/quarkus-app/lib/      /deployments/lib/
COPY --from=build /build/target/quarkus-app/*.jar     /deployments/
COPY --from=build /build/target/quarkus-app/app/      /deployments/app/
COPY --from=build /build/target/quarkus-app/quarkus/  /deployments/quarkus/

# Non-root.
RUN useradd -r -u 1001 -g root appuser \
    && mkdir -p /var/log/messenger \
    && chown -R appuser:root /deployments /var/log/messenger
USER appuser

EXPOSE 8080

# Readiness = Flyway migrated + reactive datasource + Redis reachable.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:8080/q/health/ready || exit 1

ENTRYPOINT [ "sh", "-c", "exec java $JAVA_OPTS -jar /deployments/quarkus-run.jar" ]
