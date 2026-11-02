# Quarentena de conteúdo automatizado

**Status:** implementado. Migração `V201__content_automation_checks.sql`.

## Por que existe

A diretriz de conteúdo humano (diretriz 8 do `ModerationAiService`) só era aplicada **quando alguém
denunciava**. Uma conta que existe para publicar saída de gerador nunca é denunciada — ela não
incomoda ninguém, só enche o feed. A regra existia no papel e não tinha barreira.

Esta camada é a barreira: ela roda na publicação e em varredura, sem depender de denúncia.

## O que ela faz — e o que ela NUNCA faz

O post **suspeito entra em quarentena**: some do feed, do perfil e do detalhe, mas continua no
banco, com o autor, com as reações e com a mídia. O autor é avisado de que está em análise e uma
pessoa do time decide o desfecho.

**A automação não apaga e não pune.** Nenhum caminho desta camada aplica sanção, remove post ou
bane conta. Quem libera ou remove é uma pessoa, pela fila de revisão; quem pune é uma pessoa, pelo
fluxo normal de moderação, com o caso na mão. Um falso positivo aqui esconde um post por algumas
horas — não destrói a conta de ninguém.

Enquanto o post está em quarentena ele também não conta em credibilidade, ranking nem estatística
de comunidade: as consultas nativas tratam `QUARANTINED` como fora do ar.

## Como decide

Duas etapas, da mais barata para a mais cara:

1. **Marcador determinístico** (`AutomationSignals`). Rastro literal de gerador no texto —
   declaração de ser IA, postura de assistente, formatação rígida de resposta a prompt. Se nenhum
   marcador aparece, o post é registrado como humano **sem chamar o modelo**: é o que mantém o custo
   de uma rede inteira viável.
2. **Classificador** (`ContentAutomationAnalyzer`). Prompt e contrato próprios, veredito
   `HUMAN|AUTOMATED|UNSURE` com confiança. Só `AUTOMATED` acima do piso de confiança aciona a
   quarentena; o resto fica registrado.

O prompt diz explicitamente que **usar IA como ferramenta não é infração** e que mencionar IA numa
opinião, escrever bem ou escrever formal não é sinal de máquina. Equipe (admin e moderador) fica
fora da barreira: esconder o conteúdo de quem cuida da rede seria pior que qualquer bot.

## Fila de revisão

| Rota | O que faz |
|---|---|
| `GET /moderation/quarantine` | Casos em quarentena, com autor, texto, veredito, confiança e motivo |
| `POST /moderation/quarantine/{postId}/release` | A automação errou: o post volta ao ar como estava |
| `POST /moderation/quarantine/{postId}/remove` | A violação existe: o post sai de vez (a conta não é punida por aqui) |

Todas exigem sessão de moderador. As duas saídas notificam o autor.

Uma denúncia revisada pelo painel de moderação também libera a quarentena: `dismiss` (arquivar) e
`warn` (avisar) devolvem o post ao ar, para o caso de a mesma publicação ter chegado aos dois
caminhos.

## Livro-caixa (`content_automation_checks`)

Uma linha por post: veredito, confiança, motivo, modelo, quando foi avaliado e o estado da revisão
(`NONE` quando nada saiu do ar, `PENDING` em quarentena, `RELEASED`/`REMOVED` depois da decisão).
Serve para não avaliar o mesmo post duas vezes — inclusive o que foi registrado como humano — e para
o autor conseguir entender depois por que a publicação ficou fora do ar.

## Gatilhos

| Gatilho | Quando roda | Onde |
|---|---|---|
| Gancho de publicação | Logo depois do commit de um post novo | `ContentAutomationListener` (`AFTER_COMMIT` em `PostPublishedEvent`) |
| Varredura periódica | A cada 10 minutos, olhando 72h para trás | `ContentAutomationSweepWorker` |
| Denúncia | Motivo `AI_GENERATED` na triagem de denúncia | `ModerationAiService` (diretriz 8) |
| Denúncia sexual em post | Denúncia com motivo `SEXUAL_CONTENT` em `POST`, logo depois do commit | `SexualContentReportListener` (`AFTER_COMMIT` em `SexualContentReportEvent`) |

## Denúncia sexual em post: quarentena imediata

