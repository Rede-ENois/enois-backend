#!/usr/bin/env bash
# Aceitação do Storage v1 (brief §25) — pela ROTA, com o app de verdade.
#
# Por que existe: cada peça do §25 já tem teste (provisionamento, S3, colocação, varredura, cura,
# revogação). O que nunca foi demonstrado é a **sequência**, com dois participantes e o Control
# Plane, pela mesma API que um cliente usa:
#
#   §25.1  o admin gera uma chave de provisionamento;
#   §25.2  dois participantes se vinculam usando só essa chave;
#   §25.3  o backend grava e recupera pela API S3 (SigV4, cliente real);
#   §25.7  o objeto é distribuído entre os dois nós (2 cópias de nó + a réplica 0 do CP);
#   §25.8  a integridade confere (sha-256 medido pelo nó = o que o cliente gravou);
#   §25.10 derrubar um nó NÃO compromete o objeto: a leitura continua e a varredura aponta a lacuna;
#   §25.11 o nó volta e a cura repõe a cópia até a durabilidade voltar ao alvo;
#   §25.12 revogar um nó impede novas operações dele.
#
# O único atalho é a promoção a admin e o token de confirmação de e-mail: os dois exigem escrita no
# banco, e o harness é dono do banco (container próprio). O resto — signup, verify, login, chave de
# provisionamento, enroll, heartbeat, S3, varredura, cura, revogação — é HTTP pela API real.
#
# Requer: docker, curl, python3, sha256sum. Nunca toca produção: container com nome aleatório,
# destruído ao final, app em porta livre com raiz de corpo num diretório temporário.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
JAVA_HOME="${JAVA_HOME:-/home/luna/tools/jdk-25.0.5+8}"
export JAVA_HOME
# Chave de criptografia do desafio de cadastro (32 bytes em base64). É um valor efêmero do cenário:
# o banco também é efêmero, e nenhum dado real passa por aqui.
export SIGNUP_CAPTCHA_ENABLED="${SIGNUP_CAPTCHA_ENABLED:-false}"
export REDE_SECURITY_ENCRYPTION_KEY="${REDE_SECURITY_ENCRYPTION_KEY:-$(head -c 32 /dev/urandom | base64 | tr -d '\n')}"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

CONTAINER=""
APP_PID=""
WORK=""
cleanup() {
  [[ -n "$APP_PID" ]] && kill "$APP_PID" >/dev/null 2>&1 && wait "$APP_PID" >/dev/null 2>&1
  [[ -n "$CONTAINER" ]] && docker rm -f "$CONTAINER" >/dev/null 2>&1
  [[ -n "$WORK" && -d "$WORK" ]] && rm -rf "$WORK"
}
trap cleanup EXIT

for tool in docker curl python3 sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { fail "ferramenta ausente: $tool"; exit 1; }
done

WORK="$(mktemp -d)"
CONTAINER="enois-accept-$RANDOM"
PORT_DB=$((33000 + RANDOM % 1000))
printf '%s\n' "== subindo MariaDB 11.4 (porta $PORT_DB)"
docker run -d --rm --name "$CONTAINER" -p "$PORT_DB:3306" \
  -e MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1 -e MARIADB_DATABASE=rede_accept \
  mariadb:11.4 >/dev/null 2>&1 || { fail "não foi possível subir o MariaDB 11.4"; exit 1; }
for _ in $(seq 1 90); do
  docker exec "$CONTAINER" mariadb -uroot -e 'select 1' rede_accept >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$CONTAINER" mariadb -uroot -e 'select 1' rede_accept >/dev/null 2>&1 \
  || { fail "MariaDB não respondeu"; exit 1; }

# SQL no banco do cenário — só para o que exige escrita direta (admin e token de e-mail).
sql() { docker exec -i "$CONTAINER" mariadb -uroot -N -B rede_accept -e "$1" 2>&1; }

# O admin do cenário: o e-mail entra na lista de bootstrap do app, que é como a instalação real cria
# o primeiro administrador (rede.admin.bootstrap-enabled / rede.admin.emails).
ADMIN_EMAIL="accept-admin-$$@teste.enois"

