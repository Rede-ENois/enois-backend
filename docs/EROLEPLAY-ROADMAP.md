# Éroleplay — Roadmap

Estado geral: **concluída no backend** (fases 0–10 com prova executada; PRs abertas para `qa`
aguardando revisão humana e merge, que são decisão de mantenedor).
Documento vivo: cada rodada atualiza a seção de estado e a "próxima tarefa" antes de encerrar.

## 1. Como este roadmap é usado

O agendamento de 20 minutos lê este arquivo no início de cada rodada. A ordem de trabalho é:

1. `git fetch` e conferir o estado real (branch, PRs, commits recentes).
2. Ler este roadmap, o RFC (`docs/EROLEPLAY-RFC.md`) e o log (`docs/EROLEPLAY-EXECUTION-LOG.md`).
3. Escolher a **primeira tarefa pendente** da seção 4 que não esteja reivindicada.
4. Implementar a unidade inteira (domínio + aplicação + persistência + API + testes + docs).
5. Rodar os testes de verdade e registrar os números no log.
6. Commit, push e PR para `qa`. Nunca merge.

## 2. Princípio que não pode ser quebrado

O Éroleplay **não depende de um sistema de RPG específico**. Dados (campanha, ficha, mesa,
mensagem, rolagem) são compartilháveis; **regras não são** — cada sistema tem seu adaptador e o
registro resolve por identificador. Um sistema sem adaptador registrado responde com erro
explícito, nunca com comportamento genérico inventado.

## 3. Fases

