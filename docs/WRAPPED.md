# Énóis Wrapped: retrospectiva anual

A retrospectiva anual de cada conta, calculada a partir do histórico real do ano e servida já
processada. O módulo é `wrapped/` (hexagonal: `domain`, `application` com portas, `adapters/in/web`,
`adapters/out/persistence`).

## O que a pessoa vê

Uma sequência de seções (`WrappedRetrospectiva`): métricas do ano, assuntos que publicou e sobre os
quais comentou, publicações marcantes, discussões ("tretas"), pessoas com quem mais se cruzou,
ritmo (mês/dia da semana/hora), perfil de participação, impacto na comunidade e encerramento.
Cada seção só aparece com dado; conta sem atividades recebe `dadosInsuficientes = true` e uma
experiência curta — nunca uma retrospectiva inventada.

## Intervalo do ano

`WrappedAno`: histórico de **01/01 00:00 a 25/12 23:59:59.999**, em **`America/Sao_Paulo`**, o
fuso oficial da aplicação (decisão dos mantenedores: o horário oficial é o de São Paulo, o mesmo
das demais rotinas de calendário). A janela de exibição vai de **26/12 00:00 a 01/01 00:00** do ano
seguinte, também nesse fuso.

## Fontes de dados (só o histórico real, sem métrica inventada)

| Fato | Tabela | Como entra |
|---|---|---|
| Publicação própria | `posts` | `author_id`, `status = ACTIVE`, `deleted_at is null` |
| Comentário / resposta | `comments` | `author_id`, `status = ACTIVE` |
| Reação/repost/compartilhamento | `reactions`, `post_reposts`, `post_shares` | `user_id`, `created_at` |
| Salvo | `saved_posts` | conta como interação feita (`salvos`) |
| Voto em enquete | `poll_votes` | conta como interação feita (`votosFeitos`) |
| Interações RECEBIDAS | as mesmas, pelo autor da publicação | `posts.author_id = conta` |
| Reação recebida em comentário | `comment_reactions` | conta comentários distintos com reação (`comentariosComReacao`) |
| Comunidades | `community_members`, `posts.community_id` | membro no ano e/ou publicou no ano |
| Credibilidade | `users.reputation` | coluna materializada pelo job do ranking |

Conteúdo removido/quarentenado não entra: o SQL filtra `status = ACTIVE` (e `deleted_at is null`).

## Reconhecimento de padrões (determinístico, local)

- **Assuntos:** reaproveita o `PapoAssuntoExtractor` do Papo Reto (NFD, stopwords, unigramas/bigramas,
  clustering sem sobreposição). Nada de IA externa.
- **Tretas:** agrupa os comentários da conta por publicação; conta como treta a partir de 2
  participações, ordenando por número de comentários e duração. Sem rótulo moral.
- **Perfil:** regras objetivas (debatedor, especialista, observador, criador, comunitário), cada
  uma com o número que a sustenta. Mais de um é possível.
- **Ritmo:** pico por mês, dia da semana e hora, mais a série completa para a view desenhar. A
  seção também compara a primeira e a segunda metade do ano (`WrappedComportamento`): cresceu,
  desacelerou ou estável — só volume, nunca julgamento.
- **Assuntos:** o mesmo extrator do Papo Reto, na regra de contexto (só expressão de 2+ palavras
  vira assunto; palavra solta não aparece).
- A credibilidade **não** entra na análise; é usada só para priorizar a fila e como contexto no
  impacto.

## Fila, agendamento e concorrência

- `wrapped_retrospectivas` (V380) tem uma linha por (conta, ano) com estado
  `PENDING|PROCESSING|DONE|FAILED`.
- A fila é construída na virada e **completada** (idempotente: `insert ... where not exists`) por
  `WrappedFilaConstrutor`, ordenada por `users.reputation` desc, depois data de criação e id
  (determinístico no empate). A credibilidade usada fica gravada em `prioridade` (auditoria).
- `WrappedWorker` (`@Scheduled`) só roda entre **00:00 e 05:00** (America/Sao_Paulo) e entre
  26/12 e 01/01. Uma conta por vez: `WrappedService.processarProxima` pega a próxima PENDING,
  faz o claim atômico (`update ... where estado = 'PENDING'`), calcula e grava o resultado numa
  transação. Às 05:00 o passo para de iniciar conta nova; a que estiver em andamento termina.
- Se não couber numa madrugada, a fila continua nas seguintes (o briefing aceita).
- `PROCESSING` com reserva vencida (`claim_expira_em`) volta à fila — a instância caiu no meio da
  conta, e a geração é atômica por conta (não há resultado parcial).
- Idempotência: conta `DONE` não é recalculada.

## Endpoint (privado)

`GET /wrapped/{ano}` — **sempre** da conta da sessão (`CurrentUserResolver.resolve`, que lança sem
sessão). A rota não recebe a conta como parâmetro: é impossível pedir a retrospectiva de outra
pessoa por troca de id. Respostas: `READY` (com `retrospectiva`), `NOT_READY`, `NAO_ELEGIVEL` (conta
fora da fila), `FORA_DA_JANELA` (fora de 26/12 a 01/01), `NOT_FOUND` (ano diferente do configurado).

## Configuração

`rede.wrapped.ano` (ano processado), `rede.wrapped.habilitado` (interruptor),
`rede.wrapped.delay-ms` (intervalo entre passos) e `rede.wrapped.forcar-janela` (só teste).

## Testes

- `WrappedAgregadorTests` — padrões, isolamento, fronteiras do ano, determinismo (sem banco).
- `WrappedWorkerTests` — janela 00:00–05:00 e período de exibição no fuso da aplicação.
- `WrappedFilaPersistenceTests` — inserção, ordem, claim, retomada e conclusão no H2.
- `WrappedFlowTests` — ponta a ponta (MockMvc + banco): sessão obrigatória, isolamento entre contas,
  histórico fora do ano, conteúdo removido, dados insuficientes, prioridade, idempotência, retomada.
