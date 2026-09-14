# the sandbox images. `base` is the default: small, so an agent checks what its task needs and installs the
# rest into its home. `full` adds the tools a chat agent reaches for with media and documents, which no
# user can install without root. whatever replaces either must provide bash, coreutils, findutils and
# grep, which the server's file and signal helpers run.
#
#   docker build --target base -t regolith-sandbox images/sandbox
#   docker build --target full -t regolith-sandbox-full images/sandbox

FROM debian:trixie-slim AS base

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash coreutils findutils grep procps \
        ca-certificates curl git jq less \
        python3 python3-venv \
        nodejs npm \
        zip unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

# no setuid path back to root. capabilities are already empty and no-new-privileges is set, but neither
# should be the only thing between the sandbox user and root.
RUN find / -xdev -perm /6000 -type f -exec chmod u-s,g-s {} + \
    && groupadd --gid 1000 sandbox \
    && useradd --uid 1000 --gid 1000 --home-dir /home/sandbox --shell /bin/bash sandbox \
    && mkdir -p /home/sandbox \
    && chown 1000:1000 /home/sandbox \
    && chmod 700 /home/sandbox

# tools a sandbox installs for itself land in its persistent home and stay on the path.
ENV LANG=C.UTF-8 \
    NPM_CONFIG_PREFIX=/home/sandbox/.local \
    PATH=/home/sandbox/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

USER 1000:1000
WORKDIR /home/sandbox

FROM base AS full

USER 0:0

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg imagemagick pandoc \
        sqlite3 ripgrep wget file \
        chromium fonts-dejavu-core fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*

# chromium brings a setuid sandbox helper; strip it like everything else. the container is the sandbox
# chromium would build: without capabilities or user namespaces its own cannot start, so the debian
# wrapper is told to run without it, headless, and away from the small /dev/shm a container gets.
RUN find / -xdev -perm /6000 -type f -exec chmod u-s,g-s {} + \
    && printf '%s\n' 'export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --headless=new --disable-gpu --disable-dev-shm-usage"' \
        > /etc/chromium.d/regolith

USER 1000:1000
