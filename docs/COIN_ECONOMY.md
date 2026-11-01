# Economia da faísca — cotação por uso e emissão escalada

A faísca é um **token de utilidade** com supply fixo de 2.000.000 (gênese). Nada é emitido além
disso: os ganhos saem da tesouraria e as transferências apenas movem saldo entre contas. Este
documento descreve o cálculo automático da **cotação de referência** (BRL) e a conversão da
**emissão automática** por essa cotação.

> A cotação é uma referência interna para remunerar criadores e exibir a mini bolsa. **Não é
> câmbio**: comprar faísca com dinheiro não existe, e não há promessa de valor nem mercado
> secundário (Termos, item 13).

## Como o cálculo roda

`CoinEconomyService` executa a cada 3 horas (`rede.coin.economy.cron`, padrão `0 10 */3 * * *`):

1. Mede, na janela (`window-days`, padrão 30 dias), quatro coisas no ledger:
   - **Uso em escrow** (`ESCROW_HOLD`): compras no marketplace e contratos de serviço pagos em
     faíscas.
   - **Transferências P2P** (`TRANSFER_IN`): o giro entre pessoas.
   - **Retorno ao cofre** (`PURCHASE` + `FEE_IN`): faíscas que voltam para a tesouraria em compras
     da loja e taxas de marketplace.
   - **Emissão automática** (`REWARD`): presença, sequência, jogos diários, bônus de
     microprojeto e prêmios.
2. Compara o giro com a **meta** (`target-turnover`, padrão 5% do estoque em circulação no
   período) e multiplica pela **escassez** do cofre: quanto menos faíscas restam no cofre em
   relação ao total, mais cada faísca vale. Emissão dilui e empurra para baixo.
3. Aplica no máximo **1% de variação por publicação** (`max-step`) e respeita piso de 0,1 centavo
   e teto de R$ 1,00 por faísca (`min-micros` / `max-micros`).
4. Se o valor mudou **e o período já fechou**, publica uma nova linha em `coin_rate_history` — a
   mini bolsa mostra o histórico. Sem mudança, nada é gravado.

**Cadência: uma publicação por período.** O período é `period-hours` (padrão 3h) contado da
última publicação. O cron de 3h em 3h e o botão `POST /admin/carteira/economia/recalcular` obedecem
à mesma regra: fora do período, nada é publicado e o valor não se move — clicar repetidamente não
equivale a vários períodos de mercado. Diferente de um passo proporcional ao tempo decorrido, a
cotação anda um passo inteiro por período, sempre para cima ou para baixo conforme o movimento
medido; quem decide é a janela, não o relógio do clique.

### A rodada completa mensal (validação de referência)

No **1º dia de cada mês** (`rede.coin.economy.monthly-cron`, padrão `0 40 3 1 * *`) roda o cálculo
COMPLETO: o preço é recalculado **do zero** — âncora (`anchor-micros`, padrão a cotação inicial de
1 centavo) × índice da janela de 30 dias — **sem partir do último valor publicado** e **sem o
limite de passo**. É a validação de referência da cotação: o controle de passo curto das 3h pode
se acumular fora do rumo, e a rodada mensal corrige o preço inteiro de uma vez (piso e teto
continuam valendo). Publica mesmo que o período de 3h não tenha fechado — a trava do período é do
controle suave, não da validação mensal; só não publica se o resultado for igual ao vigente.

```
cotaçãoCompleta = clamp(âncora × índice, min, max)
```

**Economia parada derruba o preço:** sem uso, sem P2P e sem retorno ao cofre o índice vai a zero e
a cotação cede um passo por execução (até o piso). O cofre precisa continuar recebendo faíscas
para a economia não secar.

### Fórmula

```
giro          = escrow + p2p × p2p-weight + retornoAoCofre   (p2p-weight padrão 0,5)
giroEfetivo   = min(giro, circulação × max-usage-ratio)
escassez      = 1 + scarcity-weight × (1 − cofre / (cofre + circulação))
razão         = (giroEfetivo / (circulação × target-turnover)) × escassez
diluição      = 1 + emissão / circulação
índice        = razão / diluição
variação      = clamp((índice − 1) × sensitivity, −max-step, +max-step)
novaCotação   = clamp(cotaçãoAtual × (1 + variação), min, max)
```

