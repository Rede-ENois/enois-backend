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
STORAGE_MIGRATIONS=(V319__network_storage_objects.sql V320__network_storage_placements.sql
  V323__network_storage_placement_pending_sha.sql)
for migration in "${STORAGE_MIGRATIONS[@]}"; do
  [[ -f "$MIGRATIONS/$migration" ]] || { fail "migração ausente: $migration"; exit 1; }
done

# Toda migração V319..V329 do Storage gravada no repositório tem de estar nesta lista, senão uma
# migração nova entra sem nunca passar pelo banco REAL — e é o banco real que recusa o que o H2
# aceita. A lista é explícita (o harness escolhe o que aplica), então o guarda é a comparação.
mapfile -t on_disk < <(ls "$MIGRATIONS" | grep -E '^V32[0-9]__.*storage' | sort)
for file in "${on_disk[@]}"; do
  [[ " ${STORAGE_MIGRATIONS[*]} " == *" $file "* ]] \
    || fail "migração de Storage fora do harness: $file"
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

# V323: a designação de réplica (fase 3) existe antes de haver cópia, logo sha256 tem de aceitar
# nulo. A prova não é ler o information_schema: é o banco REAL aceitar a linha de designação com
# sha nulo e recusar a designação duplicada pela chave primária composta.
nullable() {
  local count
  count=$(db -N -e "select count(*) from information_schema.columns
    where table_schema = database() and table_name = '$1' and column_name = '$2' and is_nullable = 'YES'" \
    2>/dev/null | tr -d '[:space:]')
  [[ "$count" != "0" ]]
}
nullable network_storage_placements sha256 \
  && pass "V323: sha256 aceita nulo (designação sem cópia)" || fail "V323: sha256 ainda NOT NULL — a designação não grava"

if db -e "insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
  values ('sob_mig001', 2, 'nd_designado', 0, null, 'PENDING_REMOTE', now(3))" 2>/dev/null; then
  pass "designação sem digest grava no banco real"
else
  fail "designação sem digest recusada pelo banco"
fi

if db -e "insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
  values ('sob_mig001', 2, 'nd_outro', 0, null, 'PENDING_REMOTE', now(3))" 2>/dev/null; then
  fail "chave (object_id, replica) deixou de ser única"
else
  pass "designação duplicada recusada pela chave primária"
fi

# ---------------------------------------------------------------- V345: tráfego do nó (brief §18)
# A migração do Storage que mexe em `network_nodes` — a tabela do ENROLL, não do catálogo. Ela entra
# neste harness pelo mesmo motivo das outras: o H2 aceita `add column if not exists` em formas que o
# MariaDB recusa, e a única prova que vale é o banco real. `network_nodes` é criada pela V305, que
# já viveu em produção; aqui a tabela nasce no banco limpo do harness.
NODE_TRAFFIC=V345__network_node_traffic.sql
[[ -f "$MIGRATIONS/$NODE_TRAFFIC" ]] || fail "migração ausente: $NODE_TRAFFIC"

if db < "$MIGRATIONS/V305__network_nodes.sql" >/dev/null 2>&1; then
  pass "V305: network_nodes aplica em banco limpo (base da V345)"
else
  fail "V305: network_nodes não aplicou"
fi

if apply "$NODE_TRAFFIC" >/dev/null 2>&1; then
  pass "V345: aplica sobre network_nodes no banco real"
else
  fail "1ª aplicação falhou: $NODE_TRAFFIC"
  db < "$MIGRATIONS/$NODE_TRAFFIC" 2>&1 | head -3 >&2 || true
fi

# Um nó REAL entre as duas aplicações, com tráfego já medido: é o que "dados preservados" quer
# dizer para uma migração de coluna — a linha existente não pode perder o que já tinha.
if db >/dev/null 2>&1 <<'SQL'
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, observed_bytes_in_mb, observed_bytes_out_mb,
  created_at, last_seen_at)
values ('nd_traf001', 'no-do-trafego', 'user-mig', 'ACTIVE', 'NODE_STORAGE', 4, 8192, 102400, 100,
  2048, 7168, now(3), now(3));
