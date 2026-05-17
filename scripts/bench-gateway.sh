#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BENCH_CONCURRENCY="${BENCH_CONCURRENCY:-1024}"
BENCH_DURATION="${BENCH_DURATION:-30s}"
BENCH_RUNS="${BENCH_RUNS:-3}"
BENCH_WARMUP_DURATION="${BENCH_WARMUP_DURATION:-10s}"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
METRICS_PORT="${METRICS_PORT:-9100}"
USER_BACKEND_PORT="${USER_BACKEND_PORT:-9001}"
ORDER_BACKEND_PORT="${ORDER_BACKEND_PORT:-9011}"
BENCH_WAIT_RETRIES="${BENCH_WAIT_RETRIES:-40}"
BENCH_WAIT_SLEEP="${BENCH_WAIT_SLEEP:-0.5}"
KEEP_BENCH_LOGS="${KEEP_BENCH_LOGS:-false}"
DIRECT_BACKEND_BASELINE="${DIRECT_BACKEND_BASELINE:-false}"

USER_BACKEND_PID=""
ORDER_BACKEND_PID=""
VERTILB_PID=""
RUNTIME_DIR=""
COMPILE_LOG=""
VERTILB_LOG=""
USER_BACKEND_LOG=""
ORDER_BACKEND_LOG=""
SUMMARY_PATH=""
DIRECT_SUMMARY_PATH=""
AGGREGATE_PATH=""
DIRECT_AGGREGATE_PATH=""
CONFIG_PATH=""

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    if [[ "${command_name}" == "hey" ]]; then
      echo "Required command not found: hey. Install hey to run the gateway benchmark." >&2
    else
      echo "Required command not found: ${command_name}" >&2
    fi

    exit 1
  fi
}

check_required_commands() {
  require_command curl
  require_command python3
  require_command grep
  require_command mktemp
  require_command kill
  require_command hey
}

validate_positive_integer() {
  local name="$1"
  local value="$2"

  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name} must be a positive integer, got: ${value}" >&2
    exit 1
  fi
}

write_summary_header() {
  local summary_path="$1"

  printf 'timestamp,target,concurrency,run,duration,qps,avg_latency_ms,p95_latency_ms,p99_latency_ms,status\n' > "${summary_path}"
}

write_aggregate_summary() {
  local summary_path="$1"
  local aggregate_path="$2"

  python3 - "${summary_path}" "${aggregate_path}" <<'PY'
import csv
import statistics
import sys
from collections import defaultdict

summary_path, aggregate_path = sys.argv[1:3]
fields = [
    "target",
    "concurrency",
    "runs",
    "qps_min",
    "qps_max",
    "qps_avg",
    "qps_median",
    "avg_latency_min_ms",
    "avg_latency_max_ms",
    "avg_latency_avg_ms",
    "avg_latency_median_ms",
    "status",
]


def parse_float(value):
    if value is None or value == "":
        return None

    try:
        return float(value)
    except ValueError:
        return None


def fmt(value):
    if value is None:
        return ""

    return f"{value:.3f}"


def aggregate(values, operation):
    if not values:
        return None

    if operation == "min":
        return min(values)

    if operation == "max":
        return max(values)

    if operation == "avg":
        return sum(values) / len(values)

    if operation == "median":
        return statistics.median(values)

    raise ValueError(f"unknown aggregate operation: {operation}")


with open(summary_path, "r", encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))

groups = defaultdict(list)
for row in rows:
    groups[(row.get("target", ""), row.get("concurrency", ""))].append(row)

