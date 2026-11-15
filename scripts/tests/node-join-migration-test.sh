#!/usr/bin/env bash
# Migração da inscrição de nó (V324) contra um MariaDB real, aplicada DUAS vezes com dados dentro.
#
# Por que existe: a V324 não é só `create table`. Ela acrescenta duas colunas numa tabela que já
# tem linhas em produção (`network_provision_tokens`), cria tabela com chave estrangeira para
# `network_nodes` e usa `char(11)`/`char(64)` — e é o banco real que recusa o que o H2 aceita
# (o caso do índice largo em varchar que já apareceu na fase 1 do Storage). Aplicar uma vez num
# banco limpo não prova nada sobre a produção; aplicar duas vezes, com dados, prova o que a migração
# promete: aditiva e idempotente.
#
# O que este harness prova:
#   1. as migrações aplicam num MariaDB 11.4 limpo, na ordem;
#   2. aplicar DE NOVO não falha e preserva as linhas gravadas (inclusive os tokens antigos, que
#      ficam com as colunas novas nulas — o caminho administrativo não muda);
#   3. o esquema resultante tem as colunas, a chave primária e a FK que o código espera;
#   4. o banco real aceita a linha de inscrição (CPF de 11 dígitos, hash de 64) e RECUSA duas
#      inscrições com o mesmo id — a prova de que a chave primária está lá.
#
# Uso:
#   ./scripts/tests/node-join-migration-test.sh
#   STORAGE_DB_DSN=... ./scripts/tests/node-join-migration-test.sh   # banco já existente
#
# O caminho padrão sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

# A inscrição depende de network_nodes existir (FK) e de network_provision_tokens (colunas novas).
# V305 é a base do módulo; V324 é a migração desta fase.
JOIN_MIGRATIONS=(V305__network_nodes.sql V324__network_join_requests.sql)
for migration in "${JOIN_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-join-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_join_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor estável.
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_join_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_join_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      ready="sim"
      break
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
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_join_test "$@"; }
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
first_ok=""
for migration in "${JOIN_MIGRATIONS[@]}"; do
  if apply "$migration" >/dev/null 2>&1; then
    first_ok="$first_ok $migration"
  else
    fail "1ª aplicação falhou: $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
[[ "$(wc -w <<<"$first_ok")" == "${#JOIN_MIGRATIONS[@]}" ]] && pass "migrações de inscrição aplicam em banco limpo"

# ---------------------------------------------------------------- dados entre as rodadas
# Um token de provisionamento antigo (caminho administrativo) tem de sobreviver à 2ª rodada com as
# colunas novas nulas: é exatamente o caso de produção — linhas anteriores a esta migração.
# O mariadb:11.4 reinicia o mysqld uma vez ao final do init: a janela entre "servidor respondeu" e
# "socket pronto" derrubava a gravação de forma intermitente (ERROR 2026 Broken pipe / 2002). O
# storage-migration-test já tinha o retry; aqui faltava -- e o harness acusava o CÓDIGO por causa
# da infra. Repetir a gravação é seguro: as chaves são fixas e o insert é único.
seeded=0
for _ in 1 2 3 4 5; do
  db >/dev/null 2>&1 <<'SQL' && { seeded=0; break; }

insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at)
values ('nd_mig_join001', 'no-mig', 'user-mig', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100, 'sa-east-1', 'BR',
  now(3));
insert into network_provision_tokens (token_hash, display_name, owner_user_id, profiles, expires_at, created_by,
  created_at)
values (repeat('b', 64), 'no-administrativo', 'user-mig', 'NODE_WORKER', now(3) + interval 1 day, 'adm-mig',
  now(3));
insert into network_join_requests (id, full_name, email, rg, cpf, motivation, term_version, accepted_at, status,
  profiles, created_at, updated_at)
values ('njr_mig001', 'Maria Migracao', 'maria@mig.test', 'MG-12.345.678', '52998224725',
  'Quero participar da rede com uma maquina dedicada', '1', now(3), 'PENDING', 'NODE_WORKER', now(3), now(3));
SQL
  seeded=1
  sleep 2
done

if [[ "$seeded" -eq 0 ]]; then
  pass "dados de teste gravados entre as rodadas (token antigo + inscrição)"
