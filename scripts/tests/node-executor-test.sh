#!/usr/bin/env bash
# Testes do loop de execução do Node Runtime (node-runtime.sh).
# Mesmo padrão de node-runtime-test.sh: PATH isolado com symlinks + stubs, sem rede real.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod awk printf cat rm mktemp wc sha256sum tr \
            openssl sort paste base64 date mv; do
  location="$(command -v "$tool")"
  ln -s "$location" "$TMP/$tool" 2>/dev/null || true
done

# ------------------------------------------------- chave e assinatura de config
# A config que o nó aceita tem de vir assinada por Ed25519, e o runtime confere contra o
# pino. Aqui a linha canônica é montada À MÃO (não chamando o parser do runtime): se os
# dois usassem o mesmo código, os dois errariam juntos e o teste não provaria nada.
KEYDIR="$TMP/keys"; mkdir -p "$KEYDIR"
openssl genpkey -algorithm Ed25519 -out "$KEYDIR/cp.pem" 2>/dev/null
openssl pkey -in "$KEYDIR/cp.pem" -pubout -outform DER -out "$KEYDIR/cp-pub.der" 2>/dev/null
CP_PUB="$(base64 -w0 "$KEYDIR/cp-pub.der")"

sign_config() {
  # sign_config <canonical>: assinatura base64 do Control Plane sobre a linha canônica.
  local msg="$KEYDIR/msg"
  printf '%s' "$1" > "$msg"
  openssl pkeyutl -sign -inkey "$KEYDIR/cp.pem" -rawin -in "$msg" -out "$KEYDIR/sig.bin" 2>/dev/null
  base64 -w0 "$KEYDIR/sig.bin"
}

sign_limits_config() {
  # sign_limits_config <configVersion> <profiles-json> <cpu> <mem> <disk> <bw> <tasks-json> [term] [ttl]:
  # assina os campos que o signer cobre, na ordem de SignedFields.canonical() — o ttlSeconds entrou
  # no formato 3 logo antes do termo, e o termo continua por último.
  local cv="$1" profiles="$2" cpu="$3" mem="$4" disk="$5" bw="$6" tasks="$7" term="${8:-0}" ttl="${9:-300}"
  sign_config "3|$cv|nd_exec1|$profiles|$cpu|$mem|$disk|$bw|$tasks|2026-11-12T12:00:00Z|$ttl|$term"
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

NH="$TMP/node-home"
mkdir -p "$NH"; chmod 700 "$NH"
printf '%s\n' '{"nodeId":"nd_exec1","status":"ACTIVE"}' > "$NH/node.json"
printf 'enk_echoteste0123\n' > "$NH/api-key"; chmod 600 "$NH/api-key"
# Pino do install: é ESTA chave (X.509 base64) que assina as configs abaixo. O runtime
# verifica a assinatura contra ela, então as configs do harness têm de ser assinadas de
# verdade — antes eram `c2ln` (base64 de "sig") e passavam porque a verificação não existia.
printf '%s' "$CP_PUB" > "$NH/pinning-key"; chmod 600 "$NH/pinning-key"

CONFIG_SIGNED='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":3,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 3 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
HEARTBEAT_OK='{"nodeId":"nd_exec1","status":"ACTIVE","profiles":["NODE_WORKER"],"serverTime":"now","mode":"NORMAL","acceptTasks":true}'
HEARTBEAT_DRAIN='{"nodeId":"nd_exec1","status":"ACTIVE","profiles":["NODE_WORKER"],"serverTime":"now","mode":"DEGRADED","acceptTasks":false}'
TASK_ASSIGNED='{"tasks":[{"taskId":"tk_1","type":"ASYNC_JOB","attempt":2,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"idem-42","payload":"{\"job\":\"x\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'
TASK_FORBIDDEN='{"tasks":[{"taskId":"tk_2","type":"MEDIA_TRANSCODE","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"idem-7","payload":"{}","dataClass":"PUBLIC_MEDIA"}],"mode":"NORMAL","serverTime":"now"}'

# curl dirigido por cenário: registra toda chamada e responde conforme $CENARIO.
: > "$TMP/curl-calls"
make_curl() {
  cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_SIGNED' ;;
  */network/tasks/next) printf '%s' '${TASK_NEXT_BODY:-$TASK_ASSIGNED}' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
  chmod +x "$TMP/curl"
}

# Um ciclo só: o loop infinito roda sob timeout curto do sleep stub.
make_command() {
  local name="$1" contents="$2"
  printf '%s\n' '#!/bin/bash' "$contents" > "$TMP/$name"
  chmod +x "$TMP/$name"
}
make_command sleep 'exit 0'  # sem espera: o teste controla quantos ciclos rodam

run_one_cycle() {
  local scenario_next="${1:-}" home="${2:-$NH}"
  : > "$TMP/curl-calls"
  TASK_NEXT_BODY="$scenario_next" make_curl
  # Um ciclo só: carrega o script até antes do loop infinito e chama cycle().
  CONTROL_PLANE_URL=https://cp.test NODE_HOME="$home" PATH="$TMP" NODE_POLL_SECONDS=1 \
    /bin/bash -c "$(sed -n '1,/^while true; do$/p' "$NODE_DIR/node-runtime.sh" | sed '/^while true; do$/d'; echo cycle)" 2>&1 || true
}

results_posted() {
  /bin/grep -c 'network/tasks/.*/result' "$TMP/curl-calls" || true
}

# ------------------------------------------------- cenário 1: ciclo completo
out=$(run_one_cycle "$TASK_ASSIGNED")
assert_contains 'heartbeat feito com a identidade do nó' "$(cat "$TMP/curl-calls")" 'X-Node-Id: nd_exec1'
assert_contains 'config assinada consultada' "$(cat "$TMP/curl-calls")" '/network/nodes/me/config'
assert_contains 'poll da fila designada' "$(cat "$TMP/curl-calls")" '/network/tasks/next'
assert_contains 'tarefa autorizada vira DONE' "$out" 'DONE tk_1'
assert_eq 'resultado postado uma vez' '1' "$(results_posted)"
assert_contains 'resultado carrega a tentativa vigente' "$(cat "$TMP/curl-calls")" '"attempt":2'
assert_contains 'output ecoa o idempotencyKey (prova de ciclo)' "$(cat "$TMP/curl-calls")" '"idem-42"'

# ------------------------------------------------- cenário 2: tarefa fora da config
out=$(run_one_cycle "$TASK_FORBIDDEN")
assert_contains 'tarefa fora da config assinada é recusada' "$out" 'recusada (fora da config assinada): MEDIA_TRANSCODE'
assert_contains 'recusa postada como FAILED TASK_NOT_ALLOWED' "$(cat "$TMP/curl-calls")" 'TASK_NOT_ALLOWED'
assert_eq 'resultado (da recusa) postado uma vez' '1' "$(results_posted)"

# ------------------------------------------------- cenário 3: drenar é não pedir
# Stub dedicado (sem run_one_cycle, que recriaria o stub padrão).
run_custom() {
  local home="${1:-$NH}"
  : > "$TMP/curl-calls"
  CONTROL_PLANE_URL=https://cp.test NODE_HOME="$home" PATH="$TMP" NODE_POLL_SECONDS=1 \
    /bin/bash -c "$(sed -n '1,/^while true; do$/p' "$NODE_DIR/node-runtime.sh" | sed '/^while true; do$/d'; echo cycle)" 2>&1 || true
}