with open(aggregate_path, "w", encoding="utf-8", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=fields)
    writer.writeheader()

    for (target, concurrency), group_rows in sorted(groups.items()):
        qps_values = []
        latency_values = []
        ok_runs = 0

        for row in group_rows:
            if row.get("status") == "ok":
                ok_runs += 1

            qps = parse_float(row.get("qps"))
            avg_latency = parse_float(row.get("avg_latency_ms"))

            if row.get("status") == "ok" and qps is not None:
                qps_values.append(qps)

            if row.get("status") == "ok" and avg_latency is not None:
                latency_values.append(avg_latency)

        run_count = len(group_rows)
        if run_count == 0 or ok_runs == 0:
            status = "failed"
        elif ok_runs == run_count and len(qps_values) == run_count and len(latency_values) == run_count:
            status = "ok"
        else:
            status = "partial"

        writer.writerow({
            "target": target,
            "concurrency": concurrency,
            "runs": str(run_count),
            "qps_min": fmt(aggregate(qps_values, "min")),
            "qps_max": fmt(aggregate(qps_values, "max")),
            "qps_avg": fmt(aggregate(qps_values, "avg")),
            "qps_median": fmt(aggregate(qps_values, "median")),
            "avg_latency_min_ms": fmt(aggregate(latency_values, "min")),
            "avg_latency_max_ms": fmt(aggregate(latency_values, "max")),
            "avg_latency_avg_ms": fmt(aggregate(latency_values, "avg")),
            "avg_latency_median_ms": fmt(aggregate(latency_values, "median")),
            "status": status,
        })
PY
}

append_summary_row() {
  local summary_path="$1"
  local target="$2"
  local concurrency="$3"
  local run="$4"
  local duration="$5"
  local log_path="$6"
  local status="$7"

  python3 - "${summary_path}" "${target}" "${concurrency}" "${run}" "${duration}" "${log_path}" "${status}" <<'PY'
import csv
import re
import sys
from datetime import datetime, timezone

summary_path, target_name, concurrency, run, duration, log_path, status = sys.argv[1:8]

try:
    with open(log_path, "r", encoding="utf-8", errors="replace") as source:
        output = source.read()
except OSError:
    output = ""


def find(pattern):
    match = re.search(pattern, output, flags=re.MULTILINE)
    return match.group(1) if match else ""


def seconds_to_ms(value):
    if not value:
        return ""

    try:
        return f"{float(value) * 1000:.3f}"
    except ValueError:
        return ""


requests_per_sec = find(r"^\s*Requests/sec:\s*([0-9]+(?:\.[0-9]+)?)\s*$")
avg_latency_ms = seconds_to_ms(find(r"^\s*Average:\s*([0-9]+(?:\.[0-9]+)?)\s+secs\s*$"))
p95_latency_ms = seconds_to_ms(find(r"^\s*95%\s+in\s+([0-9]+(?:\.[0-9]+)?)\s+secs\s*$"))
p99_latency_ms = seconds_to_ms(find(r"^\s*99%\s+in\s+([0-9]+(?:\.[0-9]+)?)\s+secs\s*$"))
timestamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

with open(summary_path, "a", encoding="utf-8", newline="") as output_file:
    writer = csv.writer(output_file)
    writer.writerow([
        timestamp,
        target_name,
        concurrency,
        run,
        duration,
        requests_per_sec,
        avg_latency_ms,
        p95_latency_ms,
        p99_latency_ms,
        status,
    ])
PY
}

print_summary_table() {
  local title="$1"
  local summary_path="$2"

  if [[ ! -f "${summary_path}" ]]; then
    return 0
  fi

  echo
  echo "${title}"
  python3 - "${summary_path}" <<'PY'
import csv
import sys

summary_path = sys.argv[1]
columns = [
    ("target", "target"),
    ("concurrency", "concurrency"),
    ("run", "run"),
    ("qps", "qps"),
    ("avg_latency_ms", "avg_ms"),
    ("p95_latency_ms", "p95_ms"),
    ("p99_latency_ms", "p99_ms"),
    ("status", "status"),
]

with open(summary_path, "r", encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))

widths = {
    key: max(len(header), *(len(row.get(key, "")) for row in rows))
    for key, header in columns
}

print(" | ".join(header.ljust(widths[key]) for key, header in columns))
print(" | ".join("-" * widths[key] for key, _ in columns))

for row in rows:
    print(" | ".join(row.get(key, "").ljust(widths[key]) for key, _ in columns))
PY
}

