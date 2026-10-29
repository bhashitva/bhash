# SPDX-License-Identifier: Apache-2.0
FROM ubuntu:24.04
ARG UBUNTU_SNAPSHOT=20261001T000000Z
RUN printf 'Types: deb deb-src\nURIs: http://snapshot.ubuntu.com/ubuntu/%s/\nSuites: noble noble-updates noble-security\nComponents: main universe\nCheck-Valid-Until: no\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' "$UBUNTU_SNAPSHOT" > /etc/apt/sources.list.d/ubuntu.sources \
 && printf 'Package: *\nPin: origin snapshot.ubuntu.com\nPin-Priority: 1001\n' > /etc/apt/preferences.d/bhash-snapshot \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get -y --allow-downgrades dist-upgrade \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --allow-downgrades clang-18 llvm-18 lld-18 libclang-rt-18-dev libc6-dev libgcc-13-dev linux-libc-dev binutils python3 dpkg-dev ca-certificates \
 && rm -rf /var/lib/apt/lists/*
# Source indexes are needed when constructing the companion source archive.
ENV BHASH_UBUNTU_SNAPSHOT=${UBUNTU_SNAPSHOT}
COPY scripts/build_native_toolchain.py LICENSE NOTICE /builder/
CMD ["sh", "-eu", "-c", "apt-get update >/dev/null && python3 /builder/build_native_toolchain.py /out"]
