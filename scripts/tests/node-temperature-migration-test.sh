#!/usr/bin/env bash
# Migração V382 (temperatura da máquina do nó) contra um MariaDB real, aplicada DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. a coluna nasce em `network_nodes` (o último valor) e em `network_node_samples` (a série do
#      quadro ao vivo), e é idempotente (2ª rodada sem erro);
#   2. nó e amostra já gravados ANTES da migração não perdem nada: a coluna nova fica NULL, que é
#      "a máquina não tem sensor legível" — 0 °C afirmaria uma temperatura que ninguém leu;
#   3. o UPSERT do heartbeat grava a coluna da amostra no mesmo minuto (troca o ponto, não abre
#      outro), e o UPDATE do nó grava o último valor, com a casa decimal preservada (39.6, não 40);
#   4. o tipo é `double` e aceita NULL.
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
  V343__network_node_observed_disk_total.sql V380__network_node_disk_io.sql
  V382__network_node_temperature.sql)
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
    V343__network_node_observed_disk_total.sql V380__network_node_disk_io.sql; do
  if apply "$migration" >/dev/null 2>&1; then
    pass "base aplica ($migration)"
  else
    fail "aplicação falhou: $migration"
    exit 1
  fi
done

# Nó e amostra do contrato ANTES da V382: só as colunas que já existiam.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country,
  observed_cpu_percent, observed_disk_free_mb, last_seen_at, created_at)
values ('nd_temp_mig001', 'no-temp', 'user-temp', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100,
  'sa-east-1', 'BR', 12.5, 7900, now(3), now(3));
insert into network_node_samples (node_id, sampled_at, cpu_percent, disk_free_mb, running_tasks)
values ('nd_temp_mig001', '2026-11-25 10:00:00.000', 12.5, 7900, 1);
SQL
seed=$?
if [[ "$seed" -eq 0 ]]; then
  pass "nó e amostra do contrato antigo gravados antes da migração"
else
  fail "não foi possível gravar o nó de teste (código $seed)"
  db -e "insert into network_nodes (id, display_name, owner_user_id, created_at) values ('nd_diag', 'd', 'u', now())" 2>&1 | head -2 >&2
  exit 1
fi

if apply V382__network_node_temperature.sql >/dev/null 2>&1; then
  pass "V382 aplica sobre as tabelas existentes"
else
  fail "V382 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V382__network_node_temperature.sql" 2>&1 | head -3 >&2
fi

for dupla in "network_nodes observed_temperature_c" "network_node_samples temperature_c"; do
  # shellcheck disable=SC2086
  column_exists $dupla \
    && pass "coluna ${dupla#* } existe em ${dupla%% *}" \
    || fail "coluna ${dupla#* } não nasceu em ${dupla%% *}"
done

# Nada é inventado: a coluna nova fica NULL no que já existia — a máquina daqueles heartbeats não
# tinha como ter medido temperatura, e 0 °C afirmaria uma leitura que ninguém fez.
row=$(db -N -e "select concat_ws('|', observed_cpu_percent, observed_disk_free_mb,
  ifnull(cast(observed_temperature_c as char), 'NULL'))
  from network_nodes where id = 'nd_temp_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$row" == "12.5|7900|NULL" ]] \
  && pass "nó existente preservado; temperatura NULL (não medida)" \
  || fail "linha do nó mudou na migração (achei '$row')"
sample=$(db -N -e "select concat_ws('|', cpu_percent, disk_free_mb,
  ifnull(cast(temperature_c as char), 'NULL'))
  from network_node_samples where node_id = 'nd_temp_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$sample" == "12.5|7900|NULL" ]] \
  && pass "amostra existente preservada; temperatura NULL" \
  || fail "amostra mudou na migração (achei '$sample')"

