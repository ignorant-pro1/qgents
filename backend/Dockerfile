# syntax=docker/dockerfile:1
# 后端应用镜像：在仓库根目录构建；用户命令的沙箱镜像位于 sandbox-images/dev-tools。
# 第一阶段使用 JDK 和 Maven 编译，第二阶段仅保留运行应用所需的 JRE 与 JAR。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先复制 POM 预取依赖；源码变化时可复用依赖层和 BuildKit 的 Maven 缓存。
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# 镜像打包跳过测试，提交前使用 JDK 21 和 Maven 在宿主机执行 mvn clean package。
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# 运行阶段：curl 用于 Compose 健康检查，CA 证书用于 HTTPS 访问。
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 qgents \
    && useradd --uid 10001 --gid qgents --create-home qgents
WORKDIR /app
COPY --from=build /build/target/qgents-0.0.1-SNAPSHOT.jar app.jar
# 固定 UID/GID 10001；宿主机挂载目录也需允许此用户写入。
RUN mkdir -p /app/logs /app/data /var/lib/qgents/workspaces /var/lib/qgents/workspace-metadata \
    && chown -R qgents:qgents /app /var/lib/qgents
USER 10001:10001
# 8080 为业务 API，8081 为运维端点；实际对外端口由 Compose 的 ports 决定。
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
