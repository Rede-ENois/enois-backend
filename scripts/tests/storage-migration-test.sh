#!/usr/bin/env bash
# Migrações do Énóis Storage contra um MariaDB real, aplicadas DUAS vezes.
#
# Por que existe: os testes de suíte rodam em H2 (ou no banco em memória), e H2 aceita coisas que o
# MariaDB de produção recusa — e vice-versa. O brief do Storage (§7 e §13) exige que a migração seja
# aditiva e idempotente, e "idempotente" só se prova aplicando de novo, com o banco já populado.
# Escrever o SQL à mão e chamá-lo de aplicável é o mesmo erro de confiar no declarado em vez do
# medido.
#
# O que este harness prova:
#   1. as migrações do Storage aplicam num MariaDB 11.4 limpo;
#   2. aplicá-las DE NOVO não falha e não altera dados já gravados;
#   3. o esquema resultante tem as colunas, a chave única e os índices que o código espera.
#
# Uso:
#   ./scripts/tests/storage-migration-test.sh            # sobe um container efêmero e testa
#   STORAGE_DB_DSN=... ./scripts/tests/storage-migration-test.sh   # usa um banco já existente
#
# Requer docker (caminho padrão) ou um cliente mysql/mariadb. Nunca toca banco de produção: o
# caminho padrão é um container com nome aleatório, destruído ao final.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

# As duas migrações que este harness cobre, na ordem. Uma migração nova de storage entra aqui: o
# harness acompanha o módulo, não uma lista fixa de arquivos que envelhece em silêncio.
STORAGE_MIGRATIONS=(V319__network_storage_objects.sql V320__network_storage_placements.sql)
for migration in "${STORAGE_MIGRATIONS[@]}"; do
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
  CONTAINER="enois-storage-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_mig_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # Espera o servidor aceitar conexão: o entrypoint fica reiniciando o mysqld até o init acabar.
  # Espera o SERVIDOR E o banco responderem. O entrypoint do mariadb:11.4 aceita conexão antes de
  # terminar o init e reinicia o mysqld no meio dele — "select 1" passando não significa servidor
  # estável. A condição é o banco de teste existir e responder a uma consulta real.
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot -e 'create database if not exists enois_mig_test' >/dev/null 2>&1 \
       && docker exec "$CONTAINER" mariadb -uroot enois_mig_test -e 'select count(*) from information_schema.tables' >/dev/null 2>&1; then
      ready="sim"
      break
    fi
    sleep 2
  done
  if [[ -z "$ready" ]]; then
    # Diagnóstico do que aconteceu, em vez de só "não ficou pronto".
    printf -- '--- logs do container ---\n' >&2
    docker logs --tail 5 "$CONTAINER" >&2 2>&1 || true
    fail "MariaDB não ficou pronto em 180s"
    exit 1
  fi
  pass "MariaDB 11.4 real no ar"
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_mig_test "$@"; }
else
  client=$(command -v mariadb || command -v mysql || echo "")
  [[ -n "$client" ]] || { fail "nem docker nem cliente mysql disponíveis"; exit 1; }
  db() { "$client" "$STORAGE_DB_DSN" "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi

# Aplica um arquivo de migração. O `if not exists` é o que torna a segunda execução inofensiva; se
# algum dia alguém trocar por `create table`, a segunda rodada falha aqui e não em produção.
apply() {
  local file="$1" attempt
  # Retry curto: o mariadb:11.4 reinicia o mysqld uma vez ao final do init, e a janela entre
  # "informação do servidor disponível" e "socket pronto" derrubava a 1ª migração de forma
  # intermitente. Repetir o arquivo é seguro por construção — são migrações idempotentes —, mas a
  # espera existe para não depender disso.
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
for migration in "${STORAGE_MIGRATIONS[@]}"; do
  if apply "$migration" >/dev/null 2>&1; then
    first_ok="$first_ok $migration"
  else
    fail "1ª aplicação falhou: $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
[[ "$(wc -w <<<"$first_ok")" == "${#STORAGE_MIGRATIONS[@]}" ]] && pass "migrações do Storage aplicam em banco limpo"

# ---------------------------------------------------------------- dados entre as rodadas
# Dados REAIS entre as duas aplicações: é isso que "dados preservados" quer dizer. Sem eles, a
# segunda rodada só prova que o DDL é tolerável a tabela vazia.
for _ in 1 2 3 4 5; do
  db >/dev/null 2>&1 <<'SQL' && break

insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
values ('sob_mig001', 'fotos', 'gato.jpg', 'image/jpeg', 1234, repeat('a', 64), 'STORED', 'user-mig', now(3), now(3)),
       ('sob_mig002', 'fotos', 'pendente.jpg', 'image/jpeg', null, null, 'PENDING', 'user-mig', now(3), now(3));
insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
values ('sob_mig001', 0, 'nd_mig', 1234, repeat('a', 64), 'OK', now(3));
SQL
  sleep 2
done
if [[ $? -eq 0 ]]; then
  pass "dados de teste gravados entre as rodadas"
else
  fail "não foi possível gravar os dados de teste"
fi

# ---------------------------------------------------------------- 2ª aplicação (idempotência)
second_failures=0
for migration in "${STORAGE_MIGRATIONS[@]}"; do
  if ! apply "$migration" >/dev/null 2>&1; then
    second_failures=$((second_failures + 1))
    fail "2ª aplicação falhou (não idempotente): $migration"
    db < "$MIGRATIONS/$migration" 2>&1 | head -3 >&2 || true
  fi
done
(( second_failures == 0 )) && pass "repetir as migrações não falha (idempotente)"

# ---------------------------------------------------------------- dados sobreviveram
lines=$(db -N -e 'select count(*) from network_storage_objects' 2>/dev/null | tr -d '[:space:]')
[[ "$lines" == "2" ]] && pass "os 2 objetos continuam após a 2ª aplicação" \
  || fail "esperava 2 objetos após a 2ª aplicação, achei '$lines'"

stored=$(db -N -e "select bytes from network_storage_objects where id = 'sob_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$stored" == "1234" ]] && pass "o tamanho medido sobreviveu à 2ª aplicação" \
  || fail "esperava bytes=1234, achei '$stored'"

placement=$(db -N -e "select node_id from network_storage_placements where object_id = 'sob_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$placement" == "nd_mig" ]] && pass "a colocação sobreviveu à 2ª aplicação" \
  || fail "esperava nd_mig, achei '$placement'"