print_aggregate_table() {
  local title="$1"
  local aggregate_path="$2"

  if [[ ! -f "${aggregate_path}" ]]; then
    return 0
  fi

  echo
  echo "${title}"
  python3 - "${aggregate_path}" <<'PY'
import csv
import sys

aggregate_path = sys.argv[1]
columns = [
    ("target", "target"),
    ("concurrency", "concurrency"),
    ("runs", "runs"),
    ("qps_min", "qps_min"),
    ("qps_max", "qps_max"),
    ("qps_avg", "qps_avg"),
    ("qps_median", "qps_median"),
    ("avg_latency_min_ms", "avg_ms_min"),
    ("avg_latency_max_ms", "avg_ms_max"),
    ("avg_latency_avg_ms", "avg_ms_avg"),
    ("avg_latency_median_ms", "avg_ms_median"),
    ("status", "status"),
]

with open(aggregate_path, "r", encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))

widths = {
    key: max(len(header), *(len(row.get(key, "")) for row in rows))
    for key, header in columns
}

print(" | ".join(header.ljust(widths[key]) for key, header in columns))
print(" | ".join("-" * widths[key] for key, _ in columns))

for row in rows:
    print(" | ".join(row.get(key, "").ljust(widths[key]) for key, _ in columns))
PY
}

run_hey_logged() {
  local duration="$1"
  local concurrency="$2"
  local url="$3"
  local log_path="$4"

  set +e
  hey -z "${duration}" -c "${concurrency}" "${url}" > "${log_path}" 2>&1
  local exit_code=$?
  set -e

  return "${exit_code}"
}

run_benchmark_suite() {
  local target="$1"
  local url="$2"
  local summary_path="$3"
  local warmup_log="$4"
  local run_log_prefix="$5"
  local aggregate_path="$6"
  local failed_runs=0

  write_summary_header "${summary_path}"

  echo "Warmup target=${target} concurrency=${BENCH_CONCURRENCY} duration=${BENCH_WARMUP_DURATION}..."
  run_hey_logged "${BENCH_WARMUP_DURATION}" "${BENCH_CONCURRENCY}" "${url}" "${warmup_log}"

  for ((run = 1; run <= BENCH_RUNS; run++)); do
    local run_log="${RUNTIME_DIR}/${run_log_prefix}${run}.log"
    local status="ok"

    echo "Benchmarking target=${target} concurrency=${BENCH_CONCURRENCY} run=${run}/${BENCH_RUNS} duration=${BENCH_DURATION}..."
    if ! run_hey_logged "${BENCH_DURATION}" "${BENCH_CONCURRENCY}" "${url}" "${run_log}"; then
      status="failed"
      failed_runs=$((failed_runs + 1))
      append_summary_row "${summary_path}" "${target}" "${BENCH_CONCURRENCY}" "${run}" "${BENCH_DURATION}" "${run_log}" "${status}"
      echo "hey failed for ${target} run=${run}; see ${run_log}" >&2
      continue
    fi

    append_summary_row "${summary_path}" "${target}" "${BENCH_CONCURRENCY}" "${run}" "${BENCH_DURATION}" "${run_log}" "${status}"
  done

  write_aggregate_summary "${summary_path}" "${aggregate_path}"

  if [[ "${failed_runs}" -eq "${BENCH_RUNS}" ]]; then
    return 1
  fi
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
  dump_log "benchmark summary" "${SUMMARY_PATH}"
  dump_log "direct backend summary" "${DIRECT_SUMMARY_PATH}"
  dump_log "benchmark aggregate" "${AGGREGATE_PATH}"
  dump_log "direct backend aggregate" "${DIRECT_AGGREGATE_PATH}"
}

