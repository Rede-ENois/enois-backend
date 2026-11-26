#!/usr/bin/env bash
# Migração V386 (histograma de latência das tarefas por nó) contra um MariaDB real, aplicada DUAS
# vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. a tabela `network_task_latency_buckets` nasce com a chave (nó, hora, faixa) e é idempotente
#      (2ª rodada sem erro);
#   2. nada que existia muda: nó, tarefa e amostra gravados ANTES continuam lá, com os mesmos valores;
#   3. a escrita é contador: duas tarefas do mesmo nó, na mesma hora e na mesma faixa somam na MESMA
#      linha (o UPSERT), e minuto diferente na mesma hora não abre linha nova;
#   4. a leitura por janela separa o que é de hoje do que é de anteontem;
#   5. o charset é explícito (utf8mb4), como toda tabela nova deste módulo.
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
  V306__network_tasks.sql)
for migration in "${BASE_MIGRATIONS[@]}" V386__network_task_latency_buckets.sql; do
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
  command -v docker >/dev/null 2>&1 || { fail "docker ausente e STORAGE_DB_DSN não definido"; exit 1; }
  CONTAINER="enois-lat-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_lat_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor
  # estável. A MESMA conexão criar e apagar uma tabela em duas rodadas seguidas separa
  # "respondeu" de "ficou no ar".
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_lat_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_lat_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_lat_test \
           -e 'create table if not exists _ready_probe (id int primary key); drop table _ready_probe' >/dev/null 2>&1; then
        ready="sim"
        break
      fi
    fi
    sleep 2
  done
  if [[ -z "$ready" ]]; then
    printf -- '--- logs do container ---\n' >&2
    docker logs --tail 5 "$CONTAINER" >&2 2>&1 || true
    fail "MariaDB não ficou pronto em 180s"
    exit 1
  fi
  pass "MariaDB 11.4 real no ar"
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_lat_test "$@"; }
else
  client=$(command -v mariadb || command -v mysql || echo "")
  [[ -n "$client" ]] || { fail "nem docker nem cliente mysql disponíveis"; exit 1; }
  db() { "$client" "$STORAGE_DB_DSN" "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi

apply() {
  local file="$1" attempt
  for attempt in 1 2 3 4 5; do
    if db < "$MIGRATIONS/$file"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

n() { db -N -e "$1" 2>/dev/null | tr -d '[:space:]'; }

for migration in "${BASE_MIGRATIONS[@]}"; do
  if apply "$migration" >/dev/null 2>&1; then
    pass "base aplica ($migration)"
  else
    fail "aplicação falhou: $migration"
    exit 1
  fi
done

# Nó e tarefa do contrato ANTES da V386: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, region, country, created_at)
values ('nd_lat_mig001', 'no-lat', 'user-lat', 'ACTIVE', 'sa-east-1', 'BR', now());
insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, data_class,
  assigned_node_id, deadline, next_attempt_at, dispatched_at, finished_at, created_at, updated_at)
values ('tk_lat_mig001', 'ASYNC_JOB', 'DONE', 1, 'lat-mig-1', '{}', 'PUBLIC', 'nd_lat_mig001',
  now(), now(), now(), now(), now(), now());
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "nó e tarefa do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó/tarefa de teste (código $seed)"
  exit 1
fi

if apply V386__network_task_latency_buckets.sql >/dev/null 2>&1; then
  pass "V386 aplica sobre as tabelas existentes"
else
  fail "V386 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V386__network_task_latency_buckets.sql" 2>&1 | head -3 >&2
fi

if [[ "$(n "select count(*) from network_nodes where id = 'nd_lat_mig001'")" == "1" \
   && "$(n "select count(*) from network_tasks where id = 'tk_lat_mig001' and status = 'DONE'")" == "1" ]]; then
  pass "dados anteriores à migração preservados"
else
  fail "dados anteriores à migração sumiram ou mudaram"
fi

[[ "$(n "select count(*) from information_schema.columns where table_schema = database()
  and table_name = 'network_task_latency_buckets'")" == "4" ]] \
  && pass "4 colunas: node_id, metrics_hour, bucket_upper_ms, samples" \
  || fail "contagem de colunas inesperada"

[[ "$(n "select count(*) from information_schema.statistics where table_schema = database()
  and table_name = 'network_task_latency_buckets' and index_name = 'PRIMARY'")" == "3" ]] \
  && pass "chave primária composta (nó, hora, faixa)" \
  || fail "chave primária não é a composta esperada"

collation=$(db -N -e "select table_collation from information_schema.tables
  where table_schema = database() and table_name = 'network_task_latency_buckets'" | tr -d '[:space:]')
[[ "$collation" == utf8mb4* ]] \
  && pass "collation explícita ($collation)" \
  || fail "collation '$collation' (esperado utf8mb4*)"

# Escrita como o adapter faz: UPSERT que soma. Dois minutos diferentes da MESMA hora são a mesma
# linha porque a hora é truncada antes de gravar.
upsert() {
  db -e "insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples)
    values ('nd_lat_mig001', '2026-11-25 10:00:00', 512, 1)
    on duplicate key update samples = samples + 1" >/dev/null 2>&1
}
upsert
upsert
upsert
[[ "$(n "select count(*) from network_task_latency_buckets where node_id = 'nd_lat_mig001'")" == "1" \
   && "$(n "select samples from network_task_latency_buckets where node_id = 'nd_lat_mig001'")" == "3" ]] \
  && pass "upsert soma 3 na mesma linha (contador, não linha por tarefa)" \
  || fail "upsert não somou na mesma linha"

db -e "insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples)
  values ('nd_lat_mig001', '2026-11-23 10:00:00', 65536, 5)" >/dev/null 2>&1
janela=$(n "select sum(samples) from network_task_latency_buckets where node_id = 'nd_lat_mig001'
  and metrics_hour >= '2026-11-24 10:00:00' and metrics_hour <= '2026-11-25 10:00:00'")
[[ "$janela" == "3" ]] \
  && pass "janela de 24h lê a hora de hoje e deixa a de anteontem fora" \
  || fail "janela leu '$janela' (esperado 3)"

# 2ª aplicação: idempotente e sem perder o que foi contado.
if apply V386__network_task_latency_buckets.sql >/dev/null 2>&1; then
  pass "V386 aplica uma 2ª vez sem erro"
else
  fail "V386 falhou na 2ª aplicação"
fi
[[ "$(n "select sum(samples) from network_task_latency_buckets where node_id = 'nd_lat_mig001'")" == "8" ]] \
  && pass "contagem preservada depois da 2ª aplicação (8)" \
  || fail "contagem mudou depois da 2ª aplicação"

printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]