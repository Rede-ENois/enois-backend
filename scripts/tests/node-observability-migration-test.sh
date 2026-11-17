#!/usr/bin/env bash
# Migração do painel de observabilidade por nó (V325) contra um MariaDB real, aplicada DUAS vezes.
#
# Por que existe: os testes de suíte rodam em H2, e o H2 aceita coisas que o MariaDB de produção
# recusa. A V325 não é só `create table`: ela usa `on duplicate key update` (upsert do ponto do
# minuto), um `delete ... where id not in (select ... limit ?)` (o TETO, que o MySQL exige em
# subconsulta derivada) e uma FK com `on delete cascade` para `network_nodes`. Se qualquer uma
# dessas formas só funcionasse no H2, a produção descobriria — e a janela do painel é escrita a
# CADA heartbeat, ou seja, o caminho mais quente do módulo.
#
# O que este harness prova:
#   1. as migrações aplicam num MariaDB 11.4 limpo, na ordem;
#   2. aplicar DE NOVO não falha e preserva as linhas gravadas;
#   3. o upsert do minuto substitui em vez de duplicar — a chave primária (node_id, sampled_at);
#   4. o `delete ... not in (select ... limit)` do teto roda no banco real (o H2 perdoa o que o
#      MariaDB recusa);
#   5. a FK com cascade apaga as amostras e os logs quando o nó some;
#   6. amostra de nó inexistente é recusada pela FK.
#
# Uso:
#   ./scripts/tests/node-observability-migration-test.sh
#   STORAGE_DB_DSN=... ./scripts/tests/node-observability-migration-test.sh
#
# O caminho padrão sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

OBS_MIGRATIONS=(V305__network_nodes.sql V325__network_node_observability.sql)
for migration in "${OBS_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-obs-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_obs_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor
  # estável. Exigir a MESMA conexão por duas rodadas seguidas (com o banco já existindo) é o
  # que separa "respondeu" de "ficou no ar": sem isso, o init reiniciava no meio do seeding e a
  # rodada seguinte encontrava um banco vazio — o harness passava ou falhava conforme o timing.
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_obs_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_obs_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      sleep 3
      if docker exec "$CONTAINER" mariadb -uroot enois_obs_test \
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
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_obs_test "$@"; }
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

# ---------------------------------------------------------------- 1ª aplicação
first_ok=0
for migration in "${OBS_MIGRATIONS[@]}"; do
  if apply "$migration" >/dev/null 2>&1; then
    first_ok=$((first_ok + 1))
  else
    fail "1ª aplicação falhou: $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
[[ "$first_ok" == "${#OBS_MIGRATIONS[@]}" ]] && pass "migrações do painel aplicam em banco limpo"

# ---------------------------------------------------------------- dados entre as rodadas
db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at)
values ('nd_obs_mig001', 'no-painel', 'user-obs', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100, 'sa-east-1', 'BR',
  now(3))
on duplicate key update display_name = values(display_name);
insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb, running_tasks,
  queue_depth, mode, activity)
values ('nd_obs_mig001', '2026-11-12 12:00:00.000', 12.5, 2048, 9000, 1, 0, 'ACTIVE', 'ocioso');
insert into network_node_logs (id, node_id, logged_at, level, message)
values ('nl_mig001', 'nd_obs_mig001', '2026-11-12 12:00:01.000', 'INFO', 'linha do painel');
SQL
if [[ $? -eq 0 ]]; then
  pass "dados de teste gravados entre as rodadas (nó, amostra e linha)"
else
  fail "não foi possível gravar os dados de teste"
  db <<'SQL' 2>&1 | head -6 >&2
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at)
values ('nd_obs_mig001', 'no-painel', 'user-obs', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100, 'sa-east-1', 'BR',
  now(3));
SQL
fi

# ---------------------------------------------------------------- 2ª aplicação
second_ok=0
for migration in "${OBS_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 && second_ok=$((second_ok + 1)) \
    || fail "2ª aplicação falhou: $migration"
done
[[ "$second_ok" == "${#OBS_MIGRATIONS[@]}" ]] && pass "migrações reaplicam sem erro (idempotentes)"

# ---------------------------------------------------------------- dados preservados
samples=$(db -N -e "select count(*) from network_node_samples where node_id = 'nd_obs_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$samples" == "1" ]] && pass "amostra gravada sobrevive à 2ª rodada" || fail "amostra sumiu (achei '$samples')"
logs=$(db -N -e "select count(*) from network_node_logs where id = 'nl_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$logs" == "1" ]] && pass "linha do painel sobrevive à 2ª rodada" || fail "linha sumiu (achei '$logs')"

# ---------------------------------------------------------------- upsert do minuto
# O serviço grava o PONTO DO MINUTO: dois heartbeats no mesmo minuto trocam o valor.
db -e "insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb,
  running_tasks, queue_depth, mode, activity)
  values ('nd_obs_mig001', '2026-11-12 12:00:00.000', 40.0, 3072, 8000, 2, 1, 'ACTIVE', 'tarefa')
  on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
  disk_free_mb = values(disk_free_mb), running_tasks = values(running_tasks),
  queue_depth = values(queue_depth), mode = values(mode), activity = values(activity)" 2>/dev/null
