#!/usr/bin/env bash
# Testes do loop de execução do Node Runtime (node-runtime.sh).
# Mesmo padrão de node-runtime-test.sh: PATH isolado com symlinks + stubs, sem rede real.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod awk printf cat rm mktemp wc sha256sum; do
  location="$(command -v "$tool")"
  ln -s "$location" "$TMP/$tool" 2>/dev/null || true
done

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
# Pino do install: a config só vale com esta publicKey.
printf '%s' 'PINNED-PUBLIC-KEY-BASE64' > "$NH/pinning-key"; chmod 600 "$NH/pinning-key"

CONFIG_SIGNED='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":1,"configVersion":3,"profiles":["NODE_WORKER"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["ASYNC_JOB"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","signed":true,"signature":"c2ln","algorithm":"Ed25519","publicKey":"PINNED-PUBLIC-KEY-BASE64"}'
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
  : > "$TMP/curl-calls"
  CONTROL_PLANE_URL=https://cp.test NODE_HOME="$NH" PATH="$TMP" NODE_POLL_SECONDS=1 \
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

CONFIG_MEDIA='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":1,"configVersion":4,"profiles":["NODE_MEDIA"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":100},"allowedTasks":["MEDIA_TRANSCODE"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","signed":true,"signature":"c2ln","algorithm":"Ed25519","publicKey":"PINNED-PUBLIC-KEY-BASE64"}'

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

printf '\nTodos os cenarios passaram (%d assercoes).\n' "$PASS"
