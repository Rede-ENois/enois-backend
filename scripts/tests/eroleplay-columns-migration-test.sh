#!/usr/bin/env bash
# Éroleplay: o boot valida a biblioteca contra um banco que JÁ passou pelo V326 (o estado da QA).
#
# Por que existe: em 15/11 a QA parou de subir com
#   Schema validation: missing column [campaignId] in table [roleplay_published_sheets]
# O gate de CI (`Schema x migrations`) cria o banco do zero a partir das migrações e passou; a QA não,
# porque já tinha a versão 326 aplicada de um build anterior. O Flyway não reaplica um número que já
# está na história, então o schema migrado é um estado que nenhum teste da suíte criava.
#
# O que este harness prova, num MariaDB real:
#   1. o banco reproduz o estado da QA: V326 aplicado com a tabela em camelCase (o 326 antigo);
#   2. as migrações novas (327) aplicam nesse banco sem erro;
#   3. depois delas, todo campo composto da entidade tem coluna correspondente — a mesma comparação
#      que o `validate` do boot faz e que falhou;
#   4. aplicar de novo não falha e não perde linha (aditiva e idempotente).
#
# Uso:
#   ./scripts/tests/eroleplay-columns-migration-test.sh
#   STORAGE_DB_DSN=... ./scripts/tests/eroleplay-columns-migration-test.sh
#
# O caminho padrão sobe um container efêmero e o destrói ao final. Nunca toca produção.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/src/main/resources/db/migration"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

V326="$MIGRATIONS/V326__eroleplay_library.sql"
V327="$MIGRATIONS/V327__eroleplay_library_column_names.sql"
for migration in "$V326" "$V327"; do
  [[ -f "$migration" ]] || { fail "migração ausente: $(basename "$migration")"; exit 1; }
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
  CONTAINER="enois-er-col-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_er_col \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  ready=""
  for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" mariadb -uroot enois_er_col \
         -e 'create table if not exists _ready_probe (id int primary key); drop table _ready_probe' >/dev/null 2>&1; then
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
  db() { docker exec -i "$CONTAINER" mariadb -uroot enois_er_col "$@"; }
else
  client=$(command -v mariadb || command -v mysql || echo "")
  [[ -n "$client" ]] || { fail "nem docker nem cliente mysql disponíveis"; exit 1; }
  db() { "$client" "$STORAGE_DB_DSN" "$@"; }
  pass "usando banco informado em STORAGE_DB_DSN"
fi

# ---------------------------------------------------------------- o estado da QA
# O V326 antigo criou a tabela com as colunas no nome do campo. Reproduzir esse formato é o ponto:
# um banco criado pelo V326 de hoje já sai em snake_case e não expõe o defeito.
if db <<'SQL' >/dev/null 2>&1
create table roleplay_published_sheets (
  id varchar(255) not null,
  campaignId varchar(255) not null,
  sheetId varchar(255) not null,
  ownerId varchar(255) not null,
  characterName varchar(120) not null,
  gameSystem varchar(80) not null,
  fieldsJson longtext null,
  publishedAt timestamp not null,
  updatedAt timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4;
create table roleplay_world_entries (
  id varchar(255) not null,
  campaignId varchar(255) not null,
  kind varchar(20) not null,
  title varchar(120) not null,
  body longtext null,
  tags varchar(600) null,
  linkedSheetId varchar(255) null,
  authorId varchar(255) not null,
  createdAt timestamp not null,
  updatedAt timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4;
insert into roleplay_published_sheets (id, campaignId, sheetId, ownerId, characterName, gameSystem,
  publishedAt, updatedAt) values ('pub_qa', 'cmp_qa', 'sh_qa', 'u_qa', 'Ana', 'dnd5e', now(), now());
SQL
then
  pass "banco reproduz o estado da QA (tabela em camelCase, com dados)"
else
  fail "não foi possível reproduzir o estado da QA"
fi

# ---------------------------------------------------------------- 1ª aplicação do 327
if db < "$V327" >/dev/null 2>&1; then
  pass "V327 aplica sobre o banco migrado da QA"
else
  fail "V327 falhou sobre o banco migrado"
  db < "$V327" 2>&1 | head -3 >&2 || true
fi

# ---------------------------------------------------------------- o que o boot exige
# Mesma regra do `validate`: todo campo composto da entidade tem coluna. A lista sai do código.
conferir() {
  local tabela="$1"; shift
  local campo existe
  for campo in "$@"; do
    existe=$(db -N -B -e "select count(*) from information_schema.columns
      where table_schema = database() and table_name = '$tabela' and column_name = '$campo'" 2>/dev/null | tr -d '[:space:]')
    if [[ "$existe" == "1" ]]; then
      pass "$tabela.$campo existe depois da V327"
    else
      fail "$tabela.$campo continua ausente depois da V327: o boot voltaria a falhar"
    fi
  done
}

conferir roleplay_published_sheets campaign_id sheet_id owner_id character_name game_system fields_json \
  published_at updated_at
conferir roleplay_world_entries campaign_id linked_sheet_id author_id created_at updated_at

# Os nomes antigos continuam: apagar coluna é destrutivo e o V327 não apaga.
conferir roleplay_published_sheets campaignId sheetId ownerId

preservadas=$(db -N -B -e "select count(*) from roleplay_published_sheets where id = 'pub_qa'" 2>/dev/null | tr -d '[:space:]')
[[ "$preservadas" == "1" ]] && pass "dado da QA preservado" || fail "linha da QA sumiu (veio '$preservadas')"

# ---------------------------------------------------------------- 2ª aplicação
if db < "$V327" >/dev/null 2>&1; then
  pass "V327 aplica de novo sem falhar (idempotente)"
else
  fail "V327 falhou na 2ª aplicação"
fi

printf '\nÉroleplay colunas: PASS %s | FAIL %s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]