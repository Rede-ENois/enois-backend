#!/usr/bin/env bash
# Monitora GET /network/nodes/me/config até a config vir assinada com a chave
# pública esperada e, então, pina uma única vez via `install.sh --start`.
#
# Regras de segurança:
#   - sem fingerprint esperado, NÃO pina (a menos que --allow-tofu seja explícito);
#   - com pino existente, nunca sobrescreve;
#   - divergência de fingerprint interrompe o monitor e exige decisão humana.
#
# Rode como root (o install.sh precisa de docker e de dar chown no NODE_HOME).
set -euo pipefail

NODE_HOME="${NODE_HOME:-/home/mel/enois-node/home}"
INSTALL_SH="${ENOIS_INSTALL_SH:-}"
EXPECTED_FP="${ENOIS_EXPECTED_FINGERPRINT:-}"
INTERVAL="${ENOIS_INTERVAL:-60}"
TIMEOUT="${ENOIS_TIMEOUT:-7200}"
LOCK_FILE="${ENOIS_MONITOR_LOCK:-/run/enois-config-signing.lock}"

ALLOW_TOFU=0
DRY_RUN=0
ONCE=0

usage() {
	cat <<'EOF'
Uso: monitorar-config-assinatura.sh [opções]

  --node-home DIR            estado do nó (padrão: $NODE_HOME ou /home/mel/enois-node/home)
  --install-sh ARQUIVO       install.sh do runtime (padrão: $ENOIS_INSTALL_SH)
  --expected-fingerprint FP  SHA-256 da chave pública, com ou sem prefixo SHA256:
  --interval SEGUNDOS        intervalo entre leituras (padrão: 60)
  --timeout SEGUNDOS         desiste depois disso; 0 = sem limite (padrão: 7200)
  --allow-tofu               permite pinar sem fingerprint (NÃO recomendado)
  --dry-run                  só diagnostica; não executa install.sh
  --once                     faz uma leitura e sai (2 = ainda sem config assinada)
  -h, --help                 esta ajuda

Códigos de saída:
  0  pino já existia ou foi gravado e confere
  2  ainda sem config assinada (--once) ou pino não tentado
  3  fingerprint divergente / pino existente com outra chave
  4  tempo esgotado sem config assinada
  5  install.sh falhou ou o pino gravado não confere
  6  config assinada encontrada, mas falta --expected-fingerprint
  1  erro de configuração/leitura
EOF
}

log() {
	local msg
	msg="[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"
	printf '%s\n' "$msg"
	if command -v logger >/dev/null 2>&1; then
		logger -t enois-config-signing -- "$*" 2>/dev/null || true
	fi
}

die() {
	log "ERRO: $*" >&2
	exit 1
}

# Aviso que só fala na transição (ou a cada 10 min): o estado "aguardando" não
# pode repetir a mesma linha a cada 60 s e enterrar o que mudou.
last_notice=""
last_notice_at=0
notice() {
	local msg="$1" now
	now=$(date +%s)
	if [[ "$msg" != "$last_notice" ]] || ((now - last_notice_at >= 600)); then
		log "$msg"
		last_notice="$msg"
		last_notice_at=$now
	fi
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
		--interval)
			[[ $# -ge 2 ]] || die "--interval precisa de um valor"
			INTERVAL="$2"
			shift 2
			;;
		--timeout)
			[[ $# -ge 2 ]] || die "--timeout precisa de um valor"
			TIMEOUT="$2"
			shift 2
			;;
		--allow-tofu)
			ALLOW_TOFU=1
			shift
			;;
		--dry-run)
			DRY_RUN=1
			shift
			;;
		--once)
			ONCE=1
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

[[ "$INTERVAL" =~ ^[0-9]+$ ]] && ((INTERVAL > 0)) || die "--interval inválido: $INTERVAL"
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "--timeout inválido: $TIMEOUT"
[[ -n "$NODE_HOME" && -d "$NODE_HOME" ]] || die "NODE_HOME não existe: $NODE_HOME"
[[ -r "$NODE_HOME/node.json" ]] || die "não consigo ler $NODE_HOME/node.json"
[[ -r "$NODE_HOME/api-key" ]] || die "não consigo ler $NODE_HOME/api-key"
[[ -r "$NODE_HOME/control-plane" ]] || die "não consigo ler $NODE_HOME/control-plane"

