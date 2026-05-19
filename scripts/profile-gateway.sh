#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PROFILE_CONCURRENCY="${PROFILE_CONCURRENCY:-1024}"
PROFILE_DURATION="${PROFILE_DURATION:-60s}"
PROFILE_RUNS="${PROFILE_RUNS:-1}"
PROFILE_WARMUP_DURATION="${PROFILE_WARMUP_DURATION:-10s}"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
METRICS_PORT="${METRICS_PORT:-9100}"
USER_BACKEND_PORT="${USER_BACKEND_PORT:-9001}"
ORDER_BACKEND_PORT="${ORDER_BACKEND_PORT:-9011}"
PROFILE_WAIT_RETRIES="${PROFILE_WAIT_RETRIES:-40}"
PROFILE_WAIT_SLEEP="${PROFILE_WAIT_SLEEP:-0.5}"
KEEP_PROFILE_LOGS="${KEEP_PROFILE_LOGS:-true}"
DELETE_PROFILE_LOGS="${DELETE_PROFILE_LOGS:-false}"
REQUEST_CONTEXT_POOL_ENABLED="${REQUEST_CONTEXT_POOL_ENABLED:-false}"
REQUEST_CONTEXT_POOL_MAX_SIZE="${REQUEST_CONTEXT_POOL_MAX_SIZE:-4096}"

USER_BACKEND_PID=""
ORDER_BACKEND_PID=""
VERTILB_PID=""
PROFILE_DIR=""
COMPILE_LOG=""
VERTILB_LOG=""
USER_BACKEND_LOG=""
ORDER_BACKEND_LOG=""
HEY_WARMUP_LOG=""
HEY_RUN_LOG=""
SUMMARY_PATH=""
AGGREGATE_PATH=""
GC_SUMMARY_PATH=""
JFR_SUMMARY_PATH=""
JFR_ALLOCATION_EVENTS_PATH=""
PROFILE_SUMMARY_PATH=""
CONFIG_PATH=""
JFR_PREFLIGHT_LOG=""
ARTIFACTS_ANNOUNCED="false"
JCMD_AVAILABLE="false"
JFR_MODE="startup_fallback"
JFR_START="process_start"
PROFILE_ALIGNMENT="startup_inclusive"
JFR_STATUS="missing"
BENCH_URL=""

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    if [[ "${command_name}" == "hey" ]]; then
      echo "Required command not found: hey. Install hey to run the gateway profiling harness." >&2
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
  require_command java
}

has_command() {
  command -v "$1" >/dev/null 2>&1
}

validate_positive_integer() {
  local name="$1"
  local value="$2"

  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name} must be a positive integer, got: ${value}" >&2
    exit 1
  fi
}