upserted=$(db -N -e "select concat(count(*), '/', max(cpu_percent)) from network_node_samples
  where node_id = 'nd_obs_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$upserted" == "1/40" ]] && pass "upsert do minuto troca o ponto (não abre dois)" \
  || fail "upsert do minuto errado (achei '$upserted', esperado '1/40')"

# ---------------------------------------------------------------- o TETO no banco real
# A forma que o adapter usa: `delete ... where id not in (select id from (...) keep_rows)`.
# O MariaDB exige a subconsulta derivada com alias; sem ela o erro só aparece aqui.
for i in $(seq 1 5); do
  db -e "insert into network_node_logs (id, node_id, logged_at, level, message)
    values ('nl_teto00$i', 'nd_obs_mig001', '2026-11-12 12:00:0${i}.000', 'INFO', 'teto $i')" 2>/dev/null
done
if db -e "delete from network_node_logs where node_id = 'nd_obs_mig001' and id not in (
  select id from (
    select id from network_node_logs where node_id = 'nd_obs_mig001' order by logged_at desc, id desc limit 3
  ) keep_rows
)" 2>/dev/null; then
  pass "delete do teto (not in + subconsulta derivada com limit) roda no banco real"
else
  fail "delete do teto recusado pelo banco real"
fi
kept=$(db -N -e "select count(*) from network_node_logs where node_id = 'nd_obs_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$kept" == "3" ]] && pass "teto deixa exatamente as 3 mais novas" || fail "teto deixou '$kept' (esperado 3)"
newest=$(db -N -e "select message from network_node_logs where node_id = 'nd_obs_mig001'
  order by logged_at desc, id desc limit 1" 2>/dev/null | tr -d '[:space:]')
[[ "$newest" == "teto5" ]] && pass "teto preserva a linha mais nova" || fail "mais nova é '$newest' (esperado teto5)"

# The delete do teto das amostras usa a mesma forma, com `sampled_at` como chave.
if db -e "delete from network_node_samples where node_id = 'nd_obs_mig001' and sampled_at not in (
  select sampled_at from (
    select sampled_at from network_node_samples where node_id = 'nd_obs_mig001' order by sampled_at desc limit 1
  ) keep_rows
)" 2>/dev/null; then
  pass "delete do teto de amostras roda no banco real"
else
  fail "delete do teto de amostras recusado pelo banco real"
fi

# ---------------------------------------------------------------- esquema
column_exists() {
  local count
  count=$(db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = '$1' and column_name = '$2'" 2>/dev/null | tr -d '[:space:]')
  [[ "$count" != "0" ]]
}
for column in cpu_percent memory_mb disk_free_mb running_tasks queue_depth mode activity; do
  column_exists network_node_samples "$column" \
    && pass "amostra tem coluna $column" || fail "amostra sem coluna $column"
done
for column in logged_at level message; do
  column_exists network_node_logs "$column" \
    && pass "console tem coluna $column" || fail "console sem coluna $column"
done

for fk in fk_network_node_logs_node fk_network_node_samples_node; do
  fk_cascade=$(db -N -e "select delete_rule from information_schema.referential_constraints
    where constraint_schema = database() and constraint_name = '$fk'" 2>/dev/null | tr -d '[:space:]')
  [[ "$fk_cascade" == "CASCADE" ]] && pass "FK $fk apaga em cascata" \
    || fail "FK $fk sem cascade (achei '$fk_cascade')"
done

# ---------------------------------------------------------------- comportamento do banco real
if db -e "insert into network_node_samples (node_id, sampled_at) values ('nd_obs_inexistente', now(3))" 2>/dev/null; then
  fail "amostra de nó inexistente foi aceita (FK não vale)"
else
  pass "amostra de nó inexistente recusada pela FK"
fi

# A chave (node_id, sampled_at) é a garantia de "um ponto por minuto": o insert cru do mesmo
# minuto tem de ser RECUSADO — o caminho do serviço usa o upsert justamente por isso.
if db -e "insert into network_node_samples (node_id, sampled_at) values ('nd_obs_mig001', '2026-11-12 12:00:00.000')" 2>/dev/null; then
  fail "chave (node_id, sampled_at) deixou de ser única"
else
  pass "mesmo minuto do mesmo nó recusado pela chave primária"
fi

# Apagar o nó leva junto a janela dele: um nó que sai da rede não deixa lixo de painel.
db -e "delete from network_nodes where id = 'nd_obs_mig001'" 2>/dev/null
left=$(db -N -e "select (select count(*) from network_node_logs where node_id = 'nd_obs_mig001')
  + (select count(*) from network_node_samples where node_id = 'nd_obs_mig001')" 2>/dev/null | tr -d '[:space:]')
[[ "$left" == "0" ]] && pass "apagar o nó leva a janela dele (cascade)" || fail "sobrou '$left' linha após apagar o nó"

# ---------------------------------------------------------------- resumo
printf '\nNode observability migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]