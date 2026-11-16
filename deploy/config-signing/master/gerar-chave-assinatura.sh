#!/usr/bin/env bash
# Gera o par Ed25519 que assina a config dos nós no Control Plane.
#
# A privada NUNCA sai deste host: o arquivo `env` é para o cofre/segredo do deploy,
# não para chat, issue ou commit. O que se compartilha com o operador do nó é apenas
# `public-key.b64` / `public-key.sha256` (fingerprint), por canal confiável.
set -euo pipefail

DIR="/etc/enois/config-signing"
FORCE=0

usage() {
	cat <<'EOF'
Uso: gerar-chave-assinatura.sh [--dir DIR] [--force]

  --dir DIR   diretório onde o par será gravado (padrão: /etc/enois/config-signing)
  --force     sobrescreve um par existente (rotaciona a chave; o pino dos nós
              precisa ser trocado manualmente depois)

Gera:
  DIR/signing.pem          privada Ed25519 (600)
  DIR/signing.pub.pem      pública Ed25519 (644)
  DIR/env                  REDE_NETWORK_CONFIG_SIGNING_KEY e ..._PUBLIC_KEY (600)
  DIR/public-key.b64       pública em X.509 DER base64, para compartilhar (644)
  DIR/public-key.sha256    fingerprint SHA-256 da pública (644)

O `env` já sai no formato que o backend lê: base64 do DER, não base64 do PEM.
EOF
}

die() {
	printf 'ERRO: %s\n' "$*" >&2
	exit 1
}

while (($# > 0)); do
	case "$1" in
		--dir)
			[[ $# -ge 2 ]] || die "--dir precisa de um valor"
			DIR="$2"
			shift 2
			;;
		--force)
			FORCE=1
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

for tool in openssl base64 sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || die "comando ausente: $tool"
done

if [[ -e "$DIR/signing.pem" && "$FORCE" -ne 1 ]]; then
	die "já existe $DIR/signing.pem; use --force para rotacionar (e prepare a troca do pino nos nós)"
fi

umask 077
mkdir -p "$DIR"
chmod 700 "$DIR"

if [[ "$FORCE" -eq 1 ]]; then
	rm -f "$DIR/signing.pem" "$DIR/signing.pub.pem" "$DIR/env" \
		"$DIR/public-key.b64" "$DIR/public-key.sha256"
fi

openssl genpkey -algorithm ed25519 -out "$DIR/signing.pem"
openssl pkey -in "$DIR/signing.pem" -pubout -out "$DIR/signing.pub.pem"

# O backend decodifica o base64 e entrega os bytes direto a PKCS8EncodedKeySpec /
# X509EncodedKeySpec: tem de ser DER. `base64 -w0 signing.pem` (PEM) não serve.
priv_b64="$(openssl pkcs8 -topk8 -nocrypt -in "$DIR/signing.pem" -outform DER | base64 -w0)"
pub_b64="$(openssl pkey -in "$DIR/signing.pem" -pubout -outform DER | base64 -w0)"

# Prova que os valores que vão para as envs são exatamente o que o backend aceita.
printf '%s' "$priv_b64" | base64 -d | openssl pkey -inform DER -noout >/dev/null 2>&1 \
	|| die "a privada gerada não decodifica como PKCS#8 DER"
printf '%s' "$pub_b64" | base64 -d | openssl pkey -pubin -inform DER -noout >/dev/null 2>&1 \
	|| die "a pública gerada não decodifica como X.509 DER"

derived_pub="$(printf '%s' "$priv_b64" | base64 -d \
	| openssl pkey -inform DER -pubout -outform DER | base64 -w0)"
[[ "$derived_pub" == "$pub_b64" ]] || die "a pública não é par da privada gerada"

fingerprint="$(printf '%s' "$pub_b64" | base64 -d | sha256sum | awk '{print $1}')"

# 600: é segredo de assinatura. O nome das variáveis tem underscore — é o que
# application.properties mapeia; SIGNINGKEY/SIGNINGPUBLICKEY não são lidos.
umask 077
cat >"$DIR/env" <<EOF
REDE_NETWORK_CONFIG_SIGNING_KEY=$priv_b64
REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY=$pub_b64
EOF
chmod 600 "$DIR/env"
chmod 600 "$DIR/signing.pem"

umask 022
printf '%s\n' "$pub_b64" >"$DIR/public-key.b64"
printf 'SHA256:%s\n' "$fingerprint" >"$DIR/public-key.sha256"
chmod 644 "$DIR/public-key.b64" "$DIR/public-key.sha256" "$DIR/signing.pub.pem"

printf '\n'
printf 'Par Ed25519 gerado em %s\n' "$DIR"
printf 'Fingerprint da pública: SHA256:%s\n' "$fingerprint"
printf '\n'
printf 'No serviço do Control Plane, carregue as DUAS variáveis de %s e reinicie:\n' "$DIR/env"
printf '  REDE_NETWORK_CONFIG_SIGNING_KEY\n'
printf '  REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY\n'
printf '\n'
printf 'Compartilhe com o operador do nó apenas:\n'
printf '  %s/public-key.b64\n' "$DIR"
printf '  %s/public-key.sha256\n' "$DIR"
printf '\n'
printf 'NÃO envie %s/env nem %s/signing.pem por chat, issue ou commit.\n' "$DIR" "$DIR"
