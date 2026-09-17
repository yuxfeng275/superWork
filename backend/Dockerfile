# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .

COPY src ./src
RUN mvn clean package -Dmaven.test.skip=true -B

# 运行阶段 (eclipse-temurin:17-jre 支持 ARM64/x86_64)
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "exec java -Djava.security.egd=file:/dev/./urandom -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar app.jar"]
