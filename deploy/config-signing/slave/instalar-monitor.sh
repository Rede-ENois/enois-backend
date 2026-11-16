#!/usr/bin/env bash
# Instala o monitor como serviço systemd no nó (roda como root).
#
# Modo padrão: serviço longo, checa a cada 60 s e pina uma vez.
# Modo --timer: oneshot + timer, checa a cada 60 s depois que a execução anterior termina.
#
# Uso:
#   sudo ./instalar-monitor.sh \
#     --node-home /home/mel/enois-node/home \
#     --install-sh /home/mel/enois-node/install.sh \
#     --expected-fingerprint SHA256:...
#
# Sem --expected-fingerprint o monitor sobe em modo "detectar apenas": quando a
# chave aparecer, ele imprime o fingerprint e para (exit 6). Aí você confere com
# o master e reinstala com o fingerprint.
#
# Remover:
#   sudo ./instalar-monitor.sh --uninstall
set -euo pipefail

NODE_HOME="/home/mel/enois-node/home"
INSTALL_SH=""
EXPECTED_FP=""
UNINSTALL=0
START=1
TIMER=0

# O instalador grava em /etc e chama o systemctl: root é exigência do próprio script, não da
# configuração. A checagem fica aqui, antes de qualquer validação de argumento, para o erro não
# depender do argumento que veio.
if [[ $EUID -ne 0 ]]; then
	printf 'ERRO: rode como root/sudo\n' >&2
	exit 1
fi

UNIT_DIR="/etc/systemd/system"
LONG_UNIT="enois-config-signing-monitor.service"
ONCE_UNIT="enois-config-signing-monitor-once.service"
TIMER_UNIT="enois-config-signing-monitor.timer"
ENV_DIR="/etc/enois"
ENV_FILE="$ENV_DIR/config-signing-monitor.env"
BIN_PATH="/usr/local/sbin/enois-monitor-config-signing"
DOC_DIR="/usr/local/share/enois-config-signing"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONITOR_SRC="$SCRIPT_DIR/monitorar-config-assinatura.sh"
LONG_SRC="$SCRIPT_DIR/$LONG_UNIT"
ONCE_SRC="$SCRIPT_DIR/$ONCE_UNIT"
TIMER_SRC="$SCRIPT_DIR/$TIMER_UNIT"

die() {
	printf 'ERRO: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Uso: instalar-monitor.sh [opções]

  --node-home DIR            estado do nó (padrão: /home/mel/enois-node/home)
  --install-sh ARQUIVO       install.sh do runtime (padrão: procura ao lado do node-home)
  --expected-fingerprint FP  SHA-256 da pública, com ou sem prefixo SHA256:
  --timer                    usa oneshot + timer em vez de serviço longo
  --no-start                 instala e habilita, mas não inicia agora
  --uninstall                remove serviço, timer, env e binário
  -h, --help                 esta ajuda
EOF
}

normalize_fp() {
	local fp="$1"
	fp="${fp#SHA256:}"
	fp="${fp#sha256:}"
	printf '%s' "$fp" | tr 'A-F' 'a-f'
}