: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_DRAIN' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'modo DEGRADED drena: acceptTasks=false' "$out" 'acceptTasks=false'
assert_not_contains 'nó drenado não pede tarefa' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 4: config sem assinatura
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '{"nodeId":"nd_exec1","operable":true,"signed":false,"signature":null,"publicKey":null,"allowedTasks":["ASYNC_JOB"]}' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'config não assinada é recusada por padrão' "$out" 'config sem assinatura'
assert_not_contains 'sem config válida não há poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 5: publicKey fora do pino
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '{"nodeId":"nd_exec1","operable":true,"signed":true,"signature":"x","algorithm":"Ed25519","publicKey":"OUTRA-CHAVE","allowedTasks":["ASYNC_JOB"]}' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'config com publicKey divergente do pino é recusada' "$out" 'publicKey diferente do pino'
assert_not_contains 'sem config confiável não há poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 5b: assinatura que não confere com o pino
# O publicKey confere com o pino (a string foi copiada), mas a assinatura não é do pino:
# é exatamente o que um Control Plane comprometido ou um proxy no caminho entrega. Antes
# da verificação de Ed25519 isto PASSAVA — comparar só o campo provava que o atacante
# sabia copiar uma string.
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":11,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"c2ln","algorithm":"Ed25519","publicKey":"$CP_PUB"}' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'assinatura que não é do pino é recusada' "$out" 'assinatura da config não confere com o pino'
assert_not_contains 'assinatura inválida não vira permissão de poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 5c: config adulterada depois de assinada
# A assinatura é VÁLIDA, mas sobre outra lista de permissões: quem mexeu no corpo trocou
# allowedTasks por um tipo que o Control Plane nunca autorizou. Se o nó conferisse só
# "existe assinatura", cederia a permissão injetada. Aqui ele reconstitui a linha
# canônica do corpo recebido e vê que a assinatura não é dela.
TAMPERED_SIG="$(sign_limits_config 12 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 2)"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":12,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB","MEDIA_TRANSCODE"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"$TAMPERED_SIG","algorithm":"Ed25519","publicKey":"$CP_PUB"}' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'permissão injetada no corpo invalida a assinatura' "$out" 'assinatura da config não confere com o pino'
assert_not_contains 'config adulterada não habilita execução' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 5d: termo menor que o último aceito
# O nó já aceitou o termo 5 e depois recebe uma config do termo 2 — assinada de verdade
# pela chave pinada. É a assinatura legítima de um Control Plane que foi deposto e voltou a
# responder: prova de autoria não basta, o termo não pode andar para trás (doc 06 §4, regra 2).
NH_TERM="$TMP/node-home-termo"; rm -rf "$NH_TERM"; mkdir -p "$NH_TERM"; chmod 700 "$NH_TERM"
printf '%s\n' '{"nodeId":"nd_exec1","status":"ACTIVE"}' > "$NH_TERM/node.json"
printf 'enk_echoteste0123\n' > "$NH_TERM/api-key"; chmod 600 "$NH_TERM/api-key"
printf '%s' "$CP_PUB" > "$NH_TERM/pinning-key"; chmod 600 "$NH_TERM/pinning-key"
printf '5\n' > "$NH_TERM/term"
CONFIG_TERM_BAIXO='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":9,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 9 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_TERM_BAIXO' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NH_TERM")
assert_contains 'config de termo menor é recusada como autoridade deposta' "$out" 'autoridade deposta, recusada'
assert_not_contains 'autoridade deposta não consegue poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'
assert_eq 'termo recusado não rebaixa o último aceito' '5' "$(head -n 1 "$NH_TERM/term")"

# ------------------------------------------------- cenário 5e: termo maior é aceito
# O mesmo nó, agora com uma config do termo 3 sobre o último aceito (1): assinatura confere e
# o termo sobe, então o ciclo segue e a tarefa roda. O registro local acompanha o termo novo.
printf '1\n' > "$NH_TERM/term"
CONFIG_TERM_ALTO='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":10,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":3,"signed":true,"signature":"'"$(sign_limits_config 10 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 3)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_TERM_ALTO' ;;
  */network/tasks/next) printf '%s' '$TASK_ASSIGNED' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"tk_1","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NH_TERM")
assert_contains 'config de termo maior é aceita e a tarefa roda' "$out" 'DONE tk_1'
assert_eq 'termo aceito sobe o registro local' '3' "$(head -n 1 "$NH_TERM/term")"

# ------------------------------------------------- cenário 5f: o termo aceito sobe no heartbeat
# Regra 5 do doc 06 §4: o Control Plane só descobre que este nó viu um mando MAIOR que o dele
# se o nó contar o termo. Quem pergunta é o heartbeat, então o termo vai no corpo dele — a
# config é resposta do servidor, não canal de subida. E nó sem termo aceito não manda o campo:
# mandar zero afirmaria "vi o termo zero", que não é a mesma coisa que não ter visto nada.
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
printf '4\n' > "$NH_TERM/term"
out=$(run_custom "$NH_TERM")
assert_contains 'heartbeat reporta o termo aceito' "$(cat "$TMP/curl-calls")" '"observedTerm":4'
rm -f "$NH_TERM/term"
out=$(run_custom "$NH_TERM")
assert_not_contains 'nó sem termo aceito não inventa termo no heartbeat' "$(cat "$TMP/curl-calls")" 'observedTerm'

# ------------------------------------------------- cenário 6: credencial nunca em log
out=$(run_one_cycle "$TASK_ASSIGNED")
assert_not_contains 'executor não imprime a API key' "$out" 'enk_echoteste0123'

# ------------------------------------------------- cenário 7: sem pino, sem config confiável
# Nó enrolado antes do pinning existir (fluxo antigo): a recusa é explícita e não há poll.
rm -rf "$TMP/node-home-sempino"; mkdir -p "$TMP/node-home-sempino"; chmod 700 "$TMP/node-home-sempino"
printf '%s\n' '{"nodeId":"nd_exec1","status":"ACTIVE"}' > "$TMP/node-home-sempino/node.json"
printf 'enk_echoteste0123\n' > "$TMP/node-home-sempino/api-key"; chmod 600 "$TMP/node-home-sempino/api-key"
: > "$TMP/curl-calls"
out=$(run_one_cycle '' "$TMP/node-home-sempino")
assert_contains 'sem pino a config é recusada com instrução' "$out" 'sem pinning-key'
assert_not_contains 'sem pino não há poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'



# ------------------------------------------------- cenário 8: MEDIA_TRANSCODE executado de verdade
# O nó baixa a origem, roda a receita do Control Plane (MediaTranscodeSpec) e envia
# o resultado; declara o que o Control Plane MEDIU na resposta do PUT.
MP="$TMP/media-probe"; rm -rf "$MP"; mkdir -p "$MP"
printf '%s' 'FAKE-MP4-BYTES' > "$MP/out.bin"

TASK_MEDIA='{"tasks":[{"taskId":"tk_9","type":"MEDIA_TRANSCODE","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"media:abc","payload":"{\"mediaId\":\"md_1\",\"sourceKey\":\"u/md_1/file.mp4\",\"mimeType\":\"video/mp4\",\"sourceBytes\":1234,\"sourceSha256\":\"aa11\",\"resultKey\":\"md_1/file.transcoded.mp4\",\"resultBudgetBytes\":9999}","dataClass":"PUBLIC_MEDIA"}],"mode":"NORMAL","serverTime":"now"}'

