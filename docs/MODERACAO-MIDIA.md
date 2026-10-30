# Barreira de mídia (conteúdo gráfico e sexual)

**Status:** implementado. Migração `V199__media_safety_checks.sql`.

## Por que existe

A moderação do Énóis nasceu reativa: só olhava o que alguém denunciava, e só o **texto** do alvo.
Uma publicação com uma imagem explícita e uma legenda curta — ou sem legenda — não tinha sinal
nenhum: não havia motivo de denúncia adequado, a pré-triagem não pesava nada e o modelo recebia só
o texto. O upload também não passa pelo backend (o navegador envia direto ao S3 por URL assinada),
então nenhum ponto do caminho olhava o arquivo.

Esta barreira fecha esse buraco: **toda imagem publicada é olhada**, independentemente de alguém
denunciar.

## Como funciona

Três gatilhos, todos alimentando o mesmo serviço:

| Gatilho | Quando roda | Onde |
|---|---|---|
| Gancho de publicação | Logo depois do commit de um post novo | `MediaSafetyListener` (`AFTER_COMMIT` em `PostPublishedEvent`) |
| Varredura periódica | A cada 5 minutos, olhando 72h para trás | `MediaSafetySweepWorker` |
| Denúncia | Quando alguém denuncia com motivo `SEXUAL_CONTENT` | `ModerationAiService` (prompt multimodal) |

O `MediaSafetyService` é o único que **age**. O classificador só dá um veredito; quem remove
conteúdo e quem sanciona é o serviço, com as regras abaixo.

### Classificação

O `MediaSafetyAnalyzer` envia a imagem ao mesmo provedor de moderação (`rede.moderation.llm.*`) e
espera uma resposta estruturada com veredito, gravidade, confiança e motivo. O contrato e o texto do
pedido estão no código (`MediaSafetyAnalyzer`), não neste documento.

### Quando a automação age sozinha

A automação só age em veredito de violação **com confiança suficiente**, e age mais rápido quando o
conteúdo envolve menor de idade. Abaixo do piso, ou com veredito inconclusivo, o registro fica no
livro-caixa e a decisão vai para revisão humana. Os pisos e a regra exata de ação vivem em
`MediaSafetyAnalyzer.canActAlone` — não são publicados aqui.

Resposta que não casa com o contrato esperado é tratada como inconclusiva, nunca como segura.

Recurso continua disponível pelo fluxo normal de penalidades.

A ação é, nesta ordem: **remover a mídia de todas as publicações que a usam** (o mesmo arquivo pode
estar em mais de uma), depois sancionar o dono e sanitizar o perfil. Se a remoção falhar, não há
sanção — conteúdo no ar e conta banida seria o pior dos dois mundos.

### Livro-caixa (`media_safety_checks`)

Uma linha por mídia, com veredito, gravidade, confiança, motivo, modelo e horário. Serve para três
coisas: idempotência (a mesma imagem não é classificada duas vezes), auditoria (dá para reconstruir
por que uma conta foi banida) e a própria varredura (a consulta de pendentes é um `left join` contra
esta tabela).

Falha ao gravar o livro-caixa não bloqueia a barreira: a remoção acontece de qualquer forma.

## Configuração

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.moderation.media.enabled` | `MODERATION_MEDIA_ENABLED` | `true` |
| `rede.moderation.media.interval-ms` | `MODERATION_MEDIA_INTERVAL_MS` | `300000` (5 min) |
| `rede.moderation.media.initial-delay-ms` | `MODERATION_MEDIA_INITIAL_DELAY_MS` | `45000` |
| `rede.moderation.media.sweep-batch` | `MODERATION_MEDIA_SWEEP_BATCH` | `25` |
| `rede.moderation.media.lookback-hours` | `MODERATION_MEDIA_LOOKBACK_HOURS` | `72` |

Sem `MODERATION_LLM_API_KEY` o classificador não responde e nada é removido automaticamente — a
varredura segue rodando e registrando `NO_VERDICT`.

## Limites conhecidos

- **Só imagem.** Vídeo e áudio não passam por esta barreira.
- **Só o que está em `media_assets`.** Imagem embutida em HTML de bio ou em link externo não é
  baixada nem olhada.
- **Custo por imagem.** Cada classificação é uma chamada ao provedor. O lote da varredura é
  pequeno (25) justamente por isso.
- **Formato do pedido multimodal** foi validado contra o contrato do provedor e coberto por teste,
  mas não há teste de integração ao vivo: a chave não existe no ambiente local.

## Onde está o código

```
moderation/domain/MediaSafetyVerdict.java          veredito + isViolation()
moderation/domain/MediaSafetyCheck.java            registro do livro-caixa
moderation/ai/MediaSafetyAnalyzer.java             classificador + regra de ação
moderation/application/MediaSafetyService.java     remove, sanciona, registra
moderation/application/MediaSafetyListener.java    gancho de publicação
moderation/application/MediaSafetySweepWorker.java varredura periódica
moderation/adapters/out/persistence/JdbcMediaSafetyCheckAdapter.java
moderation/adapters/out/media/ReportedMediaAdapter.java   lê e reduz a imagem denunciada
```
