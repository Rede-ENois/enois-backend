#!/usr/bin/env bash
# Testes do instalador e do diagnóstico do Node Runtime.
# Padrão de scripts/tests/dev-doctor-test.sh: PATH isolado com symlinks + stubs.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod read nproc awk date tar gzip cat sha256sum tr rm; do
  location="$(command -v "$tool")"
  ln -s "$location" "$TMP/$tool" 2>/dev/null || true
done

make_command() {
  local name="$1"
  local contents="$2"
  printf '%s\n' '#!/bin/bash' "$contents" > "$TMP/$name"
  chmod +x "$TMP/$name"
}

PASS=0
assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    PASS=$((PASS + 1)); printf '[PASS] %s\n' "$name"
  else
    printf '[FAIL] %s\n  esperado: <%s>\n  obtido: <%s>\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_contains() {
  local name="$1" haystack="$2" needle="$3"
  if /bin/grep -Fq -- "$needle" <<<"$haystack"; then
    PASS=$((PASS + 1)); printf '[PASS] %s\n' "$name"
  else
    printf '[FAIL] %s — <%s> não encontrado:\n%s\n' "$name" "$needle" "$haystack" >&2
    exit 1
  fi
}

assert_not_contains() {
  local name="$1" haystack="$2" needle="$3"
  if /bin/grep -Fq -- "$needle" <<<"$haystack"; then
    printf '[FAIL] %s — <%s> não deveria estar:\n%s\n' "$name" "$needle" "$haystack" >&2
    exit 1
  fi
  PASS=$((PASS + 1)); printf '[PASS] %s\n' "$name"
}

# ---------------------------------------------------------------- install.sh

# curl simulado: primeiro call é o enroll, devolve nodeId; heartbeat devolve ACTIVE.
ENROLL_BODY='{"nodeId":"nd_teste123","status":"PENDING_APPROVAL","contract":"1","message":"ok"}'
HB_BODY='{"nodeId":"nd_teste123","status":"ACTIVE","profiles":["NODE_WORKER"],"serverTime":"now","mode":"NORMAL","acceptTasks":true}'
CONFIG_PIN='{"nodeId":"nd_teste123","operable":false,"signed":true,"signature":"x","algorithm":"Ed25519","publicKey":"CP-PUBLIC-KEY-PINNED","allowedTasks":[]}'
CONFIG_NOKEY='{"nodeId":"nd_teste123","operable":false,"signed":false,"signature":null,"publicKey":null,"allowedTasks":[]}'
CONFIG_ROTATED='{"nodeId":"nd_teste123","operable":true,"signed":true,"signature":"x","algorithm":"Ed25519","publicKey":"CHAVE-ROTACIONADA-DEPOIS","allowedTasks":[]}'
# curl dirigido por cenário: CENARIO escolhe o que /me/config devolve.
make_curl() {
  cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/enroll) printf '%s' '$ENROLL_BODY' ;;
  */network/nodes/heartbeat) printf '%s' '$HB_BODY' ;;
  */network/nodes/me/config)
    case '${CENARIO:-PIN}' in
      PIN) printf '%s' '$CONFIG_PIN' ;;
      NOKEY) printf '%s' '$CONFIG_NOKEY' ;;
      ROTATED) printf '%s' '$CONFIG_ROTATED' ;;
    esac ;;
  */health) printf '%s' '{"status":"UP"}' ;;
  *) exit 22 ;;
esac
MOCK
  chmod +x "$TMP/curl"
}
make_curl

NH="$TMP/node-home"

# Enroll grava identidade com permissão restrita e encerra pedindo a chave.
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" \
  --control-plane https://cp.test --token tok_ok --region sa-east-1 --country BR 2>&1) ||
  { printf '[FAIL] enroll saiu com erro:\n%s\n' "$out" >&2; exit 1; }
assert_eq 'enroll grava node.json com 600' '600' "$(stat -c '%a' "$NH/node.json")"
assert_contains 'enroll pede a API key' "$out" '--set-key'
assert_contains 'região vai aninhada em location, como o contrato documenta' \
  "$(cat "$TMP/curl-calls")" '"location":{"region":"sa-east-1","country":"BR"}'

