# Éroleplay — Log de execução

Registro por rodada do agendamento de 20 minutos. Cada rodada anota o que existia, o que foi
implementado, os testes executados com números reais e a próxima tarefa.

---

## Rodada 1 — 2026-11-13 — Fundação (Fase 0) e Fase 1 (campanhas)

### O que já existia e foi reaproveitado

- Sessão por header (`CurrentUserResolver.resolve`) como base de toda autorização.
- `ApiException` + `GlobalExceptionHandler` para erros com código próprio.
- `IdempotencyApplicationService` nas escritas.
- `NotificationService` com `NotificationCategory`/`NotificationModule` fechados — os kinds novos
  entraram por mapeamento explícito, sem abrir a enum.
- Módulo `groups` como padrão de isolamento estrutural (tabelas próprias + matriz de
  capacidades) — o Éroleplay segue o mesmo desenho.
- `MessageStreamHub` (SSE) como único realtime disponível; o back-end não tem WebSocket.
- Épapo com `auth-gateway` aceitando apenas `callId`/`sessionId`: a mesa referencia uma
  sessão de chamada existente, não inventa sala nova.

### O que foi implementado (Fase 1)

Domínio (`com.socialcoisa.rede.eroleplay.domain`): `Campaign`, `CampaignRole`,
`CampaignVisibility`, `CampaignStatus`, `CampaignCapability`, `CampaignPermissions`,
`CampaignMember`, `CampaignMembership`, `CampaignJoinRequest`,
`CampaignJoinRequestStatus`, `GameTable`, `TableSeat`, `CampaignSlug`, `GameSystemFamily`,
`JoinOutcome`.

Aplicação: `CampaignService` (criar, editar, resolver, listar, listar as minhas, papel, membros,
entrar, convidar, sair, remover membro, transferir mestria, pedidos, decidir pedido, arquivar,
criar mesa, listar mesas, sentar, sair da mesa, listar lugares) e `CampaignNotifier`.

Portas: `EroleplayRepository`.

Persistência: `CampaignJpaEntity`, `CampaignMemberJpaEntity`, `CampaignJoinRequestJpaEntity`,
`GameTableJpaEntity`, `TableSeatJpaEntity`, os repositórios Spring Data e
`EroleplayPersistenceAdapter`.

API: `CampaignController` em `/roleplay/campaigns` — listagem pública paginada, "minhas",
detalhe por slug ou id, criação, edição, entrada, convite, saída, membros, remoção,
transferência de mestria, pedidos, aprovação/recusa, arquivamento, mesas e lugares.

Notificações: seis kinds `ROLEPLAY_CAMPAIGN_*` mapeados para a área `GROUPS`, e o prefixo
`ROLEPLAY_` mapeado no `NotificationModule` para que nenhum item caia na área padrão.

Migração: `V291__eroleplay_campaigns.sql` — cinco tabelas (`roleplay_campaigns`,
`roleplay_campaign_members`, `roleplay_join_requests`, `roleplay_tables`,
`roleplay_table_seats`), InnoDB, `utf8mb4_uca1400_ai_ci`, `if not exists`, sem chaves
estrangeiras (o collation de produção impede FK com a collation padrão).

Documentação: `docs/EROLEPLAY-RFC.md`, `docs/EROLEPLAY-ROADMAP.md`, este log, e as seções de
Éroleplay em `docs/API.md` e `docs/MODULES.md`.

### Decisões

- **A regra central ficou explícita no código:** `GameSystemFamily.of(...)` lança em valor
  desconhecido em vez de assumir uma família; a campanha não nasce com sistema genérico.
- **A descoberta só expõe `PUBLIC` e `ACTIVE`**, na própria consulta JPQL, e não no filtro da
  aplicação.
- **A decisão de pedido é atômica** (`update ... where status = PENDING`): a segunda decisão
  devolve `REQUEST_ALREADY_DECIDED`.
- **Só existe um mestre.** Um mestre que sai precisa antes transferir a mestria
  (`MASTERSHIP_TRANSFER_REQUIRED`).
- **A leitura pública devolve só a apresentação** da campanha: sem membros, sem mesas, sem
  conteúdo interno. Quem não participa vê `joined=false` e `viewerRole=null`.

### Testes

`CampaignFlowTests`, `CampaignPermissionsTests` e `CampaignSecurityTests` (resultados reais
registrados na rodada em que a suíte rodou por completo — ver a rodada 2).

### Pendências

- Fases 2 a 10 (registro de sistemas, fichas, dados, chat, voz, world builder, biblioteca,
  front, endurecimento).
- A origem rastreável da frente ainda não foi registrada como issue no backlog.
