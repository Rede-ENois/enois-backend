#!/usr/bin/env bash
# Bootstrap do Node Runtime — da máquina crua ao nó em operação, numa só passada.
#
# Uso:
#   ./bootstrap.sh --control-plane https://control.enois.ia.br --token <provision-token> \
#                  [--source qa|main] [--region sa-east-1] [--country BR] \
#                  [--cpu 2] [--memory 1024]
#   ./bootstrap.sh --update [--source main]      # muda a fonte de verdade e atualiza
#   ./bootstrap.sh --rollback                    # volta ao runtime anterior
#   ./bootstrap.sh --status                      # diagnóstico (node-doctor.sh)
#
# O que faz, em ordem:
#   1. obtém o código do nó e o constrói em imagem LOCAL — do release oficial
#      (tar.gz + SHA256SUMS no GitHub Releases, com a soma conferida contra o
#      arquivo baixado) ou, na falta dele, do git na fonte de verdade;
#   2. roda o ciclo do install.sh: enroll com o token de uso único, --set-key
#      com a chave que o admin gerou, --start que prova a credencial e sobe o
#      container (docs/rede-distribuida/09-instalacao.md §1);
#   3. grava a fonte de verdade em $NODE_HOME/runtime-source e a versão em uso
#      em $NODE_HOME/runtime-version — é delas que o --update seguinte parte.
#
# A fonte de verdade é escolha EXPLÍCITA, fechada entre qa e main: main só chega
# a produção depois de passar pela qa, e o bootstrap não adivinha qual das duas
# o participante quer nem troca sozinho. O que ele recusa é fonte arbitrária
# (branch qualquer, fork qualquer): o código do nó é o que a rede executa, e a
# lista fechada é a lista que a rede publica.
#
# A API key é emitida pelo ADMIN, nunca por este script. O que o bootstrap
# elimina é o trabalho manual de rodar quatro comandos na ordem — não a
# aprovação humana da rede.
set -euo pipefail

NODE_HOME="${NODE_HOME:-/var/lib/enois-node}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_URL="${NODE_REPO_URL:-https://github.com/Rede-ENois/enois-backend}"
REPO_SLUG="${NODE_REPO_SLUG:-Rede-ENois/enois-backend}"
WORK_DIR="${NODE_WORK_DIR:-$NODE_HOME/src}"

DEFAULT_SOURCE='qa'
SOURCE="$DEFAULT_SOURCE" FONTE_EXPLICITA=''
CONTROL_PLANE='' TOKEN='' REGION='' COUNTRY='' CPU='' MEMORY='' MODE=''

die() { printf 'Erro: %s\n' "$*" >&2; exit 1; }
info() { printf '[bootstrap] %s\n' "$*" >&2; }

usage() { sed -n '/^# Uso:/,/^# O que faz/p' "$0" | sed -e 's/^# \{0,1\}//' -e '/^O que faz/d'; }

while (($#)); do
  case "$1" in
    --control-plane) CONTROL_PLANE="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --country) COUNTRY="$2"; shift 2 ;;
    --cpu) CPU="$2"; shift 2 ;;
    --memory) MEMORY="$2"; shift 2 ;;
    --source)
      shift
      case "${1:-}" in
        qa|main) SOURCE="$1"; FONTE_EXPLICITA=1; shift ;;
        *) die "fonte desconhecida: '${1:-}' — a fonte de verdade é qa ou main" ;;
      esac
      ;;
    --update|--rollback|--status) MODE="$1"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Opção desconhecida: $1 (veja --help)" ;;
  esac
done

runtime_source_atual() { cat "$NODE_HOME/runtime-source" 2>/dev/null || printf '%s\n' "$DEFAULT_SOURCE"; }
runtime_version_atual() { head -n 1 "$NODE_HOME/runtime-version" 2>/dev/null || true; }
# O @ separa a fonte do commit e NÃO é válido em tag de imagem; a tag usa hífen.
imagem_da_versao() { printf 'enois-node-runtime:%s\n' "${1/@/-}"; }
# Diretório que contém o Dockerfile em cada origem: o tar do release é achatado
# (arquivos na raiz), então o contexto é o próprio $WORK_DIR; no git o Dockerfile
# mora em deploy/node-runtime do repositório.
contexto_da_origem() {
  case "$1" in
    release:*) printf '%s\n' "$WORK_DIR" ;;
    *) printf '%s/deploy/node-runtime\n' "$WORK_DIR" ;;
  esac
}

if [[ "$MODE" == "--status" ]]; then
  if [[ -n "$CONTROL_PLANE" ]]; then
    exec /bin/bash "$SCRIPT_DIR/node-doctor.sh" --control-plane "$CONTROL_PLANE"
  fi
  exec /bin/bash "$SCRIPT_DIR/node-doctor.sh"
