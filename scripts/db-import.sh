#!/usr/bin/env bash
set -euo pipefail

# Importa um dump SQL para um banco
# Uso: ./scripts/db-import.sh dump.sql [usuario] [senha] [host] [porta]
#       ./scripts/db-import.sh --help

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  echo "Importa um arquivo .sql para um banco de dados."
  echo ""
  echo "Uso: $0 arquivo.sql [usuario] [senha] [host] [porta]"
  echo ""
  echo "Argumentos:"
  echo "  arquivo.sql  Caminho do arquivo SQL (obrigatorio)"
  echo "  usuario      User do banco destino   (default: root)"
  echo "  senha        Senha do banco destino  (default: vazio)"
  echo "  host         Host do banco destino   (default: localhost)"
  echo "  porta        Porta do banco destino  (default: 3306)"
  echo ""
  echo "Exemplos:"
  echo "  $0 dump.sql"
  echo "  $0 dump.sql rede_admin minha_senha 192.168.1.100"
  echo "  $0 dump.sql root '' localhost 3307"
  echo ""
  echo "Requer mariadb ou mysql instalado."
  exit 0
fi

FILE="${1:-}"
DB_USER="${2:-root}"
DB_PASS="${3:-}"
DB_HOST="${4:-localhost}"
DB_PORT="${5:-3306}"

if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "ERRO: arquivo nao encontrado: $FILE"
  echo "  $0 --help"
  exit 1
fi

CLIENT=$(command -v mariadb || command -v mysql || echo "")

if [ -z "$CLIENT" ]; then
  echo "ERRO: instale mariadb-client"
  echo "  brew install mariadb"
  echo "  apt install mariadb-client"
  exit 1
fi

echo "Importando $FILE para $DB_HOST:$DB_PORT como $DB_USER ..."

if [ -n "$DB_PASS" ]; then
  "$CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS" < "$FILE"
else
  "$CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" < "$FILE"
fi

echo "Pronto!"