CONFIG_MEDIA='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":4,"profiles":["NODE_MEDIA"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["MEDIA_TRANSCODE"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 4 '["NODE_MEDIA"]' 4 8192 51200 100 '["MEDIA_TRANSCODE"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'

# ffmpeg de prova: registra argv e escreve o "arquivo transcodificado".
make_command ffmpeg "printf '%s\n' \"\$*\" >> '$MP/ffmpeg-args'
out=\"\${@: -1}\"; out=\"\${out#file:}\"; printf '%s' 'FAKE-MP4-BYTES' > \"\$out\"; exit 0"

: > "$TMP/curl-calls"; : > "$MP/ffmpeg-args"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_MEDIA' ;;
  */network/tasks/next) printf '%s' '$TASK_MEDIA' ;;
  */network/media/md_1/source) printf '%s' 'SOURCE-BYTES' ;;
  */network/media/md_1/result) printf '%s' '{"status":"STORED","resultKey":"md_1/file.transcoded.mp4","resultBytes":13,"resultSha256":"bb22cc33"}' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom)
assert_contains 'transcode baixa a origem designada' "$(cat "$TMP/curl-calls")" '/network/media/md_1/source'
assert_contains 'transcode envia o resultado ao Control Plane' "$(cat "$TMP/curl-calls")" '/network/media/md_1/result'
assert_contains 'ffmpeg roda com o filtro de escala do Control Plane' "$(cat "$MP/ffmpeg-args")" "min(854,iw)"
assert_contains 'ffmpeg usa o encoder fixo (libx264, crf 28)' "$(cat "$MP/ffmpeg-args")" '-c:v libx264 -preset veryfast -crf 28'
assert_contains 'ffmpeg confina protocolo a arquivo local' "$(cat "$MP/ffmpeg-args")" '-protocol_whitelist file'
assert_contains 'mp4 pede moov na frente (faststart)' "$(cat "$MP/ffmpeg-args")" '-movflags +faststart'
assert_contains 'resultado declara as medidas do Control Plane' "$(cat "$TMP/curl-calls")" '"resultSha256":"bb22cc33"'
assert_contains 'resultado declara o tamanho medido' "$(cat "$TMP/curl-calls")" '"resultBytes":13'
assert_contains 'task é concluída' "$out" 'transcode DONE tk_9'
assert_eq 'um único resultado postado' '1' "$(results_posted)"

# O diretório de trabalho do nó não sobrevive ao ciclo.
assert_eq 'temporários do transcode apagados' '0' "$(find "$NH" -maxdepth 1 -name 'work-task-*' | wc -l | tr -d ' ')"

# ------------------------------------------------- cenário 9: sem ffmpeg, recusa explícita
rm -f "$TMP/ffmpeg"
: > "$TMP/curl-calls"
out=$(run_custom)
assert_contains 'sem ffmpeg a tarefa falha com código próprio' "$(cat "$TMP/curl-calls")" 'TASK_EXECUTOR_MISSING'
assert_not_contains 'sem ffmpeg não baixa a origem' "$(cat "$TMP/curl-calls")" '/network/media/md_1/source'


# ------------------------------------------------- cenário 10: READ_QUERY (réplica de leitura)
# O nó percorre o catálogo por cursor, lê o corpo de cada item, guarda e confirma
# com versão+hash. Item já em dia (version conhecida) volta changed=false e não é
# rebaixado — nem reenviado.
NR="$TMP/node-replica"; rm -rf "$NR"; mkdir -p "$NR"; chmod 700 "$NR"
cp "$NH/node.json" "$NR/node.json"; cp "$NH/api-key" "$NR/api-key"
cp "$NH/pinning-key" "$NR/pinning-key"

CONFIG_REPLICA='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":5,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":2,"memoryMb":4096,"diskMb":20480,"bandwidthMbps":50},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 5 '["NODE_READ_REPLICA"]' 2 4096 20480 50 '["READ_QUERY"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
TASK_REPLICA='{"tasks":[{"taskId":"tk_r1","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:PROFILE","payload":"{\"kind\":\"PROFILE\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'

# Duas páginas: a primeira tem 2 perfis (um já em dia), a segunda fecha o catálogo.
# O stub de conteúdo responde changed=false quando a query traz version=igual.
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_REPLICA' ;;
  */network/tasks/next) printf '%s' '$TASK_REPLICA' ;;
  */network/replica/PROFILE\?limit=100)
    printf '%s' '{"kind":"PROFILE","items":["ana","bia"],"hasMore":true,"nextAfterAt":"2026-11-10T10:00:00Z","nextAfterId":"u2"}' ;;
  */network/replica/PROFILE*afterAt=*)
    printf '%s' '{"kind":"PROFILE","items":["caio"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u3"}' ;;
  */network/content/PROFILE/ana)
    printf '%s' '{"kind":"PROFILE","contentId":"ana","version":3,"contentHash":"h-ana","changed":true,"body":"{\\"username\\":\\"ana\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/content/PROFILE/bia)
    printf '%s' '{"kind":"PROFILE","contentId":"bia","version":4,"contentHash":"h-bia","changed":true,"body":"{\\"username\\":\\"bia\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/content/PROFILE/caio)
    printf '%s' '{"kind":"PROFILE","contentId":"caio","version":1,"contentHash":"h-caio","changed":true,"body":"{\\"username\\":\\"caio\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/content/PROFILE/*/synced) printf '%s' '{"contentId":"x","status":"SYNCED"}' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NR")
assert_contains 'réplica percorre o catálogo' "$(cat "$TMP/curl-calls")" '/network/replica/PROFILE?limit=100'
assert_contains 'réplica usa o cursor devolvido (nunca offset)' "$(cat "$TMP/curl-calls")" 'afterAt=2026-11-10T10:00:00Z'
assert_contains 'réplica lê o corpo de cada id' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana'
assert_contains 'réplica confirma o que guardou' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana/synced'
assert_contains 'confirmação leva versão e hash' "$(cat "$TMP/curl-calls")" '{"sourceVersion":3,"contentHash":"h-ana"}'
assert_eq 'corpo guardado no disco do nó' '{"username":"ana"}' "$(cat "$NR/replica/PROFILE/ana.json")"
assert_eq 'versão guardada ao lado do corpo' '3' "$(cat "$NR/replica/PROFILE/ana.version")"
assert_contains 'travessia concluída é reportada' "$out" 'travessia concluída'
assert_contains 'resultado declara complete=true' "$(cat "$TMP/curl-calls")" '"complete":true'
assert_eq 'cursor limpo ao concluir' '' "$(cat "$NR/replica/cursor-PROFILE")"

# Segunda rodada: com a versão guardada, o nó pergunta version= e o Control Plane
# responde changed=false — nada é rebaixado nem reconfirmado.
: > "$TMP/curl-calls"
out=$(run_custom "$NR")
assert_contains 'nó pergunta a versão que já tem' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana?version=3'
assert_not_contains 'item em dia não é reconfirmado' "$(cat "$TMP/curl-calls")" 'ana/synced'
assert_eq 'corpo em dia permanece intacto' '{"username":"ana"}' "$(cat "$NR/replica/PROFILE/ana.json")"

# ------------------------------------------------- cenário 11: réplica sem kind no payload
TASK_REPLICA_BAD='{"tasks":[{"taskId":"tk_r2","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:x","payload":"{}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_REPLICA' ;;
  */network/tasks/next) printf '%s' '$TASK_REPLICA_BAD' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"FAILED","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NR")