- **Sem circulação**, o preço não é reavaliado.
- **P2P entra com peso menor** porque é circulação, não consumo.
- **Teto de giro por janela** limita ping-pong entre contas (lavagem) a uma volta do estoque.
- **Escassez**: cofre cheio ⇒ fator 1×; cofre vazio ⇒ 1× + `scarcity-weight` (padrão 1,5×).

## Emissão automática escalada

Os valores-base continuam definidos na cotação inicial (100 faíscas = R$ 1,00). Na hora de pagar,
`CoinService.scaledReward(base)` converte para a cotação vigente **preservando o valor em reais**:

```
faíscas = base × 10.000 / cotaçãoAtualEmMicros     (mínimo 1 milésimo, piso de 0,1 centavo)
```

- Faísca subiu → a pessoa recebe **menos faíscas** pelo mesmo valor.
- Faísca caiu → recebe **mais faíscas** pelo mesmo valor.
- Escala: presença diária (5), bônus de sequência (25), prêmios dos jogos diários (10/5/3/1),
  bônus de microprojeto (200) e ajuda (20).
- Reação recebida não paga mais faísca (#330): pagar por popularidade incentiva caça a curtidas,
  e a Énóis não é um mercado de atenção. O que se recompensa é ajudar. As entradas antigas
  (`reacoes:*`) continuam no ledger como histórico.

### Ajuda: resposta aceita numa dúvida

A recompensa vai para quem **ajudou**, não para o que ficou popular. Quando quem perguntou aceita
uma resposta (`POST /api/feed/posts/{postId}/accepted-answer`), quem respondeu recebe
`scaledReward(20)` (`QuestionAnswerService.HELP_REWARD`) com ref `resposta:{postId}`:

- **Uma vez por dúvida, para sempre.** Trocar a resposta aceita (ou desfazer e aceitar de novo) não
  paga outra vez, nem para outra pessoa: não há como girar a torneira alternando a escolha.
- **Sem conta nova:** se quem responde ou quem pergunta tem menos de 7 dias de conta
  (`CoinService.NEW_ACCOUNT_WINDOW`, a mesma janela do teto de envio), a resposta é aceita mas não
  paga. Uma conta extra perguntando não vira fonte de faíscas.
- **Não paga a si mesmo:** a própria resposta não pode ser aceita.
- **Efeito colateral:** o pagamento roda depois do commit, numa transação própria; se falhar, a
  resposta continua aceita (fica só o log `answer_reward_failed`).

**Não são escalados:** o airdrop inicial (distribuição do gênese), o comunicado histórico de
desculpas e os prêmios concedidos manualmente pela administração — são valores decididos na hora,
não emissão recorrente.

## Presença diária e sequência

A presença do dia (5 faíscas, escaladas) é paga na **primeira ação real do dia**, não só ao abrir a
carteira. Contam: publicar um post (direto, no blog ou agendar), comentar, reagir a post ou
comentário, terminar um jogo diário (vitória, derrota ou fim de partida), enviar mensagem (inclusive
resposta citando outra), reagir a mensagem, entrar numa comunidade ou grupo (inclusive pedir entrada)
e abrir a carteira (`GET /carteira`). A publicação automática de um post agendado **não** conta: não
é a pessoa presente naquele dia. Conteúdo que a moderação automática põe em quarentena **conta**: a
pessoa agiu, e a quarentena é revisão posterior, não punição; quem tem sanção que bloqueia escrita já
é barrado antes da ação e por isso não registra presença.

- **Dia em `America/Sao_Paulo`**, pelo `Clock` injetado. Ref idempotente: `presenca:{conta}:{dia}`.
- **Barato e à prova de falha:** `CoinService.recordPresence(userId)` roda depois do commit da ação,
  em transação própria, e nunca lança (só registra um aviso sem dado pessoal). Se a pessoa já foi
  contada hoje, é uma leitura por chave primária e nada mais.
- **Sequência guardada** em `user_streaks` (V212): `current_days`, `longest_days`, `last_day` (último
  dia coberto, ativo ou congelado) e `last_frozen_day`.
- **Congelamento semanal:** 1 por semana ISO (segunda a domingo), sem acumular. Se faltou
  **exatamente ontem** e a semana desse dia ainda não usou o congelamento, ele é consumido sozinho na
  próxima ação: a sequência continua e o dia congelado não soma. Faltar uma segunda vez na mesma
  semana, ou dois dias seguidos, zera a sequência. Tudo é aplicado de forma preguiçosa, sem job.
- **Bônus de sequência:** a cada 7 dias seguidos (dia 7, 14, 21...) a presença paga também o bônus
  (25, escalado); nos outros dias, só a presença. O múltiplo de 7 é contado sobre a sequência
  guardada (`current_days`): o dia congelado não soma, então o descanso adia o bônus em um dia e
  nunca o paga duas vezes. Sequência zerada recomeça a contagem.
- **Quem já tinha presença:** sem backfill. Na primeira ação (ou leitura) depois do deploy, a
  sequência é reconstruída a partir das presenças dos últimos 60 dias no ledger.

## Unidade

A cotação é guardada em **micro-reais por faísca** (`brl_micros`; 1 centavo = 10.000 µ) para
permitir passos de 1%. A API expõe:

- `brlMicros` — precisão real (novo);
- `brlCents` — leitura arredondada, mantida para compatibilidade.

## Parâmetros (env)

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.coin.economy.enabled` | `COIN_ECONOMY_ENABLED` | `true` |
| `rede.coin.economy.cron` | `COIN_ECONOMY_CRON` | `0 10 */3 * * *` |
| `rede.coin.economy.zone` | `COIN_ECONOMY_ZONE` | `America/Sao_Paulo` |
| `rede.coin.economy.monthly-cron` | `COIN_ECONOMY_MONTHLY_CRON` | `0 40 3 1 * *` |
| `rede.coin.economy.anchor-micros` | `COIN_ECONOMY_ANCHOR_MICROS` | `10000` (1 centavo) |
| `rede.coin.economy.period-hours` | `COIN_ECONOMY_PERIOD_HOURS` | `3` |
| `rede.coin.economy.window-days` | `COIN_ECONOMY_WINDOW_DAYS` | `30` |
| `rede.coin.economy.target-turnover` | `COIN_ECONOMY_TARGET_TURNOVER` | `0.05` |
| `rede.coin.economy.p2p-weight` | `COIN_ECONOMY_P2P_WEIGHT` | `0.5` |
| `rede.coin.economy.max-usage-ratio` | `COIN_ECONOMY_MAX_USAGE_RATIO` | `1.0` |
| `rede.coin.economy.scarcity-weight` | `COIN_ECONOMY_SCARCITY_WEIGHT` | `0.5` |
| `rede.coin.economy.sensitivity` | `COIN_ECONOMY_SENSITIVITY` | `0.25` |
| `rede.coin.economy.max-step` | `COIN_ECONOMY_MAX_STEP` | `0.01` |
| `rede.coin.economy.min-micros` | `COIN_ECONOMY_MIN_MICROS` | `1000` (0,1 centavo) |
| `rede.coin.economy.max-micros` | `COIN_ECONOMY_MAX_MICROS` | `1000000` (R$ 1,00) |

Desligar `enabled` congela a cotação automática (a publicação manual continua funcionando).

## Observabilidade

`GET /admin/carteira/economia` (admin) devolve janela, uso de escrow, P2P, retorno ao cofre,
emissão, circulação, saldo do cofre, escassez, índice, a cotação sugerida, o período já decorrido,
a próxima avaliação e `publicavelAgora` — sem publicar nada. `POST /admin/carteira/economia/recalcular`
(admin) roda o cálculo na hora e publica só se o período fechou e o valor mudou. O worker loga
`coin_economy quote_micros=...` quando publica.