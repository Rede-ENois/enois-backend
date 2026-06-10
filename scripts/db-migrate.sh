#!/usr/bin/env bash
set -euo pipefail

# Migra o banco direto: exporta do remoto e importa no destino
# Uso: ./scripts/db-migrate.sh usuario_destino senha_destino host_destino porta_destino

ORIGIN_HOST="${MARIADB_HOST:-2a09:8280:1::121:fe41:0}"
ORIGIN_PORT="${MARIADB_PORT:-3306}"
ORIGIN_USER="${MARIADB_USER:-socialcoiso}"
ORIGIN_PASS="${MARIADB_PASSWORD:-BhaskaraDeltab24ac!}"
ORIGIN_DB="${MARIADB_DATABASE:-socialcoiso}"

DEST_USER="${1:-root}"
DEST_PASS="${2:-}"
DEST_HOST="${3:-localhost}"
DEST_PORT="${4:-3306}"

DUMPER=$(command -v mariadb-dump || command -v mysqldump || echo "")
CLIENT=$(command -v mariadb || command -v mysql || echo "")

if [ -z "$DUMPER" ] || [ -z "$CLIENT" ]; then
  echo "ERRO: instale mariadb-client (brew install mariadb / apt install mariadb-client)"
  exit 1
fi

echo "Exportando de $ORIGIN_HOST -> Importando em $DEST_HOST:$DEST_PORT ..."

if [ -n "$ORIGIN_PASS" ]; then
  PASS_OPT="--password=$ORIGIN_PASS"
else
  PASS_OPT=""
fi

"$DUMPER" \
  --host="$ORIGIN_HOST" \
  --port="$ORIGIN_PORT" \
  --user="$ORIGIN_USER" \
  $PASS_OPT \
  --single-transaction \
  --routines \
  --triggers \
  --skip-lock-tables \
  --skip-add-locks \
  --no-tablespaces \
  --complete-insert \
  "$ORIGIN_DB" | \
"$CLIENT" --host="$DEST_HOST" --port="$DEST_PORT" --user="$DEST_USER" ${DEST_PASS:+--password="$DEST_PASS"}

echo "Migracao concluida!"
