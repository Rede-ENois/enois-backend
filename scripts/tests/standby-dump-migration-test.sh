#!/usr/bin/env bash
# O dump do banco por JDBC (#953) contra um MariaDB real: o arquivo tem de CARREGAR de volta no
# cliente MariaDB e devolver o mesmo dado, incluindo o que quebra gerador de SQL ingênuo (aspas, barra
# invertida, nova linha, binário, nulo, número negativo, timestamp com milissegundos).
#
# Por que existe: a suíte roda em H2, e o H2 aceita o que o MariaDB recusa. Um teste de texto que só
# confere "saiu CREATE TABLE" passaria com um arquivo que o nó não consegue restaurar.
#
# O que este harness prova, no banco real:
#   1. a origem recebe os valores difíceis;
#   2. o dump (o mesmo comando e as mesmas opções do serviço) tem a tabela e o dado;
#   3. o arquivo CARREGA num schema novo com `mariadb < arquivo`;
#   4. texto, binário, nulo, número e data voltam idênticos;
#   5. a contagem por tabela bate com a origem;
#   6. a retomada por offset reconstitui o arquivo inteiro e a mesma digest;
#   7. um arquivo com o INSERT removido é reconhecido como cópia furada.
#
# Uso:
#   ./scripts/tests/standby-dump-migration-test.sh
#   STORAGE_DB_DSN="--host=... --user=... db" ./scripts/tests/standby-dump-migration-test.sh
set -uo pipefail

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

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
  CONTAINER="enois-dump-mig-$$-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
  if ! docker run -d --rm --name "$CONTAINER" \
      -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 \
      -e MARIADB_DATABASE=enois_dump_src \
      mariadb:11.4 >/dev/null 2>&1; then
    fail "não foi possível subir o MariaDB 11.4 (docker run falhou)"
    exit 1
  fi
  ready=""
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" mariadb -uroot -e "select 1" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  [[ -n "$ready" ]] || { fail "o MariaDB 11.4 não respondeu a tempo"; exit 1; }
  # `select 1` respondendo não é servidor estável: o entrypoint reinicia o mysqld ao fim do init, e um
  # comando que cai na janela entre os dois responderia ao primeiro e não ao segundo. Espera a
  # TRINTA tentativas seguidas de resposta no schema do teste.
  estavel=0
  for _ in $(seq 1 40); do
    if docker exec "$CONTAINER" mariadb -uroot enois_dump_src -e "select 1" >/dev/null 2>&1; then
      estavel=$((estavel + 1))
      [ "$estavel" -ge 3 ] && break
    else
      estavel=0
    fi
    sleep 2
  done
  [ "$estavel" -ge 3 ] || { fail "o MariaDB não ficou estável no schema do teste"; exit 1; }
  # Cada função é UMA forma de falar com o banco: o container ou o DSN, nunca os dois.
  src()     { docker exec -i "$CONTAINER" mariadb -uroot enois_dump_src; }
  srcN()    { docker exec -i "$CONTAINER" mariadb -uroot -N -B enois_dump_src -e "$1"; }
  dst()     { docker exec -i "$CONTAINER" mariadb -uroot enois_dump_dst; }
  dstN()    { docker exec -i "$CONTAINER" mariadb -uroot -N -B enois_dump_dst -e "$1"; }
  admin()   { docker exec -i "$CONTAINER" mariadb -uroot -e "$1"; }
  carregar() { docker exec -i "$CONTAINER" mariadb -uroot enois_dump_dst < "$1"; }
  # `docker exec` sem `-t`: com TTY o dump recusa a saída (o arquivo), e o redirect do host leva erro.
  exportar() { docker exec "$CONTAINER" mariadb-dump --user=root --skip-dump-date \
                 --single-transaction --no-tablespaces --skip-comments enois_dump_src; }
else
  # shellcheck disable=SC2206
  DSN=($STORAGE_DB_DSN)
  command -v mariadb >/dev/null 2>&1 || { fail "sem cliente mariadb"; exit 1; }
  command -v mariadb-dump >/dev/null 2>&1 || { fail "sem mariadb-dump"; exit 1; }
  BASE_SCHEMA="${DSN[${#DSN[@]}-1]}"
  src()     { mariadb "--default-character-set=utf8mb4" "${DSN[@]}"; }
  srcN()    { mariadb -N -B "${DSN[@]}" -e "$1"; }
  dst()     { mariadb --default-character-set=utf8mb4 "${DSN[@]%$BASE_SCHEMA}enois_dump_dst"; }
  dstN()    { mariadb -N -B "${DSN[@]%$BASE_SCHEMA}enois_dump_dst" -e "$1"; }
  admin()   { mariadb "${DSN[@]%$BASE_SCHEMA}" -e "$1"; }
  carregar() { mariadb --default-character-set=utf8mb4 "${DSN[@]%$BASE_SCHEMA}enois_dump_dst" < "$1"; }
  exportar() { mariadb-dump --skip-dump-date --single-transaction --no-tablespaces --skip-comments \
                 "$BASE_SCHEMA" ${STORAGE_DB_DSN_EXTRA:-}; }
fi

# O cliente `-N -B` escapa o que não é imprimível: barra invertida vira `\\` e nova linha vira `\n`
# no texto que chega aqui. Desfaz só esses dois — o valor original os tinha literalmente.
_completo() {
  local bruto="$1"
  bruto="${bruto//\\\\/\\}"
  printf '%b' "${bruto//\\n/\\n}"
}

