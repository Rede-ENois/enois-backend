#!/usr/bin/env bash
set -euo pipefail

# Migra o banco direto: exporta do remoto e importa no destino
# Uso: ./scripts/db-migrate.sh [usuario_destino] [senha_destino] [host_destino] [porta_destino]

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  echo "Migra o banco de dados: exporta do servidor atual e importa no destino."
  echo ""
  echo "Uso: $0 [usuario] [senha] [host] [porta]"
  echo ""
  echo "Argumentos (todos opcionais - usa os valores do .properties se omitidos):"
  echo "  usuario  User do banco destino          (default: root)"
  echo "  senha    Senha do banco destino          (default: vazio)"
  echo "  host     Host do banco destino           (default: localhost)"
  echo "  porta    Porta do banco destino          (default: 3306)"
  echo ""
  echo "Variaveis de ambiente (para o banco ORIGEM - lidas do application-qa.properties):"
  echo "  MARIADB_HOST                           (default: 2a09:8280:1::121:fe41:0)"
  echo "  MARIADB_PORT                           (default: 3306)"
  echo "  MARIADB_USER                           (default: socialcoiso)"
  echo "  MARIADB_PASSWORD                       (default: a que ta no properties)"
  echo "  MARIADB_DATABASE                       (default: socialcoiso)"
  echo ""
  echo "Exemplos:"
  echo "  $0                                        # remoto -> localhost:3306 root sem senha"
  echo "  $0 rede_admin minha_senha 10.0.0.5        # remoto -> 10.0.0.5:3306"
  echo "  $0 root '' localhost 3307                 # remoto -> localhost:3307"
  echo ""
  echo "Requer mariadb-dump e mariadb (ou mysqldump e mysql) instalados."
  exit 0
fi

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
  echo "ERRO: instale mariadb-client"
  echo "  brew install mariadb"
  echo "  apt install mariadb-client"
  echo "  $0 --help"
  exit 1
fi

echo "Exportando de $ORIGIN_HOST:$ORIGIN_PORT/$ORIGIN_DB"
echo "Importando em $DEST_HOST:$DEST_PORT como $DEST_USER"
echo ""

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

echo ""
echo "Migracao concluida!"
