#!/usr/bin/env bash
set -euo pipefail

# Exporta o banco remoto para um arquivo SQL local
# Uso: ./scripts/db-export.sh > dump.sql

HOST="${MARIADB_HOST:-2a09:8280:1::121:fe41:0}"
PORT="${MARIADB_PORT:-3306}"
USER="${MARIADB_USER:-socialcoiso}"
PASS="${MARIADB_PASSWORD:-BhaskaraDeltab24ac!}"
DB="${MARIADB_DATABASE:-socialcoiso}"

mariadb-dump \
  --host="$HOST" \
  --port="$PORT" \
  --user="$USER" \
  --password="$PASS" \
  --single-transaction \
  --routines \
  --triggers \
  --skip-lock-tables \
  "$DB"
