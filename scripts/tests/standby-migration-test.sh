#!/usr/bin/env bash
# Migração da cópia do banco nos nós de reserva (V364) contra um MariaDB real, aplicada DUAS vezes.
#
# Por que existe: os testes de suíte rodam em H2, e o H2 aceita coisas que o MariaDB de produção
# recusa. A V360 não é só `create table`: ela tem chave primária composta, `char(64)` para o digest,
# índice sobre `(state, completed_at)` e o upsert `on duplicate key update` que o início de cada
# cópia usa (preservando `completed_at`, `bytes` e `sha256` da cópia anterior). É este último que a
# produção exercita a cada refresh e que o H2 perdoaria mesmo se o MariaDB recusasse.
#
# O que este harness prova:
#   1. as migrações aplicam num MariaDB 11.4 limpo, na ordem;
#   2. aplicar DE NOVO não falha e preserva as linhas gravadas;
#   3. o upsert do início da cópia NÃO apaga a prova da cópia anterior (completed_at/bytes/sha256);
#   4. a chave primária (node_id, authority_id) recusa duas "vigentes" para o mesmo par;
#   5. `markReady` e `markFailed` (os comandos do adaptador) rodam no banco real;
#   6. o índice (state, completed_at) existe — é o que o refresh consulta.
#
# Uso:
#   ./scripts/tests/standby-migration-test.sh
#   STORAGE_DB_DSN=... ./scripts/tests/standby-migration-test.sh   # banco já existente
#
# O caminho padrão sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

STANDY_MIGRATIONS=(V305__network_nodes.sql V364__network_standby_snapshots.sql)
for migration in "${STANDY_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-standby-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_standby_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O mariadb:11.4 reinicia o mysqld ao final do init; "select 1" respondendo não é servidor estável.
  ready=""
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" mariadb -uroot -e "select 1" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "o MariaDB 11.4 não respondeu a tempo"; exit 1; }
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_standby_test "$@"; }
else
  command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1 \
    || { fail "STORAGE_DB_DSN definido mas sem cliente mariadb/mysql"; exit 1; }
  client=$(command -v mariadb || command -v mysql)
  db() { "$client" --default-character-set=utf8mb4 "$STORAGE_DB_DSN" "$@"; }
fi

