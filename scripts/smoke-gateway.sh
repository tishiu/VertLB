#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/build/smoke"

USER_BACKEND_PID=""
ORDER_BACKEND_PID=""
VERTILB_PID=""

cleanup() {
  for pid in "${VERTILB_PID}" "${USER_BACKEND_PID}" "${ORDER_BACKEND_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done

  wait "${VERTILB_PID}" "${USER_BACKEND_PID}" "${ORDER_BACKEND_PID}" 2>/dev/null || true
}

trap cleanup EXIT

cd "${ROOT_DIR}"

echo "Compiling VertiLB..."
./gradlew clean compileJava

mkdir -p "${LOG_DIR}"

echo "Starting mock backends..."
python3 scripts/mock-backend.py --port 9001 --service user-service > "${LOG_DIR}/user-backend.log" 2>&1 &
USER_BACKEND_PID=$!

python3 scripts/mock-backend.py --port 9011 --service order-service > "${LOG_DIR}/order-backend.log" 2>&1 &
ORDER_BACKEND_PID=$!

echo "Starting VertiLB..."
./gradlew --no-daemon run --args="-c examples/gateway-routing.json" > "${LOG_DIR}/vertilb.log" 2>&1 &
VERTILB_PID=$!

http_status() {
  python3 - "$1" <<'PY'
import sys
import urllib.error
import urllib.request

try:
    with urllib.request.urlopen(sys.argv[1], timeout=1) as response:
        print(response.status)
except urllib.error.HTTPError as error:
    print(error.code)
except Exception:
    print("000")
PY
}

get_json() {
  python3 - "$1" <<'PY'
import json
import sys
import urllib.request

with urllib.request.urlopen(sys.argv[1], timeout=3) as response:
    print(response.read().decode("utf-8"))
PY
}

assert_json_field() {
  local json="$1"
  local field="$2"
  local expected="$3"

  python3 - "$json" "$field" "$expected" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
value = payload
for part in sys.argv[2].split("."):
    value = value[part]

actual = str(value)
expected = sys.argv[3]
if actual != expected:
    raise SystemExit(f"Expected {sys.argv[2]}={expected}, got {actual}")
PY
}

assert_json_key() {
  local json="$1"
  local key="$2"

  python3 - "$json" "$key" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
if sys.argv[2] not in payload:
    raise SystemExit(f"Missing key: {sys.argv[2]}")
PY
}

wait_for_status() {
  local name="$1"
  local url="$2"
  local expected="$3"

  for _ in {1..60}; do
    if [[ "$(http_status "${url}")" == "${expected}" ]]; then
      echo "${name} ready"
      return 0
    fi
    sleep 1
  done

  echo "${name} did not become ready at ${url}" >&2
  echo "VertiLB log:" >&2
  tail -120 "${LOG_DIR}/vertilb.log" >&2 || true
  return 1
}

wait_for_status "user backend" "http://127.0.0.1:9001/health" "200"
wait_for_status "order backend" "http://127.0.0.1:9011/health" "200"
wait_for_status "gateway" "http://127.0.0.1:8080/api/unknown" "404"
wait_for_status "metrics" "http://127.0.0.1:9100/metrics" "200"

echo "Checking users route..."
users_json="$(get_json "http://127.0.0.1:8080/api/users/1?debug=true")"
assert_json_field "${users_json}" "service" "user-service"
assert_json_field "${users_json}" "path" "/users/1"
assert_json_field "${users_json}" "query" "debug=true"

echo "Checking orders route..."
orders_json="$(get_json "http://127.0.0.1:8080/api/orders/99")"
assert_json_field "${orders_json}" "service" "order-service"
assert_json_field "${orders_json}" "path" "/orders/99"

echo "Checking unmatched route..."
unknown_status="$(http_status "http://127.0.0.1:8080/api/unknown")"
if [[ "${unknown_status}" != "404" ]]; then
  echo "Expected /api/unknown to return 404, got ${unknown_status}" >&2
  exit 1
fi

echo "Checking metrics..."
metrics_json="$(get_json "http://127.0.0.1:9100/metrics")"
assert_json_key "${metrics_json}" "totalRequests"
assert_json_key "${metrics_json}" "requestsByPool"
assert_json_key "${metrics_json}" "poolStats"

echo "Smoke gateway passed"