on_error() {
  local line_number="$1"

  echo "Benchmark gateway failed at line ${line_number}" >&2
  if [[ "${KEEP_BENCH_LOGS}" == "true" && -n "${RUNTIME_DIR}" ]]; then
    echo "Benchmark temp dir: ${RUNTIME_DIR}" >&2
  fi
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
    if [[ "${KEEP_BENCH_LOGS}" == "true" ]]; then
      echo "Keeping benchmark logs in ${RUNTIME_DIR}"
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

wait_for_status() {
  local name="$1"
  local url="$2"
  local expected="$3"

  for ((attempt = 1; attempt <= BENCH_WAIT_RETRIES; attempt++)); do
    if [[ "$(http_status "${url}")" == "${expected}" ]]; then
      echo "${name} ready"
      return 0
    fi

    sleep "${BENCH_WAIT_SLEEP}"
  done

  echo "${name} did not become ready at ${url}" >&2
  return 1
}

trap 'on_error ${LINENO}' ERR
trap cleanup EXIT

check_required_commands
validate_positive_integer "BENCH_CONCURRENCY" "${BENCH_CONCURRENCY}"
validate_positive_integer "BENCH_RUNS" "${BENCH_RUNS}"

cd "${ROOT_DIR}"

RUNTIME_DIR="$(mktemp -d)"
COMPILE_LOG="${RUNTIME_DIR}/compile.log"
VERTILB_LOG="${RUNTIME_DIR}/vertilb-runtime.log"
USER_BACKEND_LOG="${RUNTIME_DIR}/user-backend.log"
ORDER_BACKEND_LOG="${RUNTIME_DIR}/order-backend.log"
SUMMARY_PATH="${RUNTIME_DIR}/benchmark-summary.csv"
DIRECT_SUMMARY_PATH="${RUNTIME_DIR}/direct-backend-summary.csv"
AGGREGATE_PATH="${RUNTIME_DIR}/benchmark-aggregate.csv"
DIRECT_AGGREGATE_PATH="${RUNTIME_DIR}/direct-backend-aggregate.csv"
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

wait_for_status "user backend" "http://127.0.0.1:${USER_BACKEND_PORT}/health" "200"
wait_for_status "order backend" "http://127.0.0.1:${ORDER_BACKEND_PORT}/health" "200"

echo "Starting VertiLB..."
./gradlew --no-daemon run --args="-c ${CONFIG_PATH}" > "${VERTILB_LOG}" 2>&1 &
VERTILB_PID=$!

wait_for_status "gateway" "http://127.0.0.1:${GATEWAY_PORT}/api/unknown" "404"
wait_for_status "metrics" "http://127.0.0.1:${METRICS_PORT}/metrics" "200"

echo "Running gateway benchmarks..."
run_benchmark_suite \
  "gateway" \
  "http://localhost:${GATEWAY_PORT}/api/users/1?debug=true" \
  "${SUMMARY_PATH}" \
  "${RUNTIME_DIR}/bench-gateway-c${BENCH_CONCURRENCY}-warmup.log" \
  "bench-gateway-c${BENCH_CONCURRENCY}-run" \
  "${AGGREGATE_PATH}"

if [[ "${DIRECT_BACKEND_BASELINE}" == "true" ]]; then
  echo "Running direct backend baseline benchmarks..."
  run_benchmark_suite \
    "direct-backend" \
    "http://localhost:${USER_BACKEND_PORT}/users/1?debug=true" \
    "${DIRECT_SUMMARY_PATH}" \
    "${RUNTIME_DIR}/bench-direct-c${BENCH_CONCURRENCY}-warmup.log" \
    "bench-direct-c${BENCH_CONCURRENCY}-run" \
    "${DIRECT_AGGREGATE_PATH}"
fi

echo
echo "Benchmark settings"
echo "concurrency=${BENCH_CONCURRENCY}"
echo "duration=${BENCH_DURATION}"
echo "warmup_duration=${BENCH_WARMUP_DURATION}"
echo "runs=${BENCH_RUNS}"

print_summary_table "Gateway benchmark summary" "${SUMMARY_PATH}"
print_aggregate_table "Gateway benchmark aggregate" "${AGGREGATE_PATH}"

if [[ "${DIRECT_BACKEND_BASELINE}" == "true" ]]; then
  print_summary_table "Direct backend benchmark summary" "${DIRECT_SUMMARY_PATH}"
  print_aggregate_table "Direct backend benchmark aggregate" "${DIRECT_AGGREGATE_PATH}"
fi
