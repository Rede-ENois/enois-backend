#!/usr/bin/env bash
# Migração V380 (memória do nó em uso + disponível) contra um MariaDB real, aplicada DUAS vezes.
#
# O que esta migração promete e o que aqui se mede:
#   1. `observed_memory_available_mb` nasce, e é idempotente (2ª rodada sem erro);
#   2. nó já registrado ANTES da migração não perde a medida: o número que estava em
#      `observed_memory_mb` era o DISPONÍVEL e vai para `observed_memory_available_mb`, com o uso
#      nulo até o próximo heartbeat. Ler o antigo como uso manteria o nó "teto atingido" com a
#      máquina ociosa — o defeito que a V380 corrige — até o heartbeat chegar;
#   4. o UPSERT do heartbeat grava as duas colunas e não deixa `observed_running_tasks` para trás —
#      o `set` novo é em uma instrução só, e uma coluna esquecida ali pararia de ser atualizada.
#
# MariaDB 11.4 real, como os outros harnesses de migração: o H2 aceita `add column if not exists`
# e o MariaDB também, mas o tipo, o `after` e o upsert de várias colunas só o servidor de verdade
# recusa. `STORAGE_DB_DSN` reaproveita um banco já em pé em vez de subir container.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

MEM_MIGRATIONS=(V305__network_nodes.sql V380__network_node_memory_split.sql)
for migration in "${MEM_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-mem-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_mem_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor
  # estável. Exigir a MESMA conexão criar e apagar uma tabela por duas rodadas seguidas separa
  # "respondeu" de "ficou no ar".
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_mem_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_mem_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_mem_test \
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
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_mem_test "$@"; }
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
  local count
  count=$(db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'network_nodes'
      and column_name = '$1'" 2>/dev/null | tr -d '[:space:]')
  [[ "$count" == "1" ]]
}

# ---------------------------------------------------------------- 1ª aplicação
if apply V305__network_nodes.sql >/dev/null 2>&1; then
  pass "V305 (tabela de nós) aplica em banco limpo"
else
  fail "1ª aplicação falhou: V305"
  exit 1
fi

# Nó registrado ANTES da V380, com as medidas do contrato antigo: `observed_memory_mb` guardava o
# DISPONÍVEL que o runtime mandava (7900 de 8192), porque era o único campo de memória. E um segundo
# nó cuja "disponível" passava do declarado (4096 de 2048): derivar daria uso negativo.
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country,
  observed_cpu_percent, observed_memory_mb, observed_running_tasks, last_seen_at, created_at)
values ('nd_mem_mig001', 'no-legado', 'user-mem', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100,
  'sa-east-1', 'BR', 12.5, 7900, 2, now(3), now(3));

insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country,
  observed_cpu_percent, observed_memory_mb, observed_running_tasks, last_seen_at, created_at)
values ('nd_mem_mig002', 'no-grande', 'user-mem', 'ACTIVE', 'NODE_WORKER', 2, 2048, 51200, 100,
  'sa-east-1', 'BR', 3.0, 4096, 1, now(3), now(3));
SQL
if [[ $? -eq 0 ]]; then
  pass "nó do contrato antigo gravado antes da migração (memória = 7900)"
else
  fail "não foi possível gravar o nó de teste"
  exit 1
fi

if apply V380__network_node_memory_split.sql >/dev/null 2>&1; then
  pass "V380 aplica sobre a tabela existente"
else
  fail "V380 falhou na 1ª aplicação"
  db < "$MIGRATIONS/V380__network_node_memory_split.sql" 2>&1 | head -3 >&2
fi

column_exists observed_memory_available_mb \
  && pass "coluna observed_memory_available_mb existe" \
  || fail "coluna observed_memory_available_mb não nasceu"

# ---------------------------------------------------------------- dados reinterpretados
row=$(db -N -e "select concat_ws('|', ifnull(observed_memory_mb, 'NULL'),
  ifnull(observed_memory_available_mb, 'NULL'), observed_running_tasks, declared_memory_mb, status)
  from network_nodes where id = 'nd_mem_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$row" == "NULL|7900|2|8192|ACTIVE" ]] \
  && pass "nó antigo reinterpretado: disponível 7900, uso NULL (não medido), resto igual" \
  || fail "linha do nó mudou na migração (achei '$row')"

row_grande=$(db -N -e "select concat_ws('|', ifnull(observed_memory_mb, 'NULL'),
  ifnull(observed_memory_available_mb, 'NULL')) from network_nodes where id = 'nd_mem_mig002'" \
  2>/dev/null | tr -d '[:space:]')
