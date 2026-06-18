#!/bin/bash
set -e

# 启动Java后端（docker内监听8003端口）
java -jar /app/xiaozhi-esp32-api.jar \
  --server.port=8003 \
  --spring.datasource.druid.url=${SPRING_DATASOURCE_DRUID_URL} \
  --spring.datasource.druid.username=${SPRING_DATASOURCE_DRUID_USERNAME} \
  --spring.datasource.druid.password=${SPRING_DATASOURCE_DRUID_PASSWORD} \
  --spring.data.redis.host=${SPRING_DATA_REDIS_HOST} \
  --spring.data.redis.password=${SPRING_DATA_REDIS_PASSWORD} \
  --spring.data.redis.port=${SPRING_DATA_REDIS_PORT} &
java_pid=$!

# 启动Nginx。Java 或 Nginx 任意一个退出，容器都退出并交给 Docker 重启。
nginx -g 'daemon off;' &
nginx_pid=$!

shutdown() {
  kill "$java_pid" "$nginx_pid" 2>/dev/null || true
  wait "$java_pid" "$nginx_pid" 2>/dev/null || true
}

trap shutdown TERM INT

set +e
wait -n "$java_pid" "$nginx_pid"
status=$?

if ! kill -0 "$java_pid" 2>/dev/null; then
  echo "manager-api exited; stopping nginx" >&2
elif ! kill -0 "$nginx_pid" 2>/dev/null; then
  echo "nginx exited; stopping manager-api" >&2
fi

shutdown
exit "$status"
