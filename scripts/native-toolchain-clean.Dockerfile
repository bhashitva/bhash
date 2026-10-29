# SPDX-License-Identifier: Apache-2.0
ARG UBUNTU_VERSION=24.04
FROM ubuntu:${UBUNTU_VERSION}
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates \
 && rm -rf /var/lib/apt/lists/* && useradd --create-home --uid 10001 bhash
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
USER bhash
ENV BHASH_TOOLCHAIN_HOME=/home/bhash/.cache/bhash/toolchains
WORKDIR /home/bhash
