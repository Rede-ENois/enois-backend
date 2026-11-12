#!/usr/bin/env bash
# Instalador do Node Runtime da Énóis Distributed Network.
#
# Uso:
#   ./install.sh --control-plane https://control.enois.ia.br --token <provision-token> \
#                [--region sa-east-1] [--country BR] [--cpu 2] [--memory 1024]
#   ./install.sh --set-key              # cola a API key que o admin gerou
#   ./install.sh --backup               # arquiva a identidade (node.json + api-key)
#   ./install.sh --restore <arquivo>    # restaura a identidade de um backup
#   ./install.sh --start                # sobe o container
#
# O que faz (docs/rede-distribuida/01-arquitetura.md §4):
#   1. grava o estado do nó em $NODE_HOME com permissão restrita;
#   2. faz o enroll com o token de provisionamento de uso único;
#   3. espera o admin gerar a API key (--set-key cola a chave uma vez);
#   4. sobe o container via docker compose e confirma o primeiro heartbeat.
#
# O token é de uso único: rodar o enroll de novo com o mesmo token falha com
# NODE_TOKEN_USED. Para reinstalar do zero, peça um token novo.
set -euo pipefail

NODE_HOME="${NODE_HOME:-/var/lib/enois-node}"
COMPOSE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_VERSION="0.1.0"

die() { printf 'Erro: %s\n' "$*" >&2; exit 1; }
info() { printf '[install] %s\n' "$*"; }

usage() {
  grep '^#' "$0" | sed -n '3,18p' | sed 's/^# \{0,1\}//'
}

CONTROL_PLANE='' TOKEN='' REGION='' COUNTRY='' CPU='' MEMORY='' MODE='' BACKUP_FILE=''
while (($#)); do
  case "$1" in
    --control-plane) CONTROL_PLANE="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --country) COUNTRY="$2"; shift 2 ;;
    --cpu) CPU="$2"; shift 2 ;;
    --memory) MEMORY="$2"; shift 2 ;;
    --set-key|--start) MODE="$1"; shift ;;
    --backup) MODE="--backup"; shift ;;
    --restore) MODE="--restore"; BACKUP_FILE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Opção desconhecida: $1 (veja --help)" ;;
  esac
done

mkdir -p "$NODE_HOME"
chmod 700 "$NODE_HOME"

# ------------------------------------------------- --backup / --restore
# O par node.json + api-key é a identidade do nó (04-identidade-e-autorizacao.md):
# perdidos, o re-enroll cria um nó NOVO e a aprovação humana se repete. O backup
# existe para reinstalar a mesma identidade em máquina reformada ou migrada.
if [[ "$MODE" == "--backup" ]]; then
  [[ -s "$NODE_HOME/node.json" ]] || die "sem $NODE_HOME/node.json — rode o enroll primeiro"
  [[ -s "$NODE_HOME/api-key" ]] || die "sem $NODE_HOME/api-key — rode --set-key primeiro"
  for tool in tar; do command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool"; done

  node_id=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
  out="enois-node-${node_id:-identidade}-$(date +%Y%m%d-%H%M%S).tar.gz"
  umask 077
  tar -C "$NODE_HOME" -czf "$out" node.json api-key
  umask 022
  info "Backup da identidade em ./$out (permissão 600)."
  info "Guarde com o mesmo cuidado da chave: quem tem o backup opera como o nó."
  exit 0
fi

if [[ "$MODE" == "--restore" ]]; then
  [[ -n "$BACKUP_FILE" ]] || die '--restore precisa do caminho do arquivo (ex.: ./install.sh --restore enois-node-nd_x.tar.gz)'
  [[ -f "$BACKUP_FILE" ]] || die "backup não encontrado: $BACKUP_FILE"
  for tool in tar; do command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool"; done

  if [[ -s "$NODE_HOME/node.json" ]]; then
    die "já existe um nó registrado em $NODE_HOME/node.json — restaurar por cima trocaria a identidade; remova-a primeiro se tem certeza"
  fi
  tar -C "$NODE_HOME" -xzf "$BACKUP_FILE"
  chmod 600 "$NODE_HOME/node.json" "$NODE_HOME/api-key" 2>/dev/null || true
  node_id=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
  [[ -n "$node_id" ]] || die "backup sem nodeId válido dentro — arquivo truncado ou não é um backup de nó"
  info "Identidade de $node_id restaurada em $NODE_HOME."
  info "Rode --start para confirmar o heartbeat e subir o container."
  exit 0
