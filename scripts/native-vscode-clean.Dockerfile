# SPDX-License-Identifier: Apache-2.0
ARG UBUNTU_VERSION=24.04
FROM node:22-bookworm-slim AS node
FROM ubuntu:${UBUNTU_VERSION}
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates \
 && rm -rf /var/lib/apt/lists/* && useradd --create-home --uid 10001 bhash \
 && mkdir -p /home/bhash/.cache && chown bhash:bhash /home/bhash/.cache
COPY --from=node /usr/local/bin/node /usr/local/bin/node
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 BHASH_TOOLCHAIN_HOME=/home/bhash/.cache/bhash/toolchains
USER bhash
WORKDIR /home/bhash
