# Runs a jar built beforehand on the host (./mvnw package). Build context: the repository root.
# Layers are extracted so that dependencies, which change rarely, cache apart from application code.
# Base images are pinned by the digest of their multi-platform index; Dependabot updates tag and digest
# together.

FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS extract
WORKDIR /build
COPY workspace-app/target/workspace-app-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
RUN addgroup -S -g 10001 workspace && adduser -S -u 10001 -G workspace workspace
WORKDIR /application
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
# By number: a name may not resolve on the host that runs the image (hadolint DL3066).
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