else
  fail "não foi possível gravar os dados de teste"
  db <<'SQL' 2>&1 | head -4 >&2
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at)
values ('nd_mig_join001', 'no-mig', 'user-mig', 'ACTIVE', 'NODE_WORKER', 4, 8192, 102400, 100, 'sa-east-1', 'BR',
  now(3));
SQL
fi

# ---------------------------------------------------------------- 2ª aplicação
second_ok=0
for migration in "${JOIN_MIGRATIONS[@]}"; do
  apply "$migration" >/dev/null 2>&1 && second_ok=$((second_ok + 1)) \
    || fail "2ª aplicação falhou: $migration"
done
[[ "$second_ok" == "${#JOIN_MIGRATIONS[@]}" ]] && pass "migrações reaplicam sem erro (idempotentes)"

# ---------------------------------------------------------------- dados preservados
tokens=$(db -N -e "select count(*) from network_provision_tokens where token_hash = repeat('b', 64)" 2>/dev/null | tr -d '[:space:]')
[[ "$tokens" == "1" ]] && pass "token de provisionamento anterior sobrevive à 2ª rodada" \
  || fail "token anterior sumiu (achei '$tokens')"

# A coluna nova é nula nas linhas antigas — e é isso que o código trata como "caminho administrativo".
legacy_nulls=$(db -N -e "select count(*) from network_provision_tokens
  where owner_name is null and owner_email is null" 2>/dev/null | tr -d '[:space:]')
[[ "$legacy_nulls" -ge 1 ]] && pass "colunas novas ficam nulas nos tokens anteriores" \
  || fail "colunas owner_name/owner_email não estão nulas no token anterior"

joint=$(db -N -e "select count(*) from network_join_requests where id = 'njr_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$joint" == "1" ]] && pass "inscrição gravada sobrevive à 2ª rodada" || fail "inscrição sumiu (achei '$joint')"

# ---------------------------------------------------------------- esquema
column_exists() {
  local count
  count=$(db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = '$1' and column_name = '$2'" 2>/dev/null | tr -d '[:space:]')
  [[ "$count" != "0" ]]
}
for column in full_name email rg cpf motivation term_version status reveal_token_hash reveal_expires_at \
  reveal_consumed_at email_sent_at; do
  column_exists network_join_requests "$column" \
    && pass "coluna $column presente" || fail "coluna $column ausente"
done

fk=$(db -N -e "select count(*) from information_schema.referential_constraints
  where constraint_schema = database() and constraint_name = 'fk_network_node_responsibles_node'" 2>/dev/null | tr -d '[:space:]')
[[ "$fk" != "0" ]] && pass "FK do responsável presente" || fail "FK fk_network_node_responsibles_node ausente"

# ---------------------------------------------------------------- comportamento do banco real
# O CPF cabe em char(11) sem truncar, e o hash de 64 em char(64): o teste grava os limites.
if db -e "insert into network_join_requests (id, full_name, email, rg, cpf, motivation, term_version,
  accepted_at, status, profiles, reveal_token_hash, created_at, updated_at)
  values ('njr_mig002', 'Joao', 'joao@mig.test', 'SP-9.876.543', '11144477735', 'motivo', '1', now(3), 'APPROVED',
  'NODE_WORKER', repeat('c', 64), now(3), now(3))" 2>/dev/null; then
  pass "inscrição com CPF de 11 e hash de 64 grava no banco real"
else
  fail "banco recusou inscrição válida (tamanho de cpf/hash)"
fi

if db -e "insert into network_join_requests (id, full_name, email, rg, cpf, motivation, term_version,
  accepted_at, status, profiles, created_at, updated_at)
  values ('njr_mig001', 'Outro', 'outro@mig.test', 'RJ-1', '52998224725', 'motivo', '1', now(3), 'PENDING',
  '', now(3), now(3))" 2>/dev/null; then
  fail "chave primária de inscrição deixou de ser única"
else
  pass "inscrição duplicada recusada pela chave primária"
fi

if db -e "insert into network_node_responsibles (node_id, full_name, email, source, created_at)
  values ('nd_inexistente', 'Fantasma', 'x@mig.test', 'JOIN_REQUEST', now(3))" 2>/dev/null; then
  fail "responsável de nó inexistente foi aceito (FK não vale)"
else
  pass "responsável de nó inexistente recusado pela FK"
fi

# ---------------------------------------------------------------- resumo
printf '\nNode join migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]