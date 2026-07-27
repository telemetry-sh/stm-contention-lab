#!/bin/sh
set -eu

image=${1:-stm-contention-lab:test}
container_id=$(docker run --detach "$image")

cleanup() {
  docker rm --force "$container_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

attempt=0
while [ "$attempt" -lt 80 ]; do
  if docker exec "$container_id" wget -q -O - http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

test "$(docker exec "$container_id" id -u)" = "10001"
test "$(docker exec "$container_id" id -g)" = "10001"
test "$(docker exec "$container_id" wget -q -O - http://127.0.0.1:8080/healthz)" = "ok"
docker exec "$container_id" wget -q -O - http://127.0.0.1:8080/ \
  | grep -q "FUNCTION RAN FIVE TIMES"
docker exec "$container_id" clojure -M:json | grep -q '"local_batch_flush"'

echo "ContainerTest: non-root runtime, health, UI, and CLI passed"
