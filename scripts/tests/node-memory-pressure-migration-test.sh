#!/usr/bin/env bash
# Migração V389 (swap em uso e pressão de memória do nó, brief §8.2 "Sistema operacional") contra
# um MariaDB real, aplicada DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. as colunas `swap_used_mb` e `memory_pressure_percent` nascem em `network_node_samples` e a
#      migração é idempotente (2ª rodada sem erro);
#   2. amostra já gravada ANTES da migração não perde nada e as colunas novas ficam NULL — "não
#      medido", nunca 0. Zero afirmaria "nenhum swap em uso" e "ninguém esperou memória";
#   3. o UPSERT do heartbeat grava as duas colunas no MESMO minuto (troca o ponto, não abre outro) e
#      preserva `memory_mb`, que fica entre elas;
#   4. os tipos são `bigint` e `double`, aceitam NULL e NÃO têm default 0 (o default escondido
#      transformaria toda linha antiga em "0 MB de swap");
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

# A V389 pede `after memory_mb` e `after swap_used_mb`: a base tem de chegar até a V381 (memória em
# uso/disponível), como a instalação que recebe esta migração.
BASE_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql
  V321__network_node_observed_disk.sql V341__network_node_observed_disk_total.sql
  V343__network_node_observed_disk_total.sql V380__network_node_disk_io.sql
  V381__network_node_memory_split.sql)
for migration in "${BASE_MIGRATIONS[@]}" V389__network_node_memory_pressure.sql; do
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
  CONTAINER="enois-swp-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_swp_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_swp_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_swp_test -e 'select 1' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_swp_test \
           -e 'create table if not exists _ready_probe (id int primary key); drop table _ready_probe' >/dev/null 2>&1; then
        ready="sim"
        break
      fi
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "MariaDB não ficou pronto em 180s"; exit 1; }
  pass "MariaDB 11.4 real no ar"
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_swp_test "$@"; }
else
  db() { mariadb "$STORAGE_DB_DSN" -e "create database if not exists enois_swp_test" 2>/dev/null
    mariadb enois_swp_test "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi
n() { db -N -B -e "$1"; }
apply() { db < "$MIGRATIONS/$1"; }

for migration in "${BASE_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 || { fail "base $migration falhou"; exit 1; }
done

# Nó e amostra do contrato ANTES da V389: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, region, country, created_at)
values ('nd_swp_mig001', 'no-swp', 'user-swp', 'ACTIVE', 'sa-east-1', 'BR', now());
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb, running_tasks)
values ('nd_swp_mig001', '2026-11-26 10:00:00.000', 12.5, 2048, 7900, 1);
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "nó e amostra do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó/amostra de teste (código $seed)"
  exit 1
fi

if apply V389__network_node_memory_pressure.sql >/dev/null 2>&1; then
  pass "V389 aplica sobre a tabela existente"
else
  fail "V389 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V389__network_node_memory_pressure.sql" 2>&1 | head -3 >&2
fi

if [[ "$(n "select count(*) from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name in ('swap_used_mb', 'memory_pressure_percent')")" == "2" ]]; then
  pass "as duas colunas nasceram em network_node_samples"
else
  fail "as colunas não nasceram"
fi

if [[ "$(n "select concat_ws('|', cpu_percent, memory_mb, disk_free_mb,
    ifnull(cast(swap_used_mb as char), 'NULL'), ifnull(cast(memory_pressure_percent as char), 'NULL'))
  from network_node_samples where node_id = 'nd_swp_mig001'")" == "12.5|2048|7900|NULL|NULL" ]]; then
  pass "a amostra anterior ficou intacta e as duas colunas são NULL, não 0"
else
  fail "a amostra anterior mudou: $(n "select concat_ws('|', cpu_percent, memory_mb, disk_free_mb, ifnull(cast(swap_used_mb as char), 'NULL'), ifnull(cast(memory_pressure_percent as char), 'NULL')) from network_node_samples where node_id = 'nd_swp_mig001'")"
fi

# `column_default` nulo vem como a string "NULL": o que não pode existir é um 0 implícito.
definicao=$(n "select group_concat(concat_ws('|', column_name, data_type, is_nullable, ifnull(column_default, 'sem-default')) order by ordinal_position separator ' / ')
  from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name in ('swap_used_mb', 'memory_pressure_percent')")
if [[ "$definicao" == "swap_used_mb|bigint|YES|sem-default / memory_pressure_percent|double|YES|sem-default" \
   || "$definicao" == "swap_used_mb|bigint|YES|NULL / memory_pressure_percent|double|YES|NULL" ]]; then
  pass "swap é bigint e pressão é double, os dois NULL sem default"
else
  fail "definição inesperada: $definicao"
fi

# UPSERT como o adapter faz: o mesmo minuto troca o ponto e preserva as medidas da linha.
HOUR="2026-11-27 09:00:00.000"
db >/dev/null 2>&1 <<SQL
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, swap_used_mb,
  memory_pressure_percent, disk_free_mb, running_tasks)
  values ('nd_swp_mig001', '$HOUR', 20.0, 3000, 512, 2.5, 7800, 1)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    swap_used_mb = values(swap_used_mb), memory_pressure_percent = values(memory_pressure_percent),
    disk_free_mb = values(disk_free_mb), running_tasks = values(running_tasks);
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, swap_used_mb,
  memory_pressure_percent, disk_free_mb, running_tasks)
  values ('nd_swp_mig001', '$HOUR', 22.0, 3100, 640, 7.5, 7700, 0)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    swap_used_mb = values(swap_used_mb), memory_pressure_percent = values(memory_pressure_percent),
    disk_free_mb = values(disk_free_mb), running_tasks = values(running_tasks);
SQL
if [[ "$(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(memory_mb = 3100), sum(swap_used_mb = 640), sum(memory_pressure_percent = 7.5))
  from network_node_samples where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR'")" == "1|1|1|1|1" ]]; then
  pass "o UPSERT do minuto troca o ponto e grava swap, pressão e memória juntos"
else
  fail "UPSERT inesperado: $(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(memory_mb = 3100), sum(swap_used_mb = 640), sum(memory_pressure_percent = 7.5)) from network_node_samples where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR'")"
fi

# Swap é MiB em uso: um valor grande (acima do teto de int) cabe na coluna.
db >/dev/null 2>&1 <<SQL
update network_node_samples set swap_used_mb = 4294967296
  where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR';
SQL
if [[ "$(n "select swap_used_mb from network_node_samples where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR'")" == "4294967296" ]]; then
  pass "swap_used_mb cabe um valor de 4 GiB (é bigint, não int)"
else
  fail "swap_used_mb truncou: $(n "select swap_used_mb from network_node_samples where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR'")"
fi

if [[ "$(n "select ifnull(sum(swap_used_mb), -1) from network_node_samples where node_id = 'nd_swp_mig001'")" == "4294967296" ]]; then
  pass "a amostra anterior continua NULL e não entra na soma"
else
  fail "soma de swap inesperada"
fi

if apply V389__network_node_memory_pressure.sql >/dev/null 2>&1; then
  pass "V389 é idempotente (2ª aplicação sem erro)"
else
  fail "V389 falhou na 2ª aplicação"
fi

if [[ "$(n "select concat_ws('|', swap_used_mb, memory_pressure_percent) from network_node_samples
  where node_id = 'nd_swp_mig001' and sampled_at = '$HOUR'")" == "4294967296|7.5" ]]; then
  pass "a 2ª aplicação preserva o valor gravado depois da 1ª"
else
  fail "a 2ª aplicação mexeu no dado vivo"
fi

printf 'node-memory-pressure-migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]