assert_contains 'réplica sem kind falha com código próprio' "$(cat "$TMP/curl-calls")" 'TASK_PAYLOAD_INVALID'
assert_not_contains 'sem kind não consulta catálogo' "$(cat "$TMP/curl-calls")" '/network/replica/'


# ------------------------------------------------- cenário 12: agregado que sumiu da origem
# 404 na origem (perfil banido / comunidade excluída) NÃO é falha de leitura: o nó
# descarta o corpo guardado. Manter faria o nó servir o que a vitrine esconde.
printf '%s' '{"username":"dora"}' > "$NR/replica/PROFILE/dora.json"
printf '2\n' > "$NR/replica/PROFILE/dora.version"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_REPLICA' ;;
  */network/tasks/next) printf '%s' '$TASK_REPLICA' ;;
  */network/replica/PROFILE*)
    printf '%s' '{"kind":"PROFILE","items":["dora"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u3"}' ;;
  */network/content/PROFILE/dora*)
    [[ "\$*" == *"-w"* ]] && printf '\n404' || exit 22 ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NR")
assert_eq 'agregado que sumiu é descartado' '0' "$(find "$NR/replica/PROFILE" -name 'dora*' | wc -l | tr -d ' ')"
assert_contains 'remoção é reportada no resultado' "$(cat "$TMP/curl-calls")" '"removed":1'
assert_not_contains 'agregado removido não é confirmado como sincronizado' "$(cat "$TMP/curl-calls")" 'dora/synced'

# ------------------------------------------------- cenário 13: EDGE_CACHE (feed de mudanças)
# O nó lê o feed pelo cursor que guardou, baixa só o que mudou pelo mesmo caminho
# autorizado da borda, guarda corpo+versão e confirma /synced. O feed não carrega
# corpo: sem baixar, a entrada ficaria velha até o TTL.
NE="$TMP/node-edge"; rm -rf "$NE"; mkdir -p "$NE"; chmod 700 "$NE"
cp "$NH/node.json" "$NE/node.json"; cp "$NH/api-key" "$NE/api-key"
cp "$NH/pinning-key" "$NE/pinning-key"

CONFIG_EDGE='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":6,"profiles":["NODE_EDGE"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":200},"allowedTasks":["EDGE_CACHE","EDGE_DELIVER"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 6 '["NODE_EDGE"]' 4 8192 51200 200 '["EDGE_CACHE","EDGE_DELIVER"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
TASK_EDGE='{"tasks":[{"taskId":"tk_e1","type":"EDGE_CACHE","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"edge:cache","payload":"{}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'

: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_EDGE' ;;
  */network/content/changes?cursor=0\&limit=200)
    printf '%s' '{"reset":false,"cursor":0,"nextSeq":2,"hasMore":false,"changes":[{"seq":1,"kind":"PROFILE","contentId":"ana","version":3,"contentHash":"h-ana","changedAt":"2026-11-11T10:00:00Z"},{"seq":2,"kind":"COMMUNITY","contentId":"cm_1","version":1,"contentHash":"h-cm","changedAt":"2026-11-11T10:01:00Z"}]}' ;;
  */network/content/PROFILE/ana/synced) printf '%s' '{"contentId":"ana","status":"SYNCED"}' ;;
  */network/content/COMMUNITY/cm_1/synced) printf '%s' '{"contentId":"cm_1","status":"SYNCED"}' ;;
  */network/content/PROFILE/ana*)
    printf '%s' '{"kind":"PROFILE","contentId":"ana","version":3,"contentHash":"h-ana","changed":true,"body":"{\\"username\\":\\"ana\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/content/COMMUNITY/cm_1*)
    printf '%s' '{"kind":"COMMUNITY","contentId":"cm_1","version":1,"contentHash":"h-cm","changed":true,"body":"{\\"id\\":\\"cm_1\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_contains 'borda lê o feed pelo cursor do nó' "$(cat "$TMP/curl-calls")" '/network/content/changes?cursor=0&limit=200'
assert_contains 'borda baixa o corpo da mudança' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana'
assert_contains 'borda confirma o que guardou' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana/synced'
assert_contains 'confirmação leva versão e hash' "$(cat "$TMP/curl-calls")" '{"sourceVersion":3,"contentHash":"h-ana"}'
assert_eq 'corpo do perfil guardado no cache' '{"username":"ana"}' "$(cat "$NE/edge/PROFILE/ana.json")"
assert_eq 'versão guardada ao lado do corpo' '3' "$(cat "$NE/edge/PROFILE/ana.version")"
assert_eq 'corpo da comunidade guardado no cache' '{"id":"cm_1"}' "$(cat "$NE/edge/COMMUNITY/cm_1.json")"
assert_eq 'cursor avançou para a última mudança' '2' "$(cat "$NE/edge/cursor")"
assert_contains 'resultado declara complete=true' "$(cat "$TMP/curl-calls")" '"complete":true'
assert_eq 'um único resultado postado' '1' "$(results_posted)"

# ------------------------------------------------- cenário 14: feed podado (reset)
# Cursor atrás da janela retida: o que não foi lido já não existe para reler.
# Descartar o cache e recomeçar do zero é o único caminho que não deixa o nó
# servindo conteúdo velho achando que está em dia.
mkdir -p "$NE/edge/PROFILE"
printf '%s' '{"username":"velho"}' > "$NE/edge/PROFILE/velho.json"
printf '9\n' > "$NE/edge/PROFILE/velho.version"
printf '4\n' > "$NE/edge/cursor"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_EDGE' ;;
  */network/content/changes?cursor=4\&limit=200)
    printf '%s' '{"reset":true,"cursor":4,"nextSeq":4,"hasMore":false,"changes":[]}' ;;
  */network/content/changes?cursor=0\&limit=200)
    printf '%s' '{"reset":false,"cursor":0,"nextSeq":7,"hasMore":false,"changes":[{"seq":7,"kind":"PROFILE","contentId":"novo","version":1,"contentHash":"h-novo","changedAt":"2026-11-11T11:00:00Z"}]}' ;;
  */network/content/PROFILE/novo/synced) printf '%s' '{"contentId":"novo","status":"SYNCED"}' ;;
  */network/content/PROFILE/novo*)
    printf '%s' '{"kind":"PROFILE","contentId":"novo","version":1,"contentHash":"h-novo","changed":true,"body":"{\\"username\\":\\"novo\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_contains 'reset é reconhecido como recarga, não como "nada mudou"' "$out" 'cache descartado'
assert_eq 'cache antigo descartado no reset' '0' "$(find "$NE/edge/PROFILE" -name 'velho*' | wc -l | tr -d ' ')"
assert_eq 'recarga grava o que veio depois do reset' '{"username":"novo"}' "$(cat "$NE/edge/PROFILE/novo.json")"
assert_eq 'cursor final é o da mudança processada' '7' "$(cat "$NE/edge/cursor")"
assert_contains 'reset não gira: feed é pedido duas vezes (4 e 0)' "$(cat "$TMP/curl-calls")" 'changes?cursor=0&limit=200'

