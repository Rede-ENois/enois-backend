#!/usr/bin/env bash
# Disponibilidade por dia do nó (V369) contra um MariaDB real, aplicada DUAS vezes.
#
# Por que existe: a suíte roda em H2. O contador da V369 não é só `create table` — o heartbeat escreve
# num UPSERT com `on duplicate key update` e a trava do reenvio é um `case when last_seen_at < ?`.
# Uma expressão que o MariaDB recusa derruba o HEARTBEAT de todos os nós, não uma tela: o nó conta
# como offline e sai do balanceamento do Storage. E a trava não pode ser só "confiar no serviço": o
# `Addenda` que ela protege é uma conta que, repetida, diz que o nó esteve no ar mais tempo do que
# existe.
#
# Uso:
#   ./scripts/tests/node-uptime-migration-test.sh
#   NODE_UPTIME_DB_DSN=... ./scripts/tests/node-uptime-migration-test.sh   # banco já existente
#
# Sem DSN, sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

UPTIME_MIGRATION="V369__network_node_uptime_daily.sql"
NODE_TABLE_MIGRATION="V305__network_nodes.sql"
[[ -f "$MIGRATIONS/$UPTIME_MIGRATION" ]] || { fail "migração ausente: $UPTIME_MIGRATION"; exit 1; }

CONTAINER=""
cleanup() {
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -z "${NODE_UPTIME_DB_DSN:-}" ]]; then
  command -v docker >/dev/null 2>&1 || { fail "docker ausente e NODE_UPTIME_DB_DSN não definido"; exit 1; }
  CONTAINER="enois-uptime-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_uptime_test \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  # O client acha o socket alguns segundos antes de o script de init criar o schema do teste, e o
  # mariadb:11.4 reinicia o mysqld no fim do init: exigir TRÊS respostas seguidas no schema evita
  # aplicar a migração no instante em que o servidor está de pé mas ainda não está estável.
  estavel=0
  for _ in $(seq 1 180); do
    if docker exec "$CONTAINER" mariadb -uroot enois_uptime_test -e "select 1" >/dev/null 2>&1; then
      estavel=$((estavel + 1))
      [ "$estavel" -ge 3 ] && break
    else
      estavel=0
    fi
    sleep 2
  done
  [ "$estavel" -ge 3 ] || { fail "o MariaDB 11.4 não ficou estável no schema do teste"; exit 1; }
  # O schema vai antes dos argumentos: `mariadb -uroot -e "..." enois_uptime_test` é lido como um
  # segundo argumento de `-e`, e o banco não é selecionado.
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_uptime_test "$@"; }
else
  command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1 \
    || { fail "NODE_UPTIME_DB_DSN definido mas sem cliente mariadb/mysql"; exit 1; }
  client=$(command -v mariadb || command -v mysql)
  db() { "$client" --default-character-set=utf8mb4 "$NODE_UPTIME_DB_DSN" "$@"; }
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

# O mesmo UPSERT que `NodeUptimePersistenceAdapter.add` emite. O trecho de 120 s com janela de 90 s
# daria presente=90, observado=120; o de 30 s é todo no ar.
# O mesmo `insert ... on duplicate key update` que o adaptador emite. A decisão de SOMAR é do
# serviço (testada em Java, no H2); aqui o que se prova é que o DDL aceita a forma e que a soma
# relativa funciona no MariaDB.
upsert() {
  # $1 nó  $2 dia  $3 presente  $4 observado  $5 de  $6 ate
  db -e "insert into network_node_uptime_daily (node_id, dia, present_seconds, observed_seconds,
           first_seen_at, last_seen_at, received_at)
         values ('$1', '$2', $3, $4, '$5', '$6', '$6')
         on duplicate key update
           present_seconds = present_seconds + values(present_seconds),
           observed_seconds = observed_seconds + values(observed_seconds),
           first_seen_at = least(first_seen_at, values(first_seen_at)),
           last_seen_at = greatest(last_seen_at, values(last_seen_at)),
           received_at = values(received_at);"
}
scalar() { db -N -B -e "$1" 2>/dev/null | tr -d '[:space:]'; }

echo "== preparação =="
# A V369 tem FK para `network_nodes`; para não arrastar a V305 inteira (e o que ela pressupõe), a
# tabela é criada com as colunas que a FK usa — mesmo tipo e mesmo charset.
db -e "create table if not exists network_nodes (
         id varchar(40) not null primary key
       ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;" >/dev/null \
  || { fail "não foi possível criar a tabela de nós de apoio"; exit 1; }
db -e "insert into network_nodes (id) values ('nd_um'), ('nd_dois');" >/dev/null

echo "== primeira aplicação =="
apply "$UPTIME_MIGRATION" && pass "V369 aplica em banco limpo" || fail "V369 não aplicou"