while (($# > 0)); do
	case "$1" in
		--node-home)
			[[ $# -ge 2 ]] || die "--node-home precisa de um valor"
			NODE_HOME="$2"
			shift 2
			;;
		--install-sh)
			[[ $# -ge 2 ]] || die "--install-sh precisa de um valor"
			INSTALL_SH="$2"
			shift 2
			;;
		--expected-fingerprint)
			[[ $# -ge 2 ]] || die "--expected-fingerprint precisa de um valor"
			EXPECTED_FP="$2"
			shift 2
			;;
		--timer)
			TIMER=1
			shift
			;;
		--no-start)
			START=0
			shift
			;;
		--uninstall)
			UNINSTALL=1
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			die "argumento desconhecido: $1"
			;;
	esac
done

command -v systemctl >/dev/null 2>&1 || die "systemd não encontrado"

if ((UNINSTALL == 1)); then
	for unit in "$TIMER_UNIT" "$ONCE_UNIT" "$LONG_UNIT"; do
		systemctl stop "$unit" 2>/dev/null || true
		systemctl disable "$unit" 2>/dev/null || true
	done
	rm -f "$UNIT_DIR/$LONG_UNIT" "$UNIT_DIR/$ONCE_UNIT" "$UNIT_DIR/$TIMER_UNIT" \
		"$ENV_FILE" "$BIN_PATH"
	systemctl daemon-reload
	printf 'Monitor removido (serviço longo, oneshot e timer).\n'
	exit 0
fi

# A validação inteira acontece antes de gravar qualquer coisa: erro de argumento não pode deixar
# um serviço instalado apontando para um NODE_HOME que não existe.
if [[ -n "$EXPECTED_FP" ]]; then
	EXPECTED_FP="$(normalize_fp "$EXPECTED_FP")"
	[[ "$EXPECTED_FP" =~ ^[0-9a-f]{64}$ ]] \
		|| die "fingerprint inválido: use 64 hex (SHA-256)"
fi

for src in "$MONITOR_SRC" "$LONG_SRC" "$ONCE_SRC" "$TIMER_SRC"; do
	[[ -r "$src" ]] || die "não encontrei $src"
done
bash -n "$MONITOR_SRC" || die "sintaxe inválida em $MONITOR_SRC"
[[ -d "$NODE_HOME" ]] || die "NODE_HOME não existe: $NODE_HOME"
[[ -r "$NODE_HOME/node.json" ]] || die "falta $NODE_HOME/node.json"
[[ -r "$NODE_HOME/api-key" ]] || die "falta $NODE_HOME/api-key"
[[ -r "$NODE_HOME/control-plane" ]] || die "falta $NODE_HOME/control-plane"

if [[ -z "$INSTALL_SH" ]]; then
	for candidate in \
		"$(dirname "$NODE_HOME")/install.sh" \
		"$NODE_HOME/install.sh" \
		"$SCRIPT_DIR/install.sh" \
		"$PWD/install.sh"; do
		if [[ -x "$candidate" ]]; then
			INSTALL_SH="$candidate"
			break
		fi
	done
fi
[[ -n "$INSTALL_SH" && -x "$INSTALL_SH" ]] \
	|| die "install.sh não encontrado; passe --install-sh /caminho/install.sh"

install -d -m 0755 "$ENV_DIR"
install -d -m 0755 "$DOC_DIR"
install -d -m 0755 "$(dirname "$BIN_PATH")"
install -m 0755 "$MONITOR_SRC" "$BIN_PATH"
install -m 0644 "$LONG_SRC" "$UNIT_DIR/$LONG_UNIT"
install -m 0644 "$ONCE_SRC" "$UNIT_DIR/$ONCE_UNIT"
install -m 0644 "$TIMER_SRC" "$UNIT_DIR/$TIMER_UNIT"
install -m 0644 "$SCRIPT_DIR/../README.md" "$DOC_DIR/README.md" 2>/dev/null || true

umask 077
cat >"$ENV_FILE" <<EOF
NODE_HOME=$NODE_HOME
ENOIS_INSTALL_SH=$INSTALL_SH
ENOIS_EXPECTED_FINGERPRINT=$EXPECTED_FP
ENOIS_INTERVAL=60
ENOIS_TIMEOUT=7200
EOF
chmod 600 "$ENV_FILE"

systemctl daemon-reload

if ((TIMER == 1)); then
	# Um modo por vez: o serviço longo e o timer não podem ficar ativos juntos.
	systemctl stop "$LONG_UNIT" 2>/dev/null || true
	systemctl disable "$LONG_UNIT" 2>/dev/null || true
	systemctl enable "$TIMER_UNIT" >/dev/null
	if ((START == 1)); then
		systemctl restart "$TIMER_UNIT"
	fi
else
	systemctl stop "$TIMER_UNIT" 2>/dev/null || true
	systemctl disable "$TIMER_UNIT" 2>/dev/null || true
	systemctl stop "$ONCE_UNIT" 2>/dev/null || true
	systemctl enable "$LONG_UNIT" >/dev/null
	if ((START == 1)); then
		systemctl restart "$LONG_UNIT"
	fi
fi

printf '\nMonitor instalado (%s).\n' "$([[ "$TIMER" -eq 1 ]] && echo timer || echo serviço-longo)"
printf '  binário: %s\n' "$BIN_PATH"
printf '  env:     %s\n' "$ENV_FILE"
if ((TIMER == 1)); then
	printf '  timer:   %s\n' "$TIMER_UNIT"
	printf '  oneshot: %s\n' "$ONCE_UNIT"
	printf '\nAcompanhe com:\n  journalctl -u %s -f\n' "$ONCE_UNIT"
	printf '  systemctl list-timers %s --no-pager\n' "$TIMER_UNIT"
else
	printf '  serviço: %s\n' "$LONG_UNIT"
	printf '\nAcompanhe com:\n  journalctl -u %s -f\n' "$LONG_UNIT"
fi
if [[ -z "$EXPECTED_FP" ]]; then
	printf '\nATENÇÃO: sem fingerprint. O monitor vai detectar a chave, imprimir o SHA-256\n'
	printf 'e parar com exit 6. Confira com o master e reinstale com --expected-fingerprint.\n'
fi
