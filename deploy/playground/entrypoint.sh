#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
mkdir -p /data/nuget /data/service /tmp/dotnet-home
if ! cmp -s /opt/bhash/warm-version /data/nuget/.bhash-warmed; then
    cp -R /opt/nuget/. /data/nuget/
    cp /opt/bhash/warm-version /data/nuget/.bhash-warmed
fi
exec java -Xmx256m -cp /opt/bhash/compiler.jar dev.bhash.playground.PlaygroundServer