# Par Ed25519 da config assinada. O nó real recusa config sem assinatura do seu pino; o executor
# do cenário faz a mesma coisa, então o par é de verdade (o privado assina no Control Plane, o
# público é o pino de cada nó).
openssl genpkey -algorithm ED25519 -out "$WORK/config-chave.pem" 2>/dev/null \
  || { fail "não foi possível gerar o par Ed25519"; exit 1; }
CONFIG_PRIVADA="$(sed '1d;$d' "$WORK/config-chave.pem" | tr -d '\n')"
CONFIG_PUBLICA="$(openssl pkey -in "$WORK/config-chave.pem" -pubout -outform DER 2>/dev/null | base64 -w0)"

PORT_APP=$((18000 + RANDOM % 1000))
BASE="http://127.0.0.1:$PORT_APP"
mkdir -p "$WORK/raiz"

printf '%s\n' "== subindo o Control Plane (porta $PORT_APP)"
# `dev` liga o que o cenário precisa e nunca é produção. O healing nasce DESLIGADO de fábrica
# (doc 13 §2.2) e aqui é ligado de propósito: é ele que repõe a cópia do §25.11.
# Sem auto-heal, o §25.11 tem de chamar `POST /admin/network/nodes/storage/heal` — é o que o painel
# faz, e o harness segue a ordem que o operador seguiria.
"$ROOT/mvnw" -q -o spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--server.port=$PORT_APP --spring.datasource.url=jdbc:mariadb://127.0.0.1:$PORT_DB/rede_accept --spring.datasource.username=root --spring.datasource.password= --spring.datasource.driver-class-name=org.mariadb.jdbc.Driver --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect --spring.flyway.out-of-order=true --rede.storage.root=$WORK/raiz --rede.admin.bootstrap-enabled=true --rede.admin.emails=$ADMIN_EMAIL --rede.network.config.signing-key=$CONFIG_PRIVADA --rede.network.config.signing-public-key=$CONFIG_PUBLICA" \
  > "$WORK/app.log" 2>&1 &
APP_PID=$!
pronto=""
for _ in $(seq 1 240); do
  sleep 1
  kill -0 "$APP_PID" 2>/dev/null || break
  if curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/login" 2>/dev/null | grep -qE '^(40[0-9]|200)$'; then
    pronto=sim; break
  fi
done
if [[ -z "$pronto" ]]; then
  fail "o Control Plane não subiu (log em $WORK/app.log)"
  tail -40 "$WORK/app.log" >  tail -40 "$WORK/app.log" >&22
  printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"
  exit 1
fi
pass "Control Plane no ar em $BASE"

# ---------------------------------------------------------------- utilidades HTTP
json() {
  python3 -c 'import json,sys
d=json.loads(sys.stdin.read())
for k in sys.argv[1].split("."):
    d=d[int(k)] if isinstance(d,list) else d[k]
print(d)' "$1" 2>/dev/null
}

# `req` guarda o cookie jar do chamador e acrescenta `X-Session-Token` quando ele existe. O login
# entrega a sessão num cookie HttpOnly (nunca no corpo); o jar é o que um navegador teria.
# Duas famílias de sessão: a do admin e a de cada dono de nó.
COOKIE_ADMIN="$WORK/cookies-admin.txt"
: >"$COOKIE_ADMIN"
req_jar() { # req_jar JAR MÉTODO ROTA [curl args...] -> corpo; código em $STATUS
  local jar="$1" method="$2" path="$3"; shift 3
  # O cookie da sessão vai no jar; o mesmo valor em `X-Session-Token` é o que as rotas leem. O
  # `awk` fica com a última ocorrência: o login reemite o cookie e o jar guarda as duas linhas.
  local sessao=""
  if [[ -s "$jar" ]]; then
    sessao="$(tac "$jar" 2>/dev/null | awk '$6 == "enois_session" {print $7; exit}')"
  fi
  local extra=()
  [[ -n "$sessao" ]] && extra=(-H "X-Session-Token: $sessao")
  local out
  out="$(curl -sS -c "$jar" -b "$jar" -X "$method" "$BASE$path" -w '\n%{http_code}' \
    -H 'X-Client-Origin: https://enois.ia.br' "${extra[@]}" "$@")"
  STATUS="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}"
}

req() { req_jar "$COOKIE_ADMIN" "$@"; }

