#!/usr/bin/env bash
# Loop de execução do Node Runtime — o lado do nó da Fase 2.
#
# Roda DENTRO do container (compose passa NODE_HOME, CONTROL_PLANE_URL e NODE_POLL_SECONDS).
# A cada ciclo:
#   1. heartbeat com a capacidade observada (resposta traz modo e acceptTasks);
#   2. lê a própria config; se assinada, confere contra a chave pública pinada;
#   3. sem acceptTasks ou operable=false, não pede tarefa (drenar é não pedir);
#   4. pede GET /network/tasks/next; executa o que estiver em allowedTasks;
#      recusa com FAILED/TASK_NOT_ALLOWED o que a config não autoriza;
#   5. devolve POST /network/tasks/{id}/result.
#
# O executor nunca interpreta conteúdo de usuário (HTML, CSS, KofScript): as tarefas
# são trabalho de infraestrutura (transcode de mídia com a receita fixa do Control
# Plane, eco para os demais tipos), e conteúdo de usuário continua nos sandboxes
# próprios (02-modelo-de-ameacas.md). Modo EMERGENCY/DEGRADED: termina o ciclo e para de pedir.
set -u

NODE_HOME="${NODE_HOME:-/var/lib/enois-node}"
CONTROL_PLANE="${CONTROL_PLANE_URL:?CONTROL_PLANE_URL é obrigatório}"
POLL_SECONDS="${NODE_POLL_SECONDS:-15}"
# "<fonte>@<sha8>" — o commit exato da fonte de verdade que o bootstrap construiu
# (compose exporta o que o install.sh leu de $NODE_HOME/runtime-version). Nó que
# mente a versão é o único jeito de o painel não ver divergência de runtime.
RUNTIME_VERSION="${RUNTIME_VERSION:-qa}"

[[ -s "$NODE_HOME/node.json" ]] || { printf '[executor] sem node.json\n' >&2; exit 1; }
[[ -s "$NODE_HOME/api-key" ]] || { printf '[executor] sem api-key\n' >&2; exit 1; }
[[ -s "$NODE_HOME/pinning-key" ]] || printf '[executor] aviso: sem pinning-key — toda config será recusada até o pino existir\n' >&2

node_id=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
node_key=$(head -n 1 "$NODE_HOME/api-key")
auth=(-H "X-Node-Id: $node_id" -H "X-Node-Key: $node_key" -H 'Content-Type: application/json')
# Para bytes crus o Content-Type é o do arquivo; mandar os dois manda header duplicado.
auth_bin=(-H "X-Node-Id: $node_id" -H "X-Node-Key: $node_key")

json_field() {
  # json_field <json> <chave>: primeiro valor string da chave, sem depender de jq.
  sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' <<<"$1" | head -n 1
}

# Aviso que o operador precisa ler uma vez, não a cada ciclo.
# O loop roda a cada NODE_POLL_SECONDS; repetir a mesma linha a cada volta faz o log
# crescer e a informação útil (mudou o estado?) sumir. Guarda a última linha emitida em
# memória, por rótulo, e cala a repetição. Estado novo, linha nova.
LAST_NOTICE=""
# Linhas que o painel do nó mostra (GET /admin/network/nodes/{id}/observability, admin).
# O runtime NÃO abre porta (brief §17): quem exibe é o Control Plane, e a linha chega lá
# EMPURRADA no heartbeat, que já acontece a cada ciclo. O buffer é curto e esvaziado a
# cada heartbeat: o que não couber num ciclo é descartado de propósito — é tela, não arquivo,
# e o arquivo de log do nó continua sendo a verdade para quem está na máquina.
EVENTS_MAX=20
# Fila em ARQUIVO, não em memória: `cycle` roda num processo por volta (e um reinício não pode
# apagar a linha que ainda não subiu). O arquivo é do nó, fica em 0600 junto da chave e é esvaziado
# assim que o heartbeat leva as linhas.
EVENTS_FILE="$NODE_HOME/pending-events"
# Contadores do disco no fim do ciclo ANTERIOR (`/proc/diskstats`), para o I/O do intervalo. O
# intervalo é entre heartbeats e não pode estar em memória: `cycle` roda num processo por volta.
DISKSTATE_FILE="$NODE_HOME/disk-prev"
# O arquivo de contadores do kernel lido pela medição de I/O do ciclo. Variável e não caminho fixo
# para o harness poder servir contadores conhecidos; em produção o padrão é o do kernel.
DISKSTATS_FILE="${DISKSTATS_FILE:-/proc/diskstats}"

event() {
  # event <nivel> <mensagem>: guarda para o próximo heartbeat.
  local level="$1" message="$2"
  # Normaliza sem ferramenta externa: o runtime roda com PATH mínimo (e um `cut` ausente
  # descartaria a linha em silêncio — foi assim que esta função falhou no harness).
  message="${message//$'\n'/ }"
  message="${message//$'\r'/ }"
  message="${message:0:400}"
  [[ -z "$message" ]] && return 0
  printf '%s|%s\n' "$level" "$message" >> "$EVENTS_FILE" 2>/dev/null || return 0
  chmod 600 "$EVENTS_FILE" 2>/dev/null || true
  # Teto: fica o que couber. Reescreve num arquivo novo e troca por mv — nunca segue symlink.
  local lines
  lines=$(wc -l < "$EVENTS_FILE" 2>/dev/null || echo 0)
  if (( lines > EVENTS_MAX )); then
    tail -n "$EVENTS_MAX" "$EVENTS_FILE" > "$EVENTS_FILE.tmp" && mv -f "$EVENTS_FILE.tmp" "$EVENTS_FILE"
  fi
}

json_escape() {
  # Escapa para string JSON: barra invertida, aspas e caracteres de controle.
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/ /g' -e 's/\r/ /g' <<<"$1"
}

events_json() {
  # Formato do contrato HeartbeatRequest.logs: [{level, message, at}]. Vazio quando não há nada.
  [[ -s "$EVENTS_FILE" ]] || return 0
  local at item level message sep="" out=""
  at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  while IFS= read -r item; do
    [[ -z "$item" ]] && continue
    level="${item%%|*}"
    message="${item#*|}"
    out+="${sep}{\"level\":\"$level\",\"message\":\"$(json_escape "$message")\",\"at\":\"$at\"}"
    sep=","
  done < "$EVENTS_FILE"
  printf '%s' "$out"
}

notice_once() {
  # notice_once <rotulo> <mensagem>: emite só quando a transição é nova.
  local key="$1" message="$2"
  [[ "$LAST_NOTICE" == "$key" ]] && return 0
  LAST_NOTICE="$key"
  printf '[executor] %s\n' "$message" >&2
  event INFO "$message"
}

task_payload() {
  # O payload da tarefa é uma string JSON dentro do JSON da tarefa: chega escapado
  # ({\"mediaId\":...}). Não serve json_field — o [^"] para no primeiro \" — então
  # o recorte vai de "payload":" até o campo que o segue no contrato, e o escape é
  # desfeito depois. Nada aqui é interpretado: só recortado.
  sed -n 's/.*"payload":"\(.*\)","dataClass":"[^"]*".*/\1/p' <<<"$1" | head -n 1 | sed 's/\\"/"/g'
}

json_number() {
  # json_number <json> <chave>: valor numérico (sem aspas); vazio se não houver.
  sed -n 's/.*"'"$2"'":\([0-9][0-9]*\).*/\1/p' <<<"$1" | head -n 1
}

json_string_array() {
  # json_string_array <json> <chave>: um item por linha, sem as aspas.
  sed -n 's/.*"'"$2"'":\[\([^]]*\)\].*/\1/p' <<<"$1" | head -n 1 | tr ',' '\n' | tr -d '"' | sed '/^$/d'
}

json_flag() {
  # json_flag <json> <chave>: true/false; vazio quando a chave não existe.
  grep -q '"'"$2"'":true' <<<"$1" && echo true || echo false
}

# allowedTasks da config assinada — a ÚNICA fonte de permissão de execução.
allowed_tasks() {
  local config rc
  if [[ ! -s "$NODE_HOME/pinning-key" ]]; then
    notice_once sem-pino 'sem pinning-key — config não verificável; rode --start para pinar a chave'
    return 1
  fi
  config=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" "$CONTROL_PLANE/network/nodes/me/config")
  rc=$?
  if (( rc != 0 )); then
    # Recusa HTTP (código 22 do --fail) NÃO é queda: é o Control Plane respondendo que esta
    # config não vale mais — revogação, chave inválida, nó desconhecido. Cai aqui e o cache não
    # pode falar por cima: ressuscitar permissão recém-negada é o oposto de fail secure.
    if (( rc == 22 )); then
      printf '[executor] Control Plane recusou a config (HTTP de erro) — drenando\n' >&2
      return 1
    fi
    # Queda de transporte: o Control Plane não respondeu. A permissão que ele assinou continua
    # valendo enquanto o TTL dela não vence (doc 06 §6) — blip de rede não derruba nó sadio.
    allowed_tasks_da_cache
    return $?
  fi
  config_valida "$config" || return 1
  config_cache_guardar "$config"
  printf '%s' "$config"
}

# config_valida <config-json>: 0 quando a config é do pino, está assinada por ele e não regride
# o termo. É a checagem inteira, e vale igual para a que chegou agora e para a guardada.
config_valida() {
  local config="$1"
  if grep -q '"signed":true' <<<"$config"; then
    printf '%s' "$config" | grep -q '"publicKey":"'"$(cat "$NODE_HOME/pinning-key")"'"' || {
      printf '[executor] config com publicKey diferente do pino — recusada\n' >&2
      return 1
    }
  else
    printf '[executor] config sem assinatura — recusada por padrão\n' >&2
    return 1
  fi
  config_verify_signature "$config" || {
    printf '[executor] assinatura da config não confere com o pino — recusada\n' >&2
    return 1
  }
  # Regra de termo (fase 6, doc 06 §4): config só sobe. Termo menor que o último aceito
  # é uma autoridade DEPOSTA tentando voltar a mandar — exatamente o cenário de split-brain
  # que o termo existe para impedir. Recusa com registro e NÃO grava o termo recusado: gravar
  # número menor reabriria a porta para a autoridade de termo intermediário voltar.
  term_regressao "$config" && {
    printf '[executor] config com term menor que o último aceito — autoridade deposta, recusada\n' >&2
    return 1
  }
  return 0
}

config_cache_guardar() {
  # Guarda a config JÁ verificada e o instante da verificação. O rename é o que impede um
  # desligamento no meio da escrita de deixar config truncada: ou o arquivo antigo, ou o novo.
  if printf '%s' "$1" > "$NODE_HOME/config.json.tmp" 2>/dev/null &&
    printf '%s\n' "$(date +%s)" > "$NODE_HOME/config-verified-at.tmp" 2>/dev/null &&
    mv -f "$NODE_HOME/config.json.tmp" "$NODE_HOME/config.json" 2>/dev/null &&
    mv -f "$NODE_HOME/config-verified-at.tmp" "$NODE_HOME/config-verified-at" 2>/dev/null; then
    rm -f "$NODE_HOME/config.json.tmp" "$NODE_HOME/config-verified-at.tmp" 2>/dev/null
    return 0
  fi
  rm -f "$NODE_HOME/config.json.tmp" "$NODE_HOME/config-verified-at.tmp" 2>/dev/null
  printf '[executor] aviso: não consegui guardar a config verificada — próxima queda será sem cache\n' >&2
  return 1
}