# ------------------------------------------------- cenário 15: agregado removido na borda
# Remoção não vira linha no feed: o nó descobre revalidando e recebe 404. Manter o
# corpo guardado faria a borda servir o que a vitrine esconde.
mkdir -p "$NE/edge/PROFILE"
printf '%s' '{"username":"gone"}' > "$NE/edge/PROFILE/gone.json"
printf '2\n' > "$NE/edge/PROFILE/gone.version"
printf '5\n' > "$NE/edge/cursor"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_EDGE' ;;
  */network/content/changes?*)
    printf '%s' '{"reset":false,"cursor":5,"nextSeq":6,"hasMore":false,"changes":[{"seq":6,"kind":"PROFILE","contentId":"gone","version":3,"contentHash":"h-gone","changedAt":"2026-11-11T12:00:00Z"}]}' ;;
  */network/content/PROFILE/gone*)
    [[ "\$*" == *"-w"* ]] && printf '\n404' || exit 22 ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_eq 'entrada removida é descartada da borda' '0' "$(find "$NE/edge/PROFILE" -name 'gone*' | wc -l | tr -d ' ')"
assert_contains 'remoção é contada no resultado' "$(cat "$TMP/curl-calls")" '"removed":1'
assert_not_contains 'agregado removido não é confirmado como sincronizado' "$(cat "$TMP/curl-calls")" 'gone/synced'

# ------------------------------------------------- cenário 16: EDGE_DELIVER em dia (cache)
# A entrega NÃO é servir HTTP (o nó não expõe porta): é o nó atestar que a entrada do
# cache dele está na versão da origem agora — revalida por ?version=, reconfirma /synced
# e responde com veredicto. Em dia, nenhum corpo é rebaixado: o que se prova é o estado.
TASK_DELIVER='{"tasks":[{"taskId":"tk_d1","type":"EDGE_DELIVER","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"deliver:x","payload":"{\"kind\":\"PROFILE\",\"contentId\":\"ana\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'

mkdir -p "$NE/edge/PROFILE"
printf '%s' '{"username":"ana"}' > "$NE/edge/PROFILE/ana.json"
printf '3\n' > "$NE/edge/PROFILE/ana.version"
printf '9\n' > "$NE/edge/cursor"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_DELIVER' ;;
  */network/content/PROFILE/ana/synced) printf '%s' '{"contentId":"ana","status":"SYNCED"}' ;;
  */network/content/PROFILE/ana*)
    printf '%s' '{"kind":"PROFILE","contentId":"ana","version":3,"contentHash":"h-ana","changed":false,"body":null}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_contains 'entrega pergunta pela versão que o nó tem' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana?version=3'
assert_contains 'entrega em dia reconfirma o que tem' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana/synced'
assert_contains 'veredicto positivo declara versão e hash' "$(cat "$TMP/curl-calls")" '"version":3,"contentHash":"h-ana","deliverable":true'
assert_contains 'veredicto declara que veio do cache' "$(cat "$TMP/curl-calls")" '"source":"cache"'
assert_eq 'corpo em dia não é rebaixado' '{"username":"ana"}' "$(cat "$NE/edge/PROFILE/ana.json")"

# ------------------------------------------------- cenário 17: EDGE_DELIVER rebaixa quando o cache ficou velho
# Versão diferente da guardada: o nó baixa o corpo novo, guarda ANTES de confirmar e
# só então responde o veredicto — atestar o que não está em disco é declaração sem lastro.
mkdir -p "$NE/edge/PROFILE"
printf '%s' '{"username":"ana","old":true}' > "$NE/edge/PROFILE/ana.json"
printf '3\n' > "$NE/edge/PROFILE/ana.version"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_DELIVER' ;;
  */network/content/PROFILE/ana/synced) printf '%s' '{"contentId":"ana","status":"SYNCED"}' ;;
  */network/content/PROFILE/ana*)
    printf '%s' '{"kind":"PROFILE","contentId":"ana","version":4,"contentHash":"h-ana4","changed":true,"body":"{\\"username\\":\\"ana\\",\\"new\\":true}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_contains 'cache velho é rebaixado da origem' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE/ana?version=3'
assert_contains 'confirmação da versão nova leva o hash novo' "$(cat "$TMP/curl-calls")" '{"sourceVersion":4,"contentHash":"h-ana4"}'
assert_eq 'corpo novo guardado antes do veredicto' '{"username":"ana","new":true}' "$(cat "$NE/edge/PROFILE/ana.json")"
assert_eq 'versão nova guardada ao lado do corpo' '4' "$(cat "$NE/edge/PROFILE/ana.version")"
assert_contains 'veredicto declara a origem do corpo (refetched)' "$(cat "$TMP/curl-calls")" '"source":"refetched"'
assert_contains 'veredicto positivo carrega a versão rebaixada' "$(cat "$TMP/curl-calls")" '"version":4,"contentHash":"h-ana4","deliverable":true'

# ------------------------------------------------- cenário 18: EDGE_DELIVER de agregado removido
# 404 na origem: entregar é impossível em qualquer nó. Descartar a entrada local e
# responder veredicto negativo é verdade de capacidade — não é falha do executor.
mkdir -p "$NE/edge/PROFILE"
printf '%s' '{"username":"sumido"}' > "$NE/edge/PROFILE/sumido.json"
printf '2\n' > "$NE/edge/PROFILE/sumido.version"
TASK_DELIVER_GONE='{"tasks":[{"taskId":"tk_d2","type":"EDGE_DELIVER","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"deliver:y","payload":"{\"kind\":\"PROFILE\",\"contentId\":\"sumido\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_DELIVER_GONE' ;;
  */network/content/PROFILE/sumido*)
    [[ "\$*" == *"-w"* ]] && printf '\n404' || exit 22 ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_eq 'entrega de agregado removido descarta a entrada' '0' "$(find "$NE/edge/PROFILE" -name 'sumido*' | wc -l | tr -d ' ')"
assert_contains 'veredicto negativo por remoção da origem' "$(cat "$TMP/curl-calls")" '"deliverable":false,"reason":"CONTENT_GONE"'
assert_not_contains 'agregado removido não é confirmado como sincronizado' "$(cat "$TMP/curl-calls")" 'sumido/synced'

# ------------------------------------------------- cenário 19: EDGE_DELIVER não é mais eco de ciclo
# O payload sem kind/contentId falha com código próprio — prova de que o tipo saiu do
# `*)` que ecoava o idempotencyKey e passou a ter executor de verdade.
TASK_DELIVER_BAD='{"tasks":[{"taskId":"tk_d3","type":"EDGE_DELIVER","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"deliver:z","payload":"{}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_EDGE' ;;
  */network/tasks/next) printf '%s' '$TASK_DELIVER_BAD' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"FAILED","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NE")
assert_contains 'entrega sem alvo no payload falha com código próprio' "$(cat "$TMP/curl-calls")" 'TASK_PAYLOAD_INVALID'
assert_not_contains 'payload inválido não consulta a origem' "$(cat "$TMP/curl-calls")" '/network/content/PROFILE'
assert_not_contains 'payload inválido não vira eco de ciclo de vida' "$(cat "$TMP/curl-calls")" '"echo":"deliver:z"'


