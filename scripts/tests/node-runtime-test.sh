#!/usr/bin/env bash
# Testes do instalador e do diagnóstico do Node Runtime.
# Padrão de scripts/tests/dev-doctor-test.sh: PATH isolado com symlinks + stubs.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod read nproc awk date tar gzip cat; do
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

# ------------------------------------------------- --update / --rollback
# Precisa de identidade + chave da imagem; cosign e docker são stubs.
NHU="$TMP/node-update"; rm -rf "$NHU"; mkdir -p "$NHU"; chmod 700 "$NHU"
printf '%s\n' "$ENROLL_BODY" > "$NHU/node.json"; chmod 600 "$NHU/node.json"
printf 'enk_update0123456789\n' > "$NHU/api-key"; chmod 600 "$NHU/api-key"
printf '%s' 'IMAGE-PUBKEY' > "$NHU/image-pubkey"; chmod 600 "$NHU/image-pubkey"

make_command docker 'case "$*" in
  "pull "*) exit 0 ;;
  image\ inspect*) printf "%s\n" "${FAKE_DIGEST:-}" ;;
  compose*) printf "%s | imagem=%s\n" "$*" "${NODE_RUNTIME_IMAGE:-}" >> "$COMPOSE_CALLS"; exit 0 ;;
esac; exit 0'

# Update sem chave da imagem não roda (nada de verificação, nada de troca).
make_command cosign 'exit 1'
rm "$NHU/image-pubkey"
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --update 2>&1) &&
  { printf '[FAIL] update passou sem chave de imagem:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'update recusa sem chave da imagem' "$out" 'update sem verificação não roda'
printf '%s' 'IMAGE-PUBKEY' > "$NHU/image-pubkey"

# Update com assinatura válida: resolve o DIGEST e grava o que rodou.
: > "$TMP/compose-calls"; export COMPOSE_CALLS="$TMP/compose-calls"
make_command cosign 'case "$1" in verify) exit 0 ;; esac; exit 1'
FAKE_DIGEST='ghcr.io/rede-enois/enois-backend@sha256:aaa111'
out=$(NODE_HOME="$NHU" PATH="$TMP" FAKE_DIGEST="$FAKE_DIGEST" /bin/bash "$NODE_DIR/install.sh" --update --version v1.2.3 2>&1)
assert_contains 'update verifica e aplica' "$out" 'atualizando'
assert_eq 'runtime-image guarda o digest verificado' "$FAKE_DIGEST" "$(cat "$NHU/runtime-image")"
assert_contains 'compose sobe com o digest verificado' "$(cat "$TMP/compose-calls")" "$FAKE_DIGEST"

# Update recusado quando a assinatura não confere: runtime atual não muda.
make_command cosign 'exit 1'
FAKE_DIGEST='ghcr.io/rede-enois/enois-backend@sha256:bbb222'
out=$(NODE_HOME="$NHU" PATH="$TMP" FAKE_DIGEST="$FAKE_DIGEST" /bin/bash "$NODE_DIR/install.sh" --update --version v2.0.0 2>&1) &&
  { printf '[FAIL] update aceitou assinatura inválida:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'assinatura inválida recusa o update' "$out" 'atualização recusada, o runtime atual continua'
assert_eq 'runtime atual sobrevive à assinatura inválida' 'ghcr.io/rede-enois/enois-backend@sha256:aaa111' "$(cat "$NHU/runtime-image")"

# Rollback volta ao digest anterior — e também verifica a assinatura.
make_command cosign 'case "$1" in verify) exit 0 ;; esac; exit 1'
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --rollback 2>&1) &&
  { printf '[FAIL] rollback sem anterior deveria falhar:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'rollback sem anterior recusa' "$out" 'não há para onde voltar'

# Com um anterior gravado, o rollback volta e guarda o atual como anterior.
printf '%s\n' 'ghcr.io/rede-enois/enois-backend@sha256:old000' > "$NHU/runtime-image.previous"
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --rollback 2>&1)
assert_eq 'rollback aplica o digest anterior' 'ghcr.io/rede-enois/enois-backend@sha256:old000' "$(cat "$NHU/runtime-image")"
assert_eq 'rollback guarda o atual como novo anterior' 'ghcr.io/rede-enois/enois-backend@sha256:aaa111' "$(cat "$NHU/runtime-image.previous")"

# Rollback com anterior que não verifica mais é recusado.
make_command cosign 'exit 1'
printf '%s\n' 'ghcr.io/rede-enois/enois-backend@sha256:aaa111' > "$NHU/runtime-image.previous"
out=$(NODE_HOME="$NHU" PATH="$TMP" /bin/bash "$NODE_DIR/install.sh" --rollback 2>&1) &&
  { printf '[FAIL] rollback aceitou digest não verificado:\n%s\n' "$out" >&2; exit 1; }
assert_contains 'rollback recusa digest que não verifica' "$out" 'rollback recusado'

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

printf '\nTodos os cenários passaram (%d asserções).\n' "$PASS"
