#!/usr/bin/env bash
# Preenche os segredos do .env local (nunca versionado) e mostra o valor que o backend precisa.
#
# Uso:  cp .env.example .env && ./gen-secrets.sh
#
# O segredo do JWT é o único valor que precisa ser copiado para o deploy do backend, como
# REDE_CALLS_JITSI_JWT_SECRET. Ele não é impresso de novo depois: se perder, gere outro e
# reinicie os containers (as chamadas em andamento caem, as novas usam o novo).
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
	echo "crie o .env primeiro: cp .env.example .env" >&2
	exit 1
fi

if grep -q '^JWT_APP_SECRET=.\+' .env; then
	echo "o .env já tem segredos; apague o arquivo e comece de novo se quiser regerar" >&2
	exit 1
fi

# 32 bytes em hex: o plugin do Prosody exige HS256 com chave larga o bastante.
jwt_secret="$(openssl rand -hex 32)"
jicofo_password="$(openssl rand -hex 16)"
jvb_password="$(openssl rand -hex 16)"

sed -i.bak \
	-e "s#^JWT_APP_SECRET=.*#JWT_APP_SECRET=${jwt_secret}#" \
	-e "s#^JICOFO_AUTH_PASSWORD=.*#JICOFO_AUTH_PASSWORD=${jicofo_password}#" \
	-e "s#^JVB_AUTH_PASSWORD=.*#JVB_AUTH_PASSWORD=${jvb_password}#" \
	.env
rm -f .env.bak

chmod 600 .env

cat <<EOF
Segredos gerados em .env (permissão 600).

Copie o valor abaixo para o deploy do backend como REDE_CALLS_JITSI_JWT_SECRET:

  ${jwt_secret}

E confira no backend:
  REDE_CALLS_JITSI_URL=$(grep '^PUBLIC_URL=' .env | cut -d= -f2-)
EOF
