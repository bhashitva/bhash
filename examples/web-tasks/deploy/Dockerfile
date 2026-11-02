# SPDX-License-Identifier: Apache-2.0
# Supply a Linux executable built for glibc <= 2.35, plus index.html.
FROM ubuntu:22.04
RUN groupadd --gid 10001 bhash && useradd --uid 10001 --gid 10001 --no-create-home bhash && mkdir /app /data && chown 10001:10001 /data
WORKDIR /app
COPY web-tasks /app/web-tasks
COPY index.html /app/index.html
RUN chmod 0555 /app/web-tasks
USER 10001:10001
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/app/web-tasks","--host=0.0.0.0","--port=8080","--db=/data/tasks.db","--page=/app/index.html"]
