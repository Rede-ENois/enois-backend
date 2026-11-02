# Barreira de links (destino suspeito)

**Status:** implementado. Migração `V200__link_safety_checks.sql`.

## Por que existe

A triagem de texto lê o que a pessoa escreveu. Um link é diferente: o texto pode ser uma piada, o
domínio pode ser uma plataforma conhecida e o **destino** pode ser pornografia, golpe ou página de
venda de conteúdo adulto. Nenhum ponto do caminho olhava para onde o link levava — e o post passava
inteiro.

Esta barreira lê o destino e, quando comprova a violação, derruba a publicação e sanciona a conta.

## Como funciona

Três camadas, da mais barata para a mais cara:

1. **Política sem visita.** Host de plataforma dedicada a conteúdo adulto é conclusivo: não existe
   motivo legítimo para publicar esse endereço numa rede profissional, então nem o destino é
   visitado nem o modelo é chamado. Palavra de cunho sexual no caminho do link é **só suspeita** —
   serve de sinal, nunca de punição sozinha ("educacao-sexual-nas-escolas" casa o termo e é
   matéria legítima).
2. **Leitura do destino.** O backend busca o endereço e extrai **título e descrição** — os do Open
   Graph quando existem, senão os do próprio HTML. Nada além disso é lido, guardado ou servido: o
   conteúdo de terceiro não vira conteúdo nosso.
3. **Classificação.** O modelo recebe esse resumo e devolve um veredito estruturado (destino limpo,
   explícito, suspeito ou inconclusivo).

O caso que motivou o módulo é o vídeo hospedado em site legítimo: o domínio não denuncia nada, o
texto não denuncia nada, e o título da página apontada diz tudo.

### Leitura segura do destino

Quem publica escolhe o endereço, então o endereço é dado **não confiável**. Antes de cada salto
(o primeiro e cada redirecionamento) o host é resolvido e **todos** os endereços precisam ser
públicos: rede interna, loopback, link-local, faixa de container e o endereço de metadados da nuvem
ficam de fora; esquema que não seja `http(s)` e porta fora de 80/443 também. Redirecionamento é
seguido na mão, com a mesma checagem em cada salto, e o teto é curto.

A leitura para no primeiro trecho da página e no teto de bytes. Falha de leitura nunca vira
acusação: o destino fica registrado como inconclusivo.

### Quando a automação age sozinha

Só com violação comprovada e confiança acima do piso; abaixo disso o registro fica no livro-caixa e
a decisão vai para revisão humana. Os pisos vivem em `LinkSafetyAnalyzer.canActAlone` e não são
publicados aqui.

A ação é, nesta ordem: **remover o post que disparou e os demais posts do mesmo autor que apontam
para o mesmo destino**, depois sancionar. Destino sexual explícito leva a banimento permanente;
golpe, phishing e venda de conteúdo restringem a conta com direito a recurso. O modelo nunca apaga
nem bane.

### Livro-caixa (`link_safety_checks`)

Uma linha por URL normalizada (chave é o hash SHA-256; o texto fica ao lado para auditoria). Serve
para idempotência — o mesmo vídeo postado por dez pessoas é visitado uma vez — e para auditoria.
Um destino inconclusivo volta a ser tentado depois do descanso configurado, senão uma página fora do
ar ficaria sem julgamento para sempre.

## Gatilhos

| Gatilho | Quando roda | Onde |
|---|---|---|
| Gancho de publicação | Logo depois do commit de um post novo | `LinkSafetyListener` (`AFTER_COMMIT` em `PostPublishedEvent`) |
| Varredura periódica | A cada 5 minutos, olhando 72h para trás | `LinkSafetySweepWorker` |
| Denúncia | Motivo `SCAM`/`SPAM` com link no texto | `ModerationAiService` (diretriz 12 do prompt) |

## Configuração

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.moderation.links.enabled` | `MODERATION_LINKS_ENABLED` | `true` |
| `rede.moderation.links.interval-ms` | `MODERATION_LINKS_INTERVAL_MS` | `300000` (5 min) |
| `rede.moderation.links.initial-delay-ms` | `MODERATION_LINKS_INITIAL_DELAY_MS` | `60000` |
| `rede.moderation.links.sweep-batch` | `MODERATION_LINKS_SWEEP_BATCH` | `15` |
| `rede.moderation.links.lookback-hours` | `MODERATION_LINKS_LOOKBACK_HOURS` | `72` |
| `rede.moderation.links.retry-hours` | `MODERATION_LINKS_RETRY_HOURS` | `24` |

Sem `MODERATION_LLM_API_KEY` o classificador não responde: o host de plataforma adulta continua
sendo barrado (não depende de modelo) e o resto fica inconclusivo para revisão humana.

## Limites conhecidos

- **Até 5 links por texto.** Acima disso o post é spam, não conteúdo.
- **Título e descrição, não o vídeo.** Vídeo dentro de página que não se descreve passa; a
  classificação de mídia cobre o que é enviado para cá, não o que está hospedado fora.
- **Encurtador.** O destino real só aparece seguindo o redirecionamento — que é feito, com a mesma
  checagem de rede a cada salto.
- **Custo por destino novo.** Cada URL inédita é uma visita e uma chamada ao modelo. O lote da
  varredura é pequeno por isso; o livro-caixa evita repetir.

## Onde está o código

```
moderation/domain/PostLinks.java                      extrai e normaliza os endereços do texto
moderation/domain/LinkPolicy.java                     host adulto, suspeita no caminho, encurtador
moderation/domain/PageSummary.java                    título e descrição de uma página
moderation/domain/LinkSafetyVerdict.java              veredito do destino
moderation/domain/LinkSafetyCheck.java                registro do livro-caixa
moderation/ai/LinkSafetyAnalyzer.java                 classificador + regra de ação
moderation/application/LinkSafetyService.java         decide, remove, sanciona, registra
moderation/application/LinkSafetyListener.java        gancho de publicação
moderation/application/LinkSafetySweepWorker.java     varredura periódica
moderation/application/port/out/LinkedPageReader.java porta de leitura do destino
moderation/adapters/out/net/HttpLinkedPageReader.java leitura com bloqueio de rede interna
moderation/adapters/out/persistence/JdbcLinkSafetyCheckAdapter.java
```
