#!/usr/bin/env bash
# Migração V391 (taxa de rede do nó, brief §8.2 "Throughput de entrada e saída" / §8.3
# "Throughput global") contra um MariaDB real, aplicada DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. as colunas `net_in_mbps` e `net_out_mbps` nascem em `network_node_samples` e a migração é
#      idempotente (2ª rodada sem erro);
#   2. amostra gravada ANTES da migração não perde nada e as colunas novas ficam NULL — "não
#      medido", nunca 0. Zero afirmaria "nada passou";
#   3. o UPSERT do minuto grava as duas taxas no MESMO ponto (troca, não abre outro) ao lado do
#      acumulado, que continua sendo outra medida;
#   4. os tipos são `double`, aceitam NULL e NÃO têm default 0 (o default escondido transformaria
#      toda linha antiga em "0 MiB/s");
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

# A V391 pede `after memory_pressure_percent`: a base tem de chegar até a V389 (quando swap e pressão
# entraram), como a instalação que recebe esta migração.
BASE_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql
  V321__network_node_observed_disk.sql V341__network_node_observed_disk_total.sql
  V343__network_node_observed_disk_total.sql V380__network_node_disk_io.sql
  V381__network_node_memory_split.sql V389__network_node_memory_pressure.sql)
for migration in "${BASE_MIGRATIONS[@]}" V391__network_node_network_throughput.sql; do
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
  CONTAINER="enois-net-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_net_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_net_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_net_test -e 'select 1' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_net_test \
           -e 'create table if not exists _ready_probe (id int primary key); drop table _ready_probe' >/dev/null 2>&1; then
        ready="sim"
        break
      fi
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "MariaDB não ficou pronto em 180s"; exit 1; }
  pass "MariaDB 11.4 real no ar"
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_net_test "$@"; }
else
  db() { mariadb "$STORAGE_DB_DSN" -e "create database if not exists enois_net_test" 2>/dev/null
    mariadb enois_net_test "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi
n() { db -N -B -e "$1"; }
apply() { db < "$MIGRATIONS/$1"; }

for migration in "${BASE_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 || { fail "base $migration falhou"; exit 1; }
done

# Nó e amostra do contrato ANTES da V391: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, region, country, created_at)
values ('nd_net_mig001', 'no-net', 'user-net', 'ACTIVE', 'sa-east-1', 'BR', now());
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, swap_used_mb,
  memory_pressure_percent, disk_free_mb, running_tasks)
values ('nd_net_mig001', '2026-11-26 10:00:00.000', 12.5, 2048, 128, 1.5, 7900, 1);
SQL
if [[ "$?" -eq 0 ]]; then
  pass "nó e amostra do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó/amostra de teste"
  exit 1
fi

if apply V391__network_node_network_throughput.sql >/dev/null 2>&1; then
  pass "V391 aplica sobre a tabela existente"
else
  fail "V391 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V391__network_node_network_throughput.sql" 2>&1 | head -3 >&2
fi

if [[ "$(n "select count(*) from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name in ('net_in_mbps', 'net_out_mbps')")" == "2" ]]; then
  pass "as duas colunas nasceram em network_node_samples"
else
  fail "as colunas não nasceram"
fi

if [[ "$(n "select concat_ws('|', cpu_percent, memory_mb, swap_used_mb, memory_pressure_percent,
    ifnull(cast(net_in_mbps as char), 'NULL'), ifnull(cast(net_out_mbps as char), 'NULL'))
  from network_node_samples where node_id = 'nd_net_mig001'")" == "12.5|2048|128|1.5|NULL|NULL" ]]; then
  pass "a amostra anterior ficou intacta e as duas colunas são NULL, não 0"
else
  fail "a amostra anterior mudou: $(n "select concat_ws('|', cpu_percent, memory_mb, swap_used_mb, memory_pressure_percent, ifnull(cast(net_in_mbps as char), 'NULL'), ifnull(cast(net_out_mbps as char), 'NULL')) from network_node_samples where node_id = 'nd_net_mig001'")"
fi

