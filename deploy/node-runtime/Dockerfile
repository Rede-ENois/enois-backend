# Imagem do Node Runtime — a base que o container do nó roda.
#
# O jar é o Control Plane e NÃO entra aqui: o entrypoint roda o loop do nó
# (node-runtime.sh, montado read-only pelo compose). A imagem existe para dar
# ao script as ferramentas que ele usa — bash, curl (todo o transporte HTTP),
# openssl (verificação Ed25519 da config assinada) e ffmpeg/ffprobe (executor
# MEDIA_TRANSCODE) — e nada mais: cada pacote extra é superfície de ataque num
# container que roda na máquina de um participante.
#
# O digest do índice multi-arch é pinado para que a máquina do participante e a
# do CI construam sobre exatamente a mesma base, e para que um rebuild meses
# depois não traga uma base trocada sem decisão consciente (atualizar o digest
# é decisão, não efeito colateral do pull).
FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc

# ca-certificates: as chamadas https do nó (heartbeat, config, tar.gz do release)
# não verificam nada sem o pacote de CAs. ffmpeg: executor de transcode.
RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    openssl \
    ffmpeg \
  && rm -rf /var/lib/apt/lists/*

# Mesmo uid/gid que o compose declara (user: "10001:10001") e que o install.sh
# usa ao gravar o estado em $NODE_HOME no host: os arquivos da API key e do
# cache precisam ser legíveis por dentro e só pelo dono.
USER 10001:10001

WORKDIR /var/lib/enois-node
ENTRYPOINT ["/bin/bash", "/node-runtime.sh"]
