#!/usr/bin/env bash
# Prova, do lado do Control Plane, que GET /network/nodes/me/config devolve
# publicKey e uma assinatura Ed25519 que confere com ela.
#
# Uso:
#   ./verificar-config-assinada.sh \
#     --control-plane https://qa.exemplo \
#     --node-id nd_... \
#     --node-key-file /caminho/api-key \
#     [--expected-fingerprint SHA256:...]
#
# A chave do nó é lida de arquivo; nunca aparece na linha de comando nem na saída.
set -euo pipefail

CP=""
NODE_ID=""
KEY_FILE=""
EXPECTED_FP=""

usage() {
	cat <<'EOF'
Uso: verificar-config-assinada.sh --control-plane URL --node-id ID --node-key-file ARQUIVO [--expected-fingerprint SHA256:...]

Saída: resumo dos campos que importam. Códigos de saída:
  0  config assinada, publicKey presente e assinatura confere
  2  config não assinada ou sem publicKey (envs ausentes/erradas no serviço)
  3  fingerprint da publicKey diverge do esperado
  1  erro de rede, credencial ou formato
EOF
}

die() {
	printf 'ERRO: %s\n' "$*" >&2
	exit 1
}

while (($# > 0)); do
	case "$1" in
		--control-plane)
			[[ $# -ge 2 ]] || die "--control-plane precisa de um valor"
			CP="${2%/}"
			shift 2
			;;
		--node-id)
			[[ $# -ge 2 ]] || die "--node-id precisa de um valor"
			NODE_ID="$2"
			shift 2
			;;
		--node-key-file)
			[[ $# -ge 2 ]] || die "--node-key-file precisa de um valor"
			KEY_FILE="$2"
			shift 2
			;;
		--expected-fingerprint)
			[[ $# -ge 2 ]] || die "--expected-fingerprint precisa de um valor"
			EXPECTED_FP="$2"
			shift 2
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

[[ -n "$CP" ]] || die "--control-plane é obrigatório"
[[ -n "$NODE_ID" ]] || die "--node-id é obrigatório"
[[ -n "$KEY_FILE" ]] || die "--node-key-file é obrigatório"
[[ -r "$KEY_FILE" ]] || die "não consigo ler $KEY_FILE"
command -v curl >/dev/null 2>&1 || die "comando ausente: curl"
command -v openssl >/dev/null 2>&1 || die "comando ausente: openssl"

node_key="$(head -n1 "$KEY_FILE" | tr -d '\r\n')"
[[ -n "$node_key" ]] || die "arquivo de chave vazio: $KEY_FILE"

resp="$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
	-H "X-Node-Id: $NODE_ID" -H "X-Node-Key: $node_key" \
	"$CP/network/nodes/me/config")" || die "GET /network/nodes/me/config falhou"

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

signed="$(json_bool "$resp" || true)"
format_version="$(json_num "$resp" formatVersion || true)"
config_version="$(json_num "$resp" configVersion || true)"
issued_at="$(json_str "$resp" issuedAt || true)"
public_key="$(json_str "$resp" publicKey || true)"
signature="$(json_str "$resp" signature || true)"

printf 'formatVersion=%s configVersion=%s signed=%s issuedAt=%s\n' \
	"${format_version:-?}" "${config_version:-?}" "${signed:-?}" "${issued_at:-?}"

if [[ -z "$format_version" ]]; then
	die "resposta sem formatVersion — endpoint ou credencial errada?"
fi
if ((format_version < 4)); then
	die "formatVersion=$format_version: backend antigo (o mapeamento das envs e o formato atual são 4)"
fi
if [[ "$signed" != "true" && -z "$public_key" ]]; then
	printf 'publicKey=null\n'
	printf 'RESULTADO: config NÃO assinada e sem publicKey. Confira no serviço os nomes exatos:\n'
	printf '  REDE_NETWORK_CONFIG_SIGNING_KEY\n'
	printf '  REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY\n'
	printf 'e reinicie. Chave privada em PEM (em vez de DER) gera o warning no log.\n'
	exit 2
fi
if [[ "$signed" != "true" ]]; then
	printf 'publicKey=presente\n'
	printf 'RESULTADO: config NÃO assinada, mas publicKey presente: a privada está ausente/inválida.\n'
	printf 'O warning "signing-key presente mas nao e Ed25519 PKCS#8" confirma formato; use DER, não PEM.\n'
	exit 2
fi
if [[ -z "$public_key" ]]; then
	printf 'publicKey=null\n'
	printf 'RESULTADO: config assinada, mas sem publicKey: a env pública está ausente/inválida.\n'
	printf 'Confira REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY (X.509 DER base64) e reinicie.\n'
	exit 2
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

printf '%s' "$public_key" | base64 -d >"$tmpdir/pub.der" 2>/dev/null \
	|| die "publicKey não é base64 válido"
openssl pkey -pubin -inform DER -in "$tmpdir/pub.der" -noout >/dev/null 2>&1 \
	|| die "publicKey não é uma chave X.509 DER válida"

fingerprint="$(sha256sum "$tmpdir/pub.der" | awk '{print $1}')"
printf 'publicKey presente (%s bytes DER)\n' "$(wc -c <"$tmpdir/pub.der" | tr -d ' ')"
printf 'fingerprint=SHA256:%s\n' "$fingerprint"

if [[ -n "$EXPECTED_FP" ]]; then
	expected="${EXPECTED_FP#SHA256:}"
	expected="${expected#sha256:}"
	expected="$(printf '%s' "$expected" | tr 'A-F' 'a-f')"
	if [[ "$expected" != "$fingerprint" ]]; then
		printf 'RESULTADO: fingerprint DIVERGENTE (esperado %s, recebido %s)\n' "$expected" "$fingerprint"
		exit 3
	fi
	printf 'fingerprint confere com o esperado\n'
fi

# Reconstrução da linha canônica igual à do backend (NetworkConfigSigner.SignedFields).
# Só é possível com jq; sem ele, os campos acima já provam presença, mas não a assinatura.
if command -v jq >/dev/null 2>&1; then
	canonical="$(printf '%s' "$resp" | jq -r '
		def jlist: sort | map(tojson) | join(",") | "[" + . + "]";
		[ (.formatVersion|tostring), (.configVersion|tostring), .nodeId,
		  (.profiles|jlist), (.limits.cpuCores|tostring), (.limits.memoryMb|tostring),
		  (.limits.diskMb|tostring), (.limits.bandwidthMbps|tostring),
		  (.allowedTasks|jlist), (.allowedRoutes|jlist), .issuedAt,
		  (.ttlSeconds|tostring), (.term|tostring) ] | join("|")')" \
		|| die "não consegui reconstruir a linha canônica"
	printf '%s' "$canonical" >"$tmpdir/msg"
	printf '%s' "$signature" | base64 -d >"$tmpdir/sig" 2>/dev/null \
		|| die "signature não é base64 válido"
	if openssl pkeyutl -verify -pubin -inkey "$tmpdir/pub.der" -keyform DER -rawin \
		-in "$tmpdir/msg" -sigfile "$tmpdir/sig" >/dev/null 2>&1; then
		printf 'assinatura Ed25519 confere com a publicKey publicada\n'
	else
		printf 'RESULTADO: assinatura NÃO confere com a publicKey publicada\n'
		exit 1
	fi
else
	printf 'AVISO: jq ausente; assinatura não verificada (instale jq para a prova completa)\n'
fi

printf 'RESULTADO: OK — signed:true, publicKey presente e verificável\n'