allowed_tasks_da_cache() {
  # A config guardada serve SÓ dentro do TTL que ela mesma declara, e SÓ se continuar conferindo
  # com o pino. Reler e reverificar não é desconfiança decorativa: o cache vive no mesmo volume
  # que a identidade do nó, e um arquivo trocado ali viraria permissão sem assinatura.
  local config at ttl now
  if [[ ! -s "$NODE_HOME/config.json" || ! -s "$NODE_HOME/config-verified-at" ]]; then
    printf '[executor] Control Plane sem resposta e nenhuma config verificada guardada — drenando\n' >&2
    return 1
  fi
  config=$(cat "$NODE_HOME/config.json")
  at=$(head -n 1 "$NODE_HOME/config-verified-at")
  ttl=$(json_number "$config" ttlSeconds)
  if [[ ! "$at" =~ ^[0-9]+$ || ! "$ttl" =~ ^[0-9]+$ ]]; then
    printf '[executor] cache de config ilegível — drenando\n' >&2
    return 1
  fi
  now=$(date +%s)
  # Relógio atrás do instante da verificação não prova frescor: `agora - verificado_em` fica
  # negativo, e negativo é menor que qualquer TTL — o prazo sumiria e a permissão valeria de novo
  # por tempo indeterminado. Acontece em VPS sem RTC (relógio volta ao boot), máquina
  # suspensa/retomada e correção de NTP para trás. Idade que não se consegue medir é idade não
  # provada, e permissão não provada não se usa (doc 06 §6, fail secure).
  if (( now < at )); then
    printf '[executor] relógio atrás do instante da verificação (%ss) — idade não provável, drenando\n' \
      "$(( at - now ))" >&2
    return 1
  fi
  if (( now - at >= ttl )); then
    # Venceu: o Control Plane está fora há mais tempo que a permissão vale. Parar de aceitar
    # tarefa nova é a única resposta honesta — ninguém pode renovar o mando que ninguém assinou.
    printf '[executor] Control Plane sem resposta e config vencida (TTL de %ss) — drenando\n' "$ttl" >&2
    return 1
  fi
  config_valida "$config" || {
    printf '[executor] config guardada não confere mais com o pino — drenando\n' >&2
    return 1
  }
  printf '[executor] Control Plane sem resposta — seguindo com a última config verificada (%ss de %ss)\n' \
    "$((now - at))" "$ttl" >&2
  printf '%s' "$config"
}

term_regressao() {
  # term_regressao <config-json>: 0 (verdadeiro) quando term < último aceito; grava o termo
  # aceito quando sobe ou empata. O estado vive no volume do nó ($NODE_HOME/term), igual ao pino.
  local term last
  term=$(json_number "$1" term)
  [[ -n "$term" ]] || return 1
  last=0
  [[ -s "$NODE_HOME/term" ]] && last=$(head -n 1 "$NODE_HOME/term")
  if (( term < last )); then
    return 0
  fi
  printf '%s\n' "$term" > "$NODE_HOME/term"
  return 1
}

# A assinatura cobre o DOCUMENTO CANÔNICO, não o JSON: a mesma linha que
# NetworkConfigSigner.SignedFields.canonical() monta no Control Plane, campo por campo
# na ordem do contrato, listas ordenadas e em JSON compacto. Reconstruir essa linha é
# recortar e ordenar, não reinterpretar o documento.
config_signature() {
  # config_signature <config-json>: imprime "canonical<TAB>assinatura"; 1 se faltar campo.
  local format_version config_version node_id profiles cpu memory disk bandwidth tasks routes issued_at ttl term disk_free disk_total signature
  format_version=$(json_number "$1" formatVersion)
  config_version=$(json_number "$1" configVersion)
  node_id=$(json_field "$1" nodeId)
  profiles=$(json_list_sorted "$1" profiles)
  cpu=$(json_number "$1" cpuCores)
  memory=$(json_number "$1" memoryMb)
  disk=$(json_number "$1" diskMb)
  bandwidth=$(json_number "$1" bandwidthMbps)
  tasks=$(json_list_sorted "$1" allowedTasks)
  # allowedRoutes entrou no formato 4 e fica logo DEPOIS de allowedTasks: campo novo nunca
  # desloca o último, e a rota é a lista que diz ao nó quais destinos ele pode chamar.
  routes=$(json_list_sorted "$1" allowedRoutes)
  issued_at=$(json_field "$1" issuedAt)
  # ttlSeconds entrou no formato 3 e fica logo antes do termo: campo novo nunca desloca o
  # último, que é a regra que deixou o formato 2 legível por quem reconstruía o formato 1.
  ttl=$(json_number "$1" ttlSeconds)
  # O termo da autoridade entrou no formato 2 (fase 6): vem por último na linha.
  # Formato 1 não o tinha e não é mais servido pelo Control Plane.
  term=$(json_number "$1" term)
  # diskFreeMb (formato 5) vem DEPOIS do termo: campo novo nunca desloca o último. É o disco
  # livre que o próprio nó mediu e subiu no heartbeat -- não o declarado, que é o teto.
  # "ausente" e não 0: zero é "sem espaço livre" e ausente é "não mediu"; assinar os dois
  # iguais tornaria indistinguível, para quem verifica, um disco cheio de um disco desconhecido.
  if grep -q '"diskFreeMb":[0-9]' <<< "$1"; then
    disk_free=$(json_number "$1" diskFreeMb)
  else
    disk_free='ausente'
  fi
  signature=$(json_field "$1" signature)
  # diskTotalMb (formato 6) vem DEPOIS do disco livre: o tamanho do volume, ao lado do que está
  # livre nele. Sem ele o nó não sabe quanto o participante reservou para si -- só o livre, que
  # muda a cada arquivo gravado. Mesma regra de ausência do formato 5: "0" diria um volume de
  # tamanho zero, e "ausente" diz que este Control Plane ainda não mede o total.
  if grep -q '"diskTotalMb":[0-9]' <<< "$1"; then
    disk_total=$(json_number "$1" diskTotalMb)
  else
    disk_total='ausente'
  fi
  [[ -n "$format_version" && -n "$config_version" && -n "$node_id" && -n "$cpu" \
      && -n "$memory" && -n "$disk" && -n "$bandwidth" && -n "$issued_at" \
      && -n "$ttl" && -n "$term" && -n "$signature" ]] || return 1
  local canonical
  canonical=$(printf '%s|%s|%s|[%s]|%s|%s|%s|%s|[%s]|[%s]|%s|%s|%s|%s|%s' \
    "$format_version" "$config_version" "$node_id" "$profiles" "$cpu" "$memory" "$disk" \
    "$bandwidth" "$tasks" "$routes" "$issued_at" "$ttl" "$term" "$disk_free" "$disk_total")
  # storageBandwidth (formato 7) vem DEPOIS do tamanho do volume: a banda própria de cada tipo
  # de storage node (brief §6). Formato <= 6 não tinha a lista e não pode ganhá-la aqui --
  # acrescentá-la mudaria a linha de uma config antiga e a assinatura deixaria de conferir.
  if [[ "$format_version" -ge 7 ]]; then
    canonical="$canonical|[$(json_list_sorted "$1" storageBandwidth)]"
  fi
  printf '%s\t%s' "$canonical" "$signature"
}

json_list_sorted() {
  # json_list_sorted <json> <chave>: itens em JSON compacto, ordenados por byte (C),
  # como o jsonList() do signer — a ordem do banco não é contrato e duas ordens dariam
  # assinaturas diferentes para a mesma permissão. Os itens já chegam com aspas (o
  # array é um blob recortado, não parseado); as aspas saem, o conjunto vazio vira []
  # como no signer, e o sort é por byte porque os itens são nomes de perfil/tarefa em
  # ASCII — o ordenamento do Java e o do `sort` batem nesse domínio, e um nome fora
  # dele quebraria a igualdade em vez de passar batido.
  #
  # O recorte vai do "[" até o "]" DEPOIS da chave pedida: o ".*" é guloso, então
  # "allowedTasks" pegaria o array inteiro se a chave pedida estivesse antes dele na
  # linha. Por isso a âncora é a chave + a sua abertura de array, e o fecho é o
  # primeiro "]" que vem depois.
  #
  # Devolve os ITENS SEM COLCHETE (o chamador põe os colchetes, como na linha
  # canônica), e nada quando a lista é vazia — assim a lista vazia vira "[]" na
  # linha, igual ao jsonList() do signer. Devolver "[]" aqui viraria "[[]]" na
  # linha assinada, e a assinatura não conferiria com a do Control Plane.
  #
  # A quebra dos itens é pela VÍRGULA QUE SEPARA ELEMENTOS do array (o trecho
  # '","'), não por qualquer vírgula: dentro de uma URL a vírgula é legítima (query
  # string) e cortar ali daria um item diferente do que o signer assinou — a linha
  # reconstruída não conferiria, e o nó recusaria a config por uma rota com
  # query string.
  local key="$2" items
  items=$(sed -n "s/.*\"$key\":\[\([^]]*\)\].*/\1/p" <<<"$1" | head -n 1 \
    | sed 's/","/\n/g' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^"//; s/"$//; s/\\"/"/g' | sed '/^$/d')
  [[ -n "$items" ]] || return 0
  printf '%s' "$items" | LC_ALL=C sort | sed 's/^/"/; s/$/"/' | paste -sd ',' -
}

config_verify_signature() {
  # config_verify_signature <config-json>: confere a assinatura Ed25519 contra o PINO
  # (o X.509 base64 gravado no install/primeiro start), não contra o publicKey que veio
  # no corpo. Essa é a diferença que importa: a config chega pela rede e pode vir de um
  # Control Plane comprometido ou de um proxy no caminho. Os limites e o allowedTasks do
  # corpo podem ser alterados, mas a assinatura do pino não pode ser forjada — e é ela
  # que o nó verifica aqui. Comparar só o campo publicKey com o pino provaria que o
  # atacante sabe copiar uma string.
  local config="$1" canonical signature dir rc
  local pair
  pair=$(config_signature "$config") || return 1
  canonical=${pair%%$'\t'*}
  signature=${pair#*$'\t'}
  dir=$(mktemp -d) || return 1
  printf '%s' "$(cat "$NODE_HOME/pinning-key")" | base64 -d > "$dir/pin.der" 2>/dev/null &&
    printf '%s' "$canonical" > "$dir/msg" &&
    printf '%s' "$signature" | base64 -d > "$dir/sig" 2>/dev/null &&
    openssl pkeyutl -verify -pubin -inkey "$dir/pin.der" -keyform DER -rawin \
      -in "$dir/msg" -sigfile "$dir/sig" >/dev/null 2>&1
  rc=$?
  rm -f "$dir/pin.der" "$dir/msg" "$dir/sig"; rmdir "$dir" 2>/dev/null
  return $rc
}

# O array allowedTasks da config — a única fonte de permissão de execução.
allowed_tasks_list() {
  sed -n 's/.*"allowedTasks":\[\([^]]*\)\].*/\1/p'
}

result() {
  # result <taskId> <attempt> <status> <output-json|vazio> <error-code|vazio> [mensagem]
  # O contrato do Control Plane (NetworkTaskController.ResultRequest) declara output como
  # STRING (JSON codificado), não objeto: enviar o objeto direto é corpo que o Jackson
  # recusa com 400, o resultado nunca chega e a tarefa volta a ser redespachada para
  # sempre — o nó executa, o CP nunca sabe. O output viaja como texto JSON dentro da string.
  local task_id="$1" attempt="$2" status="$3" output="$4" error_code="$5" message="${6:-negado pela config assinada}"
  local body output_part error_part
  output_part=''
  if [[ -n "$output" ]]; then
    output=$(printf '%s' "$output" | sed 's/\\/\\\\/g; s/"/\\"/g')
    output_part=",\"output\":\"$output\""
  fi
  error_part=''
  [[ -n "$error_code" ]] && error_part=",\"error\":{\"code\":\"$error_code\",\"message\":\"$message\",\"retryable\":false}"
  body="{\"taskId\":\"$task_id\",\"attempt\":$attempt,\"status\":\"$status\"$output_part$error_part}"
  curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" -d "$body" \
    "$CONTROL_PLANE/network/tasks/$task_id/result" >/dev/null
}

fail_task() {
  result "$1" "$2" FAILED '' "$3" "$4"
  printf '[executor] FAILED %s (%s)\n' "$1" "$3"
}

# ---------------- executor real: MEDIA_TRANSCODE ----------------
# A receita é do Control Plane (MediaTranscodeSpec): mesmos filtros de escala,
# mesmos encoders, mesmos limites de thread, mesmo demuxer/muxer por tipo. Um nó
# que invente a própria receita produz um arquivo que a origem não produziria.
TRANSCODE_SCALE="scale=w='if(gt(iw,ih),min(854,iw),-2)':h='if(gt(iw,ih),-2,min(854,ih))'"

transcode_encoders() {
  case "$1" in
    video/mp4|video/quicktime)
      printf '%s\n' -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p -threads 2 -c:a aac -b:a 128k ;;
    video/webm)
      printf '%s\n' -c:v libvpx-vp9 -preset veryfast -crf 34 -b:v 0 -row-mt 1 -threads 2 -pix_fmt yuv420p -c:a libopus -b:a 96k ;;
  esac
}