# O pino da config assinada é gravado no enroll: o executor confere toda config contra ele.
assert_eq 'pinning-key gravada com 600' '600' "$(stat -c '%a' "$NH/pinning-key")"
assert_eq 'pinning-key tem a publicKey da config' 'CP-PUBLIC-KEY-PINNED' "$(cat "$NH/pinning-key")"

# Enroll repetido é idempotente: não reenrolla (token é de uso único) e não refaz request.
make_command curl 'echo "CHAMADA-INESPERADA" ; exit 1'
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" \
  --control-plane https://cp.test --token tok_outro 2>&1) || true
assert_contains 'enroll repetido não refaz request' "$out" 'já registrado'

# --set-key recusa chave sem prefixo e grava a boa com 600.
rm "$TMP/curl"  # nunca deve haver rede nesta etapa
out=$(printf 'chave-ruim\n' | NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --set-key 2>&1) &&
  { printf '[FAIL] chave sem prefixo foi aceita:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'chave sem enk_ recusada' "$out" 'chave inválida'
out=$(printf 'enk_abcdef0123456789\n' | NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --set-key 2>&1)
assert_eq 'api-key gravada com 600' '600' "$(stat -c '%a' "$NH/api-key")"

# --start com heartbeat impossível falha (curl stub que falha = rede impossível).
make_command curl 'exit 7'
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1) &&
  { printf '[FAIL] --start passou sem heartbeat:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'start falha sem heartbeat' "$out" 'heartbeat falhou'

# ------------------------------------------------- TOFU do pino no --start
# Caminho padrão: enroll sem pino (nó ainda sem chave, config sem publicKey) →
# o primeiro --start pina a chave pública DEPOIS de provar a credencial no heartbeat.
rm -rf "$TMP/node-home-tofu"; mkdir -p "$TMP/node-home-tofu"; chmod 700 "$TMP/node-home-tofu"
printf '%s\n' "$ENROLL_BODY" > "$TMP/node-home-tofu/node.json"; chmod 600 "$TMP/node-home-tofu/node.json"
printf 'enk_tofu0123456789ab\n' > "$TMP/node-home-tofu/api-key"; chmod 600 "$TMP/node-home-tofu/api-key"
make_curl
make_command docker 'case "$*" in *"ps --format"*) exit 1 ;; esac; exit 0'
out=$(NODE_HOME="$TMP/node-home-tofu" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1)
assert_contains 'start sem pino pina a chave pública (TOFU)' "$out" 'pinada; o executor recusará configs com outra chave'
assert_eq 'pino gravado com 600' '600' "$(stat -c '%a' "$TMP/node-home-tofu/pinning-key")"
assert_eq 'pino contém a publicKey da config' 'CP-PUBLIC-KEY-PINNED' "$(cat "$TMP/node-home-tofu/pinning-key")"

# Segundo --start NÃO re-pina: o pino sobrevive, mesmo que o CP devolva outra chave.
: > "$TMP/curl-calls"
CENARIO=ROTATED make_curl
out=$(NODE_HOME="$TMP/node-home-tofu" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1)
assert_not_contains 'start com pino existente não re-pina' "$out" 'pinada'
assert_eq 'pino original sobrevive à rotação do CP' 'CP-PUBLIC-KEY-PINNED' "$(cat "$TMP/node-home-tofu/pinning-key")"
assert_contains 'segundo start ainda autentica' "$out" 'Estado do nó no Control Plane: ACTIVE'

# --start quando nem o enroll nem a config têm publicKey: aviso claro, sem pino gravado.
rm -rf "$TMP/node-home-nokey"; mkdir -p "$TMP/node-home-nokey"; chmod 700 "$TMP/node-home-nokey"
printf '%s\n' "$ENROLL_BODY" > "$TMP/node-home-nokey/node.json"; chmod 600 "$TMP/node-home-nokey/node.json"
printf 'enk_tofu0123456789cd\n' > "$TMP/node-home-nokey/api-key"; chmod 600 "$TMP/node-home-nokey/api-key"
CENARIO=NOKEY make_curl
out=$(NODE_HOME="$TMP/node-home-nokey" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1)
assert_contains 'sem publicKey na config o start avisa e segue' "$out" 'config sem publicKey'
[[ -e "$TMP/node-home-nokey/pinning-key" ]] && {
  printf '[FAIL] pino gravado vazio ou lixo:\n' >&2; exit 1; }
PASS=$((PASS + 1)); printf '[PASS] %s\n' 'sem publicKey na config nenhum pino é gravado'

# --start sem credencial prévia falha antes de qualquer rede.
rm -rf "$TMP/node-home-vazio"; mkdir -p "$TMP/node-home-vazio"
make_command curl 'exit 0'
out=$(NODE_HOME="$TMP/node-home-vazio" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1) &&
  { printf '[FAIL] --start passou sem node.json:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'start sem enroll recusa' "$out" 'rode o enroll primeiro'

# ------------------------------------------------- --backup / --restore
# Backup de identidade completa: arquivo nasce 600, e o nome carrega o nodeId.
# O --backup grava no diretório corrente: o teste roda dentro de $TMP/cwd.
mkdir -p "$TMP/cwd"
backup_file=$(cd "$TMP/cwd" && NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --backup >/dev/null; find . -maxdepth 1 -name 'enois-node-nd_teste123-*.tar.gz' | head -n 1)
[[ -n "$backup_file" ]] || { printf '[FAIL] backup não encontrado em %s\n' "$TMP/cwd" >&2; exit 1; }
backup_file="$TMP/cwd/$backup_file"
assert_eq 'backup da identidade nasce com 600' '600' "$(stat -c '%a' "$backup_file")"

# Backup sem identidade completa falha antes de qualquer rede (curl nem existe aqui).
rm -rf "$TMP/node-home-metade"; mkdir -p "$TMP/node-home-metade"
printf '%s\n' "$ENROLL_BODY" > "$TMP/node-home-metade/node.json"
out=$(NODE_HOME="$TMP/node-home-metade" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --backup 2>&1) &&
  { printf '[FAIL] --backup passou sem api-key:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'backup sem api-key recusa' "$out" 'rode --set-key primeiro'

# Restore não pisa identidade existente — trocar de nó por engano é defeito grave.
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --restore "$backup_file" 2>&1) &&
  { printf '[FAIL] --restore passou com nó já registrado:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'restore com identidade presente recusa' "$out" 'restaurar por cima trocaria a identidade'

# Restore limpo devolve a mesma identidade (mesmo nodeId) com permissões corretas,
# e o heartbeat subsequente autentica — a identidade é a mesma, não uma nova.
rm -rf "$NH"; mkdir -p "$NH"; chmod 700 "$NH"
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --restore "$backup_file" 2>&1)
assert_contains 'restore devolve o mesmo nodeId' "$out" 'nd_teste123'
assert_eq 'node.json restaurado com 600' '600' "$(stat -c '%a' "$NH/node.json")"
assert_eq 'api-key restaurada com 600' '600' "$(stat -c '%a' "$NH/api-key")"
make_command curl "case \"\${@: -1}\" in */network/nodes/heartbeat) printf '%s' '$HB_BODY' ;; *) exit 22 ;; esac"
make_command docker 'case "$*" in *"ps --format"*) exit 1 ;; esac; exit 0'
out=$(NODE_HOME="$NH" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --start --control-plane https://cp.test 2>&1)
assert_contains 'nó restaurado autentica com a mesma identidade' "$out" 'Estado do nó no Control Plane: ACTIVE'

# ------------------------------------------------- install.sh --update / --rollback
# A troca de versão vive no bootstrap.sh e o install.sh DELEGA — dois caminhos
# de atualização seriam duas regras sobre o que o nó executa. Sem api-key, o
# bootstrap herda o modo e para ANTES de rede nenhuma (curl abaixo falha tudo:
# se a recusa dependesse de rede, a mensagem esperada não apareceria).
NHU="$TMP/node-update"; rm -rf "$NHU"; mkdir -p "$NHU"; chmod 700 "$NHU"
printf '%s\n' "$ENROLL_BODY" > "$NHU/node.json"; chmod 600 "$NHU/node.json"
make_command curl 'exit 7'
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --update 2>&1) &&
  { printf '[FAIL] update passou sem api-key:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'install --update delega ao bootstrap' "$out" 'Delegando ao bootstrap.sh'
assert_contains 'bootstrap herdou o modo e recusa sem chave antes de rede' "$out" 'grave a chave que o admin gerou'
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --rollback 2>&1) &&
  { printf '[FAIL] rollback passou sem api-key:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'install --rollback também delega' "$out" 'Delegando ao bootstrap.sh'

# ------------------------------------------------- bootstrap.sh
# Release simulado: os MESMOS arquivos do repo, tar ACHATADO com VERSION e
# SHA256SUMS de verdade — o formato exato que .github/workflows/slave-release.yml
# publica. A soma é gerada sobre o nome simples do arquivo, como no workflow.
release_fixtures() {  # release_fixtures <tag> <versao> — nomes iguais aos do workflow
  local tag="$1" versao="$2" f dir asset
  asset="enois-node-runtime-$tag.tar.gz"
  dir="$TMP/rel-$tag"
  mkdir -p "$dir"
  printf '%s\n' "$versao" > "$dir/VERSION"
  for f in docker-compose.yml Dockerfile install.sh bootstrap.sh node-runtime.sh node-doctor.sh; do
    cp "$NODE_DIR/$f" "$dir/$f"
  done
  tar -C "$dir" -czf "$TMP/$asset" \
    VERSION docker-compose.yml Dockerfile install.sh bootstrap.sh node-runtime.sh node-doctor.sh
  ( cd "$TMP" && sha256sum "$asset" > "$TMP/sums-${asset%.tar.gz}" )
}
release_fixtures 'slave-v1-qa@deadbeef' 'qa@deadbeef'
release_fixtures 'slave-v2-qa@feed0001' 'qa@feed0001'
release_fixtures 'slave-v9' '0.1.0'
# SHA256SUMS que não lista o asset — caso "sem linha para conferir".
printf '%s  enois-node-runtime-outra-coisa.tar.gz\n' "$(printf 'a%.0s' {1..64})" > "$TMP/sums-fake"

REL_TAG='slave-v1-qa@deadbeef'  # vazio = nenhum release publicado (fallback git)
REL_ASSET='enois-node-runtime-slave-v1-qa@deadbeef.tar.gz'  # nome real do asset no release
REL_DOWNLOAD_OK=1               # 0 = entrega um tar adulterado
REL_SUMS_OK=1                   # 0 = entrega sums sem a linha do asset
# O stub escreve em -o <arquivo> quando presente (o bootstrap baixa o tar e o
# SHA256SUMS com -o; sem isso o conteúdo contaminaria o stdout do parse).
bootstrap_curl() {
  cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"; out=/dev/stdout
args=("\$@")
for i in "\${!args[@]}"; do
  [ "\${args[\$i]}" = "-o" ] && out="\${args[\$((i+1))]}"
done
case "\$url" in
  *api.github.com/repos/*/releases*)
    if [ -n "$REL_TAG" ]; then printf '[{"tag_name":"%s"}]' "$REL_TAG" > "\$out"; else printf '[]' > "\$out"; fi ;;
  */download/*/enois-node-runtime-*.tar.gz)
    if [ "$REL_DOWNLOAD_OK" = "1" ]; then cat "$TMP/$REL_ASSET" > "\$out"; else printf 'conteudo-adulterado' > "\$out"; fi ;;
  */download/*/SHA256SUMS)
    if [ "$REL_SUMS_OK" = "1" ]; then cat "$TMP/sums-${REL_ASSET%.tar.gz}" > "\$out"; else cat "$TMP/sums-fake" > "\$out"; fi ;;
  */network/nodes/enroll) printf '%s' '$ENROLL_BODY' > "\$out" ;;
  */network/nodes/heartbeat) printf '%s' '$HB_BODY' > "\$out" ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_PIN' > "\$out" ;;
  */health) printf '%s' '{"status":"UP"}' > "\$out" ;;
  *) exit 22 ;;