fi

for tool in curl docker tar gzip sha256sum awk; do
  command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool (o runtime é construído nesta máquina)"
done

# ------------------------------------------------- obter_codigo (release → git)
# Imprime "<versao>\t<origem>". <versao> é "<fonte>@<sha8>", o que o runtime
# declara no heartbeat; <origem> é "release:<tag>" ou "git". Só o resultado vai
# para o stdout — os avisos vão para stderr, senão contaminariam o parse.
obter_codigo() {
  local api="https://api.github.com/repos/$REPO_SLUG/releases" tag asset sums dir
  local versao linha
  tag=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    "$api?per_page=20" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p' | head -n 1) || tag=''

  if [[ -n "$tag" ]]; then
    info "Release encontrado: $tag"
    dir="$WORK_DIR.download"; rm -rf "$dir"; mkdir -p "$dir"
    asset="enois-node-runtime-$tag.tar.gz"
    curl --fail --silent --show-error --location --connect-timeout 10 --max-time 300 \
      -o "$dir/$asset" "https://github.com/$REPO_SLUG/releases/download/$tag/$asset" ||
      die "download de $asset falhou"
    curl --fail --silent --show-error --location --connect-timeout 10 --max-time 60 \
      -o "$dir/SHA256SUMS" "https://github.com/$REPO_SLUG/releases/download/$tag/SHA256SUMS" ||
      die "download do SHA256SUMS falhou — sem soma não há verificação, e sem verificação nada roda"
    # A soma é conferida contra o ARQUIVO BAIXADO, na mesma pasta: uma soma que
    # aponta para outro arquivo é recusada pelo `sha256sum -c`, não aceita por
    # comparação de string. Sem linha para este asset, não há o que conferir.
    linha=$(awk -v f="$asset" '$2 == f || $2 == "*" f { print; exit }' "$dir/SHA256SUMS")
    [[ -n "$linha" ]] || die "SHA256SUMS do release $tag não lista $asset"
    ( cd "$dir" && printf '%s\n' "$linha" | sha256sum -c --status - ) ||
      die "sha256 não confere para $asset — release alterado; não use, avise a rede"
    rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"
    # O pacote sai achatado (cada arquivo na raiz do tar, sem diretório-raiz),
    # mesmo formato que o workflow publica: extrai direto em $WORK_DIR.
    tar -C "$WORK_DIR" -xzf "$dir/$asset"
    rm -rf "$dir"
    versao=$(head -n 1 "$WORK_DIR/VERSION" 2>/dev/null | tr -d '[:space:]')
    [[ "$versao" =~ ^(qa|main)@[0-9a-f]{8}$ ]] ||
      die "release $tag sem VERSION válido (esperado <fonte>@<sha8>, veio '${versao:-vazio}')"
    printf '%s\trelease:%s\n' "$versao" "$tag"
    return 0
  fi

  info "Nenhum release do runtime publicado — obtendo do git ($SOURCE)."
  command -v git >/dev/null 2>&1 || die 'Comando ausente: git (não há release para baixar)'
  [[ -d "$WORK_DIR/.git" ]] || git clone --quiet "$REPO_URL" "$WORK_DIR" ||
    die "clone de $REPO_URL falhou"
  git -C "$WORK_DIR" fetch --quiet origin "$SOURCE" || die "fetch de origin/$SOURCE falhou"
  git -C "$WORK_DIR" checkout --quiet --force "origin/$SOURCE" ||
    die "checkout de origin/$SOURCE falhou"
  printf '%s@%s\tgit\n' "$SOURCE" "$(git -C "$WORK_DIR" rev-parse --short=8 "origin/$SOURCE")"
}

construir_imagem() {
  local versao="$1" origem="$2" tag ctx
  tag=$(imagem_da_versao "$versao")
  ctx=$(contexto_da_origem "$origem")
  info "Construindo $tag..."
  docker build --quiet -t "$tag" "$ctx" ||
    die "build da imagem falhou — o runtime atual (se houver) continua rodando"
  printf '%s\n' "$tag"
}

