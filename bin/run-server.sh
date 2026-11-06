#!/usr/bin/env bash
# ============================================================================
# run-server.sh - start/stop/inspect the IMS API server.
#
# Usage:
#   bin/run-server.sh              start in the FOREGROUND (Ctrl-C to stop)
#   bin/run-server.sh --daemon     start in the background (pidfile in logs/)
#   bin/run-server.sh --status     report daemon status (via logs/server.pid)
#   bin/run-server.sh --stop       stop the daemon (TERM, wait, then KILL)
#   bin/run-server.sh --help       show this help
#
# JVM selection: honors JAVA_HOME if set and valid, else java from PATH.
# Extra JVM flags: export JAVA_OPTS="-Xms256m ..." before invoking.
# The pidfile only tracks --daemon starts; foreground runs own their terminal.
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

LOG_DIR="$IMS_ROOT/logs"
PID_FILE="$LOG_DIR/server.pid"
OUT_FILE="$LOG_DIR/server.out"
JAR_FILE="$IMS_ROOT/target/inventory-api.jar"
# the lib/* wildcard is expanded by the JVM itself, not the shell
CLASSPATH="$IMS_ROOT/target/inventory-api.jar:$IMS_ROOT/target/lib/*"

mkdir -p "$LOG_DIR"

usage() { awk 'NR<=2 {next} /^# =+$/ {exit} {sub(/^# ?/, ""); print}' "$0"; }

log() { printf '%s\n' "$*"; }
err() { printf '%s\n' "$*" >&2; }

resolve_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_CMD="${JAVA_HOME}/bin/java"
  elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="$(command -v java)"
  else
    err "ERROR: no java found - set JAVA_HOME or put java on PATH"
    exit 1
  fi
}

check_built() {
  if [ ! -f "$JAR_FILE" ]; then
    err "ERROR: $JAR_FILE not found - build first: mvn -DskipTests package (or make build)"
    exit 1
  fi
}

is_running() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

start_foreground() {
  resolve_java
  check_built
  log "Starting IMS in the foreground (Ctrl-C to stop) ..."
  cd "$IMS_ROOT"
  # shellcheck disable=SC2086 # JAVA_OPTS is intentionally word-split
  exec "$JAVA_CMD" ${JAVA_OPTS:-} -cp "$CLASSPATH" com.ims.Main
}

start_daemon() {
  resolve_java
  check_built
  if is_running; then
    err "ERROR: already running (PID $(cat "$PID_FILE")) - use $0 --stop first"
    exit 1
  fi
  rm -f "$PID_FILE"
  log "Starting IMS in the background ..."
  cd "$IMS_ROOT"
  # shellcheck disable=SC2086 # JAVA_OPTS is intentionally word-split
  setsid nohup "$JAVA_CMD" ${JAVA_OPTS:-} -cp "$CLASSPATH" com.ims.Main \
      >> "$OUT_FILE" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_FILE"
  disown "$pid" 2>/dev/null || true
  log "Server started (PID $pid); output: logs/server.out; stop with: $0 --stop"
}

show_status() {
  if is_running; then
    log "running (PID $(cat "$PID_FILE"))"
    return 0
  elif [ -f "$PID_FILE" ]; then
    log "not running (stale pidfile: $(cat "$PID_FILE"))"
    return 3
  else
    log "not running (no pidfile at $PID_FILE)"
    return 3
  fi
}

stop_daemon() {
  if [ ! -f "$PID_FILE" ]; then
    log "not running (no pidfile)"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    log "not running (stale pidfile: ${pid:-<empty>}) - removing it"
    rm -f "$PID_FILE"
    return 0
  fi
  log "Stopping IMS server (PID $pid) ..."
  kill -TERM "$pid"
  local i
  for i in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      log "stopped cleanly (SIGTERM)"
      return 0
    fi
    sleep 1
  done
  err "PID $pid did not exit within 30s - sending SIGKILL"
  kill -KILL "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  log "killed (SIGKILL)"
  return 0
}

case "${1:-}" in
  --daemon) start_daemon ;;
  --status) show_status ;;
  --stop)   stop_daemon ;;
  -h|--help) usage; exit 0 ;;
  "")       start_foreground ;;
  *)        err "Unknown option: $1 (see $0 --help)"; exit 2 ;;
esac