transcode_formats() {
  # demuxer muxer — fixos por tipo, como no runner local: o conteúdo não escolhe nada.
  case "$1" in
    video/mp4) printf 'mov mp4' ;;
    video/quicktime) printf 'mov mov' ;;
    video/webm) printf 'matroska webm' ;;
  esac
}

exec_media_transcode() {
  local next="$1" task_id="$2" attempt="$3" config="${4:-}"
  local payload media_id mime work src dst args a demux mux resp
  local -a bw=()
  mapfile -t bw < <(bandwidth_args "$config")
  command -v ffmpeg >/dev/null 2>&1 || {
    fail_task "$task_id" "$attempt" TASK_EXECUTOR_MISSING 'ffmpeg ausente no runtime'; return 0; }
  # O payload chega como string JSON escapada dentro da tarefa: desfaz o escape
  # antes de extrair os campos (payload simples de objeto, sem aspas cruas).
  payload=$(task_payload "$next")
  media_id=$(json_field "$payload" mediaId)
  mime=$(json_field "$payload" mimeType)
  # Receita e destino são fixos por tipo: mime fora da lista não tem contrato.
  if [[ -z "$media_id" || -z "$mime" ]] || [[ -z "$(transcode_formats "$mime")" ]]; then
    fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'payload de transcode inválido'; return 0
  fi
  # O rascunho vai para o volume do nó, não para /tmp: o compose monta /tmp como
  # tmpfs de 64 MB e o transcode aceita origem de até 150 MB — em /tmp, mídia
  # grande estouraria o espaço e viraria "transcode falhou" sem causa aparente.
  work=$(mktemp -d "$NODE_HOME/work-task-XXXXXX") || {
    fail_task "$task_id" "$attempt" TRANSCODE_FAILED 'sem espaço de trabalho'; return 0; }
  # O caminho vai expandido no trap: no RETURN os locais já não existem.
  trap "rm -rf '$work'" RETURN
  src="$work/source"
  dst="$work/result"
  if ! curl --fail --silent --show-error --connect-timeout 10 --max-time 300 \
      "${bw[@]}" "${auth_bin[@]}" --output "$src" \
      "$CONTROL_PLANE/network/media/$media_id/source"; then
    fail_task "$task_id" "$attempt" TRANSCODE_FAILED 'origem não baixada'; return 0
  fi
  read -r demux mux <<<"$(transcode_formats "$mime")"
  args=(ffmpeg -hide_banner -nostdin -loglevel error -y -protocol_whitelist file
    -f "$demux" -i "file:$src" -map 0:v:0 -map 0:a? -sn -dn -map_metadata -1
    -map_chapters -1 -vf "$TRANSCODE_SCALE")
  while read -r a; do args+=("$a"); done < <(transcode_encoders "$mime")
  [[ "$mime" == video/mp4 ]] && args+=(-movflags +faststart)
  args+=(-f "$mux" "file:$dst")
  if ! "${args[@]}"; then
    fail_task "$task_id" "$attempt" TRANSCODE_FAILED 'ffmpeg falhou'; return 0
  fi
  # Quem decide é o Control Plane: ele mede o arquivo ENQUANTO grava. O nó declara
  # o que ele devolveu (as medidas dele) — o próprio hash é só fallback de rede.
  if ! resp=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 300 \
      "${bw[@]}" "${auth_bin[@]}" -H "Content-Type: $mime" --data-binary @"$dst" \
      -X PUT "$CONTROL_PLANE/network/media/$media_id/result"); then
    fail_task "$task_id" "$attempt" TRANSCODE_FAILED 'upload do resultado falhou'; return 0
  fi
  local key bytes csha
  key=$(json_field "$resp" resultKey)
  bytes=$(json_number "$resp" resultBytes)
  csha=$(json_field "$resp" resultSha256)
  # Sem as medidas do Control Plane não há o que declarar com confiança: o nó
  # declara o medido por ELE (fallback), e o Control Plane confere contra o dele.
  if [[ -z "$csha" || -z "$bytes" ]]; then
    bytes=$(wc -c < "$dst" | tr -d ' ')
    csha=$(sha256sum "$dst" | awk '{print $1}')
    [[ -n "$key" ]] || key=$(json_field "$payload" resultKey)
  fi
  if [[ -z "$key" || -z "$csha" || -z "$bytes" ]]; then
    fail_task "$task_id" "$attempt" TRANSCODE_FAILED 'resultado sem medidas verificaveis'; return 0
  fi
  result "$task_id" "$attempt" DONE \
    "{\"resultKey\":\"$key\",\"resultBytes\":$bytes,\"resultSha256\":\"$csha\"}" ''
  printf '[executor] transcode DONE %s (%s bytes)\n' "$task_id" "$bytes"
}

json_last_string() {
  # json_last_string <json> <chave>: valor da ÚLTIMA chave do objeto, mesmo com
  # aspas escapadas dentro (o body do conteúdo é uma string JSON). Desfaz o escape.
  sed -n 's/.*"'"$2"'":"\(.*\)"}$/\1/p' <<<"$1" | head -n 1 | sed 's/\\"/"/g' | sed 's/\\\\/\\/g'
}

# ---------------- executor real: READ_QUERY (réplica de leitura) ----------------
# A réplica percorre o catálogo do Control Plane (GET /network/replica/{kind}) por
# cursor (criado em, id) — nunca por offset — e, para cada id, lê o corpo em
# GET /network/content/{kind}/{id} e confirma o que guardou em POST .../synced com
# versão e hash. O catálogo só diz o que existe; o corpo vem do mesmo caminho que a
# borda lê, então não há segunda serialização do mesmo agregado.
#
# O que o nó guarda é o corpo público (perfil por @, vitrine de comunidade). Nunca
# dado pessoal de outro participante: a allowlist é do Control Plane e um tipo fora
# dela recebe 403 de lá — o nó não inventa catálogo.
REPLICA_PAGES_PER_TASK="${REPLICA_PAGES_PER_TASK:-20}"

exec_read_query() {
  local next="$1" task_id="$2" attempt="$3"
  local payload kind after_at after_id pages=0 synced=0 skipped=0 failed=0 removed=0
  local page items has_more next_at next_id id stored_version content version hash body
  local config="$4" disk_mb disk_cap_bytes
  local -a bw=()
  mapfile -t bw < <(bandwidth_args "$config")
  payload=$(task_payload "$next")
  # Mesmo teto da borda (limits.diskMb da config assinada): réplica e cache dividem
  # o disco que o participante cedeu — o contador é um só (edge/ + replica/).
  disk_mb=$(config_disk_mb "$config")
  disk_cap_bytes=$((disk_mb * 1024 * 1024))
  kind=$(json_field "$payload" kind)
  [[ -n "$kind" ]] || {
    fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'payload de réplica sem kind'; return 0; }
  # O cursor vive no nó: a travessia é longa e continua na próxima tarefa. O
  # payload pode adiantar um cursor explícito; sem ele, retoma de onde parou.
  local cursor_dir="$NODE_HOME/replica"
  mkdir -p "$cursor_dir"
  after_at=$(json_field "$payload" afterAt)
  after_id=$(json_field "$payload" afterId)
  if [[ -z "$after_at" && -f "$cursor_dir/cursor-$kind" ]]; then
    IFS='|' read -r after_at after_id < "$cursor_dir/cursor-$kind"
  fi
  mkdir -p "$cursor_dir/$kind"

  while (( pages < REPLICA_PAGES_PER_TASK )); do
    local url="$CONTROL_PLANE/network/replica/$kind?limit=100"
    [[ -n "$after_at" && -n "$after_id" ]] && \
      url="$url&afterAt=$(printf '%s' "$after_at" | sed 's/ /%20/g')&afterId=$after_id"
    page=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
      "${auth[@]}" "$url") || {
      fail_task "$task_id" "$attempt" REPLICA_CATALOG_FAILED 'catálogo da réplica indisponível'; return 0; }
    pages=$((pages + 1))
    items=$(json_string_array "$page" items)
    has_more=$(json_flag "$page" hasMore)
    next_at=$(json_field "$page" nextAfterAt)
    next_id=$(json_field "$page" nextAfterId)

    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      # O @ de perfil pode vir com caracteres que precisam ir na URL como estão no
      # contrato (o id é a chave, não um slug): só espaço é codificado.
      local safe_id
      safe_id=$(printf '%s' "$id" | sed 's/ /%20/g')
      stored_version=''
      [[ -f "$cursor_dir/$kind/$safe_id.version" ]] && stored_version=$(head -n 1 "$cursor_dir/$kind/$safe_id.version")
      local content_url="$CONTROL_PLANE/network/content/$kind/$safe_id"
      # Versão conhecida é perguntada: em dia, o Control Plane devolve changed=false
      # sem corpo — o nó não rebaixa o catálogo inteiro a cada rodada.
      [[ -n "$stored_version" ]] && content_url="$content_url?version=$stored_version"
      # Uma requisição só, com o status no fim: --fail engoliria o corpo do 404, e
      # uma segunda chamada só para ler o código dobraria a carga justamente no
      # caso em que o agregado já não existe mais.
      local raw code
      if ! raw=$(curl --silent --show-error --connect-timeout 10 --max-time 60 \
          "${bw[@]}" -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
        failed=$((failed + 1))
        printf '[executor] réplica: item %s/%s ilegível\n' "$kind" "$id" >&2
        continue
      fi
      code=$(sed -n '$p' <<<"$raw")
      content=$(sed '$d' <<<"$raw")
      if [[ "$code" == 404 ]]; then
        # O agregado sumiu da origem (perfil banido, comunidade excluída). Manter o
        # corpo guardado faria o nó servir o que a vitrine esconde — e remoção não
        # vira linha no feed, então é aqui que o nó descobre.
        rm -f "$cursor_dir/$kind/$safe_id.json" "$cursor_dir/$kind/$safe_id.version"
        removed=$((removed + 1))
        printf '[executor] réplica: %s/%s sumiu da origem — descartado\n' "$kind" "$id" >&2
        continue
      fi
      if [[ "$code" != 200 ]]; then
        failed=$((failed + 1))
        printf '[executor] réplica: item %s/%s ilegível (%s)\n' "$kind" "$id" "$code" >&2
        continue
      fi
      if [[ "$(json_flag "$content" changed)" == false ]]; then
        skipped=$((skipped + 1))
        continue
      fi
      body=$(json_last_string "$content" body)
      if [[ -z "$body" ]]; then
        failed=$((failed + 1))
        printf '[executor] réplica: item %s/%s sem corpo\n' "$kind" "$id" >&2
        continue
      fi
      version=$(json_number "$content" version)
      hash=$(json_field "$content" contentHash)
      # Guarda o corpo e a versão ANTES de confirmar: confirmar o que não está em
      # disco faria a reconciliação acusar o nó por um arquivo que ele nunca teve.
      # Teto de disco (limits.diskMb): a réplica é catálogo, não cache — evictar
      # item dela é apagar dado que o nó prometeu ter. Se o corpo não cabe, a
      # travessia para aqui, o cursor NÃO avança sobre o item não gravado e o
      # resultado diz por quê: retomar de onde parou é o único jeito de não pular
      # um agregado em silêncio.
      if ! disk_make_room "$disk_cap_bytes" "${#body}" ""; then
        failed=$((failed + 1))
        printf '[executor] réplica: %s/%s nao cabe no teto de disco declarado\n' "$kind" "$id" >&2
        result "$task_id" "$attempt" DONE \
          "{\"kind\":\"$kind\",\"pages\":$pages,\"synced\":$synced,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"complete\":false,\"diskFull\":true}" ''
        return 0
      fi
      if ! printf '%s' "$body" > "$cursor_dir/$kind/$safe_id.json"; then
        failed=$((failed + 1)); continue
      fi
      printf '%s\n' "$version" > "$cursor_dir/$kind/$safe_id.version"
      if curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
          "${auth[@]}" -d "{\"sourceVersion\":$version,\"contentHash\":\"$hash\"}" \
          "$CONTROL_PLANE/network/content/$kind/$safe_id/synced" >/dev/null; then
        synced=$((synced + 1))
      else
        failed=$((failed + 1))
        printf '[executor] réplica: confirmação de %s/%s falhou\n' "$kind" "$id" >&2
      fi
    done <<<"$items"

    if [[ "$has_more" != true || -z "$next_at" || -z "$next_id" ]]; then
      after_at=''; after_id=''
      : > "$cursor_dir/cursor-$kind"
      printf '[executor] réplica %s: travessia concluída (%s sincronizados, %s em dia, %s removidos)\n' \
        "$kind" "$synced" "$skipped" "$removed"
      result "$task_id" "$attempt" DONE \
        "{\"kind\":\"$kind\",\"pages\":$pages,\"synced\":$synced,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"complete\":true}" ''
      return 0
    fi
    after_at="$next_at"; after_id="$next_id"
    printf '%s|%s\n' "$after_at" "$after_id" > "$cursor_dir/cursor-$kind"
  done

  # Orçamento de páginas deste ciclo esgotado: devolve o cursor e conclui. A
  # próxima tarefa retoma daqui — drenar metade do catálogo não é erro.
  printf '[executor] réplica %s: %s páginas, retomando em %s|%s\n' \
    "$kind" "$pages" "$after_at" "$after_id"
  result "$task_id" "$attempt" DONE \
    "{\"kind\":\"$kind\",\"pages\":$pages,\"synced\":$synced,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"complete\":false,\"afterAt\":\"$after_at\",\"afterId\":\"$after_id\"}" ''
}