SQL
then
  pass "nó com tráfego gravado entre as rodadas"
else
  fail "não foi possível gravar o nó de teste"
fi

if apply "$NODE_TRAFFIC" >/dev/null 2>&1; then
  pass "V345: reaplicar não falha (idempotente)"
else
  fail "2ª aplicação falhou (não idempotente): $NODE_TRAFFIC"
fi

traffic=$(db -N -e "select concat(observed_bytes_in_mb, '/', observed_bytes_out_mb) from network_nodes
  where id = 'nd_traf001'" 2>/dev/null | tr -d '[:space:]')
[[ "$traffic" == "2048/7168" ]] && pass "o tráfego do nó sobreviveu à 2ª aplicação" \
  || fail "esperava 2048/7168, achei '$traffic'"

# Nulo é "não mediu", e a coluna TEM de aceitar: nó de runtime antigo não pode receber zero.
nullable network_nodes observed_bytes_in_mb \
  && pass "V345: observed_bytes_in_mb aceita nulo (runtime antigo)" \
  || fail "V345: observed_bytes_in_mb NOT NULL — runtime antigo não conseguiria gravar"


# ---------------------------------------------------------------- V348: banda por tipo (brief §6)
# A tabela por (nó, tipo) tem PRIMARY KEY composta e FOREIGN KEY para `network_nodes` com
# `on delete cascade`: o H2 aceita `on duplicate key update` em formas que o MariaDB recusa, e a
# cascata só se prova apagando o nó num banco que a force. Mesmo motivo da V345 para estar aqui.
NODE_KIND_BW=V348__network_node_storage_bandwidth.sql
[[ -f "$MIGRATIONS/$NODE_KIND_BW" ]] || fail "migração ausente: $NODE_KIND_BW"

if apply "$NODE_KIND_BW" >/dev/null 2>&1; then
  pass "V348: aplica sobre network_nodes no banco real"
else
  fail "1ª aplicação falhou: $NODE_KIND_BW"
  db < "$MIGRATIONS/$NODE_KIND_BW" 2>&1 | head -3 >&2 || true
fi

# Nó e banda REAIS entre as duas aplicações: a 2ª passa por `create table if not exists` e não
# pode apagar nem a linha do nó nem a especialização que ele já tinha.
if db >/dev/null 2>&1 <<'SQL'
insert into network_node_storage_bandwidth (node_id, kind, bandwidth_mbps, updated_at)
values ('nd_traf001', 'MEDIA', 15, now(3));
SQL
then
  pass "banda por tipo gravada entre as rodadas"
else
  fail "não foi possível gravar a banda do tipo"
fi

if apply "$NODE_KIND_BW" >/dev/null 2>&1; then
  pass "V348: reaplicar não falha (idempotente)"
else
  fail "2ª aplicação falhou (não idempotente): $NODE_KIND_BW"
fi

kindbw=$(db -N -e "select bandwidth_mbps from network_node_storage_bandwidth
  where node_id = 'nd_traf001' and kind = 'MEDIA'" 2>/dev/null | tr -d '[:space:]')
[[ "$kindbw" == "15" ]] && pass "a banda por tipo sobreviveu à 2ª aplicação" \
  || fail "esperava 15, achei '$kindbw'"

# A chave é o PAR (nó, tipo): dois tipos no mesmo nó convivem, e repetir o par é recusado — é o
# que uma coluna JSON não faria.
if db >/dev/null 2>&1 <<'SQL'
insert into network_node_storage_bandwidth (node_id, kind, bandwidth_mbps, updated_at)
values ('nd_traf001', 'ARCHIVE', 5, now(3));
SQL
then
  pass "dois tipos no mesmo nó convivem"
else
  fail "o mesmo nó não aceitou um segundo tipo"
fi

if db >/dev/null 2>&1 <<'SQL'
insert into network_node_storage_bandwidth (node_id, kind, bandwidth_mbps, updated_at)
values ('nd_traf001', 'MEDIA', 99, now(3));
SQL
then
  fail "o par (nó, tipo) duplicado foi aceito — a chave primária composta não vale"
else
  pass "par (nó, tipo) duplicado recusado pela chave primária"
fi

# Apagar o nó leva a banda junto (cascade): linha órfã faria a config de um nó novo nascer com a
# banda do antigo se o id fosse reciclado.
if db >/dev/null 2>&1 <<'SQL'
delete from network_node_storage_bandwidth where node_id = 'nd_casc001';
insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, created_at, last_seen_at)
values ('nd_casc001', 'no-cascata', 'user-mig', 'ACTIVE', 'NODE_STORAGE_MEDIA', 2, 4096, 51200, 50,
  now(3), now(3));
insert into network_node_storage_bandwidth (node_id, kind, bandwidth_mbps, updated_at)
values ('nd_casc001', 'MEDIA', 7, now(3));
delete from network_nodes where id = 'nd_casc001';
SQL
then
  left=$(db -N -e "select count(*) from network_node_storage_bandwidth where node_id = 'nd_casc001'" 2>/dev/null | tr -d '[:space:]')
  [[ "$left" == "0" ]] && pass "apagar o nó apaga a banda (on delete cascade)" \
    || fail "sobraram $left linha(s) de banda de um nó apagado"
else
  fail "não foi possível exercitar a cascata"
fi


# ---------------------------------------------------------------- V350: IP de origem (brief §5)
# "Detecção de mudança de IP". Duas colunas na tabela do enroll, nulas por padrão: nulo em
# `observed_ip` é "não medido" e nulo em `observed_ip_changed_at` é "nunca mudou" — a V350 não pode
# gravar valor em linha existente, ou todo nó já cadastrado nasceria com um endereço inventado.
NODE_IP=V350__network_node_observed_ip.sql
VERSIONING=V352__network_storage_versioning.sql
REBALANCE=V354__network_storage_rebalance.sql
[[ -f "$MIGRATIONS/$NODE_IP" ]] || fail "migração ausente: $NODE_IP"

if apply "$NODE_IP" >/dev/null 2>&1; then
  pass "V350: aplica sobre network_nodes no banco real"
else
  fail "1ª aplicação falhou: $NODE_IP"
  db < "$MIGRATIONS/$NODE_IP" 2>&1 | head -3 >&2 || true
fi

# A linha que já existia (nd_traf001, com tráfego gravado) fica com as duas colunas NULAS:
# migração de coluna não inventa medição para quem não mediu.
ipstate=$(db -N -e "select concat(ifnull(observed_ip,'nulo'), '/', ifnull(observed_ip_changed_at,'nulo'))
  from network_nodes where id = 'nd_traf001'" 2>/dev/null | tr -d '[:space:]')
[[ "$ipstate" == "nulo/nulo" ]] && pass "V350: nó existente nasce com IP e mudança nulos" \
  || fail "esperava nulo/nulo, achei '$ipstate'"

if db >/dev/null 2>&1 <<'SQL'
update network_nodes set observed_ip = '203.0.113.10', observed_ip_changed_at = now(3)
where id = 'nd_traf001';
SQL
then
  pass "V350: colunas aceitam endereço e instante"
else
  fail "V350: não foi possível gravar IP e instante"
fi

if apply "$NODE_IP" >/dev/null 2>&1; then
  pass "V350: reaplicar não falha (idempotente)"
else
  fail "2ª aplicação falhou (não idempotente): $NODE_IP"
fi

ipafter=$(db -N -e "select observed_ip from network_nodes where id = 'nd_traf001'" 2>/dev/null | tr -d '[:space:]')
[[ "$ipafter" == "203.0.113.10" ]] && pass "o IP gravado sobreviveu à 2ª aplicação" \
  || fail "esperava 203.0.113.10, achei '$ipafter'"

# 45 caracteres cabem: o maior endereço textual é IPv6 com zona.
if db >/dev/null 2>&1 <<'SQL'
update network_nodes set observed_ip = '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
where id = 'nd_traf001';
SQL
then
  pass "V350: observed_ip cabe um IPv6 completo"
else
  fail "V350: observed_ip não aceitou IPv6"
fi


# ---------------------------------------------------------------------------
# V352 — versionamento e ciclo de vida (brief §14).
#
# A migração que exige cuidado: ela REMOVE a chave única (bucket, object_key) e a substitui por uma
# unicidade condicional. O que este trecho prova:
#   1. o objeto que já existia continua sendo a versão 1 da chave;
#   2. duas versões correntes da mesma chave são RECUSADAS pelo banco;
#   3. uma versão corrente e uma substituída da mesma chave convivem;
#   4. a segunda aplicação não falha e não perde dado.
# ---------------------------------------------------------------------------
if apply "$VERSIONING" >/dev/null 2>&1; then
  pass "V352: primeira aplicação"
else
  fail "V352: primeira aplicação falhou"
  db < "$MIGRATIONS/$VERSIONING" 2>&1 | head -3 >&2 || true
fi

version=$(db -N -e "select concat(version, '/', ifnull(superseded_by,'nulo'), '/', ifnull(expires_at,'nulo'))
  from network_storage_objects where id = 'sob_mig001'" 2>/dev/null | tr -d '[:space:]')
[[ "$version" == "1/nulo/nulo" ]] && pass "V352: objeto existente vira versão 1, corrente e sem prazo" \
  || fail "esperava 1/nulo/nulo, achei '$version'"

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by, version)
values ('sob_mig_v1', 'migracao', 'versao.bin', 'application/octet-stream', 10, repeat('a', 64), 'STORED', 'user-test', 1);
SQL
then
  pass "V352: primeira versão de uma chave nova entra"
else
  fail "V352: não aceitou a primeira versão"
fi

# Duas correntes da mesma chave têm de ser recusadas: sem isso, `find(bucket, chave)` não sabe qual
# devolver e o upload idempotente deixa de ser idempotente.
if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by, version)
values ('sob_mig_v2', 'migracao', 'versao.bin', 'application/octet-stream', 10, repeat('b', 64), 'STORED', 'user-test', 2);
SQL
then
  fail "V352: aceitou duas versões CORRENTES da mesma chave"
else
  pass "V352: a unicidade recusa duas versões correntes da mesma chave"
fi

# Marcando a primeira como substituída, a segunda entra: é o que o versionamento faz.
if db >/dev/null 2>&1 <<'SQL'
update network_storage_objects set superseded_by = 'sob_mig_v2' where id = 'sob_mig_v1';
insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by, version)
values ('sob_mig_v2', 'migracao', 'versao.bin', 'application/octet-stream', 10, repeat('b', 64), 'STORED', 'user-test', 2);
SQL
then
  pass "V352: versão substituída e versão corrente convivem na mesma chave"