Conteúdo sexual é o caso em que **esperar por uma pessoa é o dano**. Por isso ele tem caminho
próprio, e não a fila comum de denúncias:

1. **Precaução primeiro.** A denúncia sexual em `POST` esconde o post na hora (`QUARANTINED`, não
   apagamento) e grava o caso como `PENDING` no livro-caixa — antes de qualquer chamada ao modelo.
   Se o processo morrer no meio, o caso já está com uma pessoa.
2. **O bot decide em seguida**, olhando o texto **e as imagens** do próprio post. Três saídas:
   - `PENALIZAR` que passa no gate (`canAutoPenalize`: sem ambiguidade e confiança ≥ 0,7) → o post
     é **removido de vez** e o caso fecha como `REMOVED`;
   - `NENHUMA` → o post **volta ao feed** como estava e o caso fecha como `RELEASED`;
   - qualquer outra coisa — `HUMAN_REVIEW`, JSON inválido, modelo fora do ar, exceção — → o post
     **continua escondido** e o caso fica `PENDING` na fila humana.
3. **A conta nunca é punida por este caminho.** Apagar conteúdo e punir conta são decisões
   separadas; a segunda continua sendo de uma pessoa.

O autor é avisado nas três etapas (em análise, liberado, removido). O caminho é idempotente: um post
já avaliado não é avaliado de novo. A mídia sexual no **upload** continua sendo tratada por
`MediaSafetyService`, que é outra barreira e não muda aqui.

Desligar `rede.moderation.sexual.enabled` devolve a denúncia sexual ao fluxo comum (só fila humana).

## Configuração

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.moderation.automation.enabled` | `MODERATION_AUTOMATION_ENABLED` | `true` |
| `rede.moderation.automation.interval-ms` | `MODERATION_AUTOMATION_INTERVAL_MS` | `600000` (10 min) |
| `rede.moderation.automation.initial-delay-ms` | `MODERATION_AUTOMATION_INITIAL_DELAY_MS` | `90000` |
| `rede.moderation.automation.sweep-batch` | `MODERATION_AUTOMATION_SWEEP_BATCH` | `20` |
| `rede.moderation.automation.lookback-hours` | `MODERATION_AUTOMATION_LOOKBACK_HOURS` | `72` |
| `rede.moderation.sexual.enabled` | `MODERATION_SEXUAL_ENABLED` | `true` |

Sem `MODERATION_LLM_API_KEY` o classificador não responde: o post com marcador fica registrado como
inconclusivo e **nada** sai do ar — o marcador sozinho nunca esconde publicação.

## Limites conhecidos

- **Só o texto do post.** Saída de gerador em comentário, mensagem ou bio não é avaliada aqui.
- **Texto sem rastro de gerador passa.** Robô bem instruído a escrever como gente não acende o
  marcador, e o modelo não é chamado. O caminho para esse caso é a denúncia.
- **Custo por post suspeito.** Cada post com marcador é uma chamada ao modelo; por isso o marcador
  determinístico vem antes e o lote da varredura é pequeno.

## Onde está o código

```
moderation/domain/AutomationSignals.java                     marcador determinístico
moderation/domain/ContentAutomationVerdict.java              veredito
moderation/domain/ContentAutomationCheck.java                registro do livro-caixa
moderation/ai/ContentAutomationAnalyzer.java                 classificador + regra de quarentena
moderation/application/ContentAutomationService.java         decide e coloca em quarentena
moderation/application/ContentAutomationListener.java        gancho de publicação
moderation/application/ContentAutomationSweepWorker.java     varredura periódica
moderation/application/ContentQuarantineReviewService.java   fila de revisão (liberar/remover)
moderation/application/SexualContentReportService.java       denúncia sexual: quarentena + bot decide
moderation/application/SexualContentReportListener.java      gancho da denúncia sexual (AFTER_COMMIT)
moderation/application/ModerationService.java                publica o evento no report()/systemReport()
feed/domain/PostStatus.java                                  status QUARANTINED
feed/application/DeletePostService.java                      quarantine()/release()
moderation/adapters/out/persistence/JdbcContentAutomationCheckAdapter.java
moderation/adapters/in/web/ModeratorController.java          rotas /moderation/quarantine
```
