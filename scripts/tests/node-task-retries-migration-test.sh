#!/usr/bin/env bash
# Migração V387 (retentativas das tarefas por nó, brief §8.2) contra um MariaDB real, aplicada DUAS
# vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. a coluna `retries` entra em `network_task_latency_buckets` e a 2ª aplicação não erra;
#   2. as linhas que já existiam (da V386) continuam lá, com `samples` intacto e `retries` 0 —
#      nenhuma medida antiga é reinterpretada como retentativa;
#   3. o UPSERT novo (`values (?, ?, ?, 0, 1) ... retries = retries + 1`) soma na MESMA linha da
#      hora e da faixa, e não zera `samples` da linha que já tinha duração;
#   4. a leitura da duração (`samples`) e a da tentativa (`retries`) são colunas separadas: somar a
#      tentativa não contamina a cauda de execução;
#   5. a 2ª aplicação preserva um dado gravado DEPOIS da 1ª (idempotência sobre dado vivo).
#
# `STORAGE_DB_DSN` reaproveita um banco já em pé em vez de subir container.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

BASE_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql
  V306__network_tasks.sql V386__network_task_latency_buckets.sql)
for migration in "${BASE_MIGRATIONS[@]}" V387__network_task_latency_retries.sql; do
  [[ -f "$MIGRATIONS/$migration" ]] || { fail "migração ausente: $migration"; exit 1; }
done

CONTAINER=""
cleanup() {
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -z "${STORAGE_DB_DSN:-}" ]]; then
  CONTAINER="enois-retries-mig-$RANDOM"
  docker run -d --rm --name "$CONTAINER" \
    -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=rede_mig \
    mariadb:11.4 >/dev/null || { fail "não subiu o MariaDB"; exit 1; }
  for _ in $(seq 1 60); do
    docker exec "$CONTAINER" mariadb -uroot -proot -e 'select 1' >/dev/null 2>&1 && break
    sleep 1
  done
  db() { docker exec -i "$CONTAINER" mariadb -uroot -proot rede_mig "$@"; }
else
  db() { mariadb "$STORAGE_DB_DSN" -e "create database if not exists rede_mig" 2>/dev/null
    mariadb rede_mig "$@"; }
fi
n() { db -N -B -e "$1"; }

apply() { db < "$MIGRATIONS/$1"; }

for migration in "${BASE_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 || { fail "base $migration falhou"; exit 1; }
done

# Uma hora ANTES da migração, com duração medida: é a linha que a V387 não pode reinterpretar.
ANTES_HOUR="2026-01-10 03:00:00"
db >/dev/null 2>&1 <<SQL
insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples)
values ('nd_retries_mig001', '$ANTES_HOUR', 64, 9);
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "linha da V386 (duração de 9 tarefas) gravada antes da V387"
else
  fail "não foi possível gravar a linha anterior à V387 (código $seed)"
  exit 1
fi

if apply V387__network_task_latency_retries.sql >/dev/null 2>&1; then
  pass "V387 aplica sobre a tabela da V386"
else
  fail "V387 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V387__network_task_latency_retries.sql" 2>&1 | head -3 >&2
fi

if [[ "$(n "select count(*) from information_schema.columns where table_schema = database()
  and table_name = 'network_task_latency_buckets' and column_name = 'retries'")" == "1" ]]; then
  pass "coluna retries presente"
else
  fail "coluna retries ausente"
fi

if [[ "$(n "select concat(samples, '/', retries) from network_task_latency_buckets
  where node_id = 'nd_retries_mig001'")" == "9/0" ]]; then
  pass "a duração de antes ficou intacta e a retentativa dela é 0"
else
  fail "a linha anterior à V387 mudou: $(n "select concat(samples, '/', retries) from network_task_latency_buckets where node_id = 'nd_retries_mig001'")"
fi

# Escrita como o adapter faz: a DURAÇÃO da hora e a TENTATIVA da mesma faixa.
HOUR="2026-01-11 10:00:00"
db >/dev/null 2>&1 <<SQL
insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples)
  values ('nd_retries_mig001', '$HOUR', 1, 1)
  on duplicate key update samples = samples + 1;
insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples, retries)
  values ('nd_retries_mig001', '$HOUR', 1, 0, 1)
  on duplicate key update retries = retries + 1;
insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples, retries)
  values ('nd_retries_mig001', '$HOUR', 1, 0, 1)
  on duplicate key update retries = retries + 1;
SQL
if [[ "$(n "select concat(samples, '/', retries) from network_task_latency_buckets
  where node_id = 'nd_retries_mig001' and bucket_upper_ms = 1")" == "1/2" ]]; then
  pass "o UPSERT da tentativa soma na mesma linha e não mexe em samples (1/2)"
else
  fail "UPSERT: $(n "select concat(samples, '/', retries) from network_task_latency_buckets where node_id = 'nd_retries_mig001' and bucket_upper_ms = 1") (esperado 1/2)"
fi

if [[ "$(n "select count(*) from network_task_latency_buckets where node_id = 'nd_retries_mig001'")" == "2" ]]; then
  pass "uma linha por (hora, faixa): as tentativas não abriram linha nova"
else
  fail "linhas inesperadas para o nó de teste"
fi

if [[ "$(n "select coalesce(sum(samples), -1) from network_task_latency_buckets
  where node_id = 'nd_retries_mig001'")" == "10" \
   && "$(n "select coalesce(sum(retries), -1) from network_task_latency_buckets
  where node_id = 'nd_retries_mig001'")" == "2" ]]; then
  pass "duração e tentativa são colunas separadas na leitura (10 e 2)"
else
  fail "somas inesperadas: samples/retries"
fi

db >/dev/null 2>&1 <<SQL
insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples, retries)
values ('nd_retries_mig002', '2026-01-12 09:00:00', 4096, 3, 1);
SQL
if apply V387__network_task_latency_retries.sql >/dev/null 2>&1; then
  pass "V387 é idempotente (2ª aplicação sem erro)"
else
  fail "V387 falhou na 2ª aplicação"
fi

if [[ "$(n "select concat(samples, '/', retries) from network_task_latency_buckets
  where node_id = 'nd_retries_mig002'")" == "3/1" \
   && "$(n "select concat(samples, '/', retries) from network_task_latency_buckets
  where node_id = 'nd_retries_mig001' and bucket_upper_ms = 1")" == "1/2" ]]; then
  pass "a 2ª aplicação preserva o dado gravado depois da 1ª"
else
  fail "a 2ª aplicação mexeu no dado vivo"
fi

collation=$(db -N -e "select table_collation from information_schema.tables
  where table_schema = database() and table_name = 'network_task_latency_buckets'" | tr -d '[:space:]')
[[ "$collation" == utf8mb4* ]] \
  && pass "collation continua explícita ($collation)" \
  || fail "collation '$collation' (esperado utf8mb4*)"

[[ "$(n "select is_nullable from information_schema.columns where table_schema = database()
  and table_name = 'network_task_latency_buckets' and column_name = 'retries'")" == "NO" \
   && "$(n "select column_default from information_schema.columns where table_schema = database()
  and table_name = 'network_task_latency_buckets' and column_name = 'retries'")" == "0" ]] \
  && pass "retries é not null default 0 (linha antiga não fica com null)" \
  || fail "definição de retries inesperada"

printf 'node-task-retries: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]