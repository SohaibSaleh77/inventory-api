#!/usr/bin/env bash
# =============================================================================
# restore.sh - IMS production database restore (from backup.sh output)
#
# Restores a .sql.gz dump produced by backup.sh into a MySQL database.
# DELIBERATELY hard to run by accident: it requires an explicit confirmation
# and refuses to restore into a database that already contains tables unless
# --force is given.
#
# Usage:
#   scripts/restore.sh [--yes] [--force] [--defaults-extra-file FILE] <file.sql.gz>
#
#   <file.sql.gz>                backup file created by scripts/backup.sh
#   --yes   (or CONFIRM=yes env) explicit confirmation - REQUIRED
#   --force                      allow restoring into a non-empty database
#                                (the dump's DROP TABLE IF EXISTS replaces
#                                matching tables; extra tables are NOT removed)
#   --defaults-extra-file FILE   MySQL option file with credentials
#   -h, --help                   Show this help
#
# Environment (all optional, defaults in parentheses):
#   IMS_DB_HOST        (127.0.0.1)
#   IMS_DB_PORT        (3306)
#   IMS_DB_USER        (ims_app)
#   IMS_DB_PASSWORD    (unset - required unless --defaults-extra-file)
#   IMS_DB_NAME        (inventory_db) - the RESTORE TARGET database
#
# The restore user needs CREATE/DROP/INSERT on the target database.
#
# Exit codes:
#   0  success (restore + post-restore verification passed)
#   1  restore or verification failed
#   2  usage error / missing prerequisites / not confirmed
# =============================================================================
set -euo pipefail

PROG="$(basename "$0")"

IMS_DB_HOST="${IMS_DB_HOST:-127.0.0.1}"
IMS_DB_PORT="${IMS_DB_PORT:-3306}"
IMS_DB_USER="${IMS_DB_USER:-ims_app}"
IMS_DB_NAME="${IMS_DB_NAME:-inventory_db}"
IMS_DB_PASSWORD="${IMS_DB_PASSWORD:-}"
DEFAULTS_FILE=""
ASSUME_YES=0
FORCE=0
BACKUP_FILE=""

log() { printf '[%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() {
  code="$1"; shift
  printf '[%s] ERROR: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
  exit "$code"
}

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
    --yes|-y)
      ASSUME_YES=1 ;;
    --force)
      FORCE=1 ;;
    -h|--help)
      usage; exit 0 ;;
    -*)
      echo "${PROG}: unknown option: $1 (see --help)" >&2; exit 2 ;;
    *)
      if [ -n "$BACKUP_FILE" ]; then
        echo "${PROG}: unexpected extra argument: $1 (exactly one backup file)" >&2; exit 2
      fi
      BACKUP_FILE="$1" ;;
  esac
  shift
done

[ -n "$BACKUP_FILE" ] || { usage >&2; exit 2; }

# --------------------------------------------------------------------------
# Prerequisites
# --------------------------------------------------------------------------
for cmd in mysql gunzip; do
  command -v "$cmd" >/dev/null 2>&1 || die 2 "required command not found: $cmd"
done

case "$IMS_DB_NAME" in
  ''|*[!A-Za-z0-9_-]*) die 2 "invalid IMS_DB_NAME: '$IMS_DB_NAME'" ;;
esac

[ -r "$BACKUP_FILE" ] || die 2 "backup file not readable: $BACKUP_FILE"
case "$BACKUP_FILE" in
  *.sql.gz|*.gz) ;;
  *) die 2 "expected a .sql.gz backup file, got: $BACKUP_FILE" ;;
esac

if [ -n "$DEFAULTS_FILE" ]; then
  [ -r "$DEFAULTS_FILE" ] || die 2 "cannot read --defaults-extra-file: $DEFAULTS_FILE"
  log "Using credentials from defaults file: $DEFAULTS_FILE"
elif [ -n "$IMS_DB_PASSWORD" ]; then
  export MYSQL_PWD="$IMS_DB_PASSWORD"   # keeps the password off the process list
  log "Using IMS_DB_PASSWORD from environment"
else
  die 2 "no credentials: set IMS_DB_PASSWORD or pass --defaults-extra-file FILE"
fi

MYSQL_ARGS=(--host="$IMS_DB_HOST" --port="$IMS_DB_PORT" --user="$IMS_DB_USER")
if [ -n "$DEFAULTS_FILE" ]; then
  MYSQL_ARGS=(--defaults-extra-file="$DEFAULTS_FILE" "${MYSQL_ARGS[@]}")