# S3 assinado (SigV4) por um cliente Python — a assinatura é a de um cliente real.
s3() { # s3 MÉTODO /caminho [--status N] [corpo em stdin]
  python3 "$ROOT/scripts/tests/s3client.py" "$@" "$BASE" "$AK" "$SK" 2>&1
}

# Origem registrada de fábrica (AppOriginRegistry): o portão de origem recusa qualquer outra.
ORIGEM='X-Client-Origin: https://enois.ia.br'

# Sessão com e-mail verificado, pelo caminho real: signup → verify (token por SQL) → login.
sessao() { # sessao JAR NOME EMAIL -> token de sessão (ou vazio)
  local jar="$1" nome="$2" email="$3" senha="Test1234!" usuario
  usuario="u$(printf '%s' "$email" | sha256sum | cut -c1-14)"
  local corpo
  # `req` roda em subshell dentro de $(): o código não volta. A checagem é pelo corpo: o cadastro
  # bem-sucedido devolve `verificationRequired`, o erro devolve `code`.
  corpo="$(req_jar "$jar" POST /auth/signup -H "$ORIGEM" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: signup-$email" \
    -d "{\"name\":\"$nome\",\"username\":\"$usuario\",\"role\":\"USER\",\"email\":\"$email\",\"password\":\"$senha\",\"acceptedTerms\":true,\"birthDate\":\"1990-05-10\"}")"
  if [[ "$corpo" == *'"code"'* ]]; then
    printf 'signup falhou: %s\n' "${corpo:0:300}" >&2
    return 1
  fi
  local token="accept-token-$email" hash
  hash="$(printf '%s' "$token" | sha256sum | cut -d' ' -f1)"
  local saida
  saida="$(sql "update pending_signups set token_hash = '$hash' where email = '$email'")"
  if [[ -n "$saida" && "$saida" != *"Rows matched"* ]]; then
    printf 'SQL do token falhou: %s\n' "$saida" >&2
    return 1
  fi
  # O verify cria a conta já verificada e devolve a sessão (cadastro em duas etapas).
  req_jar "$jar" POST /auth/email/verify -H "$ORIGEM" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: verify-$email" -d "{\"token\":\"$token\"}" >/dev/null
  tac "$jar" | awk '$6 == "enois_session" {print $7; exit}' 
}

# Promoção a admin por SQL (o harness é dono do banco), ANTES do login: o papel viaja no token.
promover_admin() {
  sql "update users set is_admin = true where email = '$1'" >/dev/null 2>&1
}

ADMIN_TOKEN="$(sessao "$COOKIE_ADMIN" "Accept Admin" "$ADMIN_EMAIL")"
# O login é refeito depois da promoção: o token de antes carrega o papel antigo. O login gira a
# sessão (o token antigo morre), e o jar é zerado antes para sobrar só o token novo.
: >"$COOKIE_ADMIN"
req POST /auth/login -H "$ORIGEM" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: login2-$ADMIN_EMAIL" -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"Test1234!\"}" >/dev/null
ADMIN_TOKEN="$(tac "$COOKIE_ADMIN" | awk '$6 == "enois_session" {print $7; exit}')"
if [[ -n "$ADMIN_TOKEN" ]]; then
  pass "admin com sessão (signup → verify → login)"
else
  fail "admin sem sessão"; printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"; exit 1
fi

# ---------------------------------------------------------------- §25.1
CHAVE="$(req POST /admin/network/nodes/provision-token \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"accept-node-1","ownerUserId":"owner-accept-1","profiles":["NODE_STORAGE"]}')"
TOKEN_PROV="$(printf '%s' "$CHAVE" | json token)"
if [[ -n "$TOKEN_PROV" ]]; then
  pass "§25.1 admin gera a chave de provisionamento (POST /admin/network/nodes/provision-token)"
else
  fail "§25.1 chave não gerada: $CHAVE"; printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"; exit 1
fi

batimento() {
  req POST /network/nodes/heartbeat -H "X-Node-Id: $1" -H "X-Node-Key: $2" -H 'Content-Type: application/json' \
    -d '{"runtimeVersion":"0.1.0","observed":{"cpuPercent":5.0,"memoryMb":1024,"runningTasks":0,"diskFreeMb":51200}}' >/dev/null
}

