#!/bin/sh
set -eu

clojure_command=${1:-clojure}
test_directory=$(mktemp -d)
server_log="$test_directory/server.log"
server_pid=

cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$test_directory"
}
trap cleanup EXIT INT TERM

PORT=0 "$clojure_command" -M:run >"$server_log" 2>&1 &
server_pid=$!

base_url=
attempt=0
while [ "$attempt" -lt 140 ]; do
  base_url=$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$server_log" | tail -n 1)
  if [ -n "$base_url" ] && curl --fail --silent "$base_url/healthz" >/dev/null 2>&1; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.1
done

if [ -z "$base_url" ] || ! curl --fail --silent "$base_url/healthz" >/dev/null 2>&1; then
  echo "server did not become healthy" >&2
  cat "$server_log" >&2
  exit 1
fi

test "$(curl --fail --silent "$base_url/healthz")" = "ok"
curl --fail --silent "$base_url/" | grep -q "FUNCTION RAN FIVE TIMES"
curl --fail --silent "$base_url/styles.css" | grep -q -- "--green:"
curl --fail --silent "$base_url/app.js" | grep -q "renderStrategies"

headers=$(curl --silent --dump-header - --output /dev/null "$base_url/")
printf '%s' "$headers" | grep -qi '^Cache-control: no-store'
printf '%s' "$headers" | grep -qi '^Content-security-policy:'
printf '%s' "$headers" | grep -qi '^X-content-type-options: nosniff'

api_response=$(curl --fail --silent \
  "$base_url/api/simulate?workers=999&operations_per_worker=1&critical_work_us=99999")
printf '%s' "$api_response" | grep -q '"workers":64'
printf '%s' "$api_response" | grep -q '"operationsPerWorker":20'
printf '%s' "$api_response" | grep -q '"criticalWorkUs":2000'
printf '%s' "$api_response" | grep -q '"policy":"alter_hot_ref"'
printf '%s' "$api_response" | grep -q '"policy":"local_batch_flush"'

status=$(curl --silent --output "$test_directory/post.json" --write-out '%{http_code}' \
  --request POST "$base_url/api/simulate")
test "$status" = "405"
grep -q '"error":"method not allowed"' "$test_directory/post.json"

status=$(curl --silent --output "$test_directory/missing.json" --write-out '%{http_code}' \
  "$base_url/missing")
test "$status" = "404"
grep -q '"error":"not found"' "$test_directory/missing.json"

if HOST=not-an-interface "$clojure_command" -M:run >"$test_directory/host.log" 2>&1; then
  echo "invalid HOST unexpectedly succeeded" >&2
  exit 1
fi
grep -q "HOST must be" "$test_directory/host.log"

echo "ServerTest: routes, normalization, headers, errors, and host validation passed"