esac
MOCK
  chmod +x "$TMP/curl"
}
# docker stub do bootstrap: build/inspect ok por padrão (DOCKER_INSPECT_FAIL=1
# simula a imagem anterior apagada da máquina); compose registrado com a tag.
DOCKER_INSPECT_FAIL=0
bootstrap_docker() {
  make_command docker 'case "$*" in
    build*) exit 0 ;;
    image\ inspect*) if [ "${DOCKER_INSPECT_FAIL:-0}" = "1" ]; then exit 74; fi; exit 0 ;;
    compose*) printf "%s | tag=%s\n" "$*" "${NODE_RUNTIME_TAG:-}" >> "$COMPOSE_CALLS"; exit 0 ;;
  esac; exit 0'
}
bootstrap_docker
: > "$TMP/compose-calls"; export COMPOSE_CALLS="$TMP/compose-calls"

NHB="$TMP/node-bootstrap"
bootstrap_run() {  # bootstrap_run <stdin> <args...> — roda com o NHB e os stubs
  local stdin="$1"; shift
  NODE_HOME="$NHB" NODE_WORK_DIR="$TMP/work" DOCKER_INSPECT_FAIL="$DOCKER_INSPECT_FAIL" \
    PATH="$TMP" /bin/bash "$NODE_DIR/bootstrap.sh" "$@" <<<"$stdin" 2>&1
}