# ------------------------------------------------- cenário 20: teto de disco declarado na borda
# O participante declara 1 MB (limits.diskMb) e a rede respeita: o cache de borda
# evicta a entrada mais antiga para caber a nova, em vez de crescer sem limite. O
# que foi evictado sai do disco E da contagem; o resto continua servindo.
ND="$TMP/node-disk"; rm -rf "$ND"; mkdir -p "$ND"; chmod 700 "$ND"
cp "$NH/node.json" "$ND/node.json"; cp "$NH/api-key" "$ND/api-key"
cp "$NH/pinning-key" "$ND/pinning-key"
# 1 MB de teto. O corpo novo tem ~1.3 KB; as duas entradas antigas somam ~2 KB.
CONFIG_DISK='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":7,"profiles":["NODE_EDGE"],"limits":{"cpuCores":2,"memoryMb":2048,"diskMb":1,"bandwidthMbps":50},"allowedTasks":["EDGE_CACHE"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 7 '["NODE_EDGE"]' 2 2048 1 50 '["EDGE_CACHE"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
TASK_EDGE_DISK='{"tasks":[{"taskId":"tk_e2","type":"EDGE_CACHE","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"edge:disk","payload":"{}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}'
# Enche o cache até passar de 1 MB: 3 entradas de 400 KB cada.
BIG=$(printf 'x%.0s' $(seq 1 409600))
mkdir -p "$ND/edge/PROFILE"
printf '%s' "$BIG" > "$ND/edge/PROFILE/velho1.json"; printf '1\n' > "$ND/edge/PROFILE/velho1.version"
printf '%s' "$BIG" > "$ND/edge/PROFILE/velho2.json"; printf '2\n' > "$ND/edge/PROFILE/velho2.version"
printf '%s' "$BIG" > "$ND/edge/PROFILE/velho3.json"; printf '3\n' > "$ND/edge/PROFILE/velho3.version"
# Idades distintas: velho1 é o mais antigo e deve sair primeiro.
touch -d '2026-01-01 00:00:00' "$ND/edge/PROFILE/velho1.json"
touch -d '2026-01-02 00:00:00' "$ND/edge/PROFILE/velho2.json"
touch -d '2026-01-03 00:00:00' "$ND/edge/PROFILE/velho3.json"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_DISK' ;;
  */network/tasks/next) printf '%s' '$TASK_EDGE_DISK' ;;
  */network/content/changes?*)
    printf '%s' '{"reset":false,"cursor":0,"nextSeq":1,"hasMore":false,"changes":[{"seq":1,"kind":"PROFILE","contentId":"novo","version":1,"contentHash":"h-novo","changedAt":"2026-11-11T12:00:00Z"}]}' ;;
  */network/content/PROFILE/novo/synced) printf '%s' '{"contentId":"novo","status":"SYNCED"}' ;;
  */network/content/PROFILE/novo*)
    printf '%s' '{"kind":"PROFILE","contentId":"novo","version":1,"contentHash":"h-novo","changed":true,"body":"{\\"username\\":\\"novo\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$ND")
assert_eq 'entrada mais antiga evictada (mtime) para caber a nova' '0' "$(find "$ND/edge/PROFILE" -name 'velho1*' | wc -l | tr -d ' ')"
assert_eq 'entrada mais nova do cache sobrevive' '1' "$(find "$ND/edge/PROFILE" -name 'velho3.json' | wc -l | tr -d ' ')"
assert_eq 'entrada nova é gravada depois de abrir espaço' '{"username":"novo"}' "$(cat "$ND/edge/PROFILE/novo.json")"
assert_contains 'resultado declara a evicção' "$(cat "$TMP/curl-calls")" '"evicted":1'
assert_not_contains 'entrada que coube não é contada como perdida' "$(cat "$TMP/curl-calls")" '"dropped":1'
assert_eq 'conteúdo guardado cabe no teto declarado' '1' "$(if (( $(stat -c %s "$ND/edge/PROFILE"/*.json | awk '{s+=$1} END {print s}') <= 1048576 )); then echo 1; else echo 0; fi)"

# ------------------------------------------------- cenário 21: sem teto declarado, nó não recusa gravar
# diskMb=0 é "não declarou limite", não "não pode gravar nada": o nó não pode parar
# de servir por causa de um campo ausente na config.
NC="$TMP/node-nocap"; rm -rf "$NC"; mkdir -p "$NC"; chmod 700 "$NC"
cp "$NH/node.json" "$NC/node.json"; cp "$NH/api-key" "$NC/api-key"
cp "$NH/pinning-key" "$NC/pinning-key"
CONFIG_NOCAP='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":8,"profiles":["NODE_EDGE"],"limits":{"cpuCores":2,"memoryMb":2048,"diskMb":0,"bandwidthMbps":50},"allowedTasks":["EDGE_CACHE"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 8 '["NODE_EDGE"]' 2 2048 0 50 '["EDGE_CACHE"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_NOCAP' ;;
  */network/tasks/next) printf '%s' '$TASK_EDGE_DISK' ;;
  */network/content/changes?*)
    printf '%s' '{"reset":false,"cursor":0,"nextSeq":1,"hasMore":false,"changes":[{"seq":1,"kind":"PROFILE","contentId":"semteto","version":1,"contentHash":"h-st","changedAt":"2026-11-11T12:00:00Z"}]}' ;;
  */network/content/PROFILE/semteto/synced) printf '%s' '{"contentId":"semteto","status":"SYNCED"}' ;;
  */network/content/PROFILE/semteto*)
    printf '%s' '{"kind":"PROFILE","contentId":"semteto","version":1,"contentHash":"h-st","changed":true,"body":"{\\"username\\":\\"st\\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NC")
assert_eq 'sem teto declarado o conteúdo é gravado' '{"username":"st"}' "$(cat "$NC/edge/PROFILE/semteto.json")"
assert_not_contains 'sem teto não há evicção' "$(cat "$TMP/curl-calls")" 'nao cabe no teto'
assert_not_contains 'sem teto não há queda de entrada' "$(cat "$TMP/curl-calls")" '"dropped":1'

# ------------------------------------------------- cenário 22: réplica para no teto em vez de pular item
# Réplica é catálogo, não cache: evictar item dela é apagar dado que o nó prometeu
# ter. No teto, a travessia para incompleta e o cursor NÃO avança sobre o item que
# não coube — retomar de onde parou é o único jeito de não pular agregado em silêncio.
NRT="$TMP/node-replica-teto"; rm -rf "$NRT"; mkdir -p "$NRT"; chmod 700 "$NRT"
cp "$NH/node.json" "$NRT/node.json"; cp "$NH/api-key" "$NRT/api-key"
cp "$NH/pinning-key" "$NRT/pinning-key"
CONFIG_REPLICA_TETO='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":9,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":1,"memoryMb":1024,"diskMb":1,"bandwidthMbps":10},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 9 '["NODE_READ_REPLICA"]' 1 1024 1 10 '["READ_QUERY"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
# 1 MB de teto; um corpo de 1,5 MB não cabe sozinho.
HUGE=$(printf 'y%.0s' $(seq 1 1572864))
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_REPLICA_TETO' ;;
  */network/tasks/next)
    printf '%s' '{"tasks":[{"taskId":"tk_rt","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:teto","payload":"{\\"kind\\":\\"PROFILE\\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}' ;;
  */network/replica/PROFILE*)
    printf '%s' '{"kind":"PROFILE","items":["gigante"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u9"}' ;;
  */network/content/PROFILE/gigante*)
    printf '%s' '{"kind":"PROFILE","contentId":"gigante","version":1,"contentHash":"h-g","changed":true,"body":"'$HUGE'"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NRT")
assert_eq 'réplica não grava item que passa do teto' '0' "$(find "$NRT/replica/PROFILE" -name 'gigante.json' 2>/dev/null | wc -l | tr -d ' ')"
assert_contains 'resultado declara disco cheio' "$(cat "$TMP/curl-calls")" '"diskFull":true'
assert_contains 'travessia conclui incompleta' "$(cat "$TMP/curl-calls")" '"complete":false'
assert_not_contains 'item que não coube não é confirmado como sincronizado' "$(cat "$TMP/curl-calls")" 'gigante/synced'