[[ "$row_grande" == "NULL|4096" ]] \
  && pass "disponível acima do declarado também vai para a coluna do disponível" \
  || fail "nó com disponível acima do declarado ficou '$row_grande'"

# A coluna nova não tem valor inventado: `NULL` é "não medido", e `0` afirmaria máquina sem memória
# livre — a mesma distinção que o contrato faz em toda medida.
zeros=$(db -N -e "select count(*) from network_nodes where observed_memory_available_mb = 0" 2>/dev/null \
  | tr -d '[:space:]')
[[ "$zeros" == "0" ]] && pass "migração não grava zero onde ninguém mediu" \
  || fail "migração inventou zero em $zeros nó(s)"

# ---------------------------------------------------------------- 2ª aplicação
if apply V380__network_node_memory_split.sql >/dev/null 2>&1; then
  pass "V380 reaplica sem erro (idempotente)"
else
  fail "V380 falhou na 2ª aplicação"
fi
column_exists observed_memory_available_mb \
  && pass "coluna continua existindo depois da 2ª rodada" \
  || fail "coluna sumiu na 2ª rodada"
# `concat_ws` SALTA nulos: a linha reinterpretada é `NULL|7900|...`, e o recorte de duas colunas
# volta só `7900`. A comparação é com essa forma, que é a mesma nas duas rodadas.
antes_2a=$(printf '%s' "$row" | cut -d'|' -f2)
row2=$(db -N -e "select concat_ws('|', observed_memory_mb, ifnull(observed_memory_available_mb, 'NULL'))
  from network_nodes where id = 'nd_mem_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$row2" == "$antes_2a" ]] \
  && pass "dados seguem intactos depois da 2ª rodada" \
  || fail "dados mudaram na 2ª rodada (achei '$row2')"

# ---------------------------------------------------------------- upsert do heartbeat
# A forma que o adapter usa: uma instrução só, com todas as colunas do heartbeat V380.
if db -e "update network_nodes set runtime_version = '0.1.0', observed_cpu_percent = 30.0,
  observed_memory_mb = 8000, observed_memory_available_mb = 192, observed_running_tasks = 0,
  last_seen_at = now(3) where id = 'nd_mem_mig001'" 2>/dev/null; then
  pass "update do heartbeat grava uso e disponível na mesma instrução"
else
  fail "update do heartbeat recusado pelo banco real"
fi
hb=$(db -N -e "select concat_ws('|', observed_memory_mb, observed_memory_available_mb, observed_running_tasks)
  from network_nodes where id = 'nd_mem_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$hb" == "8000|192|0" ]] && pass "heartbeat lê uso 8000, disponível 192, tarefas 0" \
  || fail "heartbeat gravou '$hb' (esperado '8000|192|0')"

# O uso está no teto: 8000 < 8192 não é teto, e a régua do domínio (`>=`) é a do SQL. Prova que a
# comparação que o balanceamento faz roda no banco real com os tipos da migração.
exhausted=$(db -N -e "select if(observed_memory_mb >= declared_memory_mb, 'sim', 'nao')
  from network_nodes where id = 'nd_mem_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$exhausted" == "nao" ]] && pass "8000 de 8192 não é teto (uso < declarado)" \
  || fail "régua do teto errada no banco (achei '$exhausted')"
db -e "update network_nodes set observed_memory_mb = 8192, observed_memory_available_mb = 0
  where id = 'nd_mem_mig001'" 2>/dev/null
exhausted=$(db -N -e "select if(observed_memory_mb >= declared_memory_mb, 'sim', 'nao')
  from network_nodes where id = 'nd_mem_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$exhausted" == "sim" ]] && pass "8192 de 8192 é teto" || fail "régua do teto errada no teto"

# ---------------------------------------------------------------- esquema
kind=$(db -N -e "select column_type from information_schema.columns
  where table_schema = database() and table_name = 'network_nodes'
    and column_name = 'observed_memory_available_mb'" 2>/dev/null | tr -d '[:space:]')
# O MariaDB 11.4 declara `int(11)`; o H2, `INTEGER`. A régua é o TIPO base, não a largura de tela.
[[ "$kind" == int* ]] && pass "tipo int, como a coluna de uso (não bigint/decimal)" \
  || fail "tipo da coluna é '$kind' (esperado int)"
nullable=$(db -N -e "select is_nullable from information_schema.columns
  where table_schema = database() and table_name = 'network_nodes'
    and column_name = 'observed_memory_available_mb'" 2>/dev/null | tr -d '[:space:]')
[[ "$nullable" == "YES" ]] && pass "coluna aceita NULL (não medido não é zero)" \
  || fail "coluna é '$nullable' (esperado YES)"

printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]