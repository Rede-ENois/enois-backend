#!/usr/bin/env bash
# Migração V379 (IOPS e latência de disco por nó) contra um MariaDB real, aplicada DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. as duas colunas nascem em `network_nodes` (o último valor) e em `network_node_samples` (a
#      série do quadro ao vivo), e são idempotentes (2ª rodada sem erro);
#   2. nó e amostra já gravados ANTES da migração não perdem nada: as colunas novas ficam NULL, que
#      é "não medido" — 0 afirmaria um disco parado que ninguém mediu, e a latência sem operação não
#      tem por onde dividir;
#   3. o UPSERT do heartbeat grava as duas colunas da amostra no mesmo minuto (troca o ponto, não
#      abre outro), e o UPDATE do nó grava o último valor;
#   4. os tipos são `double` e aceitam NULL.
#
# `STORAGE_DB_DSN` reaproveita um banco já em pé em vez de subir container.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

IO_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql
  V321__network_node_observed_disk.sql V341__network_node_observed_disk_total.sql
  V343__network_node_observed_disk_total.sql V379__network_node_disk_io.sql)
for migration in "${IO_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-io-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_io_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor
  # estável. A MESMA conexão criar e apagar uma tabela em duas rodadas seguidas separa
  # "respondeu" de "ficou no ar".
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_io_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_io_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_io_test \
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
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_io_test "$@"; }
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

column_exists() {
  local table="$1" column="$2" count
  count=$(db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = '$table'
      and column_name = '$column'" 2>/dev/null | tr -d '[:space:]')
  [[ "$count" == "1" ]]
}

for migration in V305__network_nodes.sql V325__network_node_observability.sql \
    V321__network_node_observed_disk.sql V341__network_node_observed_disk_total.sql \
    V343__network_node_observed_disk_total.sql; do
  if apply "$migration" >/dev/null 2>&1; then
    pass "base aplica ($migration)"
  else
    fail "aplicação falhou: $migration"
    exit 1
  fi
done

# Nó e amostra do contrato ANTES da V379: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country,
  observed_cpu_percent, observed_disk_free_mb, last_seen_at, created_at)
values ('nd_io_mig001', 'no-io', 'user-io', 'ACTIVE', 'NODE_STORAGE', 4, 8192, 102400, 100,
  'sa-east-1', 'BR', 12.5, 7900, now(3), now(3));
insert into network_node_samples (node_id, sampled_at, cpu_percent, disk_free_mb, running_tasks)
values ('nd_io_mig001', '2026-11-25 10:00:00.000', 12.5, 7900, 1);
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "nó e amostra do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó de teste (código $seed)"
  db -e "insert into network_nodes (id, display_name, owner_user_id, created_at) values ('nd_diag', 'd', 'u', now())" 2>&1 | head -2 >&2
  exit 1
fi

if apply V379__network_node_disk_io.sql >/dev/null 2>&1; then
  pass "V379 aplica sobre as tabelas existentes"
else
  fail "V379 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V379__network_node_disk_io.sql" 2>&1 | head -3 >&2
fi

for dupla in "network_nodes observed_disk_iops" "network_nodes observed_disk_latency_ms" \
             "network_node_samples disk_iops" "network_node_samples disk_latency_ms"; do
  # shellcheck disable=SC2086
  column_exists $dupla \
    && pass "coluna ${dupla#* } existe em ${dupla%% *}" \
    || fail "coluna ${dupla#* } não nasceu em ${dupla%% *}"
done

# Nada é inventado: as colunas novas ficam NULL no que já existia — não 0, que afirmaria disco
# parado e latência zero.
row=$(db -N -e "select concat_ws('|', observed_cpu_percent, observed_disk_free_mb,
  ifnull(observed_disk_iops, 'NULL'), ifnull(observed_disk_latency_ms, 'NULL'))
  from network_nodes where id = 'nd_io_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$row" == "12.5|7900|NULL|NULL" ]] \
  && pass "nó existente preservado; IOPS e latência NULL (não medido)" \
  || fail "linha do nó mudou na migração (achei '$row')"
sample=$(db -N -e "select concat_ws('|', cpu_percent, disk_free_mb,
  ifnull(disk_iops, 'NULL'), ifnull(disk_latency_ms, 'NULL'))
  from network_node_samples where node_id = 'nd_io_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$sample" == "12.5|7900|NULL|NULL" ]] \
  && pass "amostra existente preservada; IOPS e latência NULL" \
  || fail "amostra mudou na migração (achei '$sample')"