# ------------------------------------------------- cenário 23: a réplica abre espaço evictando CACHE, nunca a si mesma
# Sob pressão o que sai é borda: cache se recompõe da origem, réplica não — o nó
# prometeu aquele agregado. A entrada de réplica mais antiga (mtime 2020) tem de
# sobreviver justamente porque é a mais antiga; se a evicção a pegasse, teria
# escolhido o alvo errado.
NRC="$TMP/node-replica-cache"; rm -rf "$NRC"; mkdir -p "$NRC"; chmod 700 "$NRC"
cp "$NH/node.json" "$NRC/node.json"; cp "$NH/api-key" "$NRC/api-key"
cp "$NH/pinning-key" "$NRC/pinning-key"
CONFIG_RC='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":10,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":1,"memoryMb":1024,"diskMb":1,"bandwidthMbps":10},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 10 '["NODE_READ_REPLICA"]' 1 1024 1 10 '["READ_QUERY"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
MED=$(printf 'z%.0s' $(seq 1 409600))
mkdir -p "$NRC/edge/PROFILE" "$NRC/replica/COMMUNITY"
printf '%s' "$MED" > "$NRC/edge/PROFILE/velho.json"; printf '7\n' > "$NRC/edge/PROFILE/velho.version"
printf '%s' "$MED" > "$NRC/replica/COMMUNITY/antigo.json"; printf '3\n' > "$NRC/replica/COMMUNITY/antigo.version"
touch -d '2026-03-01 00:00:00' "$NRC/edge/PROFILE/velho.json"
touch -d '2020-01-01 00:00:00' "$NRC/replica/COMMUNITY/antigo.json"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_RC' ;;
  */network/tasks/next)
    printf '%s' '{"tasks":[{"taskId":"tk_rc","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:cache","payload":"{\\"kind\\":\\"PROFILE\\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}' ;;
  */network/replica/PROFILE*)
    printf '%s' '{"kind":"PROFILE","items":["novoreplica"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u1"}' ;;
  */network/content/PROFILE/novoreplica/synced) printf '%s' '{"contentId":"novoreplica","status":"SYNCED"}' ;;
  */network/content/PROFILE/novoreplica*)
    printf '%s' '{"kind":"PROFILE","contentId":"novoreplica","version":1,"contentHash":"h-nr","changed":true,"body":"'$MED'"}'
    [[ "\$*" == -*w* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NRC")
assert_eq 'réplica grava o item depois de abrir espaço no cache' '1' "$(find "$NRC/replica/PROFILE" -name 'novoreplica.json' 2>/dev/null | wc -l | tr -d ' ')"
assert_eq 'entrada de réplica antiga sobrevive (não é evictável)' '1' "$(find "$NRC/replica/COMMUNITY" -name 'antigo.json' | wc -l | tr -d ' ')"
assert_eq 'quem cede espaço é o cache de borda' '0' "$(find "$NRC/edge/PROFILE" -name 'velho.json' | wc -l | tr -d ' ')"
assert_contains 'a réplica confirma o que guardou' "$(cat "$TMP/curl-calls")" 'novoreplica/synced'

# ------------------------------------------------- cenário 24: banda declarada vira teto de taxa
# A banda que o participante cedeu (limits.bandwidthMbps assinado) chega ao curl como
# --limit-rate: cada transferência de corpo do nó respeita o teto. 8 Mbps = 1000 kB/s.
# O stub do curl registra o próprio argumento — a prova de comportamento de rede
# (bytes/s medidos com cronômetro) não cabe num teste determinístico, mas o argumento
# que o runtime passa é o contrato entre o limite declarado e a taxa efetiva.
NBW="$TMP/node-bw"; rm -rf "$NBW"; mkdir -p "$NBW"; chmod 700 "$NBW"
cp "$NH/node.json" "$NBW/node.json"; cp "$NH/api-key" "$NBW/api-key"
cp "$NH/pinning-key" "$NBW/pinning-key"
CONFIG_BW='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":13,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":1,"memoryMb":1024,"diskMb":20480,"bandwidthMbps":8},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 13 '["NODE_READ_REPLICA"]' 1 1024 20480 8 '["READ_QUERY"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_BW' ;;
  */network/tasks/next) printf '%s' '{"tasks":[{"taskId":"tk_bw","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:bw","payload":"{\\"kind\\":\\"PROFILE\\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}' ;;
  */network/replica/PROFILE*) printf '%s' '{"kind":"PROFILE","items":["bwuser"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u1"}' ;;
  */network/content/PROFILE/bwuser/synced) printf '%s' '{"contentId":"bwuser","status":"SYNCED"}' ;;
  */network/content/PROFILE/bwuser*)
    printf '%s' '{"kind":"PROFILE","contentId":"bwuser","version":1,"contentHash":"h-bw","changed":true,"body":"{\\"username\":\"bw\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NBW")
assert_contains 'corpo da réplica baixa sob o teto declarado' "$(cat "$TMP/curl-calls")" 'content/PROFILE/bwuser'
assert_contains '8 Mbps declarados viram 1000 kB/s no curl' "$(cat "$TMP/curl-calls")" '--limit-rate 1000k'
assert_contains 'confirmação sai — o limite não bloqueia a sincronização' "$(cat "$TMP/curl-calls")" 'bwuser/synced'

# ------------------------------------------------- cenário 25: banda 0 = sem teto
# `0` é "não declarei restrição", não "zero de banda": quem não declarou transfere
# sem --limit-rate, como sempre — punir o não declarado inventaria limite que o
# participante nunca aceitou.
NCBW="$TMP/node-nobw"; rm -rf "$NCBW"; mkdir -p "$NCBW"; chmod 700 "$NCBW"
cp "$NH/node.json" "$NCBW/node.json"; cp "$NH/api-key" "$NCBW/api-key"
cp "$NH/pinning-key" "$NCBW/pinning-key"
CONFIG_NOBW='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":14,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":1,"memoryMb":1024,"diskMb":20480,"bandwidthMbps":0},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 14 '["NODE_READ_REPLICA"]' 1 1024 20480 0 '["READ_QUERY"]' 2)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_NOBW' ;;
  */network/tasks/next) printf '%s' '{"tasks":[{"taskId":"tk_nbw","type":"READ_QUERY","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"replica:nbw","payload":"{\\"kind\\":\\"PROFILE\\"}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}' ;;
  */network/replica/PROFILE*) printf '%s' '{"kind":"PROFILE","items":["nobw"],"hasMore":false,"nextAfterAt":"2026-11-11T10:00:00Z","nextAfterId":"u1"}' ;;
  */network/content/PROFILE/nobw/synced) printf '%s' '{"contentId":"nobw","status":"SYNCED"}' ;;
  */network/content/PROFILE/nobw*)
    printf '%s' '{"kind":"PROFILE","contentId":"nobw","version":1,"contentHash":"h-nb","changed":true,"body":"{\\"username\":\"nb\"}"}'
    [[ "\$*" == *"-w"* ]] && printf '\n200' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NCBW")
assert_contains 'nó sem banda declarada baixa sem --limit-rate' "$(cat "$TMP/curl-calls")" 'content/PROFILE/nobw'
assert_not_contains 'banda 0 não injeta limite' "$(cat "$TMP/curl-calls")" '--limit-rate'
assert_contains 'sincronização segue completa sem teto' "$(cat "$TMP/curl-calls")" 'nobw/synced'

