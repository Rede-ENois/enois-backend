#!/usr/bin/env bash
# A migração do metadado do objeto (V367) num MariaDB 11.4 real: aplicada DUAS vezes, com os objetos
# que já existiam preservados.
#
# Por que existe: a suíte roda em H2, e o H2 aceita um DDL que o MariaDB recusa. O que precisa ser
# provado no banco de verdade:
#   1. a migração aplica limpa;
#   2. um objeto gravado ANTES continua lá, com o metadado nulo (o valor que reproduz o
#      comportamento anterior: leitura cai na política do tipo);
#   3. aplicar a SEGUNDA vez não falha e não apaga o que o cliente declarou;
#   4. o objeto novo guarda metadado, Cache-Control e Content-Disposition, e o valor com aspas, barra
#      e quebra de linha volta idêntico.
#
# Uso:
#   ./scripts/tests/storage-metadata-migration-test.sh
#   STORAGE_DB_DSN="--host=... --user=... db" ./scripts/tests/storage-metadata-migration-test.sh
set -uo pipefail

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
MIGRACAO="$RAIZ/src/main/resources/db/migration/V367__network_storage_object_metadata.sql"
[[ -f "$MIGRACAO" ]] || { fail "migração V367 não encontrada em $MIGRACAO"; exit 1; }

CONTAINER=""
WORK="$(mktemp -d)"
cleanup() {
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

if [[ -z "${STORAGE_DB_DSN:-}" ]]; then
  command -v docker >/dev/null 2>&1 || { fail "docker ausente e STORAGE_DB_DSN não definido"; exit 1; }
  CONTAINER="enois-v367-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 -e MARIADB_DATABASE=enois_v367 mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  estavel=0
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" mariadb -uroot enois_v367 -e "select 1" >/dev/null 2>&1; then
      estavel=$((estavel + 1))
      [ "$estavel" -ge 3 ] && break
    else
      estavel=0
    fi
    sleep 2
  done
  [ "$estavel" -ge 3 ] || { fail "o MariaDB não ficou estável no schema do teste"; exit 1; }
  sql() { docker exec -i "$CONTAINER" mariadb -uroot enois_v367; }
  sqlN() { docker exec -i "$CONTAINER" mariadb -uroot -N -B enois_v367 -e "$1"; }
else
  command -v mariadb >/dev/null 2>&1 || { fail "sem cliente mariadb"; exit 1; }
  # shellcheck disable=SC2206
  DSN=($STORAGE_DB_DSN)
  sql() { mariadb --default-character-set=utf8mb4 "${DSN[@]}"; }
  sqlN() { mariadb -N -B "${DSN[@]}" -e "$1"; }
fi

# --- 1. o estado ANTERIOR à migração ---------------------------------------
# `network_storage_objects` na forma do V352, mais um objeto de verdade já gravado.
sql <<'SQL'
drop table if exists network_storage_objects;
create table network_storage_objects (
  id varchar(40) not null,
  bucket varchar(63) not null,
  object_key varchar(700) not null,
  content_type varchar(200) not null default 'application/octet-stream',
  bytes bigint null,
  sha256 varchar(64) null,
  state varchar(16) not null,
  created_by varchar(40) not null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3),
  deleted_at timestamp(3) null,
  version bigint not null default 1,
  superseded_by varchar(40) null,
  expires_at timestamp(3) null,
  primary key (id),
  constraint uq_network_storage_objects_key unique (bucket, object_key)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;
insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by)
values ('sob_antigo', 'documentos', 'laudo.pdf', 'application/pdf', 1200, repeat('a', 64), 'STORED', 'user_1');
SQL
[ "$(sqlN "select count(*) from network_storage_objects")" = "1" ] \
  && pass "o banco começa com um objeto gravado antes da migração" \
  || fail "não foi possível montar o estado anterior à migração"

# --- 2. primeira aplicação -------------------------------------------------
if sql < "$MIGRACAO" 2>"$WORK/erro1"; then
  pass "a V367 aplica no MariaDB 11.4 real"
else
  fail "a V367 não aplica: $(head -2 "$WORK/erro1")"
fi

colunas=$(sqlN "select count(*) from information_schema.columns where table_schema = database() and table_name = 'network_storage_objects' and column_name in ('metadata_json','cache_control','content_disposition')")
[ "$colunas" = "3" ] && pass "as três colunas existem depois da primeira aplicação" \
  || fail "colunas criadas: $colunas (esperado 3)"