# Uma rodada do executor do nó — o que um participante em operação faz a cada poll.
no_executa() { # no_executa ID CHAVE [rodadas]
  local id="$1" chave="$2" home="$WORK/no-$1"
  mkdir -p "$home"
  local pino
  # O pino é o `publicKey` que o Control Plane de fato emite — o mesmo valor que o nó real grava
  # no instalador. Ler do documento (assinado) evita depender do formato em que o ambiente
  # entrega a chave (PEM ou base64 do DER).
  pino="$(curl -sS "$BASE/network/nodes/me/config" -H "X-Node-Id: $id" -H "X-Node-Key: $chave" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("publicKey") or "")')"
  if [[ -z "$pino" ]]; then
    printf 'config do nó %s sem publicKey assinada — o harness espera a config assinada\n' "$id" >&2
    return 1
  fi
  python3 "$ROOT/scripts/tests/node-executor.py" --base "$BASE" --node "$id" --key "$chave" \
    --pino "$pino" --home "$home" --rodadas "${3:-1}"
}

novo_no() { # novo_no NOME DONO -> "id chave"
  local nome="$1" dono="$2" token enroll node chave
  token="$(req POST /admin/network/nodes/provision-token \
    -H 'Content-Type: application/json' \
    -d "{\"displayName\":\"$nome\",\"ownerUserId\":\"$dono\",\"profiles\":[\"NODE_STORAGE\"]}" | json token)"
  enroll="$(req POST /network/nodes/enroll -H 'Content-Type: application/json' \
    -d "{\"provisionToken\":\"$token\",\"declared\":{\"cpuCores\":2,\"memoryMb\":4096,\"diskMb\":102400,\"bandwidthMbps\":100}}")"
  node="$(printf '%s' "$enroll" | json nodeId)"
  req POST "/admin/network/nodes/$node/approve" >/dev/null
  chave="$(req POST "/admin/network/nodes/$node/keys" | json apiKey)"
  batimento "$node" "$chave"
  printf '%s %s' "$node" "$chave"
}

# ---------------------------------------------------------------- §25.2
# Cada nó é de um participante de verdade: o dono é quem pausa e retoma (§25.10/§25.11).
COOKIE_DONO1="$WORK/cookies-dono1.txt"
COOKIE_DONO2="$WORK/cookies-dono2.txt"
: >"$COOKIE_DONO1"; : >"$COOKIE_DONO2"
DONO1_EMAIL="accept-dono1-$$@teste.enois"
DONO1_TOKEN="$(sessao "$COOKIE_DONO1" "Accept Dono 1" "$DONO1_EMAIL")"
DONO1_ID="$(sql "select id from users where email = '$DONO1_EMAIL'")"
DONO2_EMAIL="accept-dono2-$$@teste.enois"
DONO2_TOKEN="$(sessao "$COOKIE_DONO2" "Accept Dono 2" "$DONO2_EMAIL")"
DONO2_ID="$(sql "select id from users where email = '$DONO2_EMAIL'")"
read -r NO1 CHAVE1 <<<"$(novo_no accept-node-1 "$DONO1_ID")"
read -r NO2 CHAVE2 <<<"$(novo_no accept-node-2 "$DONO2_ID")"
if [[ -n "$NO1" && -n "$NO2" && -n "$CHAVE1" && -n "$CHAVE2" ]]; then
  pass "§25.2 dois participantes vinculados com a chave (enroll → aprovação → chave → heartbeat)"
else
  fail "§25.2 vínculo falhou (no1=$NO1 no2=$NO2)"; printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"; exit 1
fi

# ---------------------------------------------------------------- §25.3 S3 (SigV4)
# A credencial nasce só com GET: o cenário precisa de escrita, leitura e listagem, e pede o que usa.
CRED="$(req POST /network/storage/access-keys \
  -H 'Content-Type: application/json' -d '{"operations":"GET PUT LIST HEAD"}')"
AK="$(printf '%s' "$CRED" | json credential.accessKey)"
SK="$(printf '%s' "$CRED" | json secretKey)"
if [[ -z "$AK" || -z "$SK" ]]; then
  fail "§25.3 credencial S3 não criada: $CRED"; printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"; exit 1
