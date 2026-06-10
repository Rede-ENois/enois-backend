#!/usr/bin/env bash
set -euo pipefail

# Exporta o banco remoto para recriar em outro banco
# Uso: ./scripts/db-export.sh > rede_dump_$(date +%F).sql

HOST="${MARIADB_HOST:-2a09:8280:1::121:fe41:0}"
PORT="${MARIADB_PORT:-3306}"
USER="${MARIADB_USER:-socialcoiso}"
PASS="${MARIADB_PASSWORD:-BhaskaraDeltab24ac!}"
DB="${MARIADB_DATABASE:-socialcoiso}"

# Tenta mariadb-dump, fallback mysqldump
DUMPER=$(command -v mariadb-dump || command -v mysqldump || echo "")

if [ -z "$DUMPER" ]; then
  echo "ERRO: instale mariadb-dump ou mysqldump"
  echo "  brew install mariadb"
  echo "  apt install mariadb-client"
  exit 1
fi

echo "-- Dump de $DB em $(date)"
echo "-- Host: $HOST"
echo ""
echo "CREATE DATABASE IF NOT EXISTS $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "USE $DB;"
echo ""

"$DUMPER" \
  --host="$HOST" \
  --port="$PORT" \
  --user="$USER" \
  --password="$PASS" \
  --single-transaction \
  --routines \
  --triggers \
  --skip-lock-tables \
  --skip-add-locks \
  --no-tablespaces \
  --complete-insert \
  "$DB"

echo ""
echo "-- Dump concluido em $(date)"