# O UPDATE do nó e o UPSERT da amostra, na forma que o adapter usa: uma instrução cada, com todas
# as colunas do heartbeat V382. A amostra do MESMO minuto é trocada, não duplicada.
if db -e "update network_nodes set observed_temperature_c = 39.6
  where id = 'nd_temp_mig001'" 2>/dev/null; then
  pass "update do nó grava a temperatura"
else
  fail "update do nó recusado pelo banco real"
fi
if db -e "insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb,
    disk_iops, disk_latency_ms, temperature_c, running_tasks, queue_depth, mode, activity)
  values ('nd_temp_mig001', '2026-11-25 10:00:00.000', 20.0, 2048, 7800, 100.00, 3.00, 39.6, 1, null, 'NORMAL', null)
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
    disk_free_mb = values(disk_free_mb), disk_iops = values(disk_iops),
    disk_latency_ms = values(disk_latency_ms), temperature_c = values(temperature_c),
    running_tasks = values(running_tasks),
    queue_depth = values(queue_depth), mode = values(mode), activity = values(activity)" 2>/dev/null; then
  pass "upsert da amostra aceita a coluna nova"
else
  fail "upsert da amostra recusado pelo banco real"
fi
hb=$(db -N -e "select cast(observed_temperature_c as char) from network_nodes where id = 'nd_temp_mig001'" \
  | tr -d '[:space:]')
# A casa decimal é a MEDIDA, não ruído: 39.6 não pode virar 40, que seria outra temperatura.
[[ "$hb" == "39.6" ]] && pass "nó lê a temperatura com a casa decimal (39.6, não 40)" \
  || fail "nó gravou '$hb' (esperado '39.6')"
count=$(db -N -e "select count(*) from network_node_samples where node_id = 'nd_temp_mig001'" \
  | tr -d '[:space:]')
st=$(db -N -e "select concat_ws('|', cast(cpu_percent as char), cast(temperature_c as char))
  from network_node_samples where node_id = 'nd_temp_mig001'" \
  | tr -d '[:space:]')
[[ "$count" == "1" && "$st" == "20|39.6" ]] \
  && pass "mesmo minuto troca o ponto (1 linha) com a medida nova" \
  || fail "amostra do minuto: $count linha(s), '$st' (esperado 1, '20|39.6')"

# Segmentação: a temperatura vive em `double`, e o zero é uma medida legítima (máquina no gelo não
# existe, mas o banco não pode recusar o valor); o que ele NÃO pode confundir é nulo com zero.
db -e "insert into network_node_samples (node_id, sampled_at, temperature_c)
  values ('nd_temp_mig001', '2026-11-25 10:01:00.000', 0.00)" 2>/dev/null
zero=$(db -N -e "select cast(temperature_c as char) from network_node_samples
  where node_id = 'nd_temp_mig001' and sampled_at = '2026-11-25 10:01:00.000'" \
  | tr -d '[:space:]')
[[ "$zero" == "0" ]] && pass "temperatura 0 gravada é 0 e não NULL" \
  || fail "zero e NULL se confundiram (achei '$zero')"

# ---------------------------------------------------------------- 2ª aplicação
if apply V382__network_node_temperature.sql >/dev/null 2>&1; then
  pass "V382 reaplica sem erro (idempotente)"
else
  fail "V382 falhou na 2ª aplicação"
fi
column_exists network_nodes observed_temperature_c \
  && pass "colunas continuam existindo depois da 2ª rodada" \
  || fail "coluna sumiu na 2ª rodada"
depois=$(db -N -e "select cast(observed_temperature_c as char) from network_nodes
  where id = 'nd_temp_mig001'" | tr -d '[:space:]')
[[ "$depois" == "39.6" ]] && pass "dados seguem intactos depois da 2ª rodada" \
  || fail "dados mudaram na 2ª rodada (achei '$depois')"

# ---------------------------------------------------------------- esquema
for col in observed_temperature_c temperature_c; do
  tabela=network_nodes; [[ "$col" == temperature_c ]] && tabela=network_node_samples
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