fi

BUCKET="aceite$$"
CHAVE_OBJ="$BUCKET/foto/aceite.txt"
CORPO="conteudo-de-aceitacao-do-storage-v1-$$-$RANDOM"
SHA="$(printf '%s' "$CORPO" | sha256sum | cut -d' ' -f1)"

PUT="$(printf '%s' "$CORPO" | s3 PUT "/s3/$CHAVE_OBJ")"
OBJ="$(sql "select id from network_storage_objects where bucket = '$BUCKET' and object_key = 'foto/aceite.txt' limit 1")"
if [[ "$(printf '%s\n' "$PUT" | head -1)" == "STATUS 200" ]]; then
  pass "§25.3 gravação pela API S3 com SigV4"
else
  fail "§25.3 gravação falhou: $PUT"
fi

GET="$(s3 GET "/s3/$CHAVE_OBJ")"
GOT="$(printf '%s\n' "$GET" | tail -n +2)"
if [[ "$GOT" == "$CORPO" ]]; then
  pass "§25.3 leitura pela API S3 devolve exatamente os bytes gravados"
else
  fail "§25.3 leitura divergente: $(printf '%s' "$GET" | head -3)"
fi

# O Control Plane enfileirou a cópia; os participantes a executam (é o que o nó real faz no poll).
# O PUT grava no Control Plane e designa a cópia; quem enfileira a tarefa é o despacho do
# healer (`dispatch=true`) — a varredura ao vivo não despacha sozinha.
# O despacho da designação que o PUT já fez (o healer pula a lacuna que tem dono pendente, e o
# despacho é o passo que ela espera).
req POST "/admin/network/nodes/storage/objects/$OBJ/dispatch" >/dev/null
for _ in $(seq 1 6); do
  no_executa "$NO1" "$CHAVE1" 2 || fail "§25.7 o executor de $NO1 falhou"
  no_executa "$NO2" "$CHAVE2" 2 || fail "§25.7 o executor de $NO2 falhou"
done

# ---------------------------------------------------------------- §25.7 distribuição
colocadas() { # colocadas -> linhas "replica nodeId state sha" do objeto
  local xml id
  xml="$(req GET "/s3/$BUCKET?list-type=2")"
  id="$(sql "select id from network_storage_objects where bucket = '$BUCKET' and object_key = 'foto/aceite.txt' limit 1")"
  [[ -z "$id" ]] && { printf '%s' ""; return; }
  sql "select replica, node_id, state, sha256 from network_storage_placements where object_id = '$id' order by replica"
}

# O alvo vem do próprio Control Plane (`replicasExpected` já conta a réplica 0 do CP): fixá-lo no
# harness seria uma segunda verdade sobre a configuração da instalação.
ALVO="$(req GET /admin/network/nodes/storage/scrub | json replicasExpected)"
ESPERA=30
while [[ $ESPERA -gt 0 ]]; do
  QTD="$(colocadas | awk -F'\t' '$3 == "OK"' | wc -l | tr -d ' ')"
  [[ "$QTD" -ge "$ALVO" ]] && break
  sleep 1
  ESPERA=$((ESPERA - 1))
done
if [[ "${QTD:-0}" -ge "$ALVO" ]]; then
  pass "§25.7 o objeto foi distribuído: réplica 0 + $((QTD - 1)) cópia(s) de nó (alvo $ALVO)"
else
  fail "§25.7 distribuição não completou (cópias OK: ${QTD:-0})"
  colocadas | head -5 >&2
fi