# ------------------------------------------------- cenário 26: Control Plane fora dentro do TTL
# Doc 06 §6, primeira linha: "Control Plane fora por < TTL de config → nós continuam com a última
# config assinada". Antes do TTL não existia: bastava o GET da config falhar uma vez e o nó parava,
# mesmo com o heartbeat respondendo — um soluço de rede derrubava nó sadio.
# O nó primeiro verifica a config (com o Control Plane de pé) e depois o endpoint cai.
NTTL="$TMP/node-ttl"; rm -rf "$NTTL"; mkdir -p "$NTTL"; chmod 700 "$NTTL"
printf '%s\n' '{"nodeId":"nd_exec1","status":"ACTIVE"}' > "$NTTL/node.json"
printf 'enk_echoteste0123\n' > "$NTTL/api-key"; chmod 600 "$NTTL/api-key"
printf '%s' "$CP_PUB" > "$NTTL/pinning-key"; chmod 600 "$NTTL/pinning-key"
CONFIG_TTL_OK='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":300,"configVersion":30,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 30 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 2 300)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'

# Ciclo 1: Control Plane de pé. A config fica guardada com o instante da verificação.
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) printf '%s' '$CONFIG_TTL_OK' ;;
  */network/tasks/next) printf '%s' '{"tasks":[],"mode":"NORMAL","serverTime":"now"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
run_custom "$NTTL" >/dev/null
assert_eq 'config verificada fica guardada para a queda' '1' "$([[ -s "$NTTL/config.json" ]] && echo 1 || echo 0)"
assert_eq 'instante da verificação é gravado' '1' "$([[ -s "$NTTL/config-verified-at" ]] && echo 1 || echo 0)"

# Ciclo 2: o endpoint de config cai (curl sai != 0, sem HTTP de erro), o heartbeat continua.
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) exit 7 ;;
  */network/tasks/next) printf '%s' '{"tasks":[{"taskId":"tk_ttl","type":"ASYNC_JOB","attempt":1,"deadline":"2026-11-12T13:00:00Z","idempotencyKey":"idem-ttl","payload":"{}","dataClass":"PUBLIC_DATA"}],"mode":"NORMAL","serverTime":"now"}' ;;
  */network/tasks/*/result) printf '%s' '{"taskId":"x","status":"DONE","applied":true,"reason":"ok"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NTTL")
assert_contains 'dentro do TTL o nó segue com a última config verificada' "$out" 'seguindo com a última config verificada'
assert_contains 'e continua trabalhando (não drenou)' "$out" 'DONE tk_ttl'

# ------------------------------------------------- cenário 27: Control Plane fora ALÉM do TTL
# Segunda linha da tabela do §6: "fora por > TTL → nós param de aceitar tarefa nova e drenam".
# O instante da verificação é recuado além do TTL: a config guardada perdeu validade e o nó não
# pode inventar permissão. Para de pedir tarefa — drenar é não pedir.
printf '%s\n' "$(( $(date +%s) - 400 ))" > "$NTTL/config-verified-at"
: > "$TMP/curl-calls"
out=$(run_custom "$NTTL")
assert_contains 'passado o TTL o nó drena e diz por quê' "$out" 'config vencida (TTL de 300s) — drenando'
assert_not_contains 'vencida a config, não há poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 28: TTL maior que o recuo continua valendo
# A fronteira é o número ASSINADO, não um padrão do runtime: a mesma config com ttl de 3600s e o
# mesmo recuo de 400s continua valendo. Sem isso, um TTL curto fixo no código faria o teste do
# cenário 27 passar sem o campo ser lido do documento.
CONFIG_TTL_LONGO='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":3,"ttlSeconds":3600,"configVersion":31,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","term":2,"signed":true,"signature":"'"$(sign_limits_config 31 '["NODE_WORKER"]' 4 8192 51200 100 '["ASYNC_JOB"]' 2 3600)"'","algorithm":"Ed25519","publicKey":"'"$CP_PUB"'"}'
printf '%s' "$CONFIG_TTL_LONGO" > "$NTTL/config.json"
printf '%s\n' "$(( $(date +%s) - 400 ))" > "$NTTL/config-verified-at"
: > "$TMP/curl-calls"
out=$(run_custom "$NTTL")
assert_contains 'TTL de 3600s aceita um recuo de 400s' "$out" 'de 3600s'
assert_contains 'e o nó trabalha' "$out" 'DONE tk_ttl'

# ------------------------------------------------- cenário 29: recusa HTTP não é queda
# O Control Plane RESPONDEU que a config não vale mais (401/403/404 — revogação, chave inválida).
# Isso não é queda de rede: cair no cache aqui ressuscitaria a permissão que o Control Plane acabou
# de negar. O nó drena mesmo com cache fresco e dentro do TTL.
printf '%s' "$CONFIG_TTL_OK" > "$NTTL/config.json"
printf '%s\n' "$(date +%s)" > "$NTTL/config-verified-at"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) exit 22 ;;
  */network/tasks/next) printf '%s' '{"tasks":[],"mode":"NORMAL","serverTime":"now"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NTTL")
assert_contains 'recusa HTTP vira drenagem, não cache' "$out" 'Control Plane recusou a config (HTTP de erro) — drenando'
assert_not_contains 'recusa HTTP não habilita poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 30: cache adulterado não é permissão
# O cache vive no mesmo volume da identidade. Um arquivo trocado ali dentro (ou um backup restaurado
# de outra máquina) não pode virar permissão: o nó reverifica a assinatura da config guardada contra
# o pino antes de usá-la. Aqui a permissão guardada foi trocada por MEDIA_TRANSCODE sem reassinar.
CONFIG_ADULTERADA="${CONFIG_TTL_OK/\[\"ASYNC_JOB\"\]/[\"MEDIA_TRANSCODE\"]}"
printf '%s' "$CONFIG_ADULTERADA" > "$NTTL/config.json"
printf '%s\n' "$(date +%s)" > "$NTTL/config-verified-at"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) exit 7 ;;
  */network/tasks/next) printf '%s' '{"tasks":[],"mode":"NORMAL","serverTime":"now"}' ;;
  *) exit 22 ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NTTL")
assert_contains 'config guardada que não confere mais é recusada' "$out" 'config guardada não confere mais com o pino — drenando'
assert_not_contains 'cache adulterado não habilita poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

# ------------------------------------------------- cenário 31: sem cache, queda drena
# Nó que nunca verificou config com sucesso não tem o que reusar: a queda drena, como antes.
NNOCACHE="$TMP/node-sem-cache"; rm -rf "$NNOCACHE"; mkdir -p "$NNOCACHE"; chmod 700 "$NNOCACHE"
cp "$NH/node.json" "$NNOCACHE/node.json"; cp "$NH/api-key" "$NNOCACHE/api-key"
cp "$NH/pinning-key" "$NNOCACHE/pinning-key"
: > "$TMP/curl-calls"
cat > "$TMP/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$TMP/curl-calls"
url="\${@: -1}"
case "\$url" in
  */network/nodes/heartbeat) printf '%s' '$HEARTBEAT_OK' ;;
  */network/nodes/me/config) exit 7 ;;
  *) printf 'CHAMADA-INESPERADA %s' "\$url" >> "$TMP/curl-calls" ;;
esac
MOCK
chmod +x "$TMP/curl"
out=$(run_custom "$NNOCACHE")
assert_contains 'sem cache a queda drena' "$out" 'nenhuma config verificada guardada — drenando'
assert_not_contains 'sem cache não há poll' "$(cat "$TMP/curl-calls")" '/network/tasks/next'

printf '\nTodos os cenarios passaram (%d assercoes).\n' "$PASS"