# O UPDATE do nó e o UPSERT da amostra, na forma que o adapter usa: uma instrução cada, com todas
# as colunas do heartbeat V379. A amostra do MESMO minuto é trocada, não duplicada.
if db -e "update network_nodes set observed_disk_iops = 100.00, observed_disk_latency_ms = 3.00
  where id = 'nd_io_mig001'" 2>/dev/null; then
  pass "update do nó grava IOPS e latência"
else
  fail "update do nó recusado pelo banco real"
fi
if db -e "insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb,
    disk_iops, disk_latency_ms, running_tasks, queue_depth, mode, activity)
  values ('nd_io_mig001', '2026-11-25 10:00:00.000', 20.0, 2048, 7800, 100.00, 3.00, 1, null, 'NORMAL', null)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    disk_free_mb = values(disk_free_mb), disk_iops = values(disk_iops),
    disk_latency_ms = values(disk_latency_ms), running_tasks = values(running_tasks),
    queue_depth = values(queue_depth), mode = values(mode), activity = values(activity)" 2>/dev/null; then
  pass "upsert da amostra aceita as colunas novas"
else
  fail "upsert da amostra recusado pelo banco real"
fi
hb=$(db -N -e "select concat_ws('|', cast(observed_disk_iops as char), cast(observed_disk_latency_ms as char))
  from network_nodes where id = 'nd_io_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$hb" == "100|3" ]] && pass "nó lê IOPS 100 e latência 3" \
  || fail "nó gravou '$hb' (esperado '100|3')"
count=$(db -N -e "select count(*) from network_node_samples where node_id = 'nd_io_mig001'" \
  | tr -d '[:space:]')
st=$(db -N -e "select concat_ws('|', cast(cpu_percent as char), cast(disk_iops as char),
  cast(disk_latency_ms as char)) from network_node_samples
  where node_id = 'nd_io_mig001'" | tr -d '[:space:]')
[[ "$count" == "1" && "$st" == "20|100|3" ]] \
  && pass "mesmo minuto troca o ponto (1 linha) com as medidas novas" \
  || fail "amostra do minuto: $count linha(s), '$st' (esperado 1, '20|100|3')"

# IOPS 0.00 é MEDIDA e a coluna precisa guardá-la como zero, distinta de NULL (não medido).
db -e "insert into network_node_samples (node_id, sampled_at, disk_iops, disk_latency_ms)
  values ('nd_io_mig001', '2026-11-25 10:01:00.000', 0.00, null)" 2>/dev/null
zero=$(db -N -e "select concat_ws('|', cast(disk_iops as char), ifnull(cast(disk_latency_ms as char), 'NULL'))
  from network_node_samples where node_id = 'nd_io_mig001' and sampled_at = '2026-11-25 10:01:00.000'" \
  | tr -d '[:space:]')
[[ "$zero" == "0|NULL" ]] && pass "IOPS 0 medido é guardado como 0, latência sem operação como NULL" \
  || fail "zero e NULL se confundiram (achei '$zero')"

# ---------------------------------------------------------------- 2ª aplicação
if apply V379__network_node_disk_io.sql >/dev/null 2>&1; then
  pass "V379 reaplica sem erro (idempotente)"
else
  fail "V379 falhou na 2ª aplicação"
fi
column_exists network_nodes observed_disk_iops \
  && pass "colunas continuam existindo depois da 2ª rodada" \
  || fail "coluna sumiu na 2ª rodada"
depois=$(db -N -e "select concat_ws('|', cast(observed_disk_iops as char),
  cast(observed_disk_latency_ms as char)) from network_nodes where id = 'nd_io_mig001'" \
  | tr -d '[:space:]')
[[ "$depois" == "100|3" ]] && pass "dados seguem intactos depois da 2ª rodada" \
  || fail "dados mudaram na 2ª rodada (achei '$depois')"

# ---------------------------------------------------------------- esquema
for col in observed_disk_iops observed_disk_latency_ms disk_iops disk_latency_ms; do
  tabela=network_nodes; [[ "$col" == disk_* ]] && tabela=network_node_samples
  kind=$(db -N -e "select column_type from information_schema.columns
    where table_schema = database() and table_name = '$tabela' and column_name = '$col'" \
    | tr -d '[:space:]')
  nullable=$(db -N -e "select is_nullable from information_schema.columns
    where table_schema = database() and table_name = '$tabela' and column_name = '$col'" \
    | tr -d '[:space:]')
  # MariaDB declara `double`; o H2, `DOUBLE PRECISION`. A régua é o tipo base, sem a largura.
  [[ "$kind" == double* && "$nullable" == "YES" ]] \
    && pass "$col é $kind e aceita NULL" \
    || fail "$col é '$kind', nullable '$nullable' (esperado double, YES)"
done

printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
