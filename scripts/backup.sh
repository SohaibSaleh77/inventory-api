#!/usr/bin/env bash
# =============================================================================
# backup.sh - IMS production database backup (mysqldump -> gz -> sha256)
#
# Creates a timestamped, gzip-compressed logical dump of the IMS MySQL
# database plus a sha256 sidecar file, verifies the dump, and rotates old
# backups (default: keep 14 days).
#
# Usage:
#   scripts/backup.sh [options]
#
# Options:
#   --defaults-extra-file FILE   MySQL option file with [client]/[mysqldump]
#                                credentials (preferred over IMS_DB_PASSWORD).
#   -h, --help                   Show this help.
#
# Environment (all optional, defaults in parentheses):
#   IMS_DB_HOST        (127.0.0.1)
#   IMS_DB_PORT        (3306)
#   IMS_DB_USER        (ims_app)
#   IMS_DB_PASSWORD    (unset - required unless --defaults-extra-file)
#   IMS_DB_NAME        (inventory_db)
#   BACKUP_DIR         (/var/backups/inventory-api)
#   RETENTION_DAYS     (14)
#
# The account only needs SELECT + LOCK TABLES + TRIGGER/EXECUTE view rights;
# --single-transaction takes a consistent InnoDB snapshot without locking.
#
# Cron example (/etc/cron.d/inventory-api-backup):
#   30 2 * * * inventory IMS_DB_PASSWORD=... /opt/inventory-api/scripts/backup.sh >> /var/log/inventory-api/backup.log 2>&1
#
# Exit codes:
#   0  success
#   1  dump or verification failed
#   2  usage error / missing prerequisites
# =============================================================================
set -euo pipefail

PROG="$(basename "$0")"

IMS_DB_HOST="${IMS_DB_HOST:-127.0.0.1}"
IMS_DB_PORT="${IMS_DB_PORT:-3306}"
IMS_DB_USER="${IMS_DB_USER:-ims_app}"
IMS_DB_NAME="${IMS_DB_NAME:-inventory_db}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/inventory-api}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
IMS_DB_PASSWORD="${IMS_DB_PASSWORD:-}"
DEFAULTS_FILE=""

log() { printf '[%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() {
  code="$1"; shift
  printf '[%s] ERROR: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
  exit "$code"
}

# Print the header comment block (lines 3..closing delimiter) as help text.
usage() { awk 'NR<=2 {next} /^# =+$/ {exit} {sub(/^# ?/, ""); print}' "$0"; }

# --------------------------------------------------------------------------
# Arguments
# --------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --defaults-extra-file)
      [ $# -ge 2 ] || { echo "${PROG}: --defaults-extra-file requires a file argument" >&2; exit 2; }
      DEFAULTS_FILE="$2"; shift ;;
    --defaults-extra-file=*)
      DEFAULTS_FILE="${1#*=}" ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "${PROG}: unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

# --------------------------------------------------------------------------
# Prerequisites
# --------------------------------------------------------------------------
for cmd in mysqldump gzip sha256sum awk find stat; do
  command -v "$cmd" >/dev/null 2>&1 || die 2 "required command not found: $cmd"
done

case "$IMS_DB_NAME" in
  ''|*[!A-Za-z0-9_-]*) die 2 "invalid IMS_DB_NAME: '$IMS_DB_NAME'" ;;
esac

if [ -n "$DEFAULTS_FILE" ]; then
  [ -r "$DEFAULTS_FILE" ] || die 2 "cannot read --defaults-extra-file: $DEFAULTS_FILE"
  log "Using credentials from defaults file: $DEFAULTS_FILE"
elif [ -n "$IMS_DB_PASSWORD" ]; then
  # MYSQL_PWD keeps the password off the process list and out of `ps`.
  export MYSQL_PWD="$IMS_DB_PASSWORD"
  log "Using IMS_DB_PASSWORD from environment"
else
  die 2 "no credentials: set IMS_DB_PASSWORD or pass --defaults-extra-file FILE"
fi

