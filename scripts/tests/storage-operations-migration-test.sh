#!/usr/bin/env bash
# Série de operações do Storage (V366) contra um MariaDB real, aplicada DUAS vezes.
#
# Por que existe: os testes de suíte rodam em H2. A V366 não é só `create table` — ela guarda a série
# que o painel lê, e a escrita é um UPSERT com `on duplicate key update`, que o H2 perdoaria mesmo se o
# MariaDB recusasse. É este UPSERT que a produção exercita a CADA operação: se ele estiver errado, a
# primeira operação do minuto grava e todas as outras do mesmo minuto falham silenciosamente (o serviço
# loga aviso e segue), e o painel mostraria 1 op/s onde há milhares.
#
# O que este harness prova, no banco real:
#   1. as migrações aplicam num MariaDB 11.4 limpo, na ordem;
#   2. aplicar a V366 DE NOVO não falha e preserva as linhas gravadas;
#   3. o UPSERT soma no mesmo minuto e NÃO perde contagem (10 gravações → req_count = 10);
#   4. o UPSERT guarda o PIOR caso (max_ms) e não a média;
#   5. chave de resultado diferente (CLIENT/SERVER) é linha diferente no mesmo minuto;
#   6. a consulta que o serviço faz (soma por operação/resultado na janela) devolve a média ponderada;
#   7. a varredura de retenção apaga só o que passou do limite.
#
# Uso:
#   ./scripts/tests/storage-operations-migration-test.sh
#   STORAGE_DB_DSN=... ./scripts/tests/storage-operations-migration-test.sh   # banco já existente
#
# O caminho padrão sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

METRICS_MIGRATION="V366__network_storage_operation_metrics.sql"
[[ -f "$MIGRATIONS/$METRICS_MIGRATION" ]] || { fail "migração ausente: $METRICS_MIGRATION"; exit 1; }

CONTAINER=""
cleanup() {
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -z "${STORAGE_DB_DSN:-}" ]]; then
  command -v docker >/dev/null 2>&1 || { fail "docker ausente e STORAGE_DB_DSN não definido"; exit 1; }
  CONTAINER="enois-metrics-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_metrics_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor estável.
  ready=""
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" mariadb -uroot -e "select 1" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "o MariaDB 11.4 não respondeu a tempo"; exit 1; }
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_metrics_test "$@"; }
else
  command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1 \
    || { fail "STORAGE_DB_DSN definido mas sem cliente mariadb/mysql"; exit 1; }
  client=$(command -v mariadb || command -v mysql)
  db() { "$client" --default-character-set=utf8mb4 "$STORAGE_DB_DSN" "$@"; }
fi

apply() {
  local file="$1"
  for _ in 1 2 3 4 5; do
    if db < "$MIGRATIONS/$file"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

# O mesmo UPSERT que `StorageOperationMetrics.record` emite.
upsert() {
  db -e "insert into network_storage_operation_metrics (bucket_at, operation, outcome, req_count, total_ms, max_ms)
         values ('$1', '$2', '$3', 1, $4, $4)
         on duplicate key update req_count = req_count + 1, total_ms = total_ms + $4,
           max_ms = greatest(max_ms, $4);" >/dev/null
}
scalar() { db -N -B -e "$1" 2>/dev/null | tr -d '[:space:]'; }

echo "== primeira aplicação =="
apply "$METRICS_MIGRATION" && pass "V366 aplica em banco limpo" || fail "V366 não aplicou"

# --- 3/4/5: o UPSERT no mesmo minuto -----------------------------------------
for _ in $(seq 1 9); do upsert '2026-11-19 12:00:00.000' 'GET' 'OK' 10; done
upsert '2026-11-19 12:00:00.000' 'GET' 'OK' 990
[ "$(scalar "select req_count from network_storage_operation_metrics where operation='GET' and outcome='OK';")" = "10" ] \
  && pass "10 gravações no mesmo minuto somam req_count = 10" \
  || fail "UPSERT perdeu contagem (esperado 10)"
[ "$(scalar "select total_ms from network_storage_operation_metrics where operation='GET' and outcome='OK';")" = "1080" ] \
  && pass "total_ms soma 1.080 (9x10 + 990)" || fail "total_ms não somou"
[ "$(scalar "select max_ms from network_storage_operation_metrics where operation='GET' and outcome='OK';")" = "990" ] \
  && pass "max_ms guarda o pior caso (990), não a média" || fail "max_ms não guardou o pior caso"

upsert '2026-11-19 12:00:00.000' 'GET' 'CLIENT' 5
upsert '2026-11-19 12:00:00.000' 'GET' 'SERVER' 40
[ "$(scalar "select count(*) from network_storage_operation_metrics where bucket_at='2026-11-19 12:00:00.000';")" = "3" ] \
  && pass "OK/CLIENT/SERVER do mesmo minuto são linhas distintas" \
  || fail "resultados diferentes colapsaram na mesma linha"

# --- 6: a consulta do serviço -------------------------------------------------
# Janela de 15 minutos: média ponderada = total_ms / req_count = 1080 / 10 = 108.
media=$(scalar "select round(sum(total_ms)/sum(req_count), 3) from network_storage_operation_metrics
  where bucket_at >= '2026-11-19 11:45:00.000' and operation='GET' and outcome='OK';")
[ "$media" = "108.000" ] && pass "a consulta da janela devolve a média ponderada (108 ms)" \
  || fail "média ponderada errada: $media (esperado 108.000)"

# --- 2: segunda aplicação preserva --------------------------------------------
echo "== segunda aplicação =="
apply "$METRICS_MIGRATION" && pass "V366 aplica DE NOVO sem falhar" || fail "V366 falhou na segunda aplicação"
[ "$(scalar "select count(*) from network_storage_operation_metrics;")" = "3" ] \
  && pass "dados preservados depois da segunda aplicação (3 linhas)" || fail "segunda aplicação perdeu dados"

# --- 7: retenção ---------------------------------------------------------------
upsert '2026-09-01 12:00:00.000' 'PUT' 'OK' 1
db -e "delete from network_storage_operation_metrics where bucket_at < '2026-10-20 12:00:00.000';" >/dev/null
antigas="$(scalar "select count(*) from network_storage_operation_metrics where bucket_at < '2026-10-20 12:00:00.000';")"
recentes="$(scalar "select count(*) from network_storage_operation_metrics where bucket_at >= '2026-10-20 12:00:00.000';")"
[ "$antigas" = "0" ] && [ "$recentes" = "3" ] \
  && pass "a retenção apaga o minuto velho e preserva os recentes" \
  || fail "retenção errada (velhos=$antigas, recentes=$recentes)"

# --- a chave é (minuto, operação, resultado): operações diferentes no mesmo minuto não colidem
upsert '2026-11-19 12:00:00.000' 'PUT' 'OK' 3
[ "$(scalar "select count(*) from network_storage_operation_metrics where bucket_at='2026-11-19 12:00:00.000';")" = "4" ] \
  && pass "a chave composta aceita outra operação no mesmo minuto (4 linhas)" \
  || fail "chave composta recusou outra operação no mesmo minuto"

echo
echo "Storage operations migration: PASS $PASS | FAIL $FAIL"
[[ "$FAIL" -eq 0 ]]