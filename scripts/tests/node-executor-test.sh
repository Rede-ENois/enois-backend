#!/usr/bin/env bash
# Testes do loop de execução do Node Runtime (node-runtime.sh).
# Mesmo padrão de node-runtime-test.sh: PATH isolado com symlinks + stubs, sem rede real.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_DIR="$ROOT/deploy/node-runtime"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in bash head sed grep dirname pwd mkdir stat chmod awk printf cat rm mktemp wc sha256sum tr; do
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


# ------------------------------------------------- cenário 10: READ_QUERY (réplica de leitura)
# O nó percorre o catálogo por cursor, lê o corpo de cada item, guarda e confirma
# com versão+hash. Item já em dia (version conhecida) volta changed=false e não é
# rebaixado — nem reenviado.
NR="$TMP/node-replica"; rm -rf "$NR"; mkdir -p "$NR"; chmod 700 "$NR"
cp "$NH/node.json" "$NR/node.json"; cp "$NH/api-key" "$NR/api-key"
cp "$NH/pinning-key" "$NR/pinning-key"

CONFIG_REPLICA='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":1,"configVersion":5,"profiles":["NODE_READ_REPLICA"],"limits":{"cpuCores":2,"memoryMb":4096,"diskMb":20480,"bandwidthMbps":50},"allowedTasks":["READ_QUERY"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","signed":true,"signature":"c2ln","algorithm":"Ed25519","publicKey":"PINNED-PUBLIC-KEY-BASE64"}'
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

CONFIG_EDGE='{"nodeId":"nd_exec1","status":"ACTIVE","operable":true,"formatVersion":1,"configVersion":6,"profiles":["NODE_EDGE"],"limits":{"cpuCores":4,"memoryMb":8192,"diskMb":51200,"bandwidthMbps":200},"allowedTasks":["EDGE_CACHE","EDGE_DELIVER"],"region":"sudeste","country":"BR","issuedAt":"2026-11-12T12:00:00Z","signed":true,"signature":"c2ln","algorithm":"Ed25519","publicKey":"PINNED-PUBLIC-KEY-BASE64"}'
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

printf '\nTodos os cenarios passaram (%d assercoes).\n' "$PASS"
