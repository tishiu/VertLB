#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GATEWAY_PORT="${GATEWAY_PORT:-8080}"
METRICS_PORT="${METRICS_PORT:-9100}"
USER_BACKEND_PORT="${USER_BACKEND_PORT:-9001}"
ORDER_BACKEND_PORT="${ORDER_BACKEND_PORT:-9011}"
SMOKE_WAIT_RETRIES="${SMOKE_WAIT_RETRIES:-40}"
SMOKE_WAIT_SLEEP="${SMOKE_WAIT_SLEEP:-0.5}"
KEEP_SMOKE_LOGS="${KEEP_SMOKE_LOGS:-false}"

USER_BACKEND_PID=""
ORDER_BACKEND_PID=""
VERTILB_PID=""
RUNTIME_DIR=""
COMPILE_LOG=""
VERTILB_LOG=""
USER_BACKEND_LOG=""
ORDER_BACKEND_LOG=""
CONFIG_PATH=""

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command not found: ${command_name}" >&2
    exit 1
  fi
}

check_required_commands() {
  require_command curl
  require_command python3
  require_command grep
  require_command mktemp
  require_command kill
}

dump_log() {
  local label="$1"
  local path="$2"

  if [[ -n "${path}" && -f "${path}" ]]; then
    echo "===== ${label}: ${path} =====" >&2
    cat "${path}" >&2 || true
  fi
}

dump_logs() {
  dump_log "compile log" "${COMPILE_LOG}"
  dump_log "VertiLB runtime log" "${VERTILB_LOG}"
  dump_log "user backend log" "${USER_BACKEND_LOG}"
  dump_log "order backend log" "${ORDER_BACKEND_LOG}"
}

on_error() {
  local line_number="$1"

  echo "Smoke gateway failed at line ${line_number}" >&2
  dump_logs
}

cleanup() {
  for pid in "${VERTILB_PID}" "${USER_BACKEND_PID}" "${ORDER_BACKEND_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done

  for pid in "${VERTILB_PID}" "${USER_BACKEND_PID}" "${ORDER_BACKEND_PID}"; do
    if [[ -n "${pid}" ]]; then
      wait "${pid}" 2>/dev/null || true
    fi
  done

  if [[ -n "${RUNTIME_DIR}" && -d "${RUNTIME_DIR}" ]]; then
    if [[ "${KEEP_SMOKE_LOGS}" == "true" ]]; then
      echo "Keeping smoke logs in ${RUNTIME_DIR}"
    else
      rm -rf "${RUNTIME_DIR}"
    fi
  fi
}

generate_config() {
  python3 - "${ROOT_DIR}/examples/gateway-routing.json" "${CONFIG_PATH}" \
    "${GATEWAY_PORT}" "${METRICS_PORT}" "${USER_BACKEND_PORT}" "${ORDER_BACKEND_PORT}" <<'PY'
import json
import sys

source_path, target_path = sys.argv[1], sys.argv[2]
gateway_port = int(sys.argv[3])
metrics_port = int(sys.argv[4])
user_backend_port = int(sys.argv[5])
order_backend_port = int(sys.argv[6])

with open(source_path, "r", encoding="utf-8") as source:
    config = json.load(source)

config["listeners"][0]["host"] = "0.0.0.0"
config["listeners"][0]["port"] = gateway_port
config["metrics"]["enabled"] = True
config["metrics"]["port"] = metrics_port
config["metrics"]["path"] = "/metrics"

for pool in config["pools"]:
    pool["healthCheck"]["enabled"] = True

    if pool["name"] == "user-service":
        pool["upstreams"][0]["port"] = user_backend_port
    elif pool["name"] == "order-service":
        pool["upstreams"][0]["port"] = order_backend_port

with open(target_path, "w", encoding="utf-8") as target:
    json.dump(config, target, indent=2)
    target.write("\n")
PY
}

http_status() {
  local url="$1"

  curl \
    --silent \
    --show-error \
    --output /dev/null \
    --write-out "%{http_code}" \
    --max-time 2 \
    "${url}" 2>/dev/null || printf "000"
}

get_json() {
  local url="$1"

  curl \
    --silent \
    --show-error \
    --fail \
    --max-time 5 \
    "${url}"
}