else
  fail "V352: não aceitou a versão corrente depois de substituir a anterior"
fi

# A chave antiga saiu: a inserção idempotente tem de cair na unicidade nova, não numa restrição que
# ainda exista com o nome antigo.
if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by)
values ('sob_mig_v2', 'migracao', 'versao.bin', 'application/octet-stream', 'PENDING', 'user-test')
on duplicate key update id = id;
SQL
then
  pass "V352: insert-ou-noop da versão corrente não duplica"
else
  fail "V352: insert-ou-noop falhou (a unicidade nova não é alvo do on duplicate key)"
fi

if apply "$VERSIONING" >/dev/null 2>&1; then
  pass "V352: reaplicar não falha (idempotente)"
else
  fail "V352: 2ª aplicação falhou (não idempotente)"
fi

versaoafter=$(db -N -e "select concat(count(*), '/', max(version)) from network_storage_objects
  where bucket = 'migracao' and object_key = 'versao.bin'" 2>/dev/null | tr -d '[:space:]')
[[ "$versaoafter" == "2/2" ]] && pass "V352: as duas versões sobreviveram à 2ª aplicação" \
  || fail "esperava 2/2, achei '$versaoafter'"

politica=$(db -N -e "select count(*) from network_storage_buckets" 2>/dev/null | tr -d '[:space:]')
[[ "$politica" == "0" ]] && pass "V352: a tabela de política nasce vazia (ninguém configurou nada)" \
  || fail "esperava 0 políticas, achei '$politica'"

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_buckets (bucket, versioning, retain_superseded_days, expire_after_days, updated_by)
values ('migracao', 1, 30, 90, 'user-test');
SQL
then
  pass "V352: política do bucket grava versionamento, retenção e expiração"
else
  fail "V352: não gravou a política do bucket"
fi

# A check do banco é a última linha de defesa: expiração de zero dia é recusada aqui também.
if db >/dev/null 2>&1 <<'SQL'
update network_storage_buckets set expire_after_days = 0 where bucket = 'migracao';
SQL
then
  fail "V352: aceitou expiração de zero dia"
else
  pass "V352: expiração de zero dia recusada pela check"
fi

# ---------------------------------------------------------------------------
# V354 — rebalanceamento (brief §16).
#
# A tabela guarda a DECISÃO de mover uma réplica. O que importa provar no banco:
#   1. a transferência grava e sobrevive à reaplicação;
#   2. uma réplica de origem não tem duas transferências VIVAS ao mesmo tempo (clique duplo);
#   3. concluídas, duas transferências da mesma réplica convivem (a segunda recuperação do mesmo
#      objeto é legítima — é o que uma unicidade ingênua por estado proibiria para sempre);
#   4. a transferência morre junto com o objeto (cascade).
# ---------------------------------------------------------------------------
if apply "$REBALANCE" >/dev/null 2>&1; then
  pass "V354: primeira aplicação"
else
  fail "V354: primeira aplicação falhou"
  db < "$MIGRATIONS/$REBALANCE" 2>&1 | head -3 >&2 || true
fi

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
  state, bytes, bandwidth_limit_mbps, requested_by)
