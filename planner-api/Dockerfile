# The API only — the frontend is served by Cloudflare Pages (docs/deploy.md).
#
# Build from the repository root:   docker build -t planner-api planner-api
#
# Tests are not run here: they need Docker themselves (Testcontainers), which
# an image build does not have. Run ./gradlew test before building.

# ---------------------------------------------------------------- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies in a layer of their own, keyed only on the build files, so
# editing a .java file does not download the internet again. `dependencies`
# alone would fetch POMs but not jars, hence the small init script that
# resolves the configurations bootJar actually uses.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN printf '%s\n' \
      'allprojects {' \
      '  tasks.register("resolveDependencies") {' \
      '    doLast {' \
      '      ["compileClasspath", "runtimeClasspath", "productionRuntimeClasspath", "annotationProcessor"]' \
      '        .collect { configurations.findByName(it) }.findAll { it != null }*.resolve()' \
      '    }' \
      '  }' \
      '}' > /tmp/resolve.gradle \
 && ./gradlew --no-daemon --quiet --init-script /tmp/resolve.gradle resolveDependencies

# The boot jar picked explicitly: Gradle also writes a *-plain.jar beside it,
# which has no dependencies inside and would fail at startup.
COPY src src
RUN ./gradlew --no-daemon --quiet bootJar -x test \
 && jars="$(find build/libs -name '*.jar' ! -name '*-plain.jar')" \
 && [ "$(echo "$jars" | wc -l)" -eq 1 ] && cp "$jars" /tmp/app.jar

# Extract into layers. Deliberately without --launcher: that layout starts
# through Spring's own class loader, whose classes class-data sharing cannot
# archive — the CDS archive below would then hold only the JDK's classes.
# Plain `extract` writes app.jar with a Class-Path manifest over lib/, which
# the JVM's application class loader reads, and CDS covers all of it.
RUN java -Djarmode=tools -jar /tmp/app.jar extract --layers --destination /extracted

# -------------------------------------------------------------- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Least-changing layer first, so a code change ships only the last one.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Class-data-sharing training run: start the context and exit as soon as it
# has refreshed, writing every class that was loaded into app.jsa. It runs in
# this stage, not the JDK one, because an archive is only accepted by the
# exact JVM build that wrote it. YAML mode against a throwaway directory, so
# no database and no network are needed, and that directory is removed in the
# same layer — nothing it wrote (a generated JWT secret among it) survives.
RUN FEATURE_ENABLE_DATABASE=false DATA_DIR=/tmp/cds-data PUBLISH_DIR=/tmp/cds-data/published \
      java -XX:ArchiveClassesAtExit=app.jsa -Xlog:cds=error -Dspring.context.exit=onRefresh -jar app.jar \
 && rm -rf /tmp/cds-data /tmp/tomcat*

# The application files stay root-owned and read-only to the app. Cloud Run's
# disk is in memory and discarded, so the only writable place is a scratch
# directory for whatever still defaults to a file (mail in file mode, a
# self-generated JWT secret when JWT_SECRET is unset).
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin planner \
 && mkdir /data && chown planner /data
ENV DATA_DIR=/data \
    PUBLISH_DIR=/data/published
USER planner

# The port comes from $PORT (application.yml), which Cloud Run sets.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
