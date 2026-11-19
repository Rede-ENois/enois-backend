#!/usr/bin/env bash
# Auto-update do Node Runtime — o slot de reserva acompanha sozinho o release publicado
# (node-auto-update.sh). Roda no HOST, pelo cron instalado com `bootstrap.sh --auto-update on`.
#
# Uso:
#   ./node-auto-update.sh                    # confere o último release e atualiza, se houver um novo
#   ./node-auto-update.sh --install-cron     # instala o cron de 15 em 15 minutos (idempotente)
#   ./node-auto-update.sh --install-cron --cron "/7 * * * *"
#   ./node-auto-update.sh --remove-cron
#
# Por que este script existe: o bootstrap sabia obter código e trocar a versão (`--update`), mas
# nada na máquina chamava isso quando saía release. O master recebia deploy e a reserva ficava
# parada na versão antiga — e é justamente ela que assume quando o master cai.
#
# Por que a decisão é tomada AQUI, e não no container: o runtime roda como 10001, sem docker e com
# a raiz somente leitura. Ele não pode construir imagem nem trocar a si mesmo. O host tem docker,
# é o dono do $NODE_HOME e já roda o `bootstrap.sh --update`, que é o único caminho de troca
# (release + sha256 conferida, imagem construída na máquina, versão anterior para --rollback).
# Manter dois caminhos de atualização seria ter duas regras sobre o que o nó executa.
#
# O que decide a versão é o RELEASE PUBLICADO (GitHub Releases do runtime, tag slave-v*), que é
# onde a rede deposita o código que quer distribuir. A fonte de verdade continua fechada: o
# bootstrap recusa um release cujo VERSION não seja <fonte>@<sha8> com o commit da própria fonte,
# então o pior caso de um release errado é a máquina recusar — nunca executar outra coisa.
#
# A montanha-russa que o release resolve e o que ele NÃO resolve está na docs (09-instalacao.md):
# health check, janela de retenção e rollback automático não existem ainda; o bootstrap só troca
# depois de o código novo ser verificado, e mantém a versão anterior para `--rollback`.
set -uo pipefail

NODE_HOME="${NODE_HOME:-/var/lib/enois-node}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOCK="$NODE_HOME/update.lock"
LOG_FILE="${NODE_AUTO_UPDATE_LOG:-$NODE_HOME/auto-update.log}"
LOG_TAG='[auto-update]'
CRON_LINE_TAG='# enois-node-auto-update'
# Quanta diferença entre o relógio local e o servidor de release é "relógio torto". O release é
# escolhido por data; um relógio atrasado faria a máquina ignorar um release mais novo e um
# adiantado faria baixar um antigo. Acima disso, a atualização recusa e pede NTP.
MAX_CLOCK_SKEW_SECONDS="${NODE_AUTO_UPDATE_MAX_SKEW:-300}"

info() { printf '%s %s\n' "$LOG_TAG" "$*" >&2; }
die() { printf '%s Erro: %s\n' "$LOG_TAG" "$*" >&2; exit 1; }

usage() { sed -n '/^# Uso:/,/^# Por que este script existe/p' "$0" | sed -e 's/^# \{0,1\}//' -e '/^# Por que este script existe/d'; }

MODE='run'
CRON_EXPR='*/15 * * * *'
while (($#)); do
  case "$1" in
    --install-cron) MODE='install'; shift ;;
    --remove-cron) MODE='remove'; shift ;;
    --cron) CRON_EXPR="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Opção desconhecida: $1 (veja --help)" ;;
  esac
done

cron_file() { printf '%s\n' "${NODE_CRON_FILE:-/etc/cron.d/enois-node-auto-update}"; }

case "$MODE" in
  install)
    [[ -d /etc/cron.d ]] || die "/etc/cron.d não existe — instale o cron nesta máquina ou rode o script por um timer do systemd"
    # O cron roda como o DONO do NODE_HOME (quem rodou o bootstrap), não como root: a atualização
    # precisa do mesmo docker e do mesmo git da instalação.
    owner=$(stat -c '%U' "$NODE_HOME" 2>/dev/null || echo root)
    tmp=$(mktemp)
    printf '%s\n# Atualiza o runtime quando o release do nó for mais novo que o que roda aqui.\n%s %s NODE_HOME=%s %s >> %s 2>&1\n' \
      "$CRON_LINE_TAG" "$CRON_EXPR" "$owner" "$NODE_HOME" "$SCRIPT_DIR/node-auto-update.sh" "$LOG_FILE" > "$tmp"
    if install -m 644 "$tmp" "$(cron_file)" 2>/dev/null; then
      rm -f "$tmp"
      info "cron instalado em $(cron_file): $CRON_EXPR"
      exit 0
    fi
    rm -f "$tmp"
    info "instalação direta falhou (permissão?); esta é a linha que precisa estar em $(cron_file):"
    printf '%s %s %s NODE_HOME=%s %s >> %s 2>&1\n' \
      "$CRON_EXPR" "$owner" "$SCRIPT_DIR/node-auto-update.sh" "$NODE_HOME" "$LOG_FILE"
    exit 1
    ;;
  remove)
    if rm -f "$(cron_file)" 2>/dev/null; then
      info "cron removido: $(cron_file)"
    else
      info "não consegui remover $(cron_file) — remova à mão (permissão de root?)"
      exit 1
    fi
    exit 0
    ;;