fi

# Integrity of the compressed stream BEFORE touching the database.
log "Checking gzip integrity of ${BACKUP_FILE}"
gunzip -t "$BACKUP_FILE" || die 1 "backup file is corrupt (gunzip -t failed)"

# --------------------------------------------------------------------------
# Explicit confirmation - this overwrites the target database
# --------------------------------------------------------------------------
if [ "$ASSUME_YES" -ne 1 ] && [ "${CONFIRM:-}" != "yes" ]; then
  cat >&2 <<EOF
REFUSING to restore:
  backup : ${BACKUP_FILE}
  target : ${IMS_DB_NAME} @ ${IMS_DB_HOST}:${IMS_DB_PORT}
This will DROP and REPLACE tables in the target database.
Re-run with --yes (or CONFIRM=yes in the environment) to proceed.
EOF
  exit 2
fi

# --------------------------------------------------------------------------
# Target database checks
# --------------------------------------------------------------------------
SCHEMA_COUNT="$(mysql "${MYSQL_ARGS[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${IMS_DB_NAME}'")" \
  || die 1 "cannot connect to ${IMS_DB_HOST}:${IMS_DB_PORT} as ${IMS_DB_USER}"
case "$SCHEMA_COUNT" in
  1) ;;
  0) die 2 "target database '${IMS_DB_NAME}' does not exist - create it first:
  CREATE DATABASE ${IMS_DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" ;;
  *) die 1 "unexpected mysql output while checking schema: '$SCHEMA_COUNT'" ;;
esac

EXISTING_TABLES="$(mysql "${MYSQL_ARGS[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${IMS_DB_NAME}'")" \
  || die 1 "cannot inspect target database '${IMS_DB_NAME}'"
case "$EXISTING_TABLES" in
  ''|*[!0-9]*) die 1 "unexpected mysql output while counting tables: '$EXISTING_TABLES'" ;;
esac

if [ "$EXISTING_TABLES" -gt 0 ] && [ "$FORCE" -ne 1 ]; then
  die 1 "target database '${IMS_DB_NAME}' already contains ${EXISTING_TABLES} table(s)
Refusing to restore. Use --force to overwrite (DROP TABLE IF EXISTS in the dump
replaces matching tables; tables not present in the dump are left untouched)."
fi
[ "$EXISTING_TABLES" -eq 0 ] || log "WARNING: --force given, overwriting ${EXISTING_TABLES} existing table(s)"

# --------------------------------------------------------------------------
# Restore
# --------------------------------------------------------------------------
log "Restoring ${BACKUP_FILE} -> database '${IMS_DB_NAME}' on ${IMS_DB_HOST}:${IMS_DB_PORT}"
if ! gunzip -c "$BACKUP_FILE" | mysql "${MYSQL_ARGS[@]}" "$IMS_DB_NAME"; then
  die 1 "restore FAILED mid-stream - the target database is now in a PARTIAL
state. Fix the cause and re-run this script (it is safe to re-run with --force)."
fi
log "Restore stream completed"

# --------------------------------------------------------------------------
# Post-restore verification
# --------------------------------------------------------------------------
log "Post-restore verification:"
if ! mysql "${MYSQL_ARGS[@]}" -t "$IMS_DB_NAME" <<'SQL'
SELECT 'tables_total' AS `check`, COUNT(*) AS `value`
  FROM information_schema.tables WHERE table_schema = DATABASE();
SELECT 'users' AS `table`, COUNT(*) AS `row_count` FROM users
UNION ALL SELECT 'products',       COUNT(*) FROM products
UNION ALL SELECT 'warehouses',     COUNT(*) FROM warehouses
UNION ALL SELECT 'stock_items',    COUNT(*) FROM stock_items
UNION ALL SELECT 'stock_movements',COUNT(*) FROM stock_movements;
SELECT COUNT(*) AS `stock_ledger_mismatches`
  FROM stock_items si
  LEFT JOIN (SELECT product_id, warehouse_id, SUM(quantity) AS ledger_qty
               FROM stock_movements
              GROUP BY product_id, warehouse_id) ledger
         ON ledger.product_id = si.product_id
        AND ledger.warehouse_id = si.warehouse_id
 WHERE COALESCE(ledger.ledger_qty, 0) <> si.qty_on_hand;
SQL
then
  die 1 "restore completed but verification queries FAILED - inspect the target database manually"
fi
log "Verification queries executed (0 stock_ledger_mismatches = ledger reconciles with stock_items)"
log "Restore complete"