# --------------------------------------------------------------------------
# Dump
# --------------------------------------------------------------------------
DUMP_ARGS=(--host="$IMS_DB_HOST" --port="$IMS_DB_PORT" --user="$IMS_DB_USER"
  --single-transaction --routines --triggers --events --set-gtid-purged=OFF)
if [ -n "$DEFAULTS_FILE" ]; then
  # must be the FIRST option for mysqldump to honor it
  DUMP_ARGS=(--defaults-extra-file="$DEFAULTS_FILE" "${DUMP_ARGS[@]}")
fi

TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
BACKUP_NAME="ims-${IMS_DB_NAME}-${TIMESTAMP}.sql.gz"
BACKUP_PATH="${BACKUP_DIR}/${BACKUP_NAME}"
TMP_PATH="${BACKUP_DIR}/.${BACKUP_NAME}.tmp"

trap 'if [ -n "${TMP_PATH:-}" ]; then rm -f "$TMP_PATH" || true; fi' EXIT

mkdir -p "$BACKUP_DIR" || die 1 "cannot create backup dir: $BACKUP_DIR"

log "Backing up '${IMS_DB_NAME}' from ${IMS_DB_HOST}:${IMS_DB_PORT} -> ${BACKUP_PATH}"
if ! mysqldump "${DUMP_ARGS[@]}" "$IMS_DB_NAME" | gzip -9 > "$TMP_PATH"; then
  die 1 "mysqldump/gzip failed - no backup written"
fi

# --------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------
# (a) the head of the dump must contain schema (CREATE TABLE). `|| true`
#     neutralizes the SIGPIPE that head sends to zcat under pipefail.
HEAD_CHUNK="$(zcat "$TMP_PATH" | head -c 1048576)" || true
if ! printf '%s' "$HEAD_CHUNK" | grep -q 'CREATE TABLE'; then
  die 1 "verification failed: no CREATE TABLE in the first 1 MiB of the dump"
fi

# (b) one full decompression pass: proves the gzip stream is intact
#     end-to-end and summarizes the content.
STATS_EXIT=0
STATS="$(zcat "$TMP_PATH" | awk '
  /^CREATE TABLE/ { ct++ }
  /^INSERT INTO/  { ins++ }
  END { printf "%d %d\n", ct + 0, ins + 0 }
')" || STATS_EXIT=$?
if [ "$STATS_EXIT" -ne 0 ]; then
  die 1 "verification failed: gzip stream corrupt or unreadable (exit $STATS_EXIT)"
fi
TABLE_COUNT="${STATS%% *}"
INSERT_ROWS="${STATS##* }"

if [ "$TABLE_COUNT" -lt 1 ]; then
  die 1 "verification failed: dump contains no tables (empty or truncated dump?)"
fi

SIZE_BYTES="$(stat -c '%s' "$TMP_PATH")"
SIZE_HUMAN="$(du -h "$TMP_PATH" | cut -f1)"
log "Verified dump: ${TABLE_COUNT} tables (CREATE TABLE), ${INSERT_ROWS} INSERT INTO statements, ${SIZE_BYTES} bytes (${SIZE_HUMAN} gzipped)"

# --------------------------------------------------------------------------
# Publish + checksum sidecar (relative path so 'sha256sum -c' is portable)
# --------------------------------------------------------------------------
mv "$TMP_PATH" "$BACKUP_PATH"
( cd "$BACKUP_DIR" && sha256sum "$BACKUP_NAME" > "${BACKUP_NAME}.sha256" )
log "Wrote ${BACKUP_PATH}"
log "Wrote ${BACKUP_PATH}.sha256"

# --------------------------------------------------------------------------
# Retention rotation (dumps + checksum sidecars)
# --------------------------------------------------------------------------
log "Rotating backups older than ${RETENTION_DAYS} days in ${BACKUP_DIR}"
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'inventory-api-*.sql.gz' -o -name 'inventory-api-*.sql.gz.sha256' \) \
  -mtime "+${RETENTION_DAYS}" -print -delete | sed 's/^/  expired: /'

log "Backup complete"
