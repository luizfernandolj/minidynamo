# Build em container: a imagem nao depende do JDK/Maven da maquina de quem
# roda. Qualquer integrante do grupo (ou o professor) reproduz com o mesmo
# comando, que e a exigencia de "execucao reprodutivel" do enunciado.
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Dependencias primeiro, em camada separada: enquanto o pom.xml nao muda, o
# docker build reaproveita o cache e nao baixa o Maven Central de novo.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package


# Imagem final so com o JRE: ~90 MB por no em vez dos ~450 MB da imagem de
# build. Com 5 nos a diferenca importa.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/target/minidynamo.jar ./minidynamo.jar

EXPOSE 8080

# -XX:+UseSerialGC e Xmx pequeno: cada no guarda poucos MB em memoria e o que
# interessa e subir rapido (medimos tempo de recuperacao), nao throughput de GC.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx128m", "-jar", "/app/minidynamo.jar"]
