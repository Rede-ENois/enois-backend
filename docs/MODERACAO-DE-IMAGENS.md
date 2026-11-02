# Moderação de imagens

Uploads de imagem enviados por `/storage/{chave}` passam pelo classificador OpenNSFW antes de
chegarem ao storage. A verificação roda no pod Java com ONNX Runtime em CPU; bytes e conteúdo não
são enviados a serviços externos nem registrados em log. Os uploads de vídeo, áudio e PDF seguem
fora desta classificação.

O processo sanitiza metadados primeiro e classifica depois. Só uma imagem abaixo do limiar é
gravada. Imagem inválida, formato sem decodificador, imagem animada, falha de inferência ou falta do
classificador interrompe o upload. O frontend recebe um código específico e mostra uma mensagem
para tentar outro arquivo; não existe aprovação silenciosa.

## Modelo e configuração

O modelo ONNX deriva dos pesos OpenNSFW do Yahoo. A imagem Docker baixa a revisão fixada e confere
o SHA-256 antes de empacotar. O modelo ocupa cerca de 22,5 MB. O runtime fica limitado a uma
configuração de CPU; não é necessária GPU nem sidecar.

| Variável | Padrão | Uso |
| --- | --- | --- |
| `REDE_MEDIA_NSFW_ENABLED` | `true` fora do perfil local `dev` | Liga a verificação. Se ligada, upload direto para o bucket impede o boot. |
| `REDE_MEDIA_NSFW_THRESHOLD` | `0.70` | Score NSFW a partir do qual o upload é recusado; intervalo válido de 0 a 1. |
| `REDE_MEDIA_NSFW_THREADS` | `2` | Threads do ONNX Runtime; valores aceitos de 1 a 8. |
| `REDE_MEDIA_NSFW_MODEL_PATH` | `/app/data/open-nsfw.onnx` | Caminho do modelo validado no build da imagem. |

No desenvolvimento local, a verificação fica desligada por padrão porque o modelo é empacotado
pela imagem Docker. Para exercitar o modelo localmente, baixe a revisão indicada no `Dockerfile`,
valide o mesmo SHA-256 e configure `REDE_MEDIA_NSFW_ENABLED=true` e
`REDE_MEDIA_NSFW_MODEL_PATH=/caminho/para/open-nsfw.onnx`.

## Limites do classificador

O OpenNSFW é um filtro preliminar para pornografia em imagens. Não detecta todas as formas de
conteúdo sexual, desenhos, texto ou violência, e o próprio projeto não promete precisão. A
pontuação de 0,70 é um ponto inicial configurável, não uma medição de precisão da comunidade Énóis.
Antes de ajustar o limiar em produção, a equipe deve avaliar falsos positivos e falsos negativos
com um conjunto de validação controlado e apropriado. GIFs e outros formatos animados são recusados:
os decodificadores ImageIO podem expor quadros como retângulos parciais, então pontuar os quadros
isoladamente poderia deixar conteúdo visível sem análise.

## Dependências de runtime

ONNX Runtime Java fornece o backend CPU. A imagem usa uma base Linux com glibc, instala `libheif`
para leitura AVIF e ativa acesso nativo do JDK exigido pelo plugin ImageIO HEIF. WebP é lido pelo
plugin ImageIO TwelveMonkeys.