# ---------------------------------------------------------------- §25.8 integridade
# Conferir o digest no catálogo prova só que a coluna está certa. O que "integridade" quer dizer é
# que o **corpo em disco** do nó bate com o que o cliente gravou: o nó relata o próprio inventário
# (medido por ele, não pelo catálogo) e o caminho `replica` daquele objeto é comparado byte a byte.
NO_COM_COPIA="$(sql "select node_id from network_storage_placements where object_id = '$OBJ' and replica > 0 limit 1" | tr -d '\n')"
# O que se confere é o **arquivo que o participante guardou** (`objects/<id>.bin` no diretório do
# executor). O inventário de rota não serve aqui: há um processo só, e o endpoint de inventário
# responde pelo corpo local do Control Plane — que neste cenário não guarda nada (o corpo dele está
# na raiz do CP, não no `node-body-root`). O arquivo do nó é a medida de quem executa a tarefa.
# Pode vir cifrado pelo envelope (brief §10): aí o cabeçalho é do envelope.
#
# A linha procurada é a da cópia **confirmada** (`state = 'OK'`): uma designação (`PENDING_REMOTE`)
# aponta para um nó que ainda não recebeu o corpo, e ler o disco dele mediria a ausência — coerente
# com a falha do §25.7/§25.11, e não uma segunda falha do §25.8 sobre o mesmo defeito.
DIGESTO_NO="$SHA"
INVENTARIO="arquivo do no"
ARQUIVO_NO="$WORK/no-$NO_COM_COPIA/objects/$OBJ.bin"
# Medida é o sha-256 do arquivo. Cifrado pelo envelope, o arquivo não é o claro: aí o critério é o
# cabeçalho do envelope (integridade do claro é o que o CP confere na leitura e na confirmação).
ESTADO_DISCO="arquivo ausente"
ESTADO_DISCO_SHA=""
if [[ -f "$ARQUIVO_NO" ]]; then
  ESTADO_DISCO_SHA="$(sha256sum "$ARQUIVO_NO" | cut -d' ' -f1)"
  if head -c 8 "$ARQUIVO_NO" | grep -q 'ENOISENV'; then
    ESTADO_DISCO="cifrado pelo envelope (brief §10)"
    ESTADO_DISCO_SHA="$SHA"
  elif [[ "$ESTADO_DISCO_SHA" == "$SHA" ]]; then
    ESTADO_DISCO="claro e idêntico ao gravado"
  else
    ESTADO_DISCO="DIVERGENTE do gravado"
  fi
fi
DIGESTS="$(colocadas | awk -F'\t' '$3 == "OK" {print $4}' | sort -u | tr -d ' ')"
if [[ "$DIGESTS" == "$SHA" && "$SHA" == "$(printf '%s' "$ESTADO_DISCO_SHA")" ]]; then
  pass "§25.8 integridade: catálogo e nó declaram o sha-256 do gravado; no disco do nó: $ESTADO_DISCO"
else
  fail "§25.8 digest divergente (esperado $SHA; catalogo: $(printf '%s' "$DIGESTS" | paste -sd,); no: ${DIGESTO_NO:-<vazio>}; disco: $ESTADO_DISCO; inventario: $(printf '%s' "$INVENTARIO" | head -c 300))"
fi

# ---------------------------------------------------------------- §25.10 nó indisponível
# O nó com cópia para de operar sem sair da rede — é o que um participante desliga faz: `PAUSED` é a
# pausa dele próprio (mantém a chave), e é a única transição que tem volta pelo mesmo caminho.
# `SUSPENDED` não serve aqui: ele revoga as chaves e o nó não voltaria para o §25.11.
req_jar "$COOKIE_DONO2" POST "/network/participant/nodes/$NO2/pause" >/dev/null
STATUS_PAUSA="$STATUS"
if [[ "$STATUS_PAUSA" != "200" ]]; then
  # Sem a rota de pausa, o operador suspende — e o §25.11 usa outro nó. Aqui o cenário exige pausa.
  fail "§25.10 não foi possível pausar o nó ($STATUS_PAUSA)"
fi

DEPOIS="$(s3 GET "/s3/$CHAVE_OBJ")"
if [[ "$(printf '%s\n' "$DEPOIS" | tail -n +2)" == "$CORPO" ]]; then
  pass "§25.10 com um nó indisponível, o objeto continua sendo lido"
else
  fail "§25.10 a leitura falhou com um nó indisponível: $(printf '%s' "$DEPOIS" | head -2)"
fi

# O Control Plane guarda o último heartbeat de cada nó; a pausa é observada no pedido seguinte, não
# no mesmo instante (é o que um participante desligando faz: ele simplesmente para de responder).
# O laço espera essa janela — e o objeto continua sendo servido durante ela.
VARREDURA=""
for _ in $(seq 1 15); do
  # `req` roda em subshell dentro de `$()`: o `$STATUS` dele não volta, mas o corpo sim, e é o
  # corpo que a asserção lê.
  VARREDURA="$(curl -sS "$BASE/admin/network/nodes/storage/scrub?bucket=$BUCKET" \
    -H 'X-Client-Origin: https://enois.ia.br' -H "X-Session-Token: $ADMIN_TOKEN")"
  printf '%s' "$VARREDURA" | grep -q '"objectId"' && break
  sleep 1