# ---------------- executor real: EDGE_CACHE (cache de borda) ----------------
# O cache de borda é alimentado pelo feed de mudanças (V312): GET
# /network/content/changes devolve, depois do cursor do nó, só o que ganhou versão
# nova — kind, contentId, version, contentHash — e nunca o corpo. O corpo continua
# vindo de GET /network/content/{kind}/{id}, o mesmo caminho autorizado da borda.
#
# O cache é descartável, o cursor não: consumir a mudança sem baixar o corpo
# deixaria a entrada velha até o TTL — ou para sempre, se o agregado nunca mais for
# editado. Por isso o cursor só avança depois de a entrada estar em disco; falha no
# meio devolve complete=false e a próxima tarefa retoma daqui.
EDGE_CHANGES_PER_TASK="${EDGE_CHANGES_PER_TASK:-200}"
EDGE_PAGES_PER_TASK="${EDGE_PAGES_PER_TASK:-5}"

# ---------------- limite de disco declarado (fase 5, gestão de permissões) ----------------
# O participante declara quantos MB de disco cede (declared/limits.diskMb) e a rede
# respeita (07-plano-incremental.md, fase 5). O runtime é quem escreve no volume, então
# é quem faz valer: o teto vale para o ESTADO DE CONTEÚDO (edge/ + replica/), não para
# a identidade do nó (node.json, api-key, pino) — perder credencial por estouro de
# conteúdo seria o nó se desligar sozinho.
config_disk_mb() {
  # config_disk_mb <config-json>: MB de disco que o participante cedeu; 0 = sem teto.
  local mb
  mb=$(json_number "$1" diskMb)
  [[ -n "$mb" ]] || mb=0
  printf '%s' "$mb"
}

bandwidth_args() {
  # bandwidth_args <config-json> [payload-json]: argumento de limite de taxa do curl, um por linha;
  # vazio quando o participante não declarou banda (0 = sem teto). O limite declarado
  # é o que ele aceitou ceder de REDE, e o runtime é quem transfere: baixar corpo de
  # conteúdo e subir resultado de mídia passam todos pelo mesmo teto, em
  # --limit-rate. Mbps declarado vira kbytes/s (1 Mbps = 1000/8 = 125 kB/s, o mesmo
  # que o curl chama de k). Um limite por transferência é o agregado que importa
  # aqui: o runtime executa UMA tarefa por ciclo, então não há duas transferências
  # concorrentes competindo pelo mesmo teto.
  local mbps kbps
  mbps=$(json_number "$1" bandwidthMbps)
  # Teto POR TRANSFERENCIA (brief §16): o rebalanceamento pede quanto ceder para um objeto
  # especifico, e o nó aplica o MENOR entre ele e o que declarou. Sem este passo o teto da
  # transferência era gravado pelo CP e nunca chegava ao nó.
  local transfer_mbps
  transfer_mbps=$(json_number "$2" bandwidthLimitMbps)
  if [[ -n "$transfer_mbps" && "$transfer_mbps" != 0 ]]; then
    if [[ -z "$mbps" || "$mbps" == 0 ]] || (( transfer_mbps < mbps )); then
      mbps="$transfer_mbps"
    fi
  fi
  # O teto do TIPO (formato 7, brief §6) vence a banda geral: é o que o participante reservou
  # para este tipo de máquina. Tipo sem limite próprio cai na banda do nó -- ausência é
  # "não especializou", nunca zero, e zero mantém a regra de "sem teto".
  local kind_mbps
  kind_mbps=$(storage_kind_bandwidth "$1")
  [[ -z "$kind_mbps" ]] || mbps="$kind_mbps"
  [[ -n "$mbps" && "$mbps" != 0 ]] || return 0
  kbps=$((mbps * 125))
  printf '%s\n%s\n' --limit-rate "${kbps}k"
}