# --- 1. origem ---------------------------------------------------------------
src <<'SQL'
create table if not exists standby_probe (
  id int not null primary key,
  texto varchar(200) null,
  binario varbinary(64) null,
  numero bigint null,
  quando timestamp(3) null
) engine=InnoDB;
delete from standby_probe;
insert into standby_probe (id, texto, binario, numero, quando) values
  (1, 'normal', X'00FF10', 42, '2026-11-19 12:00:00.123'),
  (2, 'aspas ''simples'' e barra \\ e nova\nlinha', null, -7, null),
  (3, null, X'', 999999999999, '1971-01-01 00:00:00');
SQL
[ "$(srcN 'select count(*) from standby_probe' | tr -d '[:space:]')" = "3" ] \
  && pass "origem com 3 linhas e os valores difíceis" || fail "origem não recebeu as linhas"

# --- 2. o dump, com o mesmo comando e opções que o serviço usa ---------------
if exportar > "$WORK/dump.sql" 2>"$WORK/dump.err"; then
  pass "o dump sai do banco real"
else
  fail "dump falhou: $(head -2 "$WORK/dump.err")"
fi
grep -q "CREATE TABLE \`standby_probe\`" "$WORK/dump.sql" \
  && pass "o dump tem o CREATE TABLE da tabela" || fail "dump sem a tabela"
grep -q "INSERT INTO .*standby_probe" "$WORK/dump.sql" \
  && pass "o dump tem o INSERT da tabela" || fail "dump sem o INSERT"

# --- 3. carrega num schema novo --------------------------------------------
admin "drop database if exists enois_dump_dst"
admin "create database enois_dump_dst character set utf8mb4"
if carregar "$WORK/dump.sql" 2>"$WORK/load.err"; then
  pass "o dump carrega no cliente MariaDB real"
else
  fail "o dump não carrega: $(head -2 "$WORK/load.err")"
fi

# --- 4. o conteúdo volta idêntico ------------------------------------------
# O texto é comparado por COMPONENTES: aspas, barra invertida e quebra de linha cada um conferido,
# em vez de um concat que mistura o separador do esperado com o do resultado.
linha2="$(_completo "$(dstN "select texto from standby_probe where id = 2")")"
esperado="$(_completo "aspas 'simples' e barra \ e nova
linha")"
[ "$linha2" = "$esperado" ] && pass "texto com aspas/barra/nova linha volta idêntico" \
  || fail "texto não voltou idêntico: [$linha2] vs [$esperado]"
[ "$(dstN "select char_length(texto) from standby_probe where id = 2")" = "38" ] \
  && pass "o comprimento do texto confere (38)" || fail "comprimento do texto mudou"
[ "$(dstN "select hex(binario) from standby_probe where id = 1")" = "00FF10" ] \
  && pass "binário volta byte a byte (00FF10)" || fail "binário saiu diferente"
[ "$(dstN "select ifnull(texto, 'NULO') from standby_probe where id = 3")" = "NULO" ] \
  && pass "NULL volta NULL (e não a string 'NULL')" || fail "NULL virou outra coisa"
[ "$(dstN "select numero from standby_probe where id = 3")" = "999999999999" ] \
  && pass "bigint grande volta inteiro" || fail "bigint perdeu precisão"
[ "$(dstN "select numero from standby_probe where id = 2")" = "-7" ] \
  && pass "número negativo volta negativo" || fail "número negativo mudou"
[ "$(dstN "select date_format(quando, '%Y-%m-%d %H:%i:%s.%f') from standby_probe where id = 1")" = "2026-11-19 12:00:00.123000" ] \
  && pass "timestamp com milissegundos volta no mesmo instante" || fail "timestamp mudou"

# --- 5. a contagem bate ------------------------------------------------
[ "$(dstN 'select count(*) from standby_probe')" = "3" ] \
  && pass "a contagem por tabela bate com a origem (3)" || fail "contagem no destino difere"

# --- 6. retomada por offset junta o arquivo inteiro -------------------------
tamanho=$(wc -c < "$WORK/dump.sql")
metade=$((tamanho / 2))
{ head -c "$metade" "$WORK/dump.sql"; tail -c +$((metade + 1)) "$WORK/dump.sql"; } > "$WORK/juntado.sql"
if cmp -s "$WORK/dump.sql" "$WORK/juntado.sql" \
   && [ "$(sha256sum < "$WORK/dump.sql")" = "$(sha256sum < "$WORK/juntado.sql")" ]; then
  pass "retomada por offset reconstitui o mesmo arquivo e a mesma digest"
else
  fail "a junção dos trechos difere do arquivo inteiro"
fi

# --- 7. arquivo sem o INSERT é reconhecido como furado ----------------------
grep -avE "INSERT INTO .*standby_probe" "$WORK/dump.sql" > "$WORK/furado.sql"
origem="$(srcN 'select count(*) from standby_probe' | tr -d '[:space:]')"
no_arquivo="$(grep -acE "INSERT INTO .*standby_probe" "$WORK/furado.sql" || true)"
[ "$origem" -gt 0 ] && [ "$no_arquivo" -eq 0 ] \
  && pass "arquivo sem INSERT é reconhecível (origem com linha, arquivo sem o dado)" \
  || fail "não foi possível montar o arquivo furado"

echo
echo "Standby dump migration: PASS $PASS | FAIL $FAIL"
[[ "$FAIL" -eq 0 ]]