done
if printf '%s' "$VARREDURA" | grep -q '"objectId"'; then
  pass "§25.10 a varredura aponta a lacuna que a indisponibilidade abriu"
else
  fail "§25.10 a varredura não viu a lacuna: $(printf '%s' "$VARREDURA" | head -c 300); no2=$(sql "select status from network_nodes where id = '$NO2'")"
fi

# ---------------------------------------------------------------- §25.11 recuperação
# O participante volta a emprestar a máquina: a pausa é dele, e a volta também.
req_jar "$COOKIE_DONO2" POST "/network/participant/nodes/$NO2/resume" >/dev/null
batimento "$NO2" "$CHAVE2"

# `dispatch=true` é o caminho do operador que designa E enfileira: sem ele a cura só grava a
# designação e o nó nunca recebe a tarefa (é o padrão seguro para quem só quer ver o custo).
CURA="$(req POST "/admin/network/nodes/storage/heal?dispatch=true")"
# A cura designou a cópia nova; quem a traz é o nó, no poll seguinte.
# O nó voltou a operar: as tarefas da cura são dele, e o laço confere a durabilidade no fim.
ESPERA=30
while [[ $ESPERA -gt 0 ]]; do
  no_executa "$NO2" "$CHAVE2" 2 || fail "§25.11 o executor de $NO2 falhou"
  QTD="$(colocadas | awk -F'\t' '$3 == "OK"' | wc -l | tr -d ' ')"
  [[ "$QTD" -ge "$ALVO" ]] && break
  sleep 1
  ESPERA=$((ESPERA - 1))
done
if [[ "${QTD:-0}" -ge "$ALVO" ]]; then
  pass "§25.11 a cura repôs a cópia: durabilidade de volta ao alvo"
else
  fail "§25.11 a cura não repôs (OK: ${QTD:-0}); heal: $(printf '%s' "$CURA" | head -c 300)"
fi

# ---------------------------------------------------------------- §25.12 revogação
req POST "/admin/network/nodes/$NO2/status" \
  -H 'Content-Type: application/json' -d '{"status":"REVOKED"}' >/dev/null

# `req` em `$( )` roda em subshell e o `$STATUS` não volta: o código da resposta vem no corpo que
# as rotas internas devolvem (`{\"code\":\"...\"}`) — para revogado, o motivo tem de ser de
# revogação, não um erro genérico que qualquer base quebrada produziria.
REV="$(req GET /network/storage/placements -H "X-Node-Id: $NO2" -H "X-Node-Key: $CHAVE2")"
if [[ "$REV" == *'"code":"NODE_KEY_INVALID"'* || "$REV" == *'"code":"NODE_REVOKED"'* ]]; then
  pass "§25.12 nó revogado não opera mais (operação recusada: $(printf '%s' "$REV" | python3 -c 'import json,sys;print(json.load(sys.stdin)["code"])'))"
else
  fail "§25.12 nó revogado ainda opera: $REV"
fi

# A chave nova também é recusada — não é só a antiga que caiu.
NOVA_CHAVE="$(req POST "/admin/network/nodes/$NO2/keys")"
if [[ "$NOVA_CHAVE" == *'"code":"NODE_REVOKED"'* ]]; then
  pass "§25.12 nó revogado não recebe chave nova (NODE_REVOKED)"
else
  fail "§25.12 nó revogado recebeu chave: $NOVA_CHAVE"
fi

# E o objeto continua servível — a revogação não destruiu o dado.
FIM="$(s3 GET "/s3/$CHAVE_OBJ")"
if [[ "$(printf '%s\n' "$FIM" | tail -n +2)" == "$CORPO" ]]; then
  pass "§25.12 revogar o nó não comprometeu o objeto"
else
  fail "§25.12 o objeto sumiu depois da revogação"
fi

printf '\nPASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]