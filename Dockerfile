################################################################################
### Build Image
################################################################################
FROM eclipse-temurin:21 AS build-image

WORKDIR /app

# Install SBT
ENV SBT_VERSION=1.12.0
RUN curl -sSL "https://github.com/sbt/sbt/releases/download/v$SBT_VERSION/sbt-$SBT_VERSION.tgz" | gunzip | tar -x -C /usr/local \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

# Copy Source
COPY . /app/

# Build Jar
RUN sbt "examples/assembly"

################################################################################
### Final Image
################################################################################
FROM eclipse-temurin:21

# Create a non-root user
RUN groupadd -f --gid 1000 --system appuser && useradd -o -m --uid 1000 --system appuser --gid 1000

WORKDIR /home/appuser

COPY --chown=appuser:appuser --chmod=500 --from=build-image /app/examples/target/scala-3.7.4/app.jar /home/appuser/app.jar

USER appuser

CMD ["java", "-jar", "app.jar"]