storage_kind_bandwidth() {
  # storage_kind_bandwidth <config-json>: o menor limite de banda entre os tipos de storage
  # declarados na config (formato 7, brief §6), em Mbps; vazio quando não há lista. O runtime
  # executa uma tarefa por ciclo e o tipo de storage de um nó não muda entre tarefas, então o
  # teto aplicado é o do tipo dele. Com vários tipos, o MENOR: ceder mais do que qualquer tipo
  # declarou nunca é o que o participante aceitou.
  local list="$1" entry kind mbps menor=""
  # A lista é ["TIPO=mbps", ...]; cada entrada é separada por '","'.
  local entries
  entries=$(json_list_sorted "$list" storageBandwidth)
  [[ -n "$entries" ]] || return 0
  while IFS= read -r entry; do
    kind=${entry%%=*}
    mbps=${entry#*=}
    [[ "$mbps" =~ ^[0-9]+$ ]] || continue
    if [[ -z "$menor" ]] || (( mbps < menor )); then
      menor="$mbps"
    fi
  done < <(tr ',' '\n' <<< "$entries" | tr -d '"' | sed 's/^ *//;s/ *$//')
  printf '%s' "$menor"
}

content_bytes() {
  # content_bytes: bytes guardados no ESTADO DE CONTEÚDO do nó (edge/ + replica/ + objects/).
  # Contar $NODE_HOME inteiro incluiria node.json, api-key e o pino — credencial não
  # é conteúdo e não pode ser apagada por estouro de cache.
  #
  # `objects/` entram porque são corpo de verdade em disco depois desta rodada. Sem eles, o
  # teto declarado era medido sem o maior consumidor: o nó dizia usar 2 MB enquanto guardava
  # 40 GB de réplica local.
  local total=0 size f
  for f in "$NODE_HOME"/edge/*/*.json "$NODE_HOME"/edge/*/*.version \
           "$NODE_HOME"/replica/*/*.json "$NODE_HOME"/replica/*/*.version \
           "$NODE_HOME"/objects/*.bin; do
    [[ -f "$f" ]] || continue
    size=$(stat -c %s "$f") || continue
    total=$((total + size))
  done
  printf '%s' "$total"
}

disk_make_room() {
  # disk_make_room <cap-bytes> <bytes-da-nova-entrada> <arquivo-alvo|"">
  #
  # 0 = cabe; 1 = não cabe nem esvaziando o conteúdo. O teto conta o estado de
  # conteúdo (edge/ + replica/), não a identidade do nó. A entrada alvo (que vai ser
  # substituída) não entra na conta do que está ocupado: os bytes dela saem quando o
  # corpo novo entra.
  #
  # Só a BORDA é evictável. A réplica é catálogo: o nó prometeu ter aquele agregado,
  # então apagá-lo para caber conteúdo novo quebraria a promessa em silêncio. Sob
  # pressão sai cache — a borda se recompõe da origem, a réplica não.
  # Evicta a mais antiga primeiro (mtime, desempate por nome) porque num cache o que
  # foi tocado por último é o que tem mais chance de ser pedido de novo.
  local cap="$1" need="$2" keep="$3" current=0 used f oldest oldest_time time
  DISK_EVICTED=0
  (( cap > 0 )) || return 0
  [[ -n "$keep" && -f "$keep" ]] && current=$(stat -c %s "$keep")
  used=$(( $(content_bytes) - current ))
  (( used + need <= cap )) && return 0
  while (( used + need > cap )); do
    oldest=''; oldest_time=''
    for f in "$NODE_HOME"/edge/*/*.json; do
      [[ -f "$f" && "$f" != "$keep" ]] || continue
      time=$(stat -c %Y "$f") || continue
      if [[ -z "$oldest" || "$time" -lt "$oldest_time" \
          || ( "$time" -eq "$oldest_time" && "$f" < "$oldest" ) ]]; then
        oldest="$f"; oldest_time="$time"
      fi
    done
    [[ -n "$oldest" ]] || return 1
    used=$((used - $(stat -c %s "$oldest")))
    rm -f "$oldest" "${oldest%.json}.version"
    DISK_EVICTED=$((DISK_EVICTED + 1))
  done
  return 0
}

json_changes() {
  # json_changes <feed>: uma mudança por linha, como seq|kind|contentId|version|hash.
  # "changes" é o último campo do objeto do feed, então o recorte vai da abertura do
  # array até o fecho do objeto; os itens são separados por "},{".
  local body obj
  body=$(sed -n 's/.*"changes":\[\(.*\)\]}.*/\1/p' <<<"$1" | head -n 1)
  [[ -n "$body" ]] || return 0
  while IFS= read -r obj; do
    [[ -n "$obj" ]] || continue
    printf '%s|%s|%s|%s|%s\n' \
      "$(json_number "$obj" seq)" "$(json_field "$obj" kind)" \
      "$(json_field "$obj" contentId)" "$(json_number "$obj" version)" \
      "$(json_field "$obj" contentHash)"
  # O newline final importa: sem ele, `read` devolve fim de arquivo no último item e
  # a última mudança do feed nunca seria processada.
  done < <(printf '%s\n' "$body" | sed 's/},{/\n/g')
}

exec_edge_cache() {
  local next="$1" task_id="$2" attempt="$3"
  local payload cursor_dir cursor limit feed reset_done=false stalled=false
  local pages=0 stored=0 skipped=0 removed=0 failed=0 evicted=0 dropped=0
  local entries entry seq kind content_id safe_id stored_version content_url raw code content body version hash
  local has_more before
  local config="$4" disk_mb disk_cap_bytes
  local -a bw=()
  mapfile -t bw < <(bandwidth_args "$config")

  payload=$(task_payload "$next")
  # O teto de disco vem da config assinada (limits.diskMb) — a permissão que o
  # participante cedeu e que a rede respeita. 0 = sem teto declarado (nó sem limite
  # grava; é o comportamento de quem não declarou restrição, não de quem declarou).
  disk_mb=$(config_disk_mb "$config")
  disk_cap_bytes=$((disk_mb * 1024 * 1024))
  # O cursor vive no nó, como o da réplica: o payload pode adiantar um, mas sem ele
  # a tarefa retoma do ponto em que a anterior parou. 0 = nunca sincronizou.
  cursor_dir="$NODE_HOME/edge"
  mkdir -p "$cursor_dir"
  cursor=$(json_number "$payload" cursor)
  if [[ -z "$cursor" && -s "$cursor_dir/cursor" ]]; then
    cursor=$(head -n 1 "$cursor_dir/cursor")
  fi
  [[ -n "$cursor" ]] || cursor=0
  limit=$(json_number "$payload" limit)
  [[ -n "$limit" ]] || limit=$EDGE_CHANGES_PER_TASK

  while :; do
    before="$cursor"
    feed=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
      "${auth[@]}" "$CONTROL_PLANE/network/content/changes?cursor=$cursor&limit=$limit") || {
      fail_task "$task_id" "$attempt" EDGE_FEED_FAILED 'feed de mudancas indisponivel'; return 0; }
    pages=$((pages + 1))

    if [[ "$(json_flag "$feed" reset)" == true ]]; then
      # Cursor atrás da janela retida: o que o nó não leu já não existe para reler,
      # e responder "nada mudou" o deixaria servindo conteúdo velho para sempre.
      # Descartar o cache é barato — é cache — e recomeçar do zero é cursor 0, que o
      # Control Plane trata como carga normal (0 nunca é reset).
      rm -f "$cursor_dir"/*/*.json "$cursor_dir"/*/*.version
      cursor=0
      printf '0\n' > "$cursor_dir/cursor"
      printf '[executor] borda: feed podado — cache descartado, recomecando do zero\n' >&2
      # 0 nunca é reset, então a segunda volta não repete; o guarda é contra giro infinito.
      [[ "$reset_done" == true ]] && break
      reset_done=true
      continue
    fi

    has_more=$(json_flag "$feed" hasMore)
    entries=$(json_changes "$feed")
    while IFS='|' read -r seq kind content_id version hash; do
      [[ -n "$seq" && -n "$kind" && -n "$content_id" ]] || continue
      safe_id=$(printf '%s' "$content_id" | sed 's/ /%20/g')
      mkdir -p "$cursor_dir/$kind"
      stored_version=''
      [[ -f "$cursor_dir/$kind/$safe_id.version" ]] && \
        stored_version=$(head -n 1 "$cursor_dir/$kind/$safe_id.version")
      # Versão conhecida é perguntada: em dia, o Control Plane devolve changed=false
      # sem corpo — o nó não rebaixa o cache inteiro a cada mudança do feed.
      content_url="$CONTROL_PLANE/network/content/$kind/$safe_id"
      [[ -n "$stored_version" ]] && content_url="$content_url?version=$stored_version"
      # Uma requisição só, com o status no fim: --fail engoliria o corpo do 404.
      if ! raw=$(curl --silent --show-error --connect-timeout 10 --max-time 60 \
          "${bw[@]}" -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
        failed=$((failed + 1)); stalled=true; break
      fi
      code=$(sed -n '$p' <<<"$raw")
      content=$(sed '$d' <<<"$raw")
      if [[ "$code" == 404 ]]; then
        # Remoção não vira linha no feed: o nó descobre revalidando. Manter o corpo
        # faria a borda servir o que a vitrine esconde.
        rm -f "$cursor_dir/$kind/$safe_id.json" "$cursor_dir/$kind/$safe_id.version"
        removed=$((removed + 1)); cursor=$seq
        printf '%s\n' "$cursor" > "$cursor_dir/cursor"
        continue
      fi
      if [[ "$code" != 200 ]]; then
        failed=$((failed + 1)); stalled=true; break
      fi
      if [[ "$(json_flag "$content" changed)" == false ]]; then
        skipped=$((skipped + 1)); cursor=$seq
        printf '%s\n' "$cursor" > "$cursor_dir/cursor"
        continue
      fi
      body=$(json_last_string "$content" body)
      if [[ -z "$body" ]]; then
        failed=$((failed + 1)); stalled=true; break
      fi
      version=$(json_number "$content" version)
      hash=$(json_field "$content" contentHash)
      # Grava antes de confirmar: confirmar o que não está em disco faria a
      # reconciliação acusar o nó por um arquivo que ele nunca teve.
      # Teto de disco (limits.diskMb): num cache, evictar o mais antigo para caber o
      # novo é o comportamento correto — cache completo é cache útil. Se nem vazio o
      # corpo cabe, aí sim não é cache: a entrada fica fora e o resultado conta isso
      # como `dropped`, não como `unchanged` — dizer "em dia" sobre o que não está
      # guardado seria mentira no painel.
      if ! disk_make_room "$disk_cap_bytes" "${#body}" "$cursor_dir/$kind/$safe_id.json"; then
        evicted=$((evicted + DISK_EVICTED))
        dropped=$((dropped + 1)); cursor=$seq
        printf '%s\n' "$cursor" > "$cursor_dir/cursor"
        printf '[executor] borda: %s/%s nao cabe no teto de disco declarado\n' "$kind" "$content_id" >&2
        continue
      fi
      evicted=$((evicted + DISK_EVICTED))
      if ! printf '%s' "$body" > "$cursor_dir/$kind/$safe_id.json"; then
        failed=$((failed + 1)); stalled=true; break
      fi
      printf '%s\n' "$version" > "$cursor_dir/$kind/$safe_id.version"
      if curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
          "${auth[@]}" -d "{\"sourceVersion\":$version,\"contentHash\":\"$hash\"}" \
          "$CONTROL_PLANE/network/content/$kind/$safe_id/synced" >/dev/null; then
        stored=$((stored + 1))
      else
        failed=$((failed + 1))
        printf '[executor] borda: confirmacao de %s/%s falhou\n' "$kind" "$content_id" >&2
      fi
      cursor=$seq
      printf '%s\n' "$cursor" > "$cursor_dir/cursor"
    done <<<"$entries"

    if [[ "$stalled" == true ]]; then
      printf '[executor] borda: parada em seq=%s (%s falhas)\n' "$cursor" "$failed" >&2
      result "$task_id" "$attempt" DONE \
        "{\"pages\":$pages,\"stored\":$stored,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"evicted\":$evicted,\"dropped\":$dropped,\"complete\":false,\"cursor\":$cursor}" ''
      return 0
    fi
    if [[ "$has_more" != true ]]; then
      printf '[executor] borda: em dia em seq=%s (%s guardados, %s em dia, %s removidos)\n' \
        "$cursor" "$stored" "$skipped" "$removed"
      result "$task_id" "$attempt" DONE \
        "{\"pages\":$pages,\"stored\":$stored,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"evicted\":$evicted,\"dropped\":$dropped,\"complete\":true,\"cursor\":$cursor}" ''
      return 0
    fi
    # Sem avanço não há o que pedir de novo: girar sobre a mesma página não converge.
    if [[ "$cursor" == "$before" ]]; then
      break
    fi
    if (( pages >= EDGE_PAGES_PER_TASK )); then
      printf '[executor] borda: orcamento de %s paginas, retomando em seq=%s\n' \
        "$pages" "$cursor"
      result "$task_id" "$attempt" DONE \
        "{\"pages\":$pages,\"stored\":$stored,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"evicted\":$evicted,\"dropped\":$dropped,\"complete\":false,\"cursor\":$cursor}" ''
      return 0
    fi
  done

  result "$task_id" "$attempt" DONE \
    "{\"pages\":$pages,\"stored\":$stored,\"unchanged\":$skipped,\"removed\":$removed,\"failed\":$failed,\"evicted\":$evicted,\"dropped\":$dropped,\"complete\":false,\"cursor\":$cursor}" ''
}

# ---------------- executor real: EDGE_DELIVER (entrega verificada) ----------------
# Pergunta do Control Plane a um nó de borda específico: "você consegue entregar este
# conteúdo AGORA?". O modelo é pull e o nó NÃO expõe porta — ninguém abre conexão para
# ele —, então entrega não é servir HTTP: é provar que a entrada do cache existe, está
# na versão da origem e está reconfirmada em /synced. O veredicto é sobre o que o nó
# TEM, não sobre o que ele promete: revalidar com ?version= sem reconfirmar atestaria
# o cache de antes, não o estado agora — por isso /synced é obrigatório nos dois
# caminhos. Autorização de usuário continua no Control Plane; o nó só atesta capacidade.
exec_edge_deliver() {
  local next="$1" task_id="$2" attempt="$3"
  local payload kind content_id safe_id entry_dir stored_version
  local content_url raw code content version hash body source verdict
  local config="$4" disk_mb disk_cap_bytes
  local -a bw=()
  mapfile -t bw < <(bandwidth_args "$config")

  payload=$(task_payload "$next")
  # O teto é o mesmo da borda: entrega rebaixa corpo quando a origem está à frente,
  # e o que ela grava conta no disco que o participante cedeu.
  disk_mb=$(config_disk_mb "$config")
  disk_cap_bytes=$((disk_mb * 1024 * 1024))
  kind=$(json_field "$payload" kind)
  content_id=$(json_field "$payload" contentId)
  [[ -n "$kind" && -n "$content_id" ]] || {
    fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'payload de entrega sem kind ou contentId'; return 0; }
  safe_id=$(printf '%s' "$content_id" | sed 's/ /%20/g')
  entry_dir="$NODE_HOME/edge/$kind"
  mkdir -p "$entry_dir"

  stored_version=''
  [[ -f "$entry_dir/$safe_id.version" ]] && stored_version=$(head -n 1 "$entry_dir/$safe_id.version")
  content_url="$CONTROL_PLANE/network/content/$kind/$safe_id"
  [[ -n "$stored_version" ]] && content_url="$content_url?version=$stored_version"
  # Uma requisição só, com o status no fim: --fail engoliria o corpo do 404.
  if ! raw=$(curl --silent --show-error --connect-timeout 10 --max-time 60 \
      "${bw[@]}" -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
    verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_ORIGIN_UNREADABLE\"}"
    result "$task_id" "$attempt" DONE "$verdict" ''
    return 0
  fi
  code=$(sed -n '$p' <<<"$raw")
  content=$(sed '$d' <<<"$raw")
  if [[ "$code" == 404 ]]; then
    # O agregado sumiu da origem: entregar é impossível e manter o corpo guardado
    # faria a borda servir o que a vitrine esconde. Veredicto negativo por remoção
    # alheia é verdade de capacidade, não falha do nó — o nó não é punido por ele.
    rm -f "$entry_dir/$safe_id.json" "$entry_dir/$safe_id.version"
    verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"CONTENT_GONE\"}"
    result "$task_id" "$attempt" DONE "$verdict" ''
    return 0
  fi
  if [[ "$code" != 200 ]]; then
    verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_ORIGIN_UNREADABLE\"}"
    result "$task_id" "$attempt" DONE "$verdict" ''
    return 0
  fi

  if [[ "$(json_flag "$content" changed)" == false ]]; then
    # Em dia: a entrada que o nó tem É a versão da origem. A confirmação ainda vai —
    # é ela que deixa o veredicto conferível pelo Control Plane (snapshot do lado de lá).
    version="$stored_version"
    hash=$(json_field "$content" contentHash)
    source="cache"
  else
    body=$(json_last_string "$content" body)
    if [[ -z "$body" ]]; then
      verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_ORIGIN_UNREADABLE\"}"
      result "$task_id" "$attempt" DONE "$verdict" ''
      return 0
    fi
    version=$(json_number "$content" version)
    hash=$(json_field "$content" contentHash)
    # Grava antes de confirmar, como no EDGE_CACHE: atestar o que não está em disco
    # é declaração sem lastro. O teto de disco vale aqui igual: entrada de borda é
    # cache, então evictar o mais antigo para caber o novo é o certo.
    if ! disk_make_room "$disk_cap_bytes" "${#body}" "$entry_dir/$safe_id.json"; then
      verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_DISK_FAILED\"}"
      result "$task_id" "$attempt" DONE "$verdict" ''
      return 0
    fi
    if ! printf '%s' "$body" > "$entry_dir/$safe_id.json"; then
      verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_DISK_FAILED\"}"
      result "$task_id" "$attempt" DONE "$verdict" ''
      return 0
    fi
    printf '%s\n' "$version" > "$entry_dir/$safe_id.version"
    source="refetched"
  fi
  if ! curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
      "${auth[@]}" -d "{\"sourceVersion\":$version,\"contentHash\":\"$hash\"}" \
      "$CONTROL_PLANE/network/content/$kind/$safe_id/synced" >/dev/null; then
    verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"deliverable\":false,\"reason\":\"EDGE_CONFIRM_FAILED\"}"
    result "$task_id" "$attempt" DONE "$verdict" ''
    return 0
  fi
  verdict="{\"kind\":\"$kind\",\"contentId\":\"$content_id\",\"version\":$version,\"contentHash\":\"$hash\",\"deliverable\":true,\"source\":\"$source\"}"
  result "$task_id" "$attempt" DONE "$verdict" ''
  printf '[executor] entrega %s/%s v%s verificada (%s)\n' "$kind" "$content_id" "$version" "$source"
}

# ---------------- executor real: API_REQUEST (rota autorizada) ----------------
# O nó pode chamar um destino PREVISTO — nunca um destino que venha no payload. Um nó
# que aceita URL de quem pede é um SSRF para dentro da rede do participante (o alvo
# clássico é o endereço de metadados da nuvem, mas o mesmo caminho alcança o roteador,
# o banco e o painel do hypervisor). Por isso o destino mora na CONFIG ASSINADA
# (allowedRoutes, formato 4): o payload só diz o NOME da rota, e o nome é resolvido
# contra a lista assinada. Destino fora da lista não existe.
#
# O que o nó NÃO faz aqui:
#   - não repassa a credencial dele (X-Node-Key) para o destino: a rota é terceiro, e
#     a chave do nó é identidade — mandá-la seria entregar credencial a quem não
#     autorizou (doc 02, linha "distribuir credencial do banco");
#   - não segue redirecionamento: um 302 para dentro da rede repetiria o SSRF com a
#     autorização do dono da rota;
#   - não devolve o corpo da resposta. O resultado é EVIDÊNCIA (status, bytes,
#     digest), não o conteúdo — a resposta pode ser dado que o Control Plane não
#     pediu para transportar.
API_REQUEST_MAX_BYTES="${API_REQUEST_MAX_BYTES:-1048576}"
API_REQUEST_TIMEOUT="${API_REQUEST_TIMEOUT:-30}"

route_lookup() {
  # route_lookup <config-json> <nome>: imprime a URL da rota; 1 se o nome não estiver
  # na lista assinada. A lista vem da config VERIFICADA (a assinatura já foi conferida
  # contra o pino quando a config chegou), então o que está aqui é o que o Control
  # Plane autorizou — e só.
  #
  # A lista é lida do array JSON inteiro, e NÃO item a item pelo json_string_array: aquele
  # helper corta a vírgula como separador, e a vírgula é caractere legítimo de URL (query
  # string) — cortar ali daria um destino DIFERENTE do assinado, e a chamada iria para um
  # caminho que ninguém autorizou. Aqui a quebra é pela vírgula QUE SEPARA ITENS do array
  # (o padrão ""," entre dois elementos), que só aparece entre dois elementos — uma vírgula
  # dentro da URL não é seguida de aspas.
  local config="$1" name="$2" array item
  [[ -n "$name" ]] || return 1
  # O fecho do array é o PRIMEIRO "]" depois da abertura: o Control Plane não aceita
  # destino com "]" (ele fecharia a lista), e um "(.*)" guloso pegaria até o último
  # "]" da linha — que seria o de outra lista, se alguma viesse depois.
  array=$(sed -n 's/.*"allowedRoutes":\[\([^]]*\)\].*/\1/p' <<<"$config" | head -n 1)
  [[ -n "$array" ]] || return 1
  while IFS= read -r item; do
    [[ -n "$item" ]] || continue
    item=${item#\"}
    item=${item%\"}
    # Entrada sem "=" não tem nome nem destino: não é rota, é lixo na config.
    [[ "$item" == *=* ]] || continue
    [[ "${item%%=*}" == "$name" ]] || continue
    printf '%s' "${item#*=}"
    return 0
  done < <(printf '%s\n' "$array" | sed 's/","/\n/g')
  return 1
}

# ---------------- executor real: OBJECT_PUT (replica de objeto do Storage) ----------------
# O Control Plane decidiu que este no guarda uma copia e enfileirou a tarefa. O no
# PUXA o corpo na URL do Control Plane (ele nao conhece o endereco de outro no --
# brief §17), confere o que baixou contra o medido pelo CP e confirma. Quem prova a
# copia e o CP: ele mede de novo o que o no declara.
#
# O arquivo NAO fica em NODE_HOME/replica: `disk_make_room` trata replica/ como
# promessa (nao evictavel) e o CP e quem decide o que o no hospeda, por colocacao no
# catalogo. Guardar aqui criaria duas verdades sobre a mesma copia. Este executor baixa,
# confere e confirma -- retencao local entra quando houver politica de cache que o CP
# consiga reconciliar (ver docs/rede-distribuida/13-storage-fase-3-replicacao.md §5.3).
exec_object_put() {
  local next="$1" task_id="$2" attempt="$3" config="${4:-}"
  local payload object_id expect_sha expect_bytes replica tmp got_sha got_bytes resp code
  local -a bw=()

  payload=$(task_payload "$next")
  # O payload entra no teto: uma transferência de rebalanceamento pede quanto ceder para ESTE
  # objeto, e vale o menor entre esse pedido e a banda declarada (bandwidth_args).
  mapfile -t bw < <(bandwidth_args "$config" "$payload")
  object_id=$(json_field "$payload" objectId)
  expect_sha=$(json_field "$payload" sha256)
  expect_bytes=$(json_number "$payload" bytes)
  replica=$(json_number "$payload" replica)
  if [[ -z "$object_id" || -z "$expect_sha" || -z "$expect_bytes" ]]; then
    fail_task "$task_id" "$attempt" STORAGE_TASK_PAYLOAD_INVALID \
      'tarefa OBJECT_PUT sem objectId/sha256/bytes'
    return 0
  fi

  # Onde o corpo FICA: o objeto e guardado no proprio disco deste no, no diretorio de corpos.
  # Antes o executor baixava, confirmava e apagava -- o catalogo dizia "copia OK aqui" e o
  # arquivo nao existia em lugar nenhum. Diretorio proprio (nao `replica/`, que disk_make_room
  # trata como promessa nao evictavel e cuja poda e do CP).
  local objects_dir="$NODE_HOME/objects"
  mkdir -p "$objects_dir" || {
    fail_task "$task_id" "$attempt" STORAGE_TMP_UNAVAILABLE 'sem diretorio para o corpo'; return 0; }
  tmp=$(mktemp "$objects_dir/object-put.XXXXXX" 2>/dev/null) || {
    fail_task "$task_id" "$attempt" STORAGE_TMP_UNAVAILABLE 'sem diretorio para o corpo temporario'; return 0; }

  # O corpo vem pelo mesmo canal autenticado de toda tarefa, com o mesmo teto de banda
  # que o participante declarou ceder: baixar corpo e subir resultado passam pelo mesmo
  # limite porque e ele quem transfere. `-s` e obrigatorio: sem ele o curl copia o CORPO
  # para a saida padrao, e o painel do no receberia bytes de conteudo de usuario como se
  # fossem linha de log.
  if ! curl --fail --silent --show-error --connect-timeout 10 --max-time 1800 \
      ${bw[@]+"${bw[@]}"} "${auth[@]}" -o "$tmp" \
      "$CONTROL_PLANE/network/storage/objects/$object_id"; then
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_FETCH_FAILED 'nao foi possivel baixar o objeto'
    return 0
  fi

  # A MEDIDA e do runtime, nao do payload: o que veio pela rede e o que se confere.
  got_sha=$(sha256sum "$tmp" | cut -d' ' -f1)
  got_bytes=$(stat -c %s "$tmp")
  if [[ "$got_sha" != "$expect_sha" || "$got_bytes" != "$expect_bytes" ]]; then
    # Divergencia nao e transitoria: baixar de novo daria o mesmo corpo. O no NAO confirma
    # -- confirmar aqui seria gravar a prova de uma copia que nao confere.
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_REPLICA_MISMATCH \
      "corpo divergente (sha=$got_sha bytes=$got_bytes esperado sha=$expect_sha bytes=$expect_bytes)"
    return 0
  fi

  # Capacidade: o teto declarado e o que o participante cedeu. O corpo ja esta em disco,
  # entao a verificacao e ANTES de manter: se nao cabe, o temporario sai e a tarefa falha
  # com motivo proprio, em vez de encher o disco de quem contribui.
  local disk_mb disk_cap_bytes
  disk_mb=$(config_disk_mb "$config")
  disk_cap_bytes=$((disk_mb * 1024 * 1024))
  if (( disk_cap_bytes > 0 )) && (( got_bytes > disk_cap_bytes )); then
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_OBJECT_TOO_LARGE \
      "objeto de $got_bytes bytes excede os ${disk_mb}MB cedidos"
    return 0
  fi
  # O teto não é "este objeto cabe no disco", é "o que o Énóis ocupa aqui, somando este objeto,
  # continua dentro do que o participante cedeu" (brief §7, "não permitir que o armazenamento do
  # Énóis consuma espaço além do limite configurado"). Antes só o tamanho do objeto era comparado
  # com o teto: dez réplicas de 900MB passavam os dez testes num disco cedido de 1GB, e o
  # `content_bytes` do nó — que já conta `objects/*.bin` — mostrava o estouro no heartbeat sem
  # ninguém recusar. A réplica não é evictável (é promessa de catálogo), então aqui não há
  # esvaziamento: se não cabe somado, a tarefa falha com motivo próprio e o CP designa outro nó.
  local alvo="$objects_dir/$object_id.bin"
  if ! disk_make_room "$disk_cap_bytes" "$got_bytes" "$alvo"; then
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_NO_DISK_ROOM \
      "objeto de $got_bytes bytes nao cabe no espaco cedido (${disk_mb}MB) somado ao que ja esta guardado"
    return 0
  fi

  # O corpo so ganha o nome final DEPOIS da confirmacao: o CP ja aceitou que este no guarda o
  # objeto, e um processo morto entre as duas etapas deixa um `.partial` (varrido no proximo
  # ciclo), nunca um corpo servivel que o catalogo nao conhece.
  resp=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
    "${auth[@]}" -H 'Content-Type: application/json' -d \
    "{\"bytes\":$got_bytes,\"sha256\":\"$got_sha\"}" \
    "$CONTROL_PLANE/network/storage/objects/$object_id/replica") || {
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_REPLICA_REFUSED 'confirmacao de replica recusada'
    return 0; }
  mv -f "$tmp" "$objects_dir/$object_id.bin" || {
    rm -f "$tmp"
    fail_task "$task_id" "$attempt" STORAGE_BODY_COMMIT_FAILED 'nao foi possivel guardar o corpo'
    return 0; }

  # O output leva as medidas QUE ESTE NO mediu -- o CP confere contra as dele e recusa
  # DONE sem copia confirmada. `code` vazio = confirmado.
  result "$task_id" "${attempt:-1}" DONE \
    "{\"objectId\":\"$object_id\",\"replica\":${replica:-0},\"bytes\":$got_bytes,\"sha256\":\"$got_sha\"}" ''
  printf '[executor] DONE %s (replica %s de %s, %s bytes)\n' "$task_id" "${replica:-?}" "$object_id" "$got_bytes"
}

exec_storage_scrub() {
  # exec_storage_scrub: confere o que este no guarda no proprio disco e devolve o que nao confere.
  #
  # O Control Plane so rele o corpo do disco da instalacao em que roda (doc 13 §5.7). A copia daqui
  # so este no pode conferir: ele e o unico com acesso ao arquivo (o CP nao abre conexao para o no).
  # Antes, um corpo trocado neste disco continuava contando como durabilidade no catalogo.
  #
  # Roda DEPOIS do heartbeat e ANTES de pedir tarefa: uma varredura longa num disco grande nao pode
  # atrasar o heartbeat, que e a unica saida do no. Sem objetos, a saida e imediata.
  local permitted="${1:-}" objects_dir="$NODE_HOME/objects" f id sha lote primeiro resp stale
  [[ -d "$objects_dir" ]] || return 0
  # Quem guarda objeto e no de armazenamento. Rodar em no que nao guarda mandaria relatorio vazio
  # todo ciclo e so faria volume no log do Control Plane.
  grep -q '"OBJECT_PUT"' <<<"[$permitted]" || return 0

  lote=''
  primeiro=1
  enviar() {
    [[ -n "$lote" ]] || return 0
    resp=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
      "${auth[@]}" -d "[$lote]" "$CONTROL_PLANE/network/storage/scrub") || {
      # Nao e transitorio no sentido de repetir agora: a proxima volta reenvia. O estado do
      # catalogo so muda quando o CP ACEITA o relatorio -- um no que falha em entregar nao pode
      # declarar que a copia esta boa.
      event warn 'varredura do disco nao foi entregue ao Control Plane'
      return 0; }
    stale=$(json_number "$resp" stale)
    (( ${stale:-0} > 0 )) && event warn "varredura: ${stale} copia(s) deste no nao conferem (marcadas STALE)"
    lote=''
    primeiro=1
  }

  for f in "$objects_dir"/*.bin; do
    [[ -f "$f" ]] || continue
    id="${f##*/}"
    id="${id%.bin}"
    sha=$(sha256sum "$f" 2>/dev/null | cut -d' ' -f1)
    # Arquivo ilegivel: NAO entra no relatorio. "Nao consegui ler" e diferente de "leu e divergiu";
    # declarar divergencia aqui derrubaria uma copia boa por um erro de I/O passageiro.
    [[ -n "$sha" ]] || continue
    [[ "$primeiro" == 1 ]] || lote+=','
    lote+="{\"objectId\":\"$id\",\"actualSha256\":\"$sha\"}"
    primeiro=0
    # Lote pequeno: o corpo de cada item e curto, e um relatorio gigante numa request so pode
    # estourar o limite do servidor no meio -- reenviar tudo de novo seria pior.
    if [[ $((${#lote} / 128)) -ge 200 ]]; then
      enviar
    fi
  done
  enviar
  return 0
}

exec_api_request() {
  local next="$1" task_id="$2" attempt="$3"
  local config="${4:-}"
  local payload route method url raw code work file bytes digest status
  local -a bw=()
  mapfile -t bw < <(bandwidth_args "$config")

  payload=$(task_payload "$next")
  route=$(json_field "$payload" route)
  if [[ -z "$route" ]]; then
    fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'payload sem route'; return 0
  fi
  # O nome da rota é um identificador, não texto livre: ele volta no resultado, e um nome
  # com aspas ou barra inverteria o JSON do resultado — ou pior, apareceria como campo.
  [[ "$route" =~ ^[A-Za-z0-9._-]+$ ]] || {
    fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'nome de rota inválido'; return 0; }
  # Só GET e HEAD: qualquer método que escreva no destino muda o mundo de quem
  # assinou a rota, e a rota foi assinada para ser consultada.
  method=$(json_field "$payload" method)
  [[ -n "$method" ]] || method='GET'
  case "$method" in
    GET|HEAD) ;;
    *) fail_task "$task_id" "$attempt" TASK_PAYLOAD_INVALID 'método não permitido na rota'; return 0 ;;
  esac

  # Destino: só da lista assinada. Não há fallback, não há "se não achar, usa o que veio".
  if ! url=$(route_lookup "$config" "$route"); then
    fail_task "$task_id" "$attempt" API_ROUTE_NOT_ALLOWED "rota não autorizada: $route"; return 0
  fi
  # http/https e nada mais: um destino com outro esquema não é rota de API.
  case "$url" in
    http://*|https://*) ;;
    *) fail_task "$task_id" "$attempt" API_ROUTE_NOT_ALLOWED 'destino da rota não é http(s)'; return 0 ;;
  esac

  work=$(mktemp -d "$NODE_HOME/work-api-XXXXXX") || {
    fail_task "$task_id" "$attempt" API_REQUEST_FAILED 'sem espaço de trabalho'; return 0; }
  trap "rm -rf '$work'" RETURN
  file="$work/body"
  # Sem "${auth[@]}": a credencial do nó não vai para o destino. Sem -L: um
  # redirecionamento seria um destino novo, e destino novo não está autorizado.
  # Com --max-filesize, o curl aborta quando o tamanho declarado é grande demais.
  if ! raw=$(curl --silent --show-error --connect-timeout 10 --max-time "$API_REQUEST_TIMEOUT" \
      "${bw[@]}" --max-filesize "$API_REQUEST_MAX_BYTES" -X "$method" \
      -o "$file" -w '%{http_code}' "$url"); then
    fail_task "$task_id" "$attempt" API_REQUEST_FAILED 'requisição à rota falhou'; return 0
  fi
  code="$raw"
  status="${code:-0}"
  bytes=0
  digest=''
  if [[ -f "$file" ]]; then
    bytes=$(stat -c %s "$file" 2>/dev/null || echo 0)
    digest=$(sha256sum "$file" 2>/dev/null | awk '{print $1}')
  fi
  # Teto conferido no corpo real: --max-filesize só olha o Content-Length declarado,
  # e um servidor que não declara nada passa por ele.
  if (( bytes > API_REQUEST_MAX_BYTES )); then
    fail_task "$task_id" "$attempt" API_REQUEST_FAILED 'resposta da rota acima do teto'; return 0
  fi
  # Status e medida são evidência; o corpo NÃO volta. Um 404 é resposta válida da
  # rota — o nó cumpriu o contrato e o Control Plane decide o que fazer com isso.
  result "$task_id" "$attempt" DONE \
    "{\"route\":\"$route\",\"status\":$status,\"bytes\":$bytes,\"digest\":\"$digest\"}" ''
  printf '[executor] rota %s status=%s bytes=%s\n' "$route" "$status" "$bytes"
}

cycle() {
  # 1. heartbeat: estado, modo e se pode pedir tarefa.
  local hb mode accept_tasks observed_cpu observed_mem observed_avail mem_swap mem_pressure observed_disk observed_term term_part disk_part mem_part
  local disk_now disk_prev disk_prev_at disk_now_ns d_reads d_writes d_ms p_reads p_writes p_ms ops disk_io_iops disk_io_latency disk_io_part=
  observed_cpu=$(awk 'NR==1 {printf "%.1f", $1}' /proc/loadavg 2>/dev/null || echo 0)
  # Memória em DUAS medidas, como o Control Plane lê desde a V378: `memoryMb` é o USO e
  # `memoryAvailableMb` o DISPONÍVEL. Antes o runtime media só `MemAvailable` e o mandava como
  # `memoryMb`, que o Control Plane compara com o teto cedido — um nó com 7,9 GB livres de 8 GB
  # declarados saía do balanceamento como "teto atingido", com a máquina ociosa. O uso é
  # `MemTotal - MemAvailable` (a definição que o próprio relatório do sistema usa); swap é
  # `SwapUsed` (o DEFAULT do kernel já é `SwapTotal - SwapFree`). Sem `/proc/meminfo` a medida não
  # vai: ausente é "não medido", e 0 seria "máquina ociosa" para o que ninguém leu.
  mem_part=''
  if [[ -r /proc/meminfo ]]; then
    mem_medido=$(awk '
      /^MemTotal:/ {total = $2}
      /^MemAvailable:/ {avail = $2}
      /^SwapTotal:/ {stotal = $2}
      /^SwapFree:/ {sfree = $2}
      END {
        if (avail == "" || total == "") exit 1
        printf "%d %d", int((total - avail) / 1024), int(avail / 1024)
        if (stotal != "") printf " %d", int((stotal - sfree) / 1024)
      }' /proc/meminfo 2>/dev/null) || mem_medido=''
    if [[ -n "$mem_medido" ]]; then
      read -r observed_mem observed_avail mem_swap <<<"$mem_medido"
      mem_part="\"memoryMb\":$observed_mem,\"memoryAvailableMb\":$observed_avail"
      [[ "${mem_swap:-}" =~ ^[0-9]+$ ]] && mem_part="$mem_part,\"swapUsedMb\":$mem_swap"
    fi
  fi
  # Pressão de memória (brief §8.2, "pressão de memória"): o PSI do kernel mede o tempo em que
  # tarefas ficaram paradas esperando memória — o número que diz "o nó está sofrendo" antes de o
  # uso chegar ao teto. Média de 300 s, em porcentagem; sem o arquivo (kernel anterior a 4.20, ou
  # PSI desligado no boot) o campo NÃO vai.
  if [[ -r /proc/pressure/memory ]]; then
    mem_pressure=$(awk '
      /^some/ {for (i = 1; i <= NF; i++) if ($i ~ /^avg300=/) {v = $i; sub(/^avg300=/, "", v); printf "%.1f", v}}
    ' /proc/pressure/memory 2>/dev/null)
    [[ -n "$mem_pressure" ]] && mem_part="$mem_part,\"memoryPressurePercent\":$mem_pressure"
  fi
  # Disco livre MEDIDO no diretório de estado do nó — é este que o Control Plane mostra ao lado do
  # `declared_disk_mb` do enroll e que a capacidade do Storage leva em conta. O declarado é o que o
  # participante prometeu; este é o que existe. Sem a medida, um nó que perdeu disco continua sendo
  # tratado como se tivesse tudo que prometeu.
  #
  # Vazio quando o `df` não responde: no contrato, nulo é "não reportado", e mandar 0 diria "sem
  # espaço" — o oposto do que se sabe. `-P` dá uma linha por sistema de arquivos, sem quebra de
  # linha para nomes com espaço.
  observed_disk=$(df -P -m "$NODE_HOME" 2>/dev/null | awk 'NR==2 {print $4}')
  [[ "$observed_disk" =~ ^[0-9]+$ ]] || observed_disk=''
  # IOPS e latência de disco (brief §8.2), medidos como DELTA entre este ciclo e o anterior em
  # `/proc/diskstats` — o arquivo apontado por `DISKSTATS_FILE`, que em produção é o do kernel. Ler o
  # contador absoluto e mandar o total seria inútil: os campos são taxa e tempo por operação.
  #
  # O denominador é o intervalo desde a MEDIDA anterior, gravado em NANOSSEGUNDOS neste mesmo
  # arquivo de estado. Tempo de arquivo (`stat -c %Y`) seria mais barato, mas a precisão dele varia
  # com o sistema de arquivos da máquina do participante — e num intervalo de 5 s, 1 s de erro
  # vira 20% de IOPS. O relógio do runtime é a única fonte que tem a mesma resolução no `/tmp`, em
  # tmpfs e em qualquer volume.
  #
  # O que conta como disco: `sd*`, `nvme*n1`, `vd*`, `xvd*` e `hd*` — os nomes que o kernel dá aos
  # discos físicos e virtuais. Ficam de fora partições (`sda1`) e dispositivos de bloco que não são
  # disco (loop, ram, dm, md): o I/O deles já aparece no disco de baixo, e somar os dois contaria
  # em dobro. Contadores não numéricos (disco removido) são ignorados.
  #
  # SEM MEDIDA no primeiro ciclo (não há anterior para subtrair) e sem amostra válida quando o
  # intervalo é zero ou algum contador retrocedeu (disco trocado, contador de 32 bits reiniciado):
  # nesses casos o campo NÃO vai. Retrocesso por campo é o que impede uma taxa falsa e enorme de
  # virar o "IOPS" do nó no painel.
  disk_io_part=''
  if [[ -r "$DISKSTATS_FILE" ]]; then
    disk_now=$(awk '
      {
        name = $3
        if (name !~ /^(sd[a-z]+|nvme[0-9]+n[0-9]+|vd[a-z]+|xvd[a-z]+|hd[a-z]+)$/) next
        # Layout do kernel: 4 = leituras concluídas, 6 = setores lidos, 7 = ms lendo,
        # 8 = escritas concluídas, 10 = setores escritos, 11 = ms escrevendo. As colunas de
        # SETORES (6 e 10) não são tempo: usar 6/10 para latência daria sempre 0 ms.
        if (NF < 11) next
        reads += $4; writes += $8
        ms += ($7 ~ /^[0-9]+$/ ? $7 : 0) + ($11 ~ /^[0-9]+$/ ? $11 : 0)
        seen = 1
      }
      END {
        if (!seen) exit 0
        printf "%d %d %d", reads, writes, ms
      }' "$DISKSTATS_FILE") || disk_now=''
    if [[ -n "$disk_now" ]]; then
      disk_prev_at=''
      disk_prev=''
      if [[ -r "$DISKSTATE_FILE" ]]; then
        read -r disk_prev_at disk_prev < "$DISKSTATE_FILE" 2>/dev/null || true
      fi
      if [[ "$disk_now" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ && "$disk_prev" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ \
            && "$disk_prev_at" =~ ^[0-9]+$ ]]; then
        disk_now_ns=$(date +%s%N)
        if [[ "$disk_now_ns" =~ ^[0-9]+$ ]] && (( disk_now_ns > disk_prev_at )); then
          read -r d_reads d_writes d_ms <<<"$disk_now"
          read -r p_reads p_writes p_ms <<<"$disk_prev"
          if (( d_reads >= p_reads && d_writes >= p_writes && d_ms >= p_ms )); then
            ops=$(( (d_reads - p_reads) + (d_writes - p_writes) ))
            disk_io_iops=$(awk -v o="$ops" -v ns="$(( disk_now_ns - disk_prev_at ))" \
              'BEGIN {printf "%.2f", o / (ns / 1000000000)}')
            disk_io_part="\"diskIops\":$disk_io_iops"
            # Latência só faz sentido com operação concluída: sem I/O o tempo total é zero, e dividir
            # afirmaria "0 ms", que não mede coisa nenhuma.
            if (( ops > 0 )); then
              disk_io_latency=$(awk -v ms="$(( d_ms - p_ms ))" -v o="$ops" 'BEGIN {printf "%.2f", ms / o}')
              disk_io_part="$disk_io_part,\"diskLatencyMs\":$disk_io_latency"
            fi
          fi
        fi
      fi
      # O estado do próximo ciclo é o de AGORA — mesmo quando esta rodada não mediu: se o arquivo
      # não avançasse, um contador reiniciado deixaria o nó sem I/O até o processo reiniciar.
      disk_now_ns=$(date +%s%N)
      if [[ "$disk_now_ns" =~ ^[0-9]+$ ]]; then
        printf '%s %s\n' "$disk_now_ns" "$disk_now" > "$DISKSTATE_FILE" 2>/dev/null \
          && chmod 600 "$DISKSTATE_FILE" 2>/dev/null || true
      fi
    fi
  fi
  # Tamanho do volume medido na MESMA chamada do livre: os dois saem da mesma linha do `df`, então
  # um não pode estar descrevendo um sistema de arquivos diferente do outro. É o que permite ao
  # Control Plane separar "espaço reservado pelo participante" (total - declarado) do "oferecido"
  # (brief do Storage §7); com só o livre, a reserva era impossível de calcular. Mesma regra de
  # ausência: vazio quando o `df` não responde, nunca 0.
  observed_disk_total=$(df -P -m "$NODE_HOME" 2>/dev/null | awk 'NR==2 {print $2}')
  [[ "$observed_disk_total" =~ ^[0-9]+$ ]] || observed_disk_total=''
  # O termo aceito vai no heartbeat (doc 06 §4, regra 5): é assim que o Control Plane
  # descobre que este nó viu um controlador de mando maior que o dele. Nó que ainda não
  # aceitou config nenhuma não reporta termo — zero afirmaria "vi o termo zero", que é
  # diferente de não ter visto termo nenhum.
  observed_term=''
  [[ -s "$NODE_HOME/term" ]] && observed_term=$(head -n 1 "$NODE_HOME/term")
  term_part=''
  [[ -n "$observed_term" ]] && term_part=",\"observedTerm\":$observed_term"
  disk_part=''
  [[ -n "$observed_disk" ]] && disk_part=",\"diskFreeMb\":$observed_disk"
  [[ -n "$observed_disk_total" ]] && disk_part="$disk_part,\"diskTotalMb\":$observed_disk_total"
  # Uso do ESTADO DE CONTEÚDO (edge/ + replica/ + objects/), o mesmo que o teto mede. Sem ele o
  # Control Plane só sabia somar o catálogo e nunca comparava, nó por nó, "quanto ele usa" com
  # "quanto ele prometeu" -- a pergunta do §7 do brief do Storage. Zero é resposta legítima (nó
  # sem conteúdo), então o campo vai sempre que o cálculo roda; `df` que falhou não afeta.
  disk_part="$disk_part,\"usedMb\":$(( $(content_bytes) / 1048576 ))"
  # Tráfego ACUMULADO das interfaces desde o boot (brief §8.2, "uso de rede"): o Control Plane já
  # tem as colunas `observed_bytes_in_mb` / `observed_bytes_out_mb` (V345) e o heartbeat já aceita
  # os campos — mas o runtime nunca os mediu, e o painel do nó não mostrava tráfego nenhum. Vai em
  # MiB, como as outras medidas de tamanho; loopback fica de fora. Sem `/proc/net/dev` os campos
  # NÃO vão: ausente é "não medido", nunca zero, que afirmaria "não transferiu nada".
  net_part=''
  if [[ -r /proc/net/dev ]]; then
    net_medido=$(awk -F'[: ]+' '/^[ ]*[a-z]/ && $2 != "lo" { rx += $3; tx += $11 } END { printf "%d %d", rx / 1048576, tx / 1048576 }' /proc/net/dev)
    read -r net_rx_mb net_tx_mb <<< "$net_medido"
    if [[ "$net_rx_mb" =~ ^[0-9]+$ && "$net_tx_mb" =~ ^[0-9]+$ ]]; then
      net_part=",\"bytesInMb\":$net_rx_mb,\"bytesOutMb\":$net_tx_mb"
    fi
  fi
  # Linhas do ciclo vão JUNTO do heartbeat: é a única saída do nó (ele não abre porta), e o
  # Control Plane guarda uma janela curta para o painel do nó. O buffer é esvaziado depois de
  # enviado; se o heartbeat falhar, as linhas ficam para a próxima volta.
  local events logs_part=""
  events=$(events_json)
  [[ -n "$events" ]] && logs_part=",\"logs\":[$events]"
  hb=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" -d "{\"runtimeVersion\":\"$RUNTIME_VERSION\",\"observed\":{\"cpuPercent\":$observed_cpu,\"runningTasks\":0$mem_part$disk_io_part$disk_part$net_part}$term_part$logs_part}" \
    "$CONTROL_PLANE/network/nodes/heartbeat") || { printf '[executor] heartbeat falhou\n' >&2; return 1; }
  : > "$EVENTS_FILE" 2>/dev/null || true
  mode=$(json_field "$hb" mode)
  accept_tasks=$(grep -q '"acceptTasks":true' <<<"$hb" && echo true || echo false)
  # O status do Control Plane é a resposta à pergunta "fui aceito?" — e antes ele existia no
  # corpo do heartbeat e morria ali. Um nó PENDING_APPROVAL e um nó aprovado mas em modo sem
  # despacho registravam a MESMA linha (`modo=X acceptTasks=false`), e o operador não tinha
  # como saber se faltava a aprovação do admin ou uma condição da rede. Agora o motivo sai
  # por extenso, e só quando muda: transição é o que importa, não o ciclo.
  status=$(json_field "$hb" status)
  if [[ "$accept_tasks" == true ]]; then
    notice_once "aceito-${status:-?}-${mode:-?}" "aceito pela rede — status=${status:-?} modo=${mode:-?} (aceita tarefas)"
  else
    case "${status:-}" in
      PENDING_APPROVAL)
        notice_once 'aguardando-aprovacao' \
          "aguardando aprovação — o nó existe e autentica, mas o admin ainda não aprovou (status=$status)" ;;
      QUARANTINED|SUSPENDED)
        notice_once "quarentena-${status}" \
          "fora de operação — status=$status; o nó autentica e drena até o admin liberar" ;;
      REVOKED)
        notice_once 'revogado' \
          "nó revogado — status=$status; a revogação é terminal, provisione um nó novo" ;;
      ACTIVE)
        notice_once "drenando-${mode:-?}" \
          "aprovado, mas a rede não despacha agora — modo=${mode:-?}; drenando (drenar é não pedir)" ;;
      *)
        notice_once "sem-aceite-${status:-?}-${mode:-?}" \
          "a rede não despacha — status=${status:-?} modo=${mode:-?} acceptTasks=false" ;;
    esac
  fi
  printf '[executor] modo=%s acceptTasks=%s\n' "${mode:-?}" "$accept_tasks"

  # 2. drenar é NÃO PEDIR: modo sem despacho ou nó fora de operação não consulta fila.
  [[ "$accept_tasks" == true ]] || return 0

  # 3. config assinada: a permissão que vale é a que o Control Plane assinou.
  local config allowed
  config=$(allowed_tasks) || return 1
  allowed=$(allowed_tasks_list <<<"$config")
  printf '[executor] allowedTasks=%s\n' "$allowed"

  # 3.5 a varredura do proprio disco: a copia daqui so o dono pode conferir, e o Control Plane nao
  # abre conexao para o no (doc 13 §5.8).
  exec_storage_scrub "$allowed"

  # 4. fila designada a este nó.
  local next tasks_list
  next=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" "$CONTROL_PLANE/network/tasks/next") || { printf '[executor] poll falhou\n' >&2; return 1; }

  # Uma tarefa por ciclo (a fila do nó é pequena; o claim vence se a execução demorar).
  local task_id task_type attempt
  task_id=$(printf '%s' "$next" | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p' | head -n 1)
  [[ -n "$task_id" ]] || return 0
  task_type=$(printf '%s' "$next" | sed -n 's/.*"taskId":"[^"]*","type":"\([^"]*\)".*/\1/p' | head -n 1)
  attempt=$(printf '%s' "$next" | sed -n 's/.*"taskId":"[^"]*","type":"[^"]*","attempt":\([0-9]*\).*/\1/p' | head -n 1)
  printf '[executor] tarefa %s tipo=%s tentativa=%s\n' "$task_id" "$task_type" "${attempt:-?}"

  # 5. executor por perfil: a config assinada autoriza; o que não está nela é recusado.
  if grep -q "\"$task_type\"" <<<"[$allowed]"; then
    case "$task_type" in
      MEDIA_TRANSCODE) exec_media_transcode "$next" "$task_id" "${attempt:-1}" "$config" ;;
      READ_QUERY) exec_read_query "$next" "$task_id" "${attempt:-1}" "$config" ;;
      EDGE_CACHE) exec_edge_cache "$next" "$task_id" "${attempt:-1}" "$config" ;;
      EDGE_DELIVER) exec_edge_deliver "$next" "$task_id" "${attempt:-1}" "$config" ;;
      API_REQUEST) exec_api_request "$next" "$task_id" "${attempt:-1}" "$config" ;;
      OBJECT_PUT) exec_object_put "$next" "$task_id" "${attempt:-1}" "$config" ;;
      *)
        # Prova de ciclo de vida: eco do idempotencyKey como output. Conteúdo de usuário
        # (HTML, CSS, KofScript) NUNCA executa aqui — continua nos sandboxes próprios.
        local idem
        idem=$(json_field "$next" idempotencyKey)
        result "$task_id" "${attempt:-1}" DONE "{\"echo\":\"$idem\"}" ''
        printf '[executor] DONE %s\n' "$task_id"
        ;;
    esac
  else
    result "$task_id" "${attempt:-1}" FAILED '' TASK_NOT_ALLOWED
    printf '[executor] recusada (fora da config assinada): %s\n' "$task_type"
  fi
}

while true; do
  cycle || true
  sleep "$POLL_SECONDS"
done
