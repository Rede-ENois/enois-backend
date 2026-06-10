#!/usr/bin/env bash
set -euo pipefail

# Importa o dump para um novo banco
# Uso: ./scripts/db-import.sh dump.sql
# Uso: ./scripts/db-import.sh dump.sql usuario senha host porta

FILE="${1:-}"
DB_USER="${2:-root}"
DB_PASS="${3:-}"
DB_HOST="${4:-localhost}"
DB_PORT="${5:-3306}"

if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "Uso: $0 arquivo.sql [usuario] [senha] [host] [porta]"
  echo "  (usuario, senha, host, porta sao opcionais - default root, vazio, localhost, 3306)"
  exit 1
fi

CLIENT=$(command -v mariadb || command -v mysql || echo "")

if [ -z "$CLIENT" ]; then
  echo "ERRO: instale mariadb-client ou mysql-client"
  exit 1
fi

echo "Importando $FILE para $DB_HOST:$DB_PORT..."

if [ -n "$DB_PASS" ]; then
  "$CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS" < "$FILE"
else
  "$CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" < "$FILE"
fi

echo "Pronto!"