fi

# ---------------------------------------------------------------- --set-key
if [[ "$MODE" == "--set-key" ]]; then
  printf 'Cole a API key do nó (enk_...) e pressione Enter: '
  read -r key
  [[ "$key" == enk_* ]] || die 'chave inválida: deve começar com enk_ (nunca é gravada em log)'
  umask 177
  printf '%s\n' "$key" > "$NODE_HOME/api-key"
  umask 022
  info "Chave gravada em $NODE_HOME/api-key (permissão 600). Rode --start para subir o container."
  exit 0
fi

# ---------------------------------------------------------------- enroll
if [[ "$MODE" != "--start" ]]; then
  [[ -n "$CONTROL_PLANE" ]] || die '--control-plane é obrigatório'
  [[ -n "$TOKEN" ]] || die '--token é obrigatório'
  for tool in curl; do command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool"; done

  if [[ -s "$NODE_HOME/node.json" ]]; then
    info "Nó já registrado em $NODE_HOME/node.json — nada a fazer (diagnóstico: ./node-doctor.sh)."
  else
    declared_cpu="${CPU:-$(nproc 2>/dev/null || echo 1)}"
    declared_mem="${MEMORY:-$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 1024)}"

    body=$(printf '{"provisionToken":"%s","runtimeVersion":"%s","declared":{"cpuCores":%s,"memoryMb":%s,"diskMb":20480,"bandwidthMbps":100}' \
      "$TOKEN" "$RUNTIME_VERSION" "$declared_cpu" "$declared_mem")
    if [[ -n "$REGION" ]]; then
      # Forma documentada no contrato (03-contratos.md §2): aninhada em location.
      body="$body,\"location\":{\"region\":\"$REGION\",\"country\":\"${COUNTRY:-XX}\"}"
    fi
    body="$body}"

    info "Fazendo enroll no Control Plane $CONTROL_PLANE..."
    enroll=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
      -H 'Content-Type: application/json' -d "$body" \
      "$CONTROL_PLANE/network/nodes/enroll") || die "enroll falhou (token inválido, expirado ou já usado?)"

    node_id=$(printf '%s' "$enroll" | sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p')
    [[ -n "$node_id" ]] || die "resposta do enroll sem nodeId: $enroll"

    umask 177
    printf '%s\n' "$enroll" > "$NODE_HOME/node.json"
    umask 022
    info "Nó $node_id registrado — pendente de aprovação."
  fi
  info "Próximo passo: o admin gera a API key na tela de administração; depois rode --set-key."
  exit 0
fi

# ---------------------------------------------------------------- --start
[[ -s "$NODE_HOME/node.json" ]] || die "sem $NODE_HOME/node.json — rode o enroll primeiro"
[[ -s "$NODE_HOME/api-key" ]] || die "sem $NODE_HOME/api-key — rode --set-key primeiro"
command -v curl >/dev/null 2>&1 || die 'Comando ausente: curl'

node_id=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
node_key=$(head -n 1 "$NODE_HOME/api-key")
: "${CONTROL_PLANE:?--start precisa de --control-plane ou CONTROL_PLANE no ambiente}"

# Antes de subir qualquer container, prova que a credencial serve — subir um nó
# com chave revogada só cria um loop de erro no container.
info "Confirmando autenticação com heartbeat..."
hb=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
  -H "X-Node-Id: $node_id" -H "X-Node-Key: $node_key" \
  -H 'Content-Type: application/json' -d '{"runtimeVersion":"'"$RUNTIME_VERSION"'"}' \
  "$CONTROL_PLANE/network/nodes/heartbeat") || die "heartbeat falhou — diagnostique com ./node-doctor.sh"
status=$(printf '%s' "$hb" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
info "Estado do nó no Control Plane: ${status:-desconhecido}"

for tool in docker; do command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool"; done
export CONTROL_PLANE_URL
docker compose -f "$COMPOSE_DIR/docker-compose.yml" up -d
info "Container subido. Diagnóstico: NODE_HOME=$NODE_HOME ./node-doctor.sh"
