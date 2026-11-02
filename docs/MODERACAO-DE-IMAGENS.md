# Moderação de imagens

Uploads de imagem enviados por `/storage/{chave}` passam pelo classificador OpenNSFW antes de
chegarem ao storage. A verificação roda no pod Java com ONNX Runtime em CPU. Um score acima do
limiar é apenas um sinal preliminar: antes de recusar o upload, o backend pede confirmação ao
analisador semântico de mídia já usado na checagem pós-publicação. Uma resposta segura libera a
imagem; violação confirmada recusa; resposta inconclusiva retorna erro temporário para tentar de
novo. A checagem pós-publicação e a varredura continuam como segunda camada. O OpenNSFW local não
envia bytes a serviços externos nem registra conteúdo em log; a confirmação semântica envia uma
versão reduzida da imagem ao provedor configurado em `rede.moderation.llm.*`. Os uploads
de vídeo, áudio e PDF seguem fora da classificação de imagem.

O processo sanitiza metadados primeiro e classifica depois. Imagem inválida, formato sem decodificador,
imagem animada, falha do classificador local ou falta do classificador interrompe o upload. Uma
imagem acima do limiar só é recusada como conteúdo proibido quando a confirmação semântica retorna
violação com confiança suficiente. Sem veredito semântico, o upload pode ser repetido mais tarde;
publicações continuam cobertas pela checagem pós-publicação e pela varredura.

## Modelo e configuração

O modelo ONNX deriva dos pesos OpenNSFW do Yahoo. A imagem Docker baixa a revisão fixada e confere
o SHA-256 antes de empacotar. O modelo ocupa cerca de 22,5 MB. O runtime fica limitado a uma
configuração de CPU; não é necessária GPU nem sidecar.

| Variável | Padrão | Uso |
| --- | --- | --- |
| `REDE_MEDIA_NSFW_ENABLED` | `true` fora do perfil local `dev` | Liga a verificação. Se ligada, upload direto para o bucket impede o boot. |
| `REDE_MEDIA_NSFW_THRESHOLD` | `0.70` | Score que aciona a confirmação semântica; intervalo válido de 0 a 1. |
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
A confirmação semântica reduz falsos positivos do filtro preliminar, mas não substitui a avaliação
de precisão com um conjunto de validação controlado e apropriado. GIFs e outros formatos animados
são recusados:
os decodificadores ImageIO podem expor quadros como retângulos parciais, então pontuar os quadros
isoladamente poderia deixar conteúdo visível sem análise.

## Dependências de runtime

ONNX Runtime Java fornece o backend CPU. A imagem usa uma base Linux com glibc, instala `libheif`
para leitura AVIF e ativa acesso nativo do JDK exigido pelo plugin ImageIO HEIF. WebP é lido pelo
plugin ImageIO TwelveMonkeys.
