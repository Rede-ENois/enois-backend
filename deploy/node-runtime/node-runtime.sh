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
RUNTIME_VERSION="${RUNTIME_VERSION:-0.1.0}"

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
  local config
  if [[ ! -s "$NODE_HOME/pinning-key" ]]; then
    printf '[executor] sem pinning-key — config não verificável; rode --start para pinar a chave\n' >&2
    return 1
  fi
  config=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" "$CONTROL_PLANE/network/nodes/me/config") || return 1
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
  printf '%s' "$config"
}

# A assinatura cobre o DOCUMENTO CANÔNICO, não o JSON: a mesma linha que
# NetworkConfigSigner.SignedFields.canonical() monta no Control Plane, campo por campo
# na ordem do contrato, listas ordenadas e em JSON compacto. Reconstruir essa linha é
# recortar e ordenar, não reinterpretar o documento.
config_signature() {
  # config_signature <config-json>: imprime "canonical<TAB>assinatura"; 1 se faltar campo.
  local format_version config_version node_id profiles cpu memory disk bandwidth tasks issued_at signature
  format_version=$(json_number "$1" formatVersion)
  config_version=$(json_number "$1" configVersion)
  node_id=$(json_field "$1" nodeId)
  profiles=$(json_list_sorted "$1" profiles)
  cpu=$(json_number "$1" cpuCores)
  memory=$(json_number "$1" memoryMb)
  disk=$(json_number "$1" diskMb)
  bandwidth=$(json_number "$1" bandwidthMbps)
  tasks=$(json_list_sorted "$1" allowedTasks)
  issued_at=$(json_field "$1" issuedAt)
  signature=$(json_field "$1" signature)
  [[ -n "$format_version" && -n "$config_version" && -n "$node_id" && -n "$cpu" \
      && -n "$memory" && -n "$disk" && -n "$bandwidth" && -n "$issued_at" \
      && -n "$signature" ]] || return 1
  printf '%s|%s|%s|[%s]|%s|%s|%s|%s|[%s]|%s\t%s' \
    "$format_version" "$config_version" "$node_id" "$profiles" "$cpu" "$memory" "$disk" \
    "$bandwidth" "$tasks" "$issued_at" "$signature"
}

json_list_sorted() {
  # json_list_sorted <json> <chave>: itens em JSON compacto, ordenados por byte (C),
  # como o jsonList() do signer — a ordem do banco não é contrato e duas ordens dariam
  # assinaturas diferentes para a mesma permissão. Os itens já chegam com aspas (o
  # array é um blob recortado, não parseado); as aspas saem, o conjunto vazio vira []
  # como no signer, e o sort é por byte porque os itens são nomes de perfil/tarefa em
  # ASCII — o ordenamento do Java e o do `sort` batem nesse domínio, e um nome fora
  # dele quebraria a igualdade em vez de passar batido.
  local items
  items=$(sed -n 's/.*"'"$2"'":\[\([^]]*\)\].*/\1/p' <<<"$1" | head -n 1 \
    | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^"//; s/"$//; s/\\"/"/g' | sed '/^$/d')
  [[ -n "$items" ]] || { printf '[]'; return; }
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
  local task_id="$1" attempt="$2" status="$3" output="$4" error_code="$5" message="${6:-negado pela config assinada}"
  local body output_part error_part
  output_part=''
  [[ -n "$output" ]] && output_part=",\"output\":$output"
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
  local next="$1" task_id="$2" attempt="$3"
  local payload media_id mime work src dst args a demux mux resp
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
      "${auth_bin[@]}" --output "$src" \
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
      "${auth_bin[@]}" -H "Content-Type: $mime" --data-binary @"$dst" \
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
          -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
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
# O cache de borda é alimentado pelo feed de mudanças (V283): GET
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

content_bytes() {
  # content_bytes: bytes guardados no ESTADO DE CONTEÚDO do nó (edge/ + replica/).
  # Contar $NODE_HOME inteiro incluiria node.json, api-key e o pino — credencial não
  # é conteúdo e não pode ser apagada por estouro de cache.
  local total=0 size f
  for f in "$NODE_HOME"/edge/*/*.json "$NODE_HOME"/edge/*/*.version \
           "$NODE_HOME"/replica/*/*.json "$NODE_HOME"/replica/*/*.version; do
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
          -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
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
      -w '\n%{http_code}' "${auth[@]}" "$content_url"); then
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

cycle() {
  # 1. heartbeat: estado, modo e se pode pedir tarefa.
  local hb mode accept_tasks observed_cpu observed_mem
  observed_cpu=$(awk 'NR==1 {printf "%.1f", $1}' /proc/loadavg 2>/dev/null || echo 0)
  observed_mem=$(awk '/MemAvailable/ {printf "%d", ($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
  hb=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" -d "{\"runtimeVersion\":\"$RUNTIME_VERSION\",\"observed\":{\"cpuPercent\":$observed_cpu,\"memoryMb\":$observed_mem,\"runningTasks\":0}}" \
    "$CONTROL_PLANE/network/nodes/heartbeat") || { printf '[executor] heartbeat falhou\n' >&2; return 1; }
  mode=$(json_field "$hb" mode)
  accept_tasks=$(grep -q '"acceptTasks":true' <<<"$hb" && echo true || echo false)
  printf '[executor] modo=%s acceptTasks=%s\n' "${mode:-?}" "$accept_tasks"

  # 2. drenar é NÃO PEDIR: modo sem despacho ou nó fora de operação não consulta fila.
  [[ "$accept_tasks" == true ]] || return 0

  # 3. config assinada: a permissão que vale é a que o Control Plane assinou.
  local config allowed
  config=$(allowed_tasks) || return 1
  allowed=$(allowed_tasks_list <<<"$config")
  printf '[executor] allowedTasks=%s\n' "$allowed"

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
      MEDIA_TRANSCODE) exec_media_transcode "$next" "$task_id" "${attempt:-1}" ;;
      READ_QUERY) exec_read_query "$next" "$task_id" "${attempt:-1}" "$config" ;;
      EDGE_CACHE) exec_edge_cache "$next" "$task_id" "${attempt:-1}" "$config" ;;
      EDGE_DELIVER) exec_edge_deliver "$next" "$task_id" "${attempt:-1}" "$config" ;;
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
