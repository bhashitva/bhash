# SPDX-License-Identifier: Apache-2.0
FROM node:24-alpine AS editor
WORKDIR /src/playground
COPY playground/package*.json ./
RUN npm ci --no-audit --no-fund
COPY playground/ ./
COPY deploy/analytics/ /src/deploy/analytics/
COPY src/main/antlr4/Bhash.g4 /src/src/main/antlr4/Bhash.g4
COPY LICENSE NOTICE LICENSING.md /src/
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS compiler
WORKDIR /src
COPY pom.xml ./
COPY src/ src/
COPY LICENSE NOTICE LICENSING.md THIRD_PARTY_NOTICES.md ./
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM mcr.microsoft.com/dotnet/sdk:10.0-noble
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE=true DOTNET_PROCESSOR_COUNT=2 \
    NUGET_PACKAGES=/data/nuget DOTNET_CLI_HOME=/tmp/dotnet-home \
    BHASH_COMPILER_JAR=/opt/bhash/compiler.jar BHASH_WEB=/opt/bhash/web \
    BHASH_DATA=/data/service BHASH_BIND=0.0.0.0 PORT=8080 BHASH_WASM_OFFLINE=true
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
# SDK and reference/package dependencies are restored once during image construction.
RUN dotnet workload install wasm-tools
COPY --from=compiler /src/target/bhash-compiler-0.1.0-SNAPSHOT.jar /opt/bhash/compiler.jar
COPY --from=editor /src/playground/dist /opt/bhash/web
RUN mkdir -p /data /opt/nuget /tmp/warm \
    && printf 'कार्य मुख्य() -> शून्य { लिखो("तैयार"); }' > /tmp/warm/hello.bhash \
    && BHASH_WASM_OFFLINE=false NUGET_PACKAGES=/opt/nuget java -jar /opt/bhash/compiler.jar compile /tmp/warm/hello.bhash --target wasm-browser -o /tmp/warm/web \
    && BHASH_WASM_OFFLINE=false NUGET_PACKAGES=/opt/nuget java -cp /opt/bhash/compiler.jar dev.bhash.backend.wasm.PrebuiltBrowserHost /opt/bhash/browser-host \
    && (dotnet --version; sha256sum /opt/bhash/compiler.jar) > /opt/bhash/warm-version \
    && rm -rf /tmp/warm \
    && useradd --uid 10001 --create-home --home-dir /home/bhash bhash \
    && chown -R 10001:10001 /data
COPY --chmod=755 deploy/playground/entrypoint.sh /opt/bhash/entrypoint.sh
ENV BHASH_BROWSER_HOST=/opt/bhash/browser-host \
    XDG_RUNTIME_DIR=/tmp/bhash-runtime XDG_DATA_HOME=/data/user-data \
    LANG=C.UTF-8 LC_ALL=C.UTF-8
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD curl --fail --silent http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["/opt/bhash/entrypoint.sh"]