# Fonte fora da lista fechada (qa|main) é recusa no parse, antes de rede nenhuma:
# o código do nó é o que a rede executa, não qualquer branch que a máquina aponte.
rm -rf "$NHB"
make_command curl 'exit 7'
out=$(bootstrap_run '' --control-plane https://cp.test --token tok --source trunk 2>&1) &&
  { printf '[FAIL] bootstrap aceitou fonte fora da lista:\n%s\n' "$out" >&2; exit 1; }
assert_contains "fonte fora da lista fechada recusa" "$out" "fonte desconhecida: 'trunk'"

# Fluxo completo: release oficial verificado → imagem local → enroll → chave → start.
REL_TAG='slave-v1-qa@deadbeef'; REL_ASSET='enois-node-runtime-slave-v1-qa@deadbeef.tar.gz'; REL_DOWNLOAD_OK=1; REL_SUMS_OK=1; bootstrap_curl
out=$(bootstrap_run 'enk_boot0123456789
' --control-plane https://cp.test --token tok_novo --region sa-east-1 --country BR --source qa) ||
  { printf '[FAIL] fluxo completo do bootstrap falhou:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'bootstrap resolve o release oficial' "$out" 'Release encontrado: slave-v1-qa@deadbeef'
assert_contains 'código obtido do release com origem' "$out" 'Código obtido: qa@deadbeef (origem: release:slave-v1-qa@deadbeef)'
assert_eq 'runtime-version é o commit da fonte' 'qa@deadbeef' "$(cat "$NHB/runtime-version")"
assert_eq 'runtime-version gravada com 600' '600' "$(stat -c '%a' "$NHB/runtime-version")"
assert_eq 'runtime-source é a fonte explícita' 'qa' "$(cat "$NHB/runtime-source")"
assert_eq 'URL do Control Plane gravada para updates futuros' 'https://cp.test' "$(cat "$NHB/control-plane")"
assert_contains 'enroll leva o token de provisionamento' "$(cat "$TMP/curl-calls")" '"provisionToken":"tok_novo"'
assert_contains 'enroll aninha a região em location' "$(cat "$TMP/curl-calls")" '"location":{"region":"sa-east-1","country":"BR"}'
assert_eq 'api-key colada gravada com 600' '600' "$(stat -c '%a' "$NHB/api-key")"
assert_contains 'compose sobe a imagem enois-node-runtime com a versão' \
  "$(cat "$TMP/compose-calls")" 'tag=qa-deadbeef'
assert_contains 'o @ da versão vira hífen na tag (não é válido em tag)' \
  "$(cat "$TMP/compose-calls")" 'tag=qa-deadbeef'
assert_contains 'heartbeat declara o commit construído, não uma constante' \
  "$(cat "$TMP/curl-calls")" '"runtimeVersion":"qa@deadbeef"'
assert_contains 'bootstrap encerra com o nó em operação' "$out" 'Nó em operação: qa@deadbeef'

# Pacote adulterado (sha divergente do SHA256SUMS publicado) é recusado e o
# runtime atual segue rodando — a conferência é contra o baixado, não de string.
REL_DOWNLOAD_OK=0; bootstrap_curl
out=$(bootstrap_run '' --update) &&
  { printf '[FAIL] update aceitou pacote adulterado:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'sha256 divergente recusa o pacote' "$out" 'sha256 não confere'
assert_eq 'runtime atual sobrevive ao pacote adulterado' 'qa@deadbeef' "$(cat "$NHB/runtime-version")"

# SHA256SUMS sem a linha deste asset: não há o que conferir, não roda.
REL_DOWNLOAD_OK=1; REL_SUMS_OK=0; bootstrap_curl
out=$(bootstrap_run '' --update) &&
  { printf '[FAIL] update aceitou sums sem a linha do asset:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'sums sem a linha do asset recusa' "$out" 'não lista enois-node-runtime-slave-v1-qa@deadbeef.tar.gz'

# VERSION fora do formato <fonte>@<sha8> recusa mesmo com sha válido: a versão
# é o que o painel usa para ver divergência de runtime entre nós.
dirbad="$TMP/rel-bad"; mkdir -p "$dirbad"
printf '%s\n' '0.1.0' > "$dirbad/VERSION"
for f in docker-compose.yml Dockerfile install.sh bootstrap.sh node-runtime.sh node-doctor.sh; do
  cp "$NODE_DIR/$f" "$dirbad/$f"
done
tar -C "$dirbad" -czf "$TMP/enois-node-runtime-slave-v9.tar.gz" \
  VERSION docker-compose.yml Dockerfile install.sh bootstrap.sh node-runtime.sh node-doctor.sh
( cd "$TMP" && sha256sum 'enois-node-runtime-slave-v9.tar.gz' > "$TMP/sums-slave-v9" )
REL_TAG='slave-v9'; REL_ASSET='enois-node-runtime-slave-v9.tar.gz'; REL_SUMS_OK=1; bootstrap_curl
out=$(bootstrap_run '' --update) &&
  { printf '[FAIL] update aceitou VERSION inválida:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'VERSION fora do formato recusa o release' "$out" "sem VERSION válido (esperado <fonte>@<sha8>, veio '0.1.0')"

# Sem release publicado, o fallback é o git na fonte de verdade explícita.
make_command git 'case "$*" in
  clone*) exit 0 ;;
  *fetch\ --quiet\ origin*) exit 0 ;;
  *checkout\ --quiet\ --force*) exit 0 ;;
  *rev-parse\ --short=8*) printf "cafe1234\n" ;;
  *) exit 1 ;;
esac'
REL_TAG=''; REL_ASSET=''; bootstrap_curl
out=$(bootstrap_run '' --update --source main) ||
  { printf '[FAIL] fallback git falhou:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'sem release cai para o git na fonte' "$out" 'Nenhum release do runtime publicado'
assert_eq 'versão do git é fonte@sha8' 'main@cafe1234' "$(cat "$NHB/runtime-version")"
assert_eq 'fonte explícita --source main gravada' 'main' "$(cat "$NHB/runtime-source")"
assert_eq 'o que rodava antes vai para o previous' 'qa@deadbeef' "$(cat "$NHB/runtime-version.previous")"
assert_contains 'compose sobe a imagem do commit do git' "$(cat "$TMP/compose-calls")" 'tag=main-cafe1234'

# --update com o MESMO commit: nada a fazer — sem build, sem reinício de container.
: > "$TMP/compose-calls"
out=$(bootstrap_run '' --update) ||
  { printf '[FAIL] update da mesma versão falhou:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'mesma versão não refaz nada' "$out" 'nada a fazer'
assert_eq 'nenhum container reiniciado na mesma versão' '0' "$(awk 'END{print NR}' "$TMP/compose-calls")"

# Update para release novo: constrói, empurra o anterior para .previous e reinicia.
REL_TAG='slave-v2-qa@feed0001'; REL_ASSET='enois-node-runtime-slave-v2-qa@feed0001.tar.gz'; bootstrap_curl
out=$(bootstrap_run '' --update) ||
  { printf '[FAIL] update para release novo falhou:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'update novo constrói a imagem do commit' "$out" 'Construindo enois-node-runtime:qa-feed0001'
assert_eq 'runtime-version avança para o novo commit' 'qa@feed0001' "$(cat "$NHB/runtime-version")"
assert_eq 'runtime anterior preservado no previous' 'main@cafe1234' "$(cat "$NHB/runtime-version.previous")"
assert_eq 'runtime-source segue a fonte do commit aplicado' 'qa' "$(cat "$NHB/runtime-source")"
assert_contains 'compose reinicia com a tag nova' "$(cat "$TMP/compose-calls")" 'tag=qa-feed0001'

# Rollback volta ao previous sem trocar a fonte de verdade.
out=$(bootstrap_run '' --rollback) ||
  { printf '[FAIL] rollback falhou:\n%s\n' "$out" >&2; exit 1; }
assert_eq 'rollback volta ao runtime anterior' 'main@cafe1234' "$(cat "$NHB/runtime-version")"
assert_eq 'rollback guarda o atual como próximo anterior' 'qa@feed0001' "$(cat "$NHB/runtime-version.previous")"
assert_eq 'rollback não troca a fonte de verdade' 'qa' "$(cat "$NHB/runtime-source")"
assert_contains 'compose reinicia com a imagem anterior' "$(cat "$TMP/compose-calls")" 'tag=main-cafe1234'

# Rollback sem previous: não há para onde voltar (antes de rede e docker).
NHV="$TMP/node-rollback-vazio"; rm -rf "$NHV"; mkdir -p "$NHV"; chmod 700 "$NHV"
printf '%s\n' "$ENROLL_BODY" > "$NHV/node.json"; chmod 600 "$NHV/node.json"
printf 'enk_rollback0123456\n' > "$NHV/api-key"; chmod 600 "$NHV/api-key"
out=$(NODE_HOME="$NHV" PATH="$TMP" /bin/bash "$NODE_DIR/bootstrap.sh" --rollback 2>&1) &&
  { printf '[FAIL] rollback sem anterior passou:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'rollback sem anterior recusa' "$out" 'não há para onde voltar'

# Rollback cuja imagem anterior não existe mais nesta máquina é recusado —
# "voltar" sem a imagem seria construir de novo exatamente o que se quis evitar.
printf '%s\n' 'qa@sumiu1234' > "$NHB/runtime-version.previous"
DOCKER_INSPECT_FAIL=1
out=$(bootstrap_run '' --rollback) &&
  { printf '[FAIL] rollback aceitou imagem ausente:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'rollback sem a imagem anterior recusa' "$out" 'não está mais nesta máquina'
assert_eq 'runtime atual sobrevive ao rollback impossível' 'main@cafe1234' "$(cat "$NHB/runtime-version")"
DOCKER_INSPECT_FAIL=0

# ---------------------------------------------------------------- node-doctor.sh

# Cenário completo e offline: credenciais em ordem; docker existe, mas o container
# não está rodando e não há Control Plane configurado — WARN, não FAIL.
NH2="$TMP/node-doctor-home"; rm -rf "$NH2"; mkdir -p "$NH2"; chmod 700 "$NH2"
printf '%s\n' "$ENROLL_BODY" > "$NH2/node.json"
printf 'enk_abcdef0123456789\n' > "$NH2/api-key"
chmod 600 "$NH2/api-key"
make_command docker 'case "$*" in *"ps --format"*) exit 1 ;; esac; exit 0'
out=$(NODE_HOME="$NH2" PATH="$TMP" /bin/bash "$NODE_DIR/node-doctor.sh" 2>&1) && doctor_rc=0 || doctor_rc=$?
exit_code="$doctor_rc"
printf '%s\n' "$out" > "$TMP/doctor-out"
assert_eq 'doctor sem FAIL sai 0' '0' "$exit_code"
assert_contains 'doctor lista o nó' "$out" 'nd_teste123'
assert_contains 'doctor avisa container ausente' "$out" '[WARN]'

# Sem node.json é FAIL (exit 1).
rm "$NH2/node.json"
out=$(NODE_HOME="$NH2" PATH="$TMP" /bin/bash "$NODE_DIR/node-doctor.sh" 2>&1) && doctor_rc=0 || doctor_rc=$?
exit_code="$doctor_rc"
printf '%s\n' "$out" > "$TMP/doctor-out"
assert_eq 'doctor sem node.json sai 1' '1' "$exit_code"
assert_contains 'doctor explica o que falta' "$out" 'install.sh'

# Doctor aceita --control-plane documentado e checa conectividade contra a URL.
printf '%s\n' "$ENROLL_BODY" > "$NH2/node.json"
make_curl
out=$(NODE_HOME="$NH2" PATH="$TMP" /bin/bash "$NODE_DIR/node-doctor.sh" --control-plane https://cp.test 2>&1) && doctor_rc=0 || doctor_rc=$?
assert_eq 'doctor com --control-plane sai 0' '0' "$doctor_rc"
assert_contains 'doctor checa conectividade da URL passada' "$out" 'Control Plane acessível'

# Doctor nunca imprime a chave em claro.
/bin/grep -Fq 'enk_abcdef0123456789' <<<"$out" && {
  printf '[FAIL] doctor vazou a API key\n' >&2; exit 1; }
PASS=$((PASS + 1)); printf '[PASS] %s\n' 'doctor não imprime a API key'

# 5b: cache de config vencido é WARN com idade e TTL — e o doctor tem de DIZER que o nó drena,
# porque senão o operador lê "vencida" e conclui que o nó está quebrado em vez de esperando.
printf '%s' '{"nodeId":"nd_teste123","formatVersion":3,"ttlSeconds":300}' > "$NH2/config.json"
printf '%s\n' "$(( $(date +%s) - 900 ))" > "$NH2/config-verified-at"
out=$(NODE_HOME="$NH2" PATH="$TMP" /bin/bash "$NODE_DIR/node-doctor.sh" 2>&1) && doctor_rc=0 || doctor_rc=$?
assert_eq 'doctor com cache vencido não falha (nó drena, não quebrou)' '0' "$doctor_rc"
assert_contains 'doctor reporta a config vencida com o TTL' "$out" 'Config vencida há 600s (TTL de 300s)'
assert_contains 'doctor diz o que o nó faz com isso' "$out" 'o nó drena até revalidar'

# Chaos: relógio atrás da verificação. A idade não é medível, e "vencida" seria diagnóstico errado:
# a causa é a hora da máquina, e o doctor tem de apontar para ela.
printf '%s\n' "$(( $(date +%s) + 600 ))" > "$NH2/config-verified-at"
out=$(NODE_HOME="$NH2" PATH="$TMP" /bin/bash "$NODE_DIR/node-doctor.sh" 2>&1) && doctor_rc=0 || doctor_rc=$?
assert_eq 'doctor com relógio atrás não falha' '0' "$doctor_rc"
assert_contains 'doctor aponta o relógio, não a config' "$out" 'Relógio atrás do instante da verificação'
assert_contains 'e manda conferir NTP' "$out" 'confira NTP/hora da máquina'
rm -f "$NH2/config.json" "$NH2/config-verified-at"

printf '\nTodos os cenários passaram (%d asserções).\n' "$PASS"