# ---------------------------------------------------------------- esquema esperado
has_column() {
  db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = '$1' and column_name = '$2'" 2>/dev/null | tr -d '[:space:]'
}
for column in id bucket object_key bytes sha256 state created_by deleted_at; do
  [[ "$(has_column network_storage_objects "$column")" == "1" ]] \
    && pass "network_storage_objects.$column existe" \
    || fail "coluna ausente: network_storage_objects.$column"
done
for column in object_id replica node_id sha256 state verified_at; do
  [[ "$(has_column network_storage_placements "$column")" == "1" ]] \
    && pass "network_storage_placements.$column existe" \
    || fail "coluna ausente: network_storage_placements.$column"
done

# A chave única de (bucket, chave) é o que impede dois objetos com o mesmo nome: sem ela, o
# upload repetido criaria duplicata em vez de retomar.
dup=$(db -N -e "select count(*) from information_schema.statistics
  where table_schema = database() and table_name = 'network_storage_objects'
  and index_name = 'uq_network_storage_objects_key' and non_unique = 0" 2>/dev/null | tr -d '[:space:]')
[[ "$dup" == "2" ]] && pass "chave única (bucket, object_key) presente nas 2 colunas" \
  || fail "índice único de (bucket, object_key) ausente ou incompleto (colunas: '$dup')"

# A chave primária (object_id, replica) é o que a fase 3 usa para replicar sem alterar a tabela.
pk=$(db -N -e "select count(*) from information_schema.statistics
  where table_schema = database() and table_name = 'network_storage_placements'
  and index_name = 'PRIMARY'" 2>/dev/null | tr -d '[:space:]')
[[ "$pk" == "2" ]] && pass "chave primária (object_id, replica) já aceita a fase 3" \
  || fail "chave primária de placements deveria ter 2 colunas, achei '$pk'"

# A chave estrangeira: colocação sem objeto seria cópia órfã apontando para o nada.
fk=$(db -N -e "select count(*) from information_schema.referential_constraints
  where constraint_schema = database() and table_name = 'network_storage_placements'" 2>/dev/null | tr -d '[:space:]')
[[ "$fk" == "1" ]] && pass "colocação tem FK para o objeto (sem cópia órfã)" \
  || fail "FK de placements ausente (achei '$fk')"

# Os índices de leitura: listagem por bucket e varredura por nó.
index_exists() {
  local count
  count=$(db -N -e "select count(*) from information_schema.statistics
    where table_schema = database() and table_name = '$1' and index_name = '$2'" 2>/dev/null | tr -d '[:space:]')
  [[ "$count" != "0" ]]
}
index_exists network_storage_objects idx_network_storage_objects_bucket \
  && pass "índice de listagem por bucket presente" || fail "índice idx_network_storage_objects_bucket ausente"
index_exists network_storage_placements idx_network_storage_placements_node \
  && pass "índice por nó presente" || fail "índice idx_network_storage_placements_node ausente"

printf '\nStorage migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
(( FAIL == 0 ))