validate_boolean() {
  local name="$1"
  local value="$2"

  if [[ "${value}" != "true" && "${value}" != "false" ]]; then
    echo "${name} must be true or false, got: ${value}" >&2
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

append_profile_note() {
  local message="$1"

  if [[ -n "${PROFILE_SUMMARY_PATH}" ]]; then
    printf '%s\n' "${message}" >> "${PROFILE_SUMMARY_PATH}"
  fi
}

dump_log_tail() {
  local label="$1"
  local path="$2"

  if [[ -n "${path}" && -f "${path}" ]]; then
    echo "===== ${label}: ${path} =====" >&2
    tail -n 80 "${path}" >&2 || true
  fi
}

dump_logs() {
  dump_log_tail "VertiLB runtime log" "${VERTILB_LOG}"
  dump_log_tail "user backend log" "${USER_BACKEND_LOG}"
  dump_log_tail "order backend log" "${ORDER_BACKEND_LOG}"
}

announce_artifacts() {
  if [[ "${ARTIFACTS_ANNOUNCED}" != "true" && -n "${PROFILE_DIR}" ]]; then
    echo "Profiling artifacts: ${PROFILE_DIR}"
    ARTIFACTS_ANNOUNCED="true"
  fi
}

on_error() {
  local line_number="$1"

  echo "Profile gateway failed at line ${line_number}" >&2
  announce_artifacts
  dump_logs
}

stop_process() {
  local pid_var_name="$1"
  local pid="${!pid_var_name}"

  if [[ -n "${pid}" ]]; then
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  fi

  if [[ -n "${pid}" ]]; then
    wait "${pid}" 2>/dev/null || true
  fi

  printf -v "${pid_var_name}" '%s' ""
}

stop_all_processes() {
  stop_process "VERTILB_PID"
  stop_process "USER_BACKEND_PID"
  stop_process "ORDER_BACKEND_PID"
}

cleanup() {
  stop_all_processes

  if [[ -n "${PROFILE_DIR}" && -d "${PROFILE_DIR}" ]]; then
    announce_artifacts

    if [[ "${DELETE_PROFILE_LOGS}" == "true" ]]; then
      echo "Deleting profiling artifacts because DELETE_PROFILE_LOGS=true"
      rm -rf "${PROFILE_DIR}"
    else
      echo "Keeping profiling artifacts in ${PROFILE_DIR}"
    fi
  fi
}

generate_config() {
  python3 - "${ROOT_DIR}/examples/gateway-routing.json" "${CONFIG_PATH}" \
    "${GATEWAY_PORT}" "${METRICS_PORT}" "${USER_BACKEND_PORT}" "${ORDER_BACKEND_PORT}" \
    "${REQUEST_CONTEXT_POOL_ENABLED}" "${REQUEST_CONTEXT_POOL_MAX_SIZE}" <<'PY'
import json
import sys

source_path, target_path = sys.argv[1], sys.argv[2]
gateway_port = int(sys.argv[3])
metrics_port = int(sys.argv[4])
user_backend_port = int(sys.argv[5])
order_backend_port = int(sys.argv[6])
request_context_pool_enabled = sys.argv[7].lower() == "true"
request_context_pool_max_size = int(sys.argv[8])

with open(source_path, "r", encoding="utf-8") as source:
    config = json.load(source)

config["listeners"][0]["host"] = "0.0.0.0"
config["listeners"][0]["port"] = gateway_port
config["metrics"]["enabled"] = True
config["metrics"]["port"] = metrics_port
config["metrics"]["path"] = "/metrics"
config["performance"] = {
    "requestContextPool": {
        "enabled": request_context_pool_enabled,
        "maxSize": request_context_pool_max_size,
    }
}

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

  for ((attempt = 1; attempt <= PROFILE_WAIT_RETRIES; attempt++)); do
    if [[ "$(http_status "${url}")" == "${expected}" ]]; then
      echo "${name} ready"
      return 0
    fi

    sleep "${PROFILE_WAIT_SLEEP}"
  done

  echo "${name} did not become ready at ${url}" >&2
  return 1
}

verify_jfr_support() {
  local support_file="${PROFILE_DIR}/jfr-support-check.jfr"

  set +e
  java "-XX:StartFlightRecording=filename=${support_file},duration=1s,settings=profile" -version > "${JFR_PREFLIGHT_LOG}" 2>&1
  local exit_code=$?
  set -e

  if [[ "${exit_code}" -ne 0 ]]; then
    echo "JFR preflight failed. The current JVM rejected -XX:StartFlightRecording." >&2
    echo "See ${JFR_PREFLIGHT_LOG} for details." >&2
    return 1
  fi

  rm -f "${support_file}"
}

start_aligned_jfr() {
  set +e
  jcmd "${VERTILB_PID}" \
    JFR.start \
    name=vertilb-profile \
    settings=profile \
    filename="${PROFILE_DIR}/vertilb.jfr" > "${JFR_PREFLIGHT_LOG}" 2>&1
  local exit_code=$?
  set -e

  if [[ "${exit_code}" -ne 0 ]]; then
    echo "Aligned JFR start failed via jcmd." >&2
    echo "See ${JFR_PREFLIGHT_LOG} for details." >&2
    return 1
  fi

  JFR_STATUS="started"
  append_profile_note "JFR started with jcmd after warmup."
}

stop_aligned_jfr() {
  set +e
  jcmd "${VERTILB_PID}" \
    JFR.stop \
    name=vertilb-profile \
    filename="${PROFILE_DIR}/vertilb.jfr" >> "${JFR_PREFLIGHT_LOG}" 2>&1
  local exit_code=$?
  set -e

  if [[ "${exit_code}" -ne 0 ]]; then
    echo "Aligned JFR stop failed via jcmd." >&2
    echo "See ${JFR_PREFLIGHT_LOG} for details." >&2
    return 1
  fi

  JFR_STATUS="produced"
  append_profile_note "JFR stopped with jcmd after measured benchmark."
}

wait_for_file() {
  local label="$1"
  local path="$2"
  local retries="${3:-20}"
  local sleep_seconds="${4:-0.5}"

  for ((attempt = 1; attempt <= retries; attempt++)); do
    if [[ -s "${path}" ]]; then
      return 0
    fi

    sleep "${sleep_seconds}"
  done

  echo "Expected ${label} at ${path}, but it was not produced." >&2
  return 1
}

generate_gc_summary() {
  if [[ ! -s "${PROFILE_DIR}/gc.log" ]]; then
    printf 'GC summary parsing skipped: gc.log was not produced.\n' > "${GC_SUMMARY_PATH}"
    append_profile_note "GC summary parsing skipped: gc.log was not produced."
    return 0
  fi

  python3 - "${PROFILE_DIR}/gc.log" "${GC_SUMMARY_PATH}" <<'PY'
import re
import sys

gc_log_path, summary_path = sys.argv[1], sys.argv[2]
pause_count = 0
durations_ms = []
duration_pattern = re.compile(r"\s([0-9]+(?:\.[0-9]+)?)(ms|s)$")

with open(gc_log_path, "r", encoding="utf-8", errors="replace") as source:
    for raw_line in source:
        line = raw_line.strip()
        if "Pause" not in line:
            continue

        match = duration_pattern.search(line)
        if not match:
            continue

        pause_count += 1
        value, unit = match.groups()
        duration_ms = float(value) * 1000.0 if unit == "s" else float(value)
        durations_ms.append(duration_ms)

with open(summary_path, "w", encoding="utf-8") as target:
    target.write(f"pause_count={pause_count}\n")
    if durations_ms:
        target.write(f"total_pause_ms={sum(durations_ms):.3f}\n")
        target.write(f"max_pause_ms={max(durations_ms):.3f}\n")
    else:
        target.write("GC summary parsing skipped: no completed pause-duration lines were found.\n")
PY
}

generate_jfr_outputs() {
  if ! command -v jfr >/dev/null 2>&1; then
    echo "JFR was recorded, but the jfr CLI is unavailable; skipping summary extraction."
    append_profile_note "JFR summary skipped: jfr CLI is unavailable."
    return 0
  fi

  if jfr summary "${PROFILE_DIR}/vertilb.jfr" > "${JFR_SUMMARY_PATH}" 2>&1; then
    echo "Wrote JFR summary to ${JFR_SUMMARY_PATH}"
    append_profile_note "JFR summary written: ${JFR_SUMMARY_PATH}"
  else
    echo "JFR summary generation failed; see ${JFR_SUMMARY_PATH}" >&2
    append_profile_note "JFR summary generation failed: see ${JFR_SUMMARY_PATH}"
  fi

  if jfr print \
    --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.GarbageCollection,jdk.GCHeapSummary \
    "${PROFILE_DIR}/vertilb.jfr" > "${JFR_ALLOCATION_EVENTS_PATH}" 2>&1; then
    echo "Wrote JFR allocation events to ${JFR_ALLOCATION_EVENTS_PATH}"
    append_profile_note "JFR allocation events written: ${JFR_ALLOCATION_EVENTS_PATH}"
  else
    rm -f "${JFR_ALLOCATION_EVENTS_PATH}"
    echo "JFR allocation event extraction is unavailable on this JVM; skipping."
    append_profile_note "JFR allocation event extraction skipped: jfr print failed for allocation and GC events."
  fi
}

write_profile_summary() {
  {
    echo "profile_dir=${PROFILE_DIR}"
    echo "target=${BENCH_URL}"
    echo "concurrency=${PROFILE_CONCURRENCY}"
    echo "duration=${PROFILE_DURATION}"
    echo "benchmark_duration=${PROFILE_DURATION}"
    echo "warmup_duration=${PROFILE_WARMUP_DURATION}"
    echo "runs=${PROFILE_RUNS}"
    echo "request_context_pool_enabled=${REQUEST_CONTEXT_POOL_ENABLED}"
    echo "request_context_pool_max_size=${REQUEST_CONTEXT_POOL_MAX_SIZE}"
    echo "jfr_mode=${JFR_MODE}"
    echo "jfr_start=${JFR_START}"
    echo "profile_alignment=${PROFILE_ALIGNMENT}"
    echo "gc_log=$([[ -s "${PROFILE_DIR}/gc.log" ]] && echo produced || echo missing)"
    echo "jfr=$([[ -s "${PROFILE_DIR}/vertilb.jfr" ]] && echo produced || echo "${JFR_STATUS}")"
    echo "jfr_summary=$([[ -s "${JFR_SUMMARY_PATH}" ]] && echo produced || echo skipped)"
    echo "jfr_allocation_events=$([[ -s "${JFR_ALLOCATION_EVENTS_PATH}" ]] && echo produced || echo skipped)"
    echo "benchmark_summary=$([[ -s "${SUMMARY_PATH}" ]] && echo produced || echo missing)"
    echo "benchmark_aggregate=$([[ -s "${AGGREGATE_PATH}" ]] && echo produced || echo missing)"
  } > "${PROFILE_SUMMARY_PATH}.tmp"

  if [[ -s "${PROFILE_SUMMARY_PATH}" ]]; then
    {
      cat "${PROFILE_SUMMARY_PATH}.tmp"
      echo
      echo "notes:"
      cat "${PROFILE_SUMMARY_PATH}"
    } > "${PROFILE_SUMMARY_PATH}.new"
    mv "${PROFILE_SUMMARY_PATH}.new" "${PROFILE_SUMMARY_PATH}"
  else
    mv "${PROFILE_SUMMARY_PATH}.tmp" "${PROFILE_SUMMARY_PATH}"
  fi

  rm -f "${PROFILE_SUMMARY_PATH}.tmp"
}

run_warmup() {
  local target="gateway"
  local url="${BENCH_URL}"

  echo "Warmup target=${target} concurrency=${PROFILE_CONCURRENCY} duration=${PROFILE_WARMUP_DURATION}..."
  run_hey_logged "${PROFILE_WARMUP_DURATION}" "${PROFILE_CONCURRENCY}" "${url}" "${HEY_WARMUP_LOG}"
}

run_measured_benchmark() {
  local target="gateway"
  local url="${BENCH_URL}"
  local failed_runs=0

  write_summary_header "${SUMMARY_PATH}"

  : > "${HEY_RUN_LOG}"

  for ((run = 1; run <= PROFILE_RUNS; run++)); do
    local run_log="${PROFILE_DIR}/hey-run-${run}.log"
    local status="ok"

    echo "Benchmarking target=${target} concurrency=${PROFILE_CONCURRENCY} run=${run}/${PROFILE_RUNS} duration=${PROFILE_DURATION}..."
    if ! run_hey_logged "${PROFILE_DURATION}" "${PROFILE_CONCURRENCY}" "${url}" "${run_log}"; then
      status="failed"
      failed_runs=$((failed_runs + 1))
      append_summary_row "${SUMMARY_PATH}" "${target}" "${PROFILE_CONCURRENCY}" "${run}" "${PROFILE_DURATION}" "${run_log}" "${status}"
      {
        printf '===== run %s (failed) =====\n' "${run}"
        cat "${run_log}"
        printf '\n'
      } >> "${HEY_RUN_LOG}"
      echo "hey failed for ${target} run=${run}; see ${run_log}" >&2
      continue
    fi

    append_summary_row "${SUMMARY_PATH}" "${target}" "${PROFILE_CONCURRENCY}" "${run}" "${PROFILE_DURATION}" "${run_log}" "${status}"
    {
      printf '===== run %s =====\n' "${run}"
      cat "${run_log}"
      printf '\n'
    } >> "${HEY_RUN_LOG}"
  done

  write_aggregate_summary "${SUMMARY_PATH}" "${AGGREGATE_PATH}"

  if [[ "${failed_runs}" -eq "${PROFILE_RUNS}" ]]; then
    return 1
  fi
}

trap 'on_error ${LINENO}' ERR
trap cleanup EXIT

check_required_commands
validate_positive_integer "PROFILE_CONCURRENCY" "${PROFILE_CONCURRENCY}"
validate_positive_integer "PROFILE_RUNS" "${PROFILE_RUNS}"
validate_positive_integer "REQUEST_CONTEXT_POOL_MAX_SIZE" "${REQUEST_CONTEXT_POOL_MAX_SIZE}"
validate_boolean "REQUEST_CONTEXT_POOL_ENABLED" "${REQUEST_CONTEXT_POOL_ENABLED}"

cd "${ROOT_DIR}"

PROFILE_DIR="$(mktemp -d)"
COMPILE_LOG="${PROFILE_DIR}/compile.log"
VERTILB_LOG="${PROFILE_DIR}/vertilb-runtime.log"
USER_BACKEND_LOG="${PROFILE_DIR}/user-backend.log"
ORDER_BACKEND_LOG="${PROFILE_DIR}/order-backend.log"
HEY_WARMUP_LOG="${PROFILE_DIR}/hey-warmup.log"
HEY_RUN_LOG="${PROFILE_DIR}/hey-run.log"
SUMMARY_PATH="${PROFILE_DIR}/benchmark-summary.csv"
AGGREGATE_PATH="${PROFILE_DIR}/benchmark-aggregate.csv"
GC_SUMMARY_PATH="${PROFILE_DIR}/gc-summary.txt"
JFR_SUMMARY_PATH="${PROFILE_DIR}/jfr-summary.txt"
JFR_ALLOCATION_EVENTS_PATH="${PROFILE_DIR}/jfr-allocation-events.txt"
PROFILE_SUMMARY_PATH="${PROFILE_DIR}/profile-summary.txt"
CONFIG_PATH="${PROFILE_DIR}/gateway-routing.json"
JFR_PREFLIGHT_LOG="${PROFILE_DIR}/jfr-preflight.log"
BENCH_URL="http://localhost:${GATEWAY_PORT}/api/users/1?debug=true"

announce_artifacts

generate_config

echo "Compiling VertiLB install distribution..."
./gradlew clean installDist > "${COMPILE_LOG}" 2>&1

if has_command jcmd; then
  JCMD_AVAILABLE="true"
  JFR_MODE="aligned_jcmd"
  JFR_START="after_warmup"
  PROFILE_ALIGNMENT="measured_window"
else
  verify_jfr_support
  append_profile_note "jcmd is unavailable; using startup-inclusive JFR fallback."
fi

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

echo "Starting VertiLB with GC and JFR profiling..."
if [[ "${JCMD_AVAILABLE}" == "true" ]]; then
  JAVA_OPTS="-Xlog:gc*:file=${PROFILE_DIR}/gc.log:time,uptime,level,tags" \
    "${ROOT_DIR}/build/install/vertilb/bin/vertilb" -c "${CONFIG_PATH}" > "${VERTILB_LOG}" 2>&1 &
else
  JAVA_OPTS="-Xlog:gc*:file=${PROFILE_DIR}/gc.log:time,uptime,level,tags -XX:StartFlightRecording=filename=${PROFILE_DIR}/vertilb.jfr,duration=${PROFILE_DURATION},settings=profile" \
    "${ROOT_DIR}/build/install/vertilb/bin/vertilb" -c "${CONFIG_PATH}" > "${VERTILB_LOG}" 2>&1 &
  JFR_STATUS="startup_configured"
fi
VERTILB_PID=$!

wait_for_status "gateway" "http://127.0.0.1:${GATEWAY_PORT}/api/unknown" "404"
wait_for_status "metrics" "http://127.0.0.1:${METRICS_PORT}/metrics" "200"

echo "Running gateway profiling benchmark..."
run_warmup

if [[ "${JCMD_AVAILABLE}" == "true" ]]; then
  start_aligned_jfr
fi

run_measured_benchmark

if [[ "${JCMD_AVAILABLE}" == "true" ]]; then
  stop_aligned_jfr
fi

stop_all_processes

wait_for_file "GC log" "${PROFILE_DIR}/gc.log"
wait_for_file "JFR recording" "${PROFILE_DIR}/vertilb.jfr"

generate_gc_summary
generate_jfr_outputs
write_profile_summary

echo
echo "Profiling settings"
echo "concurrency=${PROFILE_CONCURRENCY}"
echo "duration=${PROFILE_DURATION}"
echo "warmup_duration=${PROFILE_WARMUP_DURATION}"
echo "runs=${PROFILE_RUNS}"

print_summary_table "Gateway benchmark summary" "${SUMMARY_PATH}"
print_aggregate_table "Gateway benchmark aggregate" "${AGGREGATE_PATH}"

echo
echo "PROFILE_DIR=${PROFILE_DIR}"
