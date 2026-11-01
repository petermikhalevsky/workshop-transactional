# JRE-only образ — принципиально для воркшопа.
# В контейнере нет jstack/jcmd: диагностировать можно только через kill -3,
# /actuator/threaddump и внешний JDK-контейнер, вошедший в PID namespace.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC"

RUN apk add --no-cache wget curl
RUN mkdir -p /app/artifacts

COPY --from=build /build/target/order-service.jar /app/app.jar

EXPOSE 8080 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=6 \
    CMD wget -qO- http://localhost:8081/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