esac

# ---------------------------------------------------------------- execução
[[ -s "$NODE_HOME/node.json" ]] || die "sem $NODE_HOME/node.json — esta máquina não tem nó instalado"
[[ -s "$NODE_HOME/runtime-version" ]] || die "sem $NODE_HOME/runtime-version — rode o bootstrap completo primeiro"
[[ -f "$SCRIPT_DIR/bootstrap.sh" ]] ||
  die "bootstrap.sh não encontrado ao lado deste script — a atualização depende dele"
for tool in curl date stat awk sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool (a atualização depende dele)"
done

# O relógio é a variável que o release escolhido usa (data do release). Um relógio torto não
# atualiza — e não teria como provar que a versão escolhida é a certa.
conferir_relogio() {
  local server epoch agora dif
  server=$(curl --silent --show-error --connect-timeout 10 --max-time 20 -I \
    "${NODE_RELEASE_API:-https://api.github.com}" 2>/dev/null |
    awk 'tolower($1) == "date:" { for (i = 2; i < NF; i++) printf "%s ", $i; printf "%s", $NF; exit }') || server=''
  [[ -n "$server" ]] || { info "sem a data do servidor de release; seguindo com o relógio local"; return 0; }
  epoch=$(date -u -d "$server" +%s 2>/dev/null) || { info "data do servidor não reconhecida; seguindo com o local"; return 0; }
  agora=$(date -u +%s)
  dif=$(( agora - epoch ))
  (( dif < 0 )) && dif=$(( -dif ))
  if (( dif > MAX_CLOCK_SKEW_SECONDS )); then
    info "relógio local ~${dif}s fora do servidor de release (> ${MAX_CLOCK_SKEW_SECONDS}s) — atualização recusada; confira o NTP"
    return 1
  fi
}

# A trava é o diretório: `mkdir` falha se já existe, e não existe janela entre checar e criar.
if ! mkdir "$LOCK" 2>/dev/null; then
  # Trava presa: um processo morto no meio deixaria o nó sem atualizar para sempre. Uma hora é mais
  # que o build da imagem leva, e a hora é do RELÓGIO do host que roda este script.
  lock_age=$(( $(date +%s) - $(stat -c '%Y' "$LOCK" 2>/dev/null || echo 0) ))
  if (( lock_age > 3600 )); then
    info "trava de ${lock_age}s parece órfã — removendo"
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || { info "não consegui retomar a trava — tentando na próxima"; exit 0; }
  else
    info "outra atualização em andamento ($LOCK) — tentando na próxima"
    exit 0
  fi
fi
trap 'rm -rf "$LOCK"' EXIT

conferir_relogio || exit 0

atual=$(head -n 1 "$NODE_HOME/runtime-version" | tr -d '[:space:]')
[[ "$atual" =~ ^(qa|main)@[0-9a-f]{8}$ ]] || die "runtime-version inválida ('${atual:-vazio}') — não vou trocar o que não sei o que é"

# O bootstrap resolve o último release e, se ele já é o que roda, não faz nada. Pedir a ele é o
# caminho único: release + sha256 + imagem + --start. Toda a verificação vive lá.
info "conferindo release do runtime (roda aqui: $atual)"
if /bin/bash "$SCRIPT_DIR/bootstrap.sh" --update >>"$LOG_FILE" 2>&1; then
  agora=$(head -n 1 "$NODE_HOME/runtime-version" | tr -d '[:space:]')
  if [[ "$agora" == "$atual" ]]; then
    info "nada a atualizar: $atual é o último release publicado"
  else
    info "runtime atualizado: $atual → $agora"
  fi
  exit 0
fi
info "atualização falhou; o runtime atual ($atual) continua rodando (log: $LOG_FILE)"
exit 1