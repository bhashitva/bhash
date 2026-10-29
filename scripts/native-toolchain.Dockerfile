# SPDX-License-Identifier: Apache-2.0
FROM ubuntu:22.04
# Ubuntu libraries are resolved from an immutable snapshot; LLVM 18 is pinned to
# the official LLVM Jammy build and authenticated by its dedicated signing key.
RUN apt-get -o APT::Update::Error-Mode=any update && apt-get install -y --no-install-recommends ca-certificates curl gnupg && rm -rf /var/lib/apt/lists/*
ARG UBUNTU_SNAPSHOT=20261001T000000Z
ARG LLVM_DEB_VERSION=18.1.8~++20240731024944+3b5b5c1ec4a3-1~exp1~20240731145000.144
RUN rm -f /etc/apt/sources.list \
 && printf 'Types: deb deb-src\nURIs: https://snapshot.ubuntu.com/ubuntu/%s/\nSuites: jammy jammy-updates jammy-security\nComponents: main universe\nCheck-Valid-Until: no\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' "$UBUNTU_SNAPSHOT" > /etc/apt/sources.list.d/ubuntu.sources \
 && printf 'Package: *\nPin: origin snapshot.ubuntu.com\nPin-Priority: 1001\n' > /etc/apt/preferences.d/bhash-snapshot \
 && curl --proto '=https' --tlsv1.2 -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key -o /usr/share/keyrings/bhash-llvm.asc \
 && gpg --batch --show-keys --with-colons /usr/share/keyrings/bhash-llvm.asc | grep -q '^fpr:::::::::6084F3CF814B57C1CF12EFD515CF4D18AF4F7421:'
RUN printf 'Types: deb deb-src\nURIs: https://apt.llvm.org/jammy/\nSuites: llvm-toolchain-jammy-18\nComponents: main\nSigned-By: /usr/share/keyrings/bhash-llvm.asc\n' > /etc/apt/sources.list.d/bhash-llvm.sources \
 && apt-get -o APT::Update::Error-Mode=any update \
 && DEBIAN_FRONTEND=noninteractive apt-get -y --allow-downgrades dist-upgrade \
 && apt-cache policy clang-18 llvm-18 lld-18 libclang-rt-18-dev \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --allow-downgrades "clang-18=$LLVM_DEB_VERSION" "llvm-18=$LLVM_DEB_VERSION" "lld-18=$LLVM_DEB_VERSION" "libclang-rt-18-dev=$LLVM_DEB_VERSION" libc6-dev libgcc-11-dev linux-libc-dev binutils python3 dpkg-dev ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV BHASH_UBUNTU_SNAPSHOT=${UBUNTU_SNAPSHOT} BHASH_LLVM_DEB_VERSION=${LLVM_DEB_VERSION}
COPY scripts/build_native_toolchain.py LICENSE NOTICE /builder/
CMD ["sh", "-eu", "-c", "apt-get -o APT::Update::Error-Mode=any update >/dev/null && python3 /builder/build_native_toolchain.py /out"]