# `column_default` nulo vem como a string "NULL": o que não pode existir é um 0 implícito.
definicao=$(n "select group_concat(concat_ws('|', column_name, data_type, is_nullable, ifnull(column_default, 'sem-default')) order by ordinal_position separator ' / ')
  from information_schema.columns where table_schema = database()
  and table_name = 'network_node_samples' and column_name in ('net_in_mbps', 'net_out_mbps')")
if [[ "$definicao" == "net_in_mbps|double|YES|sem-default / net_out_mbps|double|YES|sem-default" \
   || "$definicao" == "net_in_mbps|double|YES|NULL / net_out_mbps|double|YES|NULL" ]]; then
  pass "as duas taxas são double, NULL sem default"
else
  fail "definição inesperada: $definicao"
fi

# UPSERT como o adapter faz: o mesmo minuto troca o ponto e preserva as medidas da linha.
HOUR="2026-11-27 09:00:00.000"
db >/dev/null 2>&1 <<SQL
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, swap_used_mb,
  memory_pressure_percent, net_in_mbps, net_out_mbps, disk_free_mb, running_tasks)
  values ('nd_net_mig001', '$HOUR', 20.0, 3000, 256, 2.5, 1.25, 0.5, 7800, 1)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    swap_used_mb = values(swap_used_mb), memory_pressure_percent = values(memory_pressure_percent),
    net_in_mbps = values(net_in_mbps), net_out_mbps = values(net_out_mbps),
    disk_free_mb = values(disk_free_mb), running_tasks = values(running_tasks);
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, swap_used_mb,
  memory_pressure_percent, net_in_mbps, net_out_mbps, disk_free_mb, running_tasks)
  values ('nd_net_mig001', '$HOUR', 22.0, 3100, 300, 7.5, 3.375, 1.125, 7700, 0)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    swap_used_mb = values(swap_used_mb), memory_pressure_percent = values(memory_pressure_percent),
    net_in_mbps = values(net_in_mbps), net_out_mbps = values(net_out_mbps),
    disk_free_mb = values(disk_free_mb), running_tasks = values(running_tasks);
SQL
if [[ "$(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(net_in_mbps = 3.375), sum(net_out_mbps = 1.125), sum(memory_mb = 3100))
  from network_node_samples where node_id = 'nd_net_mig001' and sampled_at = '$HOUR'")" == "1|1|1|1|1" ]]; then
  pass "o UPSERT do minuto troca o ponto e grava as duas taxas junto do resto"
else
  fail "UPSERT inesperado: $(n "select concat_ws('|', count(*), sum(cpu_percent = 22.0), sum(net_in_mbps = 3.375), sum(net_out_mbps = 1.125), sum(memory_mb = 3100)) from network_node_samples where node_id = 'nd_net_mig001' and sampled_at = '$HOUR'")"
fi

# A taxa é precisão fracionária: um valor pequeno (rede ociosa) não é arredondado a 0 nem a inteiro.
db >/dev/null 2>&1 <<SQL
update network_node_samples set net_in_mbps = 0.00048828125, net_out_mbps = 123.456789
  where node_id = 'nd_net_mig001' and sampled_at = '$HOUR';
SQL
if [[ "$(n "select concat_ws('|', net_in_mbps, net_out_mbps) from network_node_samples
  where node_id = 'nd_net_mig001' and sampled_at = '$HOUR'")" == "0.00048828125|123.456789" ]]; then
  pass "as taxas guardam fração (double), sem virar inteiro"
else
  fail "as taxas perderam precisão: $(n "select concat_ws('|', net_in_mbps, net_out_mbps) from network_node_samples where node_id = 'nd_net_mig001' and sampled_at = '$HOUR'")"
fi

if [[ "$(n "select ifnull(sum(net_in_mbps), -1) from network_node_samples where node_id = 'nd_net_mig001'")" == "0.00048828125" ]]; then
  pass "a amostra anterior continua NULL e não entra na soma"
else
  fail "soma das taxas inesperada"
fi

if apply V391__network_node_network_throughput.sql >/dev/null 2>&1; then
  pass "V391 é idempotente (2ª aplicação sem erro)"
else
  fail "V391 falhou na 2ª aplicação"
fi

if [[ "$(n "select concat_ws('|', net_in_mbps, net_out_mbps) from network_node_samples
  where node_id = 'nd_net_mig001' and sampled_at = '$HOUR'")" == "0.00048828125|123.456789" ]]; then
  pass "a 2ª aplicação preserva o valor gravado depois da 1ª"
else
  fail "a 2ª aplicação mexeu no dado vivo"
fi

printf 'node-network-throughput-migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]