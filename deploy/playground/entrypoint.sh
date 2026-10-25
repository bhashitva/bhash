#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
umask 077
mkdir -p /data/nuget /data/service /data/user-data /tmp/dotnet-home /tmp/bhash-runtime
chmod 700 /tmp/bhash-runtime
if ! cmp -s /opt/bhash/warm-version /data/nuget/.bhash-warmed; then
    cp -R /opt/nuget/. /data/nuget/
    cp /opt/bhash/warm-version /data/nuget/.bhash-warmed
fi
exec java -Xmx256m -cp /opt/bhash/compiler.jar dev.bhash.playground.PlaygroundServer