[ "$(sqlN "select if(metadata_json is null, 'NULO', 'VALOR') from network_storage_objects where id = 'sob_antigo'")" = "NULO" ] \
  && pass "o objeto que já existia continua lá, com metadado nulo (comportamento anterior preservado)" \
  || fail "o objeto antigo mudou de conteúdo na migração"

# --- 3. o objeto novo com o que o cliente declarou -------------------------
sql <<'SQL'
insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state, created_by,
  metadata_json, cache_control, content_disposition)
values ('sob_novo', 'documentos', 'novo.pdf', 'application/pdf', 900, repeat('b', 64), 'STORED', 'user_1',
  '{"autor":"Ana","setor":"juridico"}', 'private, max-age=60', 'attachment; filename="laudo-final.pdf"');
SQL
[ "$(sqlN "select cache_control from network_storage_objects where id = 'sob_novo'")" = "private, max-age=60" ] \
  && pass "Cache-Control declarado é guardado com o objeto" \
  || fail "Cache-Control não foi guardado"
[ "$(sqlN "select content_disposition from network_storage_objects where id = 'sob_novo'")" = 'attachment; filename="laudo-final.pdf"' ] \
  && pass "Content-Disposition com aspas é guardado literalmente" \
  || fail "Content-Disposition perdeu as aspas"

# --- 4. segunda aplicação: idempotente e sem perder dado --------------------
if sql < "$MIGRACAO" 2>"$WORK/erro2"; then
  pass "a V367 aplica pela SEGUNDA vez sem erro"
else
  fail "a segunda aplicação falhou: $(head -2 "$WORK/erro2")"
fi
# `json_extract` devolve JSON (a string sai com aspas); o valor comparável é o não-citado.
[ "$(sqlN "select json_unquote(json_extract(metadata_json, '$.autor')) from network_storage_objects where id = 'sob_novo'")" = "Ana" ] \
  && pass "o metadado declarado sobrevive à segunda aplicação" \
  || fail "a segunda aplicação perdeu o metadado"
[ "$(sqlN "select count(*) from network_storage_objects")" = "2" ] \
  && pass "as duas linhas continuam (nenhuma dada por recriada)" \
  || fail "a segunda aplicação mudou a quantidade de objetos"

# --- 5. o valor com aspas, barra e quebra de linha volta idêntico -----------
# `json_object` codifica o valor, e o heredoc com aspas simples não interpreta escape: a barra e a
# quebra chegam ao banco LITERAIS e o JSON guarda as formas escapadas. O valor é conferido pelos
# componentes DENTRO do banco, em vez de comparar o texto que o cliente escapou ao sair — assim o
# eixo do esperado não se mistura com o da saída.
sql <<'SQL'
update network_storage_objects
set metadata_json = json_object('nota', 'aspas ''simples'' e barra \\ e nova
linha')
where id = 'sob_novo';
SQL
nota_sql="json_unquote(json_extract(metadata_json, '\$.nota'))"
[ "$(sqlN "select instr($nota_sql, '''') > 0 from network_storage_objects where id = 'sob_novo'")" = "1" ] \
  && pass "a aspa simples volta no valor" || fail "a aspa simples se perdeu"
[ "$(sqlN "select instr($nota_sql, char(92)) > 0 from network_storage_objects where id = 'sob_novo'")" = "1" ] \
  && pass "a barra invertida volta no valor" || fail "a barra invertida se perdeu"
[ "$(sqlN "select instr($nota_sql, char(10)) > 0 from network_storage_objects where id = 'sob_novo'")" = "1" ] \
  && pass "a quebra de linha volta como quebra de verdade" || fail "a quebra virou dois caracteres"
[ "$(sqlN "select char_length($nota_sql) from network_storage_objects where id = 'sob_novo'")" = "38" ] \
  && pass "o comprimento do valor confere (38)" \
  || fail "comprimento do valor mudou: $(sqlN "select char_length($nota_sql) from network_storage_objects where id = 'sob_novo'")"

# A outra LINHA não foi tocada: o metadado de um objeto não vaza para o vizinho.
[ "$(sqlN "select if(metadata_json is null, 'NULO', 'VALOR') from network_storage_objects where id = 'sob_antigo'")" = "NULO" ] \
  && pass "a linha vizinha continua como estava" || fail "a atualização de um objeto tocou o outro"

echo
echo "Storage metadata migration: PASS $PASS | FAIL $FAIL"
[[ "$FAIL" -eq 0 ]]