node_id="$(sed -n 's/.*"nodeId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json" | head -n1)"
[[ -n "$node_id" ]] || die "nodeId não encontrado em $NODE_HOME/node.json"
node_key="$(head -n1 "$NODE_HOME/api-key" | tr -d '\r\n')"
[[ -n "$node_key" ]] || die "api-key vazia"
CP="$(head -n1 "$NODE_HOME/control-plane" | tr -d '\r\n')"
CP="${CP%/}"
[[ -n "$CP" ]] || die "control-plane vazio"

if [[ -z "$INSTALL_SH" ]]; then
	for candidate in \
		"$(dirname "$NODE_HOME")/install.sh" \
		"$NODE_HOME/install.sh" \
		"$PWD/install.sh" \
		"$(dirname "$0")/install.sh"; do
		if [[ -x "$candidate" ]]; then
			INSTALL_SH="$candidate"
			break
		fi
	done
fi
if [[ -n "$EXPECTED_FP" ]]; then
	EXPECTED_FP="$(normalize_fp "$EXPECTED_FP")"
	[[ "$EXPECTED_FP" =~ ^[0-9a-f]{64}$ ]] \
		|| die "fingerprint esperado inválido: use 64 hex (SHA-256)"
fi

# Um lock evita que um --start manual e o serviço rodem ao mesmo tempo.
if command -v flock >/dev/null 2>&1; then
	mkdir -p "$(dirname "$LOCK_FILE")" 2>/dev/null || true
	exec 9>"$LOCK_FILE" || die "não consegui abrir o lock $LOCK_FILE"
	flock -n 9 || {
		log "outra instância do monitor já está rodando; saindo"
		exit 0
	}
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

json_bool() {
	printf '%s' "$1" | grep -oE '"signed"[[:space:]]*:[[:space:]]*(true|false)' \
		| head -n1 | sed 's/.*:[[:space:]]*//'
}

json_num() {
	printf '%s' "$1" | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*[0-9]+" \
		| head -n1 | sed 's/.*:[[:space:]]*//'
}

json_str() {
	printf '%s' "$1" | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
		| head -n1 | sed 's/^[^:]*:[[:space:]]*"//; s/"$//'
}

