#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_SCRIPT="${ROOT_DIR}/scripts/profile-gateway.sh"

fail() {
  echo "$1" >&2
  exit 1
}

assert_contains() {
  local pattern="$1"

  if ! grep -Fq -- "${pattern}" "${TARGET_SCRIPT}"; then
    fail "Expected ${TARGET_SCRIPT} to contain: ${pattern}"
  fi
}

[[ -f "${TARGET_SCRIPT}" ]] || fail "Missing ${TARGET_SCRIPT}"

bash -n "${TARGET_SCRIPT}"

assert_contains 'PROFILE_CONCURRENCY="${PROFILE_CONCURRENCY:-1024}"'
assert_contains 'PROFILE_DURATION="${PROFILE_DURATION:-60s}"'
assert_contains 'PROFILE_WARMUP_DURATION="${PROFILE_WARMUP_DURATION:-10s}"'
assert_contains 'PROFILE_RUNS="${PROFILE_RUNS:-1}"'
assert_contains 'KEEP_PROFILE_LOGS="${KEEP_PROFILE_LOGS:-true}"'
assert_contains 'DELETE_PROFILE_LOGS="${DELETE_PROFILE_LOGS:-false}"'
assert_contains 'REQUEST_CONTEXT_POOL_ENABLED="${REQUEST_CONTEXT_POOL_ENABLED:-false}"'
assert_contains 'REQUEST_CONTEXT_POOL_MAX_SIZE="${REQUEST_CONTEXT_POOL_MAX_SIZE:-4096}"'
assert_contains '-Xlog:gc*:file=${PROFILE_DIR}/gc.log:time,uptime,level,tags'
assert_contains '-XX:StartFlightRecording=filename=${PROFILE_DIR}/vertilb.jfr,duration=${PROFILE_DURATION},settings=profile'
assert_contains 'JFR.start'
assert_contains 'JFR.stop'
assert_contains 'jfr_mode='
assert_contains 'jfr_start='
assert_contains 'profile_alignment='
assert_contains 'benchmark_duration='
assert_contains 'jfr summary'
assert_contains 'jdk.ObjectAllocationInNewTLAB'
assert_contains 'gc-summary.txt'
assert_contains 'profile-summary.txt'
assert_contains 'benchmark-summary.csv'
assert_contains 'benchmark-aggregate.csv'
assert_contains 'requestContextPool'

echo "profile-gateway script contract verified"
