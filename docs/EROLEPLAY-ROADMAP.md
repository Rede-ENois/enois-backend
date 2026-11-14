# Éroleplay — Roadmap

Estado geral: **em andamento**.
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
| 0 | RFC, modelo de domínio, contratos de integração, roadmap | em andamento |
| 1 | Campanhas: papéis, visibilidade, membros, pedidos, mesas, lugares | em andamento |
| 2 | Registro de sistemas + primeiro `SystemAdapter` | pendente |
| 3 | Character Sheet Engine (modelo do mestre, preenchimento, privacidade por campo) | pendente |
| 4 | Dice Engine (servidor, registro imutável, rolagem pedida pelo mestre) | pendente |
| 5 | Chat de campanha (IC/OOC, privado, fixado, presença) | pendente |
| 6 | Voz por mesa via Épapo + mesa virtual | pendente |
| 7 | World Builder | pendente |
| 8 | Biblioteca e compartilhamento | pendente |
| 9 | Front do Éroleplay (identidade visual própria, tema por campanha) | pendente |
| 10 | Observabilidade, endurecimento e revisão de segurança | pendente |

## 4. Fila de trabalho

### Fase 0 — fundação documental

- [x] Reconhecimento do ecossistema com fatos verificados no código
- [x] RFC técnico (`docs/EROLEPLAY-RFC.md`)
- [x] Roadmap e log de execução
- [ ] Registrar a origem (issue) da frente no backlog

### Fase 1 — campanhas

- [x] Domínio: campanha, papel, visibilidade, estado, membro, pedido, mesa, lugar
- [x] `CampaignPermissions` como fonte única de decisão de acesso
- [x] Persistência (V291) e portas de saída
- [x] `CampaignService` com criação, edição, entrada, convite, saída, remoção,
      transferência de mestria, pedido/decisão, arquivamento, mesas e lugares
- [x] API REST `/roleplay/campaigns`
- [x] Notificações `ROLEPLAY_CAMPAIGN_*`
- [x] Testes: fluxo de campanha, matriz de permissões, segurança/autorização
- [ ] Documentar a superfície em `docs/MODULES.md` e `docs/API.md`

### Fase 2 — registro de sistemas

- [x] `GameSystem` (identificador, nome, família, autoria, licença, link oficial, original da plataforma)
- [x] `GameSystemRegistry` com resolução por identificador e erro explícito para sistema sem adaptador
- [x] Contrato `SystemAdapter` (identidade, procedência, família e atributos da ficha)
- [x] Primeiro adaptador: família D20, regras originais (`d20-generico`)
- [x] API de consulta do registro, pública (`GET /roleplay/systems`)
- [x] Testes do registro (sistema desconhecido, resolução, fallback proibido, família divergente)

### Fase 3 — fichas de personagem

- [ ] `SheetTemplate` definido pelo mestre (campos, tipos, obrigatoriedade, visibilidade)
- [ ] `CharacterSheet` preenchida pelo jogador, com valores derivados das regras do sistema
- [ ] Validação de preenchimento no servidor, contra o template
- [ ] Privacidade: o jogador não lê a ficha de outro; visibilidade por campo; memberCount
      nunca revela ficha
- [ ] Persistência + API + testes de IDOR/enumeração

### Fase 4 — motor de dados

- [ ] Rolagem no servidor com fonte de aleatoriedade não previsível pelo cliente
- [ ] Registro imutável da rolagem (quem, quando, o quê, resultado, contexto)
- [ ] Rolagem solicitada pelo mestre, respondida pelo jogador, com estado
- [ ] API + testes de integridade do registro

### Fase 5 — chat de campanha

- [ ] Canais IC/OOC por campanha, privados e fixados
- [ ] Presença via SSE reaproveitando `MessageStreamHub`
- [ ] Persistência + API + testes

### Fase 6 — voz e mesa virtual

- [ ] Referência `voiceRoomId` para sessão de chamada do Épapo (sem inventar sala nova)
- [ ] Embutir a chamada com os contratos existentes de `/chamadas`
- [ ] Estado de mesa (turnos, tokens, posições) no back-end

### Fase 7 — World Builder

- [ ] Mundo, localizações, pistas, NPCs, com referência a partir das campanhas

### Fase 8 — biblioteca

- [ ] Publicação de campanha/ficha com opt-in do autor
- [ ] Leitura pública sem vazar dados de membro

### Fase 9 — front

- [ ] Identidade visual própria (tema por campanha), componentes no design system
- [ ] Telas de campanha, mesa, ficha, dados

### Fase 10 — endurecimento

- [ ] Observabilidade (eventos, métricas) e revisão de segurança por fase

## 5. Próxima tarefa

**Fase 3**: modelo de ficha definido pelo mestre.

Começo: `SheetTemplate` (campos, tipos, obrigatoriedade, visibilidade) montado a partir dos
atributos que o adapter do sistema declara; preenchimento pelo jogador com validação no servidor;
privacidade por campo (o jogador não lê a ficha de outro) bloqueada na API; persistência +
testes de IDOR/enumeração.

## 6. Critérios de encerramento do agendamento

O agendamento só é desativado quando as fases 0 a 10 estiverem concluídas **com prova
executada**: código no repositório, testes verdes, docs atualizadas e PRs abertos. PR aguardando
revisão humana não impede a continuação nem o encerramento.