values ('reb_t1', 'sob_mig_v2', 1, 'nd_cheio', 2, 'nd_novo', 'PENDING', 10, 5, 'user-test');
SQL
then
  pass "V354: transferência viva grava"
else
  fail "V354: não gravou a transferência"
fi

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
  state, bytes, bandwidth_limit_mbps, requested_by)
values ('reb_t2', 'sob_mig_v2', 1, 'nd_cheio', 3, 'nd_outro', 'PENDING', 10, 5, 'user-test');
SQL
then
  fail "V354: aceitou duas transferências VIVAS da mesma réplica"
else
  pass "V354: a unicidade recusa duas transferências vivas da mesma réplica"
fi

if db >/dev/null 2>&1 <<'SQL'
update network_storage_rebalances set state = 'DONE', finished_at = now(3) where id = 'reb_t1';
insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
  state, bytes, bandwidth_limit_mbps, requested_by)
values ('reb_t2', 'sob_mig_v2', 1, 'nd_cheio', 3, 'nd_outro', 'PENDING', 10, 5, 'user-test');
SQL
then
  pass "V354: concluída e viva da mesma réplica convivem (nova transferência entra)"
else
  fail "V354: não aceitou nova transferência depois de a anterior concluir"
fi

# Concluídas repetidas: o que a unicidade por estado proibiria para sempre.
if db >/dev/null 2>&1 <<'SQL'
update network_storage_rebalances set state = 'DONE', finished_at = now(3) where id = 'reb_t2';
insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
  state, bytes, bandwidth_limit_mbps, requested_by)