public_key_fingerprint() {
	# $1 = base64 X.509 DER; imprime 64 hex ou falha.
	local b64="$1" der="$tmpdir/pub.der"
	[[ "$b64" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || return 1
	printf '%s' "$b64" | base64 -d >"$der" 2>/dev/null || return 1
	openssl pkey -pubin -inform DER -in "$der" -noout >/dev/null 2>&1 || return 1
	sha256sum "$der" | awk '{print $1}'
}

pin_fingerprint() {
	# $1 = caminho do pinning-key (base64 X.509 DER)
	public_key_fingerprint "$(cat "$1")"
}

pin_path="$NODE_HOME/pinning-key"

if [[ -s "$pin_path" ]]; then
	if fp="$(pin_fingerprint "$pin_path")"; then
		if [[ -n "$EXPECTED_FP" && "$fp" != "$EXPECTED_FP" ]]; then
			log "pino existente DIVERGE do fingerprint esperado (pino=SHA256:$fp esperado=SHA256:$EXPECTED_FP)"
			exit 3
		fi
		log "pino já existe (SHA256:$fp); nada a fazer"
		exit 0
	else
		log "ERRO: $pin_path existe mas não é uma chave X.509 DER válida; remova/refaça o pino manualmente após conferir"
		exit 3
	fi
fi

fetch_config() {
	curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
		-H "X-Node-Id: $node_id" -H "X-Node-Key: $node_key" \
		"$CP/network/nodes/me/config"
}

deadline=0
if ((TIMEOUT > 0)); then
	deadline=$(( $(date +%s) + TIMEOUT ))
fi

pin_attempts=0
while :; do
	if resp="$(fetch_config 2>"$tmpdir/curl.err")"; then
		signed="$(json_bool "$resp" || true)"
		format_version="$(json_num "$resp" formatVersion || true)"
		config_version="$(json_num "$resp" configVersion || true)"
		public_key="$(json_str "$resp" publicKey || true)"

		if [[ -z "$format_version" ]]; then
			die "resposta sem formatVersion; endpoint ou credencial errada?"
		elif [[ "$format_version" != "4" ]]; then
			die "formatVersion=$format_version (o runtime espera 4); NÃO vou pinar. Atualize o backend/script."
		elif [[ "$signed" != "true" && -z "$public_key" ]]; then
			notice "config NÃO assinada e sem publicKey: as duas envs estão ausentes ou com nome errado no master (signed=$signed)"
		elif [[ "$signed" != "true" ]]; then
			notice "config NÃO assinada, mas publicKey presente: a privada está ausente ou inválida no master (signed=$signed)"
		elif [[ -z "$public_key" ]]; then
			notice "config assinada (signed=true), mas sem publicKey: a env pública está ausente ou inválida no master"
		else
			if ! fp="$(public_key_fingerprint "$public_key")"; then
				die "publicKey recebida não é X.509 DER válida; NÃO vou pinar"
			elif [[ -z "$EXPECTED_FP" && "$ALLOW_TOFU" -ne 1 ]]; then
				log "config assinada encontrada. Fingerprint da pública: SHA256:$fp"
				log "para pinar, rode de novo com --expected-fingerprint SHA256:$fp (ou --allow-tofu, não recomendado)"
				exit 6
			elif [[ -n "$EXPECTED_FP" && "$fp" != "$EXPECTED_FP" ]]; then
				log "fingerprint DIVERGENTE: esperado SHA256:$EXPECTED_FP, recebido SHA256:$fp; NÃO vou pinar"
				exit 3
			else
				if [[ -n "$EXPECTED_FP" ]]; then
					log "config assinada e fingerprint confere (SHA256:$fp, configVersion=${config_version:-?})"
				else
					log "config assinada; modo --allow-tofu, fingerprint SHA256:$fp (configVersion=${config_version:-?})"
				fi
				if ((DRY_RUN == 1)); then
					log "dry-run: pararia aqui e rodaria ${INSTALL_SH:-install.sh} --start"
					exit 0
				fi
				[[ -n "$INSTALL_SH" && -x "$INSTALL_SH" ]] \
					|| die "install.sh não encontrado; passe --install-sh /caminho/install.sh"
				((EUID == 0)) || die "precisa de root/sudo para rodar install.sh e gravar o pino"
				log "executando install.sh --start para pinar"
				if NODE_HOME="$NODE_HOME" "$INSTALL_SH" --start --control-plane "$CP"; then
					if [[ -s "$pin_path" ]] && [[ "$(cat "$pin_path")" == "$public_key" ]]; then
						# O container roda como 10001:10001; o pino precisa ser legível por ele.
						chown 10001:10001 "$pin_path" 2>/dev/null || true
						chmod 600 "$pin_path"
						log "pino gravado e confere com a chave servida (SHA256:$fp)"
						exit 0
					fi
					log "install.sh terminou, mas $pin_path não confere com a publicKey servida"
					exit 5
				fi
				pin_attempts=$((pin_attempts + 1))
				log "install.sh --start falhou (tentativa $pin_attempts/3)"
				if ((pin_attempts >= 3)); then
					exit 5
				fi
				if ((ONCE == 1)); then
					exit 5
				fi
			fi
		fi
	else
		notice "falha ao ler a config: $(tr '\n' ' ' <"$tmpdir/curl.err")"
	fi

	if ((ONCE == 1)); then
		exit 2
	fi
	now=$(date +%s)
	if ((TIMEOUT > 0 && now >= deadline)); then
		log "tempo esgotado (${TIMEOUT}s) sem config assinada; parando"
		exit 4
	fi
	sleep "$INTERVAL"
done
