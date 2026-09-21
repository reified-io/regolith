# build context: the repository root.
#
# the server image is also the image of its helper containers: the firewall helper, the home disk
# helper and the network probe all run from it, so what the server drives and what it checks come
# from the same build. the `tools` stage is that part alone, for tests that need helpers without the
# server.

# every base image is pinned by digest, which dependabot refreshes: a tag can be rebuilt under the
# server, a digest cannot.
FROM eclipse-temurin:21-jre@sha256:49e21e16e3c86eb7816a44a67549910ed090fbeb40c29c525d58bf5e02e91b0f AS tools

# the server drives the host's docker daemon through this binary, so the tarball is verified against a
# pinned checksum instead of trusting the download. version and checksum change together.
ARG DOCKER_CLI_VERSION=29.7.2
ARG DOCKER_CLI_SHA256=803d433f226db4776e1768fd319fc6c6e4935a456acf84fcc0080818b854bc8f

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl \
        iptables iproute2 netcat-openbsd \
        e2fsprogs util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/docker.tgz "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
    && echo "${DOCKER_CLI_SHA256}  /tmp/docker.tgz" | sha256sum -c - \
    && tar -xzf /tmp/docker.tgz -C /usr/local/bin --strip-components=1 docker/docker \
    && rm /tmp/docker.tgz \
    && docker --version

COPY images/server/scripts/firewall.sh images/server/scripts/homedisk.sh /opt/regolith/scripts/
RUN chmod 755 /opt/regolith/scripts/*.sh

FROM eclipse-temurin:21-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties detekt.yml ./
COPY gradle gradle
# the convention plugins every module applies.
COPY buildSrc buildSrc
# every module settings.gradle.kts includes must exist, even the ones the server does not build.
COPY protocol protocol
COPY server server
COPY pages pages
COPY sdk sdk
COPY koog koog
RUN ./gradlew :server:installDist --no-daemon --console=plain

FROM tools

COPY --from=build /src/server/build/install/server /opt/regolith
# the image redistributes the work, and apache-2.0 asks that a copy travel with it.
COPY LICENSE /opt/regolith/LICENSE

ENV REGOLITH_STATE_DIR=/var/lib/regolith
# the state directory is deliberately not a VOLUME: every helper runs this same image and mounts
# nothing there, so a declared volume makes docker create an anonymous one per helper run. a
# deployment mounts a named volume, and the server creates the directory when it is not mounted.

# the pages role serves the public and holds no socket, so it runs unprivileged. docker copies this
# ownership onto a fresh named volume, which is what lets uid 1000 write its releases.
RUN install -d -o 1000 -g 1000 /var/lib/regolith-pages
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/v1/health || exit 1

ENTRYPOINT ["/opt/regolith/bin/server"]