apply() {
  local file="$1"
  for _ in 1 2 3 4 5; do
    if db < "$MIGRATIONS/$file"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

# ---------------------------------------------------------------- 1ª aplicação
first_ok=""
for migration in "${STANDY_MIGRATIONS[@]}"; do
  if apply "$migration" >/dev/null 2>&1; then
    first_ok="$first_ok $migration"
  else
    fail "1ª aplicação falhou: $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
[[ "$(wc -w <<<"$first_ok")" == "${#STANDY_MIGRATIONS[@]}" ]] && pass "migrações da reserva aplicam em banco limpo"

# ---------------------------------------------------------------- dados entre as rodadas
# Dados REAIS entre as duas aplicações: é isso que "dados preservados" quer dizer.
db >/dev/null 2>&1 <<'SQL' && pass "dados de teste gravados entre as rodadas" || fail "não foi possível gravar os dados"
insert into network_nodes (id, display_name, owner_user_id, status, profiles, created_at)
values ('nd_standby', 'no-de-reserva', 'user-reserva', 'ACTIVE', 'NODE_STORAGE', now(3));
insert into network_standby_snapshots
  (node_id, authority_id, state, term, started_at, completed_at, bytes, byte_start, sha256, requested_by)
values ('nd_standby', 'original', 'READY', 0, now(3), now(3), 204, 0, repeat('a', 64), 'admin');
SQL

# ---------------------------------------------------------------- 2ª aplicação (idempotência)
second_failures=0
for migration in "${STANDY_MIGRATIONS[@]}"; do
  if ! apply "$migration" >/dev/null 2>&1; then
    second_failures=$((second_failures + 1))
    fail "2ª aplicação falhou (não idempotente): $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
(( second_failures == 0 )) && pass "repetir as migrações não falha (idempotente)"

# ---------------------------------------------------------------- dados sobreviveram
count=$(db -N -e 'select count(*) from network_standby_snapshots' 2>/dev/null | tr -d '[:space:]')
[[ "$count" == "1" ]] && pass "a cópia gravada continua após a 2ª aplicação" \
  || fail "esperava 1 cópia após a 2ª aplicação, achei '$count'"

digest=$(db -N -e "select sha256 from network_standby_snapshots where node_id = 'nd_standby'" 2>/dev/null | tr -d '[:space:]')
[[ "$digest" == "$(printf 'a%.0s' $(seq 1 64))" ]] && pass "o digest sobreviveu à 2ª aplicação" \
  || fail "o digest esperado (64 × 'a') não ficou na linha ('$digest')"

# ---------------------------------------------------------------- upsert do início da cópia
# A instrução é a do adaptador (START_SYNC): preserva a prova da cópia anterior.
db >/dev/null 2>&1 <<'SQL' && pass "início de cópia nova (upsert) não falha no banco real" \
  || fail "upsert do início da cópia recusado pelo MariaDB"
insert into network_standby_snapshots
  (node_id, authority_id, state, term, started_at, byte_start, failure_reason, requested_by)
values ('nd_standby', 'original', 'SYNCING', 1, now(3), 0, null, 'scheduler')
on duplicate key update
  state = 'SYNCING', term = values(term), started_at = values(started_at),
  byte_start = values(byte_start), failure_reason = null, requested_by = values(requested_by);
SQL

preserved=$(db -N -e "select concat(state, '|', sha256, '|', bytes) from network_standby_snapshots where node_id = 'nd_standby'" 2>/dev/null | tr -d '[:space:]')
[[ "$preserved" == "SYNCING|$(printf 'a%.0s' $(seq 1 64))|204" ]] \
  && pass "o upsert preserva digest e bytes da cópia anterior" \
  || fail "o upsert apagou a prova da cópia anterior ('$preserved')"

# ---------------------------------------------------------------- markReady / markFailed
db >/dev/null 2>&1 -e "update network_standby_snapshots set state = 'READY', completed_at = now(3), bytes = 300, sha256 = repeat('b', 64), term = 1 where node_id = 'nd_standby' and authority_id = 'original'" \
  && [[ "$(db -N -e "select state from network_standby_snapshots where node_id = 'nd_standby'" | tr -d '[:space:]')" == "READY" ]] \
  && pass "markReady grava o estado final" || fail "markReady recusado ou não gravou"

db >/dev/null 2>&1 -e "update network_standby_snapshots set state = 'FAILED', failure_reason = 'conexao caiu' where node_id = 'nd_standby' and authority_id = 'original'" \
  && [[ "$(db -N -e "select state from network_standby_snapshots where node_id = 'nd_standby'" | tr -d '[:space:]')" == "FAILED" ]] \
  && pass "markFailed grava o estado" || fail "markFailed recusado ou não gravou"

reason=$(db -N -e "select failure_reason from network_standby_snapshots where node_id = 'nd_standby'" 2>/dev/null)
[[ "${reason//[[:space:]]/}" == "conexaocaiu" ]] && pass "markFailed grava o motivo sem perder acentos/espacos" \
  || fail "o motivo gravado ficou '$reason'"

# ---------------------------------------------------------------- chave primária do par
if db >/dev/null 2>&1 -e "insert into network_standby_snapshots (node_id, authority_id, state, term, requested_by) values ('nd_standby', 'original', 'SYNCING', 2, 'duplicada')"; then
  fail "o banco aceitou duas linhas para o mesmo (nó, autoridade) — a chave primária não existe"
else
  pass "chave primária (node_id, authority_id) recusa duplicata"
fi

# ---------------------------------------------------------------- índices que o código usa
idx=$(db -N -e "select count(*) from information_schema.statistics
  where table_schema = database() and table_name = 'network_standby_snapshots'
  and index_name = 'idx_network_standby_snapshots_ready'" 2>/dev/null | tr -d '[:space:]')
[[ "$idx" == "2" ]] && pass "índice (state, completed_at) presente nas 2 colunas" \
  || fail "índice do refresh ausente ou incompleto (colunas: '$idx')"

printf '\nStandby migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]