# ------------------------------------------------------------- --update / --rollback
if [[ "$MODE" == "--update" || "$MODE" == "--rollback" ]]; then
  [[ -s "$NODE_HOME/node.json" ]] || die "sem $NODE_HOME/node.json — rode o bootstrap completo primeiro"
  [[ -s "$NODE_HOME/api-key" ]] || die "sem $NODE_HOME/api-key — grave a chave que o admin gerou"

  CONTROL_PLANE="${CONTROL_PLANE:-$(head -n 1 "$NODE_HOME/control-plane" 2>/dev/null || true)}"
  atual=$(runtime_version_atual)

  if [[ "$MODE" == "--rollback" ]]; then
    alvo=$(head -n 1 "$NODE_HOME/runtime-version.previous" 2>/dev/null || true)
    [[ -n "$alvo" ]] || die "nenhum runtime anterior gravado — não há para onde voltar"
    docker image inspect "$(imagem_da_versao "$alvo")" >/dev/null 2>&1 ||
      die "a imagem do runtime anterior ($alvo) não está mais nesta máquina — rollback impossível"
    origem="anterior"
  else
    [[ -n "$FONTE_EXPLICITA" ]] || SOURCE=$(runtime_source_atual)
    IFS=$'\t' read -r alvo origem < <(obter_codigo)
    [[ -n "$alvo" ]] || die "não consegui resolver o commit da fonte $SOURCE"
    if [[ "$alvo" == "$atual" ]]; then
      info "Já está rodando $alvo — nada a fazer."
      exit 0
    fi
    construir_imagem "$alvo" "$origem" >/dev/null
  fi

  umask 177
  if [[ -n "$atual" ]]; then
    printf '%s\n' "$atual" > "$NODE_HOME/runtime-version.previous"
  fi
  printf '%s\n' "$alvo" > "$NODE_HOME/runtime-version"
  if [[ "$MODE" == "--update" ]]; then
    printf '%s\n' "${alvo%%@*}" > "$NODE_HOME/runtime-source"
  fi
  umask 022

  [[ -n "$CONTROL_PLANE" ]] || die "sem URL do Control Plane — rode o bootstrap completo uma vez ou passe --control-plane"
  NODE_RUNTIME_TAG="${alvo/@/-}" /bin/bash "$SCRIPT_DIR/install.sh" --start --control-plane "$CONTROL_PLANE"
  info "Runtime agora em $alvo (origem: $origem). Diagnóstico: NODE_HOME=$NODE_HOME ./bootstrap.sh --status"
  exit 0
fi

# ---------------------------------------------------------------- fluxo completo
mkdir -p "$NODE_HOME"
chmod 700 "$NODE_HOME"

umask 177
printf '%s\n' "$SOURCE" > "$NODE_HOME/runtime-source"
umask 022
info "Fonte de verdade do runtime: $SOURCE (troque com --update --source main)"

IFS=$'\t' read -r versao origem < <(obter_codigo)
[[ -n "$versao" ]] || die "não consegui resolver o código do runtime"
info "Código obtido: $versao (origem: $origem)"
construir_imagem "$versao" "$origem" >/dev/null
info "Imagem local pronta: $(imagem_da_versao "$versao")"

umask 177
printf '%s\n' "$versao" > "$NODE_HOME/runtime-version"
[[ -n "$CONTROL_PLANE" ]] && printf '%s\n' "$CONTROL_PLANE" > "$NODE_HOME/control-plane"
umask 022

if [[ -s "$NODE_HOME/node.json" ]]; then
  info "Nó já registrado em $NODE_HOME/node.json — enroll pulado."
else
  [[ -n "$CONTROL_PLANE" ]] || die '--control-plane é obrigatório'
  [[ -n "$TOKEN" ]] || die '--token é obrigatório (o admin gera em POST /admin/network/nodes/provision-token)'
  enroll=(--control-plane "$CONTROL_PLANE" --token "$TOKEN")
  [[ -n "$REGION" ]] && enroll+=(--region "$REGION")
  [[ -n "$COUNTRY" ]] && enroll+=(--country "$COUNTRY")
  [[ -n "$CPU" ]] && enroll+=(--cpu "$CPU")
  [[ -n "$MEMORY" ]] && enroll+=(--memory "$MEMORY")
  /bin/bash "$SCRIPT_DIR/install.sh" "${enroll[@]}"
fi

if [[ -s "$NODE_HOME/api-key" ]]; then
  info "API key já gravada — --set-key pulado."
else
  info "O admin precisa gerar a API key deste nó agora (tela de administração)."
  printf 'Cole a API key do nó (enk_...) e pressione Enter: '
  read -r key
  [[ "$key" == enk_* ]] || die 'chave inválida: deve começar com enk_ (nunca é gravada em log)'
  umask 177
  printf '%s\n' "$key" > "$NODE_HOME/api-key"
  umask 022
fi

CONTROL_PLANE="${CONTROL_PLANE:-$(head -n 1 "$NODE_HOME/control-plane" 2>/dev/null || true)}"
[[ -n "$CONTROL_PLANE" ]] || die '--control-plane é obrigatório no primeiro start'
NODE_RUNTIME_TAG="${versao/@/-}" /bin/bash "$SCRIPT_DIR/install.sh" --start --control-plane "$CONTROL_PLANE"
info "Nó em operação: $versao. Diagnóstico: NODE_HOME=$NODE_HOME ./bootstrap.sh --status"