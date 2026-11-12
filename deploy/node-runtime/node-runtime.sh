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
# O executor nunca interpreta o payload: as tarefas hoje são de prova de ciclo de
# vida (eco do idempotencyKey), e conteúdo de usuário continua nos sandboxes próprios
# (02-modelo-de-ameacas.md). Modo EMERGENCY/DEGRADED: termina o ciclo e para de pedir.
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

json_field() {
  # json_field <json> <chave>: primeiro valor string da chave, sem depender de jq.
  sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' <<<"$1" | head -n 1
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
  printf '%s' "$config"
}

# O array allowedTasks da config — a única fonte de permissão de execução.
allowed_tasks_list() {
  sed -n 's/.*"allowedTasks":\[\([^]]*\)\].*/\1/p'
}

result() {
  # result <taskId> <attempt> <status> <output-json|vazio> <error-code|vazio>
  local task_id="$1" attempt="$2" status="$3" output="$4" error_code="$5" body output_part error_part
  output_part=''
  [[ -n "$output" ]] && output_part=",\"output\":$output"
  error_part=''
  [[ -n "$error_code" ]] && error_part=",\"error\":{\"code\":\"$error_code\",\"message\":\"negado pela config assinada\",\"retryable\":false}"
  body="{\"taskId\":\"$task_id\",\"attempt\":$attempt,\"status\":\"$status\"$output_part$error_part}"
  curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "${auth[@]}" -d "$body" \
    "$CONTROL_PLANE/network/tasks/$task_id/result" >/dev/null
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
    # Prova de ciclo de vida: eco do idempotencyKey como output. Conteúdo de usuário
    # (HTML, CSS, KofScript) NUNCA executa aqui — continua nos sandboxes próprios.
    local idem
    idem=$(json_field "$next" idempotencyKey)
    result "$task_id" "${attempt:-1}" DONE "{\"echo\":\"$idem\"}" ''
    printf '[executor] DONE %s\n' "$task_id"
  else
    result "$task_id" "${attempt:-1}" FAILED '' TASK_NOT_ALLOWED
    printf '[executor] recusada (fora da config assinada): %s\n' "$task_type"
  fi
}

while true; do
  cycle || true
  sleep "$POLL_SECONDS"
done