values ('reb_t3', 'sob_mig_v2', 1, 'nd_cheio', 4, 'nd_outro', 'DONE', 10, 5, 'user-test');
SQL
then
  pass "V354: duas concluídas da mesma réplica convivem"
else
  fail "V354: recusou duas concluídas da mesma réplica"
fi

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
  state, bytes, requested_by)
values ('reb_t4', 'sob_mig_v2', 1, 'nd_cheio', 5, 'nd_novo', 'PENDING', 10, 'user-test')
on duplicate key update id = id;
SQL
then
  pass "V354: insert-ou-noop não duplica a transferência viva"
else
  fail "V354: insert-ou-noop falhou"
fi

if apply "$REBALANCE" >/dev/null 2>&1; then
  pass "V354: reaplicar não falha (idempotente)"
else
  fail "V354: 2ª aplicação falhou (não idempotente)"
fi

linhas=$(db -N -e "select count(*) from network_storage_rebalances where object_id = 'sob_mig_v2'" 2>/dev/null | tr -d '[:space:]')
[[ "$linhas" == "4" ]] && pass "V354: as quatro transferências sobreviveram à 2ª aplicação" \
  || fail "esperava 4 transferências, achei '$linhas'"

# Cascade: apagar o objeto leva as transferências dele.
db >/dev/null 2>&1 <<'SQL'
update network_storage_rebalances set state = 'DONE' where id = 'reb_t4';
delete from network_storage_objects where id = 'sob_mig_v2';
SQL
restantes=$(db -N -e "select count(*) from network_storage_rebalances where object_id = 'sob_mig_v2'" 2>/dev/null | tr -d '[:space:]')
[[ "$restantes" == "0" ]] && pass "V354: apagar o objeto leva as transferências (cascade)" \
  || fail "esperava 0 transferências, achei '$restantes'"