assert_json_field() {
  local json="$1"
  local field="$2"
  local expected="$3"

  python3 - "${json}" "${field}" "${expected}" <<'PY'
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

  if ! printf '%s' "${json}" | grep -q "\"${key}\""; then
    echo "Missing key: ${key}" >&2
    exit 1
  fi
}

wait_for_status() {
  local name="$1"
  local url="$2"
  local expected="$3"

  for ((attempt = 1; attempt <= SMOKE_WAIT_RETRIES; attempt++)); do
    if [[ "$(http_status "${url}")" == "${expected}" ]]; then
      echo "${name} ready"
      return 0
    fi

    sleep "${SMOKE_WAIT_SLEEP}"
  done

  echo "${name} did not become ready at ${url}" >&2
  return 1
}

trap 'on_error ${LINENO}' ERR
trap cleanup EXIT

check_required_commands

cd "${ROOT_DIR}"

RUNTIME_DIR="$(mktemp -d)"
COMPILE_LOG="${RUNTIME_DIR}/compile.log"
VERTILB_LOG="${RUNTIME_DIR}/vertilb-runtime.log"
USER_BACKEND_LOG="${RUNTIME_DIR}/user-backend.log"
ORDER_BACKEND_LOG="${RUNTIME_DIR}/order-backend.log"
CONFIG_PATH="${RUNTIME_DIR}/gateway-routing.json"

generate_config

echo "Compiling VertiLB..."
./gradlew clean compileJava > "${COMPILE_LOG}" 2>&1

echo "Starting mock backends..."
python3 scripts/mock-backend.py \
  --port "${USER_BACKEND_PORT}" \
  --service user-service > "${USER_BACKEND_LOG}" 2>&1 &
USER_BACKEND_PID=$!

python3 scripts/mock-backend.py \
  --port "${ORDER_BACKEND_PORT}" \
  --service order-service > "${ORDER_BACKEND_LOG}" 2>&1 &
ORDER_BACKEND_PID=$!

echo "Starting VertiLB..."
./gradlew --no-daemon run --args="-c ${CONFIG_PATH}" > "${VERTILB_LOG}" 2>&1 &
VERTILB_PID=$!

wait_for_status "user backend" "http://127.0.0.1:${USER_BACKEND_PORT}/health" "200"
wait_for_status "order backend" "http://127.0.0.1:${ORDER_BACKEND_PORT}/health" "200"
wait_for_status "gateway" "http://127.0.0.1:${GATEWAY_PORT}/api/unknown" "404"
wait_for_status "metrics" "http://127.0.0.1:${METRICS_PORT}/metrics" "200"

echo "Checking users route..."
users_json="$(get_json "http://127.0.0.1:${GATEWAY_PORT}/api/users/1?debug=true")"
assert_json_field "${users_json}" "service" "user-service"
assert_json_field "${users_json}" "path" "/users/1"
assert_json_field "${users_json}" "query" "debug=true"

echo "Checking orders route..."
orders_json="$(get_json "http://127.0.0.1:${GATEWAY_PORT}/api/orders/99")"
assert_json_field "${orders_json}" "service" "order-service"
assert_json_field "${orders_json}" "path" "/orders/99"

echo "Checking unmatched route..."
unknown_status="$(http_status "http://127.0.0.1:${GATEWAY_PORT}/api/unknown")"
if [[ "${unknown_status}" != "404" ]]; then
  echo "Expected /api/unknown to return 404, got ${unknown_status}" >&2
  exit 1
fi

echo "Checking retryable upstream status handling..."
retryable_status="$(http_status "http://127.0.0.1:${GATEWAY_PORT}/api/users/fail503")"
if [[ "${retryable_status}" != "503" ]]; then
  echo "Expected retryable upstream status path to return final 503, got ${retryable_status}" >&2
  exit 1
fi

echo "Checking metrics..."
metrics_json="$(get_json "http://127.0.0.1:${METRICS_PORT}/metrics")"
assert_json_key "${metrics_json}" "totalRequests"
assert_json_key "${metrics_json}" "requestsByPool"
assert_json_key "${metrics_json}" "poolStats"
assert_json_key "${metrics_json}" "latencySummary"

echo "Smoke gateway passed"
