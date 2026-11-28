#!/usr/bin/env bash
# Migração V388 (threads do processo do nó, brief §8.2 "Runtime") contra um MariaDB real, aplicada
# DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. a coluna `threads` nasce em `network_node_samples` e é idempotente (2ª rodada sem erro);
#   2. amostra já gravada ANTES da migração não perde nada e a coluna nova fica NULL — "não
#      medido", nunca 0. Zero afirmaria um processo sem thread nenhuma;
#   3. o UPSERT do heartbeat grava a coluna no MESMO minuto (troca o ponto, não abre outro) e
#      carrega `cpu_percent`/`disk_free_mb` da linha, como o adapter faz;
#   4. o tipo é `int` e aceita NULL, e `threads` não é NOT NULL com default 0 (o default escondido
#      transformaria toda linha antiga em "0 threads");
#   5. a 2ª aplicação preserva um valor gravado DEPOIS da 1ª.
#
# `STORAGE_DB_DSN` reaproveita um banco já em pé em vez de subir container.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

# A V388 pede `after temperature_c`: a base tem de chegar até a V382, como a instalação que recebe
# esta migração.
BASE_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql
  V321__network_node_observed_disk.sql V341__network_node_observed_disk_total.sql
  V343__network_node_observed_disk_total.sql V380__network_node_disk_io.sql
  V382__network_node_temperature.sql)
for migration in "${BASE_MIGRATIONS[@]}" V388__network_node_threads.sql; do
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
  CONTAINER="enois-thr-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_thr_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_thr_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_thr_test -e 'select 1' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_thr_test \
           -e 'create table if not exists _ready_probe (id int primary key); drop table _ready_probe' >/dev/null 2>&1; then
        ready="sim"
        break
      fi
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "MariaDB não ficou pronto em 180s"; exit 1; }
  pass "MariaDB 11.4 real no ar"
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_thr_test "$@"; }
else
  db() { mariadb "$STORAGE_DB_DSN" -e "create database if not exists enois_thr_test" 2>/dev/null
    mariadb enois_thr_test "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi
n() { db -N -B -e "$1"; }
apply() { db < "$MIGRATIONS/$1"; }

for migration in "${BASE_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 || { fail "base $migration falhou"; exit 1; }
done

# Nó e amostra do contrato ANTES da V388: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, region, country, created_at)
values ('nd_thr_mig001', 'no-thr', 'user-thr', 'ACTIVE', 'sa-east-1', 'BR', now());
insert into network_node_samples (node_id, sampled_at, cpu_percent, disk_free_mb, running_tasks)
values ('nd_thr_mig001', '2026-11-26 10:00:00.000', 12.5, 7900, 1);
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "nó e amostra do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó/amostra de teste (código $seed)"
  exit 1
fi

if apply V388__network_node_threads.sql >/dev/null 2>&1; then
  pass "V388 aplica sobre a tabela existente"
else
  fail "V388 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V388__network_node_threads.sql" 2>&1 | head -3 >&2
fi

if [[ "$(n "select count(*) from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name = 'threads'")" == "1" ]]; then
  pass "coluna threads nasceu em network_node_samples"
else
  fail "coluna threads não nasceu"
fi

if [[ "$(n "select concat_ws('|', cpu_percent, disk_free_mb, ifnull(cast(threads as char), 'NULL'))
  from network_node_samples where node_id = 'nd_thr_mig001'")" == "12.5|7900|NULL" ]]; then
  pass "a amostra anterior ficou intacta e threads é NULL, não 0"
else
  fail "a amostra anterior mudou: $(n "select concat_ws('|', cpu_percent, disk_free_mb, ifnull(cast(threads as char), 'NULL')) from network_node_samples where node_id = 'nd_thr_mig001'")"
fi

# `column_default` nulo é "sem default": o que não pode existir é um 0 implícito, que faria toda
# linha anterior (e todo insert que não manda a coluna) virar "0 threads".
definicao=$(n "select concat_ws('|', data_type, is_nullable, ifnull(column_default, 'sem-default'))
  from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name = 'threads'")
if [[ "$definicao" == "int|YES|sem-default" || "$definicao" == "int|YES|NULL" ]]; then
  pass "threads é int NULL sem default (linha antiga não vira 0)"
else
  fail "definição de threads inesperada: $definicao"
fi

# UPSERT como o adapter faz: o mesmo minuto troca o ponto e preserva as medidas da linha.
HOUR="2026-11-27 09:00:00.000"
db >/dev/null 2>&1 <<SQL
insert into network_node_samples (node_id, sampled_at, cpu_percent, disk_free_mb, running_tasks, threads)
  values ('nd_thr_mig001', '$HOUR', 20.0, 7800, 1, 9)
  on duplicate key update cpu_percent = values(cpu_percent), disk_free_mb = values(disk_free_mb),
    running_tasks = values(running_tasks), threads = values(threads);
insert into network_node_samples (node_id, sampled_at, cpu_percent, disk_free_mb, running_tasks, threads)
  values ('nd_thr_mig001', '$HOUR', 22.0, 7700, 0, 11)
  on duplicate key update cpu_percent = values(cpu_percent), disk_free_mb = values(disk_free_mb),
    running_tasks = values(running_tasks), threads = values(threads);
SQL
if [[ "$(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(threads = 11))
  from network_node_samples where node_id = 'nd_thr_mig001' and sampled_at = '$HOUR'")" == "1|1|1" ]]; then
  pass "o UPSERT do minuto troca o ponto e grava threads (1 linha, 22.0, 11)"
else
  fail "UPSERT inesperado: $(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(threads = 11)) from network_node_samples where node_id = 'nd_thr_mig001' and sampled_at = '$HOUR'")"
fi

if [[ "$(n "select ifnull(sum(threads), -1) from network_node_samples where node_id = 'nd_thr_mig001'")" == "11" ]]; then
  pass "a amostra anterior continua NULL e não entra na soma"
else
  fail "soma de threads inesperada"
fi

if apply V388__network_node_threads.sql >/dev/null 2>&1; then
  pass "V388 é idempotente (2ª aplicação sem erro)"
else
  fail "V388 falhou na 2ª aplicação"
fi

if [[ "$(n "select ifnull(cast(threads as char), 'NULL') from network_node_samples
  where node_id = 'nd_thr_mig001' and sampled_at = '$HOUR'")" == "11" ]]; then
  pass "a 2ª aplicação preserva o valor gravado depois da 1ª"
else
  fail "a 2ª aplicação mexeu no dado vivo"
fi

printf 'node-threads-migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]