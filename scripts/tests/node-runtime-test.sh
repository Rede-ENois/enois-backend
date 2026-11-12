#!/usr/bin/env bash
# Testes do instalador e do diagnóstico do Node Runtime.
# Padrão de scripts/tests/dev-doctor-test.sh: PATH isolado com symlinks + stubs.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod read nproc awk date tar gzip; do
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

# ---------------------------------------------------------------- install.sh

# curl simulado: primeiro call é o enroll, devolve nodeId; heartbeat devolve ACTIVE.
ENROLL_BODY='{"nodeId":"nd_teste123","status":"PENDING_APPROVAL","contract":"1","message":"ok"}'
HB_BODY='{"nodeId":"nd_teste123","status":"ACTIVE","profiles":["NODE_WORKER"],"serverTime":"now","mode":"NORMAL","acceptTasks":true}'
CONFIG_PIN='{"nodeId":"nd_teste123","operable":false,"signed":true,"signature":"x","algorithm":"Ed25519","publicKey":"CP-PUBLIC-KEY-PINNED","allowedTasks":[]}'
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/enroll) printf '%s' '$ENROLL_BODY' ;;
  */network/nodes/heartbeat) printf '%s' '$HB_BODY' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_PIN' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"

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

# Doctor nunca imprime a chave em claro.
/bin/grep -Fq 'enk_abcdef0123456789' <<<"$out" && {
  printf '[FAIL] doctor vazou a API key\n' >&2; exit 1; }
PASS=$((PASS + 1)); printf '[PASS] %s\n' 'doctor não imprime a API key'

printf '\nTodos os cenários passaram (%d asserções).\n' "$PASS"
