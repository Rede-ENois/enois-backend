#!/usr/bin/env bash
set -euo pipefail

# Exporta o banco remoto para stdout
# Uso: ./scripts/db-export.sh > dump.sql
#       ./scripts/db-export.sh --help

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  echo "Exporta o banco remoto para um arquivo SQL."
  echo ""
  echo "Uso: $0 > arquivo.sql"
  echo ""
  echo "Variaveis de ambiente:"
  echo "  MARIADB_HOST       (default: 2a09:8280:1::121:fe41:0)"
  echo "  MARIADB_PORT       (default: 3306)"
  echo "  MARIADB_USER       (default: socialcoiso)"
  echo "  MARIADB_PASSWORD   (default: a do application-qa.properties)"
  echo "  MARIADB_DATABASE   (default: socialcoiso)"
  echo ""
  echo "Exemplo:"
  echo "  $0 > backup_\$(date +%F).sql"
  echo "  gzip backup_\$(date +%F).sql"
  echo ""
  echo "Requer mariadb-dump ou mysqldump instalado."
  exit 0
fi

HOST="${MARIADB_HOST:-2a09:8280:1::121:fe41:0}"
PORT="${MARIADB_PORT:-3306}"
USER="${MARIADB_USER:-socialcoiso}"
PASS="${MARIADB_PASSWORD:-BhaskaraDeltab24ac!}"
DB="${MARIADB_DATABASE:-socialcoiso}"

DUMPER=$(command -v mariadb-dump || command -v mysqldump || echo "")

if [ -z "$DUMPER" ]; then
  echo "ERRO: instale mariadb-dump ou mysqldump"
  echo "  brew install mariadb"
  echo "  apt install mariadb-client"
  echo "  $0 --help"
  exit 1
fi

echo "-- Dump de $DB em $(date)"
echo "-- Host: $HOST"
echo ""
echo "CREATE DATABASE IF NOT EXISTS $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "USE $DB;"
echo ""

if [ -n "$PASS" ]; then
  PASS_OPT="--password=$PASS"
else
  PASS_OPT=""
fi

"$DUMPER" \
  --host="$HOST" \
  --port="$PORT" \
  --user="$USER" \
  $PASS_OPT \
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