| Fase | Entrega | Estado |
|---|---|---|
| 0 | RFC, modelo de domínio, contratos de integração, roadmap | concluída |
| 1 | Campanhas: papéis, visibilidade, membros, pedidos, mesas, lugares | concluída |
| 2 | Registro de sistemas + primeiro `SystemAdapter` | concluída |
| 3 | Character Sheet Engine (modelo do mestre, preenchimento, privacidade por campo) | concluída |
| 4 | Dice Engine (servidor, registro imutável, rolagem pedida pelo mestre) | concluída |
| 5 | Chat de campanha (IC/OOC, privado, fixado, presença) | concluída |
| 6 | Voz por mesa via Épapo + mesa virtual | concluída |
| 7 | World Builder | concluída |
| 8 | Biblioteca e compartilhamento | concluída |
| 9 | Front do Éroleplay (identidade visual própria, tema por campanha) | backend concluído; front em revisão (PR #898) |
| 10 | Observabilidade, endurecimento e revisão de segurança | concluída |

## 4. Fila de trabalho

### Fase 0 — fundação documental

- [x] Reconhecimento do ecossistema com fatos verificados no código
- [x] RFC técnico (`docs/EROLEPLAY-RFC.md`)
- [x] Roadmap e log de execução
- [x] Registrar a origem (issue) da frente no backlog (issue #701, "plataforma universal de RPG
  de mesa e RPG textual")

### Fase 1 — campanhas

- [x] Domínio: campanha, papel, visibilidade, estado, membro, pedido, mesa, lugar
- [x] `CampaignPermissions` como fonte única de decisão de acesso
- [x] Persistência (V293) e portas de saída
- [x] `CampaignService` com criação, edição, entrada, convite, saída, remoção,
      transferência de mestria, pedido/decisão, arquivamento, mesas e lugares
- [x] API REST `/roleplay/campaigns`
- [x] Notificações `ROLEPLAY_CAMPAIGN_*`
- [x] Testes: fluxo de campanha, matriz de permissões, segurança/autorização
- [x] Documentar a superfície em `docs/MODULES.md` e `docs/API.md`

### Fase 2 — registro de sistemas

- [x] `GameSystem` (identificador, nome, família, autoria, licença, link oficial, original da plataforma)
- [x] `GameSystemRegistry` com resolução por identificador e erro explícito para sistema sem adaptador
- [x] Contrato `SystemAdapter` (identidade, procedência, família e atributos da ficha)
- [x] Primeiro adaptador: família D20, regras originais (`d20-generico`)
- [x] API de consulta do registro, pública (`GET /roleplay/systems`)
- [x] Testes do registro (sistema desconhecido, resolução, fallback proibido, família divergente)

### Fase 3 — fichas de personagem

- [x] `SheetTemplate` definido pelo mestre (campos, tipos, obrigatoriedade, visibilidade)
- [x] `CharacterSheet` preenchida pelo jogador, com valores derivados das regras do sistema
- [x] Validação de preenchimento no servidor, contra o template
- [x] Privacidade: o jogador não lê a ficha de outro; visibilidade por campo; memberCount
      nunca revela ficha
- [x] Persistência (V294) + API + testes de IDOR/enumeração

### Fase 4 — motor de dados

- [x] Rolagem no servidor com fonte de aleatoriedade não previsível pelo cliente
- [x] Registro imutável da rolagem (quem, quando, o quê, resultado, contexto)
- [x] Rolagem solicitada pelo mestre, respondida pelo jogador, com estado
- [x] API + testes de integridade do registro

### Fase 5 — chat de campanha

- [x] Canais IC/OOC por campanha, privados e fixados
- [x] Presença via SSE reaproveitando `MessageStreamHub`
- [x] Persistência + API + testes

### Fase 6 — voz e mesa virtual

- [x] Referência da chamada do Épapo na mesa (`table_id` em `call_sessions`, espelho do V256; a
      mesa guarda o id da chamada — o room/token de entrada continua só pelo `join`)
- [x] Embutir a chamada com os contratos existentes de `/chamadas` (`POST
      /roleplay/campaigns/{c}/tables/{t}/voice` abre/devolve a chamada; entrada pelo `join` de
      sempre; público de voz = campanha; aviso `CALL_TABLE_LIVE` aos participantes)
- [x] Estado de mesa no back-end (`roleplay_table_states` + `roleplay_table_tokens`, V297): ordem
      de fala, vez (mestre passa a qualquer um; jogador assume a própria), cena visível e tokens
      (dono move o dele; cenário só o mestre)

### Fase 7 — World Builder

- [x] `WorldEntry` — o atlas da campanha: `LOCALE` (local), `CHARACTER` (NPC), `ITEM` (item),
      `PLOT` (pista/rumor) e `LORE` (história do mundo). O mundo é do GRUPO: qualquer participante
      registra, edita e apaga o que escreveu; o mestre pode em tudo; leitura é de quem participa
- [x] Vinculação opcional com ficha da MESMA campanha (o id não sai na API — sai o nome do
      personagem); persistência V298; API `/roleplay/campaigns/{c}/world` com atlas filtrável por
      tipo e busca por título/corpo/tags; escritas sob `Idempotency-Key`

### Fase 8 — biblioteca

- [x] Publicação de FICHA com opt-in do DONO: a cópia é instantâneo do recorte que ele já abriu
      à campanha — campo privado nunca entra na cópia; republicar atualiza, despublicar apaga a
      cópia sem tocar na ficha; persistência V326; escrita sob `Idempotency-Key`
- [x] Leitura pública (`/roleplay/library`) sem vazar dado de membro: id da ficha, da campanha e
      do dono NUNCA saem na projeção (a publicação tem id próprio); ficha alheia e inexistente
      respondem o mesmo 404; busca por nome do personagem e sistema; mudança publica o mesmo
      sinal `campaign` do chat/mesa

### Fase 9 — front

- [x] Identidade visual própria (tema por campanha), componentes no design system (PR #248,
  mergeada; release v0.37.0 contém os componentes de roleplay)
- [x] Telas de campanha, mesa, ficha, dados (PR #898 do front, mergeada: `/roleplay` e
  `/roleplay/$slug` com diretorio, mesas, ficha, dados, chat e mundo)

### Fase 10 — endurecimento

- [x] Trilha de auditoria dos atos de campanha na trilha única do sistema (`ROLEPLAY_*` em
  `audit_logs`) e leitura do histórico no painel (`GET /admin/roleplay/campaigns/{id}/history`,
  exclusiva de administrador, 404 para campanha desconhecida)
- [x] Observabilidade (métricas de eventos do módulo): `GET /admin/monitoring/roleplay` no painel
  de monitorabilidade, exclusivo de moderador — só agregado (campanhas por estado/visibilidade/
  sistema, volume de membros/mesas/fichas/chat/dados/mundo/biblioteca, ritmo de 7 e 30 dias),
  nunca conteúdo de campanha nem identidade de pessoa
- [x] Revisão de segurança por fase (varredura de toda a superfície da API do módulo: voz da mesa
  virou estado de mestre, a rota de voz cruza campanha e mesa, leitura de pedido de rolagem e
  privacidade de ficha provadas por teste)

## 5. Próxima tarefa

**Nenhuma pendência de backend.** As fases 0 a 10 estão concluídas no backend com prova executada
(código, testes verdes, docs e PRs para `qa` — aguardando revisão humana e merge, que é decisão de
mantenedor). O que segue é acompanhamento das PRs abertas e a validação de tela do front.

Mundo da campanha (localizações, pistas, NPCs) com referência a partir das campanhas, seguindo o
mesmo contrato das demais peças: aditivo, autorização na campanha (ver = participante, escrever =
mestre), SSE pelo mesmo evento `campaign`, e nada de conteúdo de sistema comercial.

## 6. Critérios de encerramento do agendamento

O agendamento só é desativado quando as fases 0 a 10 estiverem concluídas **com prova
executada**: código no repositório, testes verdes, docs atualizadas e PRs abertos. PR aguardando
revisão humana não impede a continuação nem o encerramento.

**Estado (2026-11-16): critérios satisfeitos.** Fases 0–10 concluídas no backend, com testes
executados (módulo Éroleplay 200/200; com chamadas 249/249) e docs atualizadas. As entregas estão
em PRs abertas para `qa`, aguardando revisão humana — o merge é decisão de mantenedor. O
agendamento pode ser desativado.