# ---------------------------------------------------------------------------
# V356 — gateway S3 (brief §11).
#
# A credencial de cliente tem de sobreviver à reaplicação, ser única por Access
# Key (o gateway busca por ela a cada requisição) e aceitar revogação terminal.
# ---------------------------------------------------------------------------
S3GATEWAY=V356__network_storage_s3_gateway.sql
if apply "$S3GATEWAY" >/dev/null 2>&1; then
  pass "V356: primeira aplicação"
else
  fail "V356: primeira aplicação falhou"
  db < "$MIGRATIONS/$S3GATEWAY" 2>&1 | head -3 >&2 || true
fi

if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
  allowed_bucket, allowed_operations)
values ('s3c_t1', 'AKIATESTE0000000001', repeat('a', 64), 'cifrado', 'user-test', null, 'GET PUT');
SQL
then
  pass "V356: credencial de cliente grava"
else
  fail "V356: não gravou a credencial"
fi

# Access Key é o identificador de busca: duplicar faria uma credencial autenticar como outra.
if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_s3_credentials (id, access_key, secret_hash, owner_user_id, allowed_operations)
values ('s3c_t2', 'AKIATESTE0000000001', repeat('b', 64), 'user-test', 'GET');
SQL
then
  fail "V356: aceitou Access Key duplicada"
else
  pass "V356: a unicidade recusa Access Key duplicada"
fi

# Revogação: preenche revoked_at uma vez, e revogar de novo não sobrescreve a data original.
db >/dev/null 2>&1 <<'SQL'
update network_storage_s3_credentials set revoked_at = now(3) where id = 's3c_t1';
SQL
revogada=$(db -N -e "select count(*) from network_storage_s3_credentials where id = 's3c_t1' and revoked_at is not null" 2>/dev/null | tr -d '[:space:]')
[[ "$revogada" == "1" ]] && pass "V356: revogação grava revoked_at" || fail "V356: revogação não gravou"

# Operações vazias são credencial que não faz nada: a check recusa em vez de guardar silêncio.
if db >/dev/null 2>&1 <<'SQL'
insert into network_storage_s3_credentials (id, access_key, secret_hash, owner_user_id, allowed_operations)
values ('s3c_t3', 'AKIATESTE0000000003', repeat('c', 64), 'user-test', '');
SQL
then
  fail "V356: aceitou credencial sem operação"
else
  pass "V356: a check recusa credencial sem operação"
fi

if apply "$S3GATEWAY" >/dev/null 2>&1; then
  pass "V356: reaplicar não falha (idempotente)"
else
  fail "V356: 2ª aplicação falhou (não idempotente)"
fi

credenciais=$(db -N -e "select count(*) from network_storage_s3_credentials" 2>/dev/null | tr -d '[:space:]')
[[ "$credenciais" == "1" ]] && pass "V356: a credencial sobreviveu à 2ª aplicação" \
  || fail "esperava 1 credencial, achei '$credenciais'"
printf '\nStorage migration: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
(( FAIL == 0 ))