#!/usr/bin/env bash
# Preenche os segredos do .env local (nunca versionado) e mostra o valor que o backend precisa.
#
# Uso:  cp .env.example .env && ./gen-secrets.sh
#
# Este script é da stack antiga, sem o auth gateway: as chamadas e as salas de mentoria de hoje
# ficam na Epapo (Rede-ENois/epapo), com os segredos gerados lá. O que este script imprime serve
# só para a stack antiga continuar de pé; ele não é mais o caminho de instalação.
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

Este script imprime o segredo da stack antiga. Na Epapo o segredo de assinatura fica só lá.

  ${jwt_secret}

E confira no backend:
  (o nome novo do host no backend é REDE_CALLS_EPAPO_URL)
EOF