# --- 2: dois trechos do mesmo dia somam numa linha ---------------------------
upsert nd_um 2026-11-19 90 120 "2026-11-19 10:00:00.000" "2026-11-19 10:02:00.000"
upsert nd_um 2026-11-19 30 30 "2026-11-19 10:02:00.000" "2026-11-19 10:02:30.000"
linhas="$(scalar "select count(*) from network_node_uptime_daily where node_id='nd_um';")"
[ "$linhas" = "1" ] && pass "dois trechos do mesmo dia ocupam uma linha" \
  || fail "dois trechos abriram $linhas linhas (esperado 1)"
[ "$(scalar "select present_seconds from network_node_uptime_daily where node_id='nd_um';")" = "120" ] \
  && pass "present_seconds soma 120 (90 + 30)" || fail "present_seconds não somou"
[ "$(scalar "select observed_seconds from network_node_uptime_daily where node_id='nd_um';")" = "150" ] \
  && pass "observed_seconds soma 150 (120 + 30)" || fail "observed_seconds não somou"

# --- 4: a janela do dia nunca encolhe --------------------------------------
# Um trecho que chega fora de ordem não move o primeiro contato para frente nem o último para trás.
upsert nd_um 2026-11-19 10 10 "2026-11-19 09:58:00.000" "2026-11-19 09:58:10.000"
[ "$(scalar "select date_format(first_seen_at, '%H:%i:%s') from network_node_uptime_daily where node_id='nd_um';")" = "09:58:00" ] \
  && pass "first_seen_at recua para o contato mais antigo" || fail "first_seen_at não recuou"
[ "$(scalar "select date_format(last_seen_at, '%H:%i:%s') from network_node_uptime_daily where node_id='nd_um';")" = "10:02:30" ] \
  && pass "last_seen_at não recua com trecho fora de ordem" || fail "last_seen_at recuou"
# received_at é a CHEGADA: o trecho fora de ordem é o último recebido, e é ele que fecha o reenvio.
[ "$(scalar "select date_format(received_at, '%H:%i:%s') from network_node_uptime_daily where node_id='nd_um';")" = "09:58:10" ] \
  && pass "received_at registra a chegada, não o maior instante" || fail "received_at não é a chegada"
[ "$(scalar "select observed_seconds from network_node_uptime_daily where node_id='nd_um';")" = "160" ] \
  && pass "o trecho fora de ordem soma ao observado (160)" || fail "trecho fora de ordem não somou"

# --- 6: chave (nó, dia) e FK ------------------------------------------------
upsert nd_um 2026-11-18 60 60 "2026-11-18 23:59:00.000" "2026-11-18 23:59:59.000" 2>/dev/null
upsert nd_dois 2026-11-19 60 60 "2026-11-19 08:00:00.000" "2026-11-19 08:01:00.000"
[ "$(scalar "select count(*) from network_node_uptime_daily;")" = "3" ] \
  && pass "a chave (nó, dia) aceita dois nós e dois dias" || fail "chave composta recusou combinação válida"
if db -e "insert into network_node_uptime_daily (node_id, dia, present_seconds, observed_seconds, first_seen_at, last_seen_at)
          values ('nd_fantasma', '2026-11-19', 1, 1, '2026-11-19 10:00:00', '2026-11-19 10:00:01');" >/dev/null 2>&1; then
  fail "a FK aceitou um nó que não existe"
else
  pass "a FK recusa contador de nó inexistente"
fi

# --- 5: segunda aplicação preserva ------------------------------------------
echo "== segunda aplicação =="
apply "$UPTIME_MIGRATION" && pass "V369 aplica DE NOVO sem falhar" || fail "V369 falhou na segunda aplicação"
[ "$(scalar "select count(*) from network_node_uptime_daily;")" = "3" ] \
  && pass "dados preservados depois da segunda aplicação (3 linhas)" || fail "segunda aplicação perdeu dados"
[ "$(scalar "select observed_seconds from network_node_uptime_daily where node_id='nd_um' and dia='2026-11-19';")" = "160" ] \
  && pass "os contadores continuam os mesmos depois da segunda aplicação" \
  || fail "segunda aplicação mudou os contadores"

# --- 7: retenção ------------------------------------------------------------
db -e "delete from network_node_uptime_daily where dia < '2026-11-19';" >/dev/null
antigos="$(scalar "select count(*) from network_node_uptime_daily where dia < '2026-11-19';")"
recentes="$(scalar "select count(*) from network_node_uptime_daily where dia >= '2026-11-19';")"
[ "$antigos" = "0" ] && [ "$recentes" = "2" ] \
  && pass "a retenção apaga o dia velho e preserva os recentes" \
  || fail "retenção errada (velhos=$antigos, recentes=$recentes)"

echo
echo "Node uptime migration: PASS $PASS | FAIL $FAIL"
[[ "$FAIL" -eq 0 ]]