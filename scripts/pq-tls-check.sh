#!/usr/bin/env bash
# Mede se o host negocia o grupo híbrido pós-quântico X25519MLKEM768 no TLS 1.3.
#
# Por que dentro de um contêiner: o OpenSSL 3.0 das imagens atuais não conhece o grupo, e um
# cliente que não o conhece "passa" no teste sem provar nada. A imagem alpine traz OpenSSL 3.5.
#
# Uso: scripts/pq-tls-check.sh [host...]   (padrão: os hosts de PRODUÇÃO)
# Sai com código 1 se algum host NÃO negociar o grupo — serve para virar verificação de deploy.
#
# A QA fica FORA do padrão de propósito: a borda dela (Traefik direto, sem Cloudflare) ainda não
# negocia o grupo, e um host que não PODE cumprir não deve transformar o portão em vermelho
# permanente. Para medir a QA (informativo):
#   scripts/pq-tls-check.sh enois-qa.b7cloud.tech enois-api-qa.b7cloud.tech
# — leia o código de saída como relatório daquele ambiente, não como gate de produção.
set -uo pipefail

HOSTS=("$@")
if [ ${#HOSTS[@]} -eq 0 ]; then
  HOSTS=(enois.ia.br api.enois.ia.br)
fi

falhou=0
for host in "${HOSTS[@]}"; do
  grupo=$(timeout 60 docker run --rm alpine:latest sh -c "
    apk add --no-cache openssl >/dev/null 2>&1
    echo | openssl s_client -connect ${host}:443 -servername ${host} \
      -groups X25519MLKEM768:x25519 -tls1_3 2>/dev/null | grep -i 'Negotiated TLS1.3 group' | head -1
  " 2>/dev/null | sed 's/.*: //')
  if [ "$grupo" = "X25519MLKEM768" ]; then
    echo "OK      $host  ($grupo)"
  else
    echo "FALHOU  $host  (negociado: ${grupo:-clássico})"
    falhou=1
  fi
done

if [ "$falhou" -ne 0 ]; then
  echo
  echo "Host sem PQ híbrido: a borda (Cloudflare/terminador TLS) precisa habilitar X25519MLKEM768."
fi
exit "$falhou"
