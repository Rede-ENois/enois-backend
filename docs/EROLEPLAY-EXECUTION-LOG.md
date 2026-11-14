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

---

## Rodada 2 — 2026-11-14 — Fase 2 (registro de sistemas)

### O que já existia e foi reaproveitado

- A criação/edição de campanha da Fase 1, onde o identificador do sistema entrava sem validação.
- `ApiException` para os erros com código próprio.
- O padrão de config de módulo (`EmotesConfig`) para publicar adapters como beans.

### O que foi implementado (Fase 2)

Domínio: `AttributeType` (NUMBER, TEXT, LONGTEXT, BOOLEAN, CHOICE), `SheetAttribute` (descritor
com chave, rótulo, tipo, obrigatoriedade e opções), `SystemAdapter` (contrato: identidade,
procedência e atributos) e `D20System` — o sistema D20 original do Éroleplay, com seis atributos,
nível, pontos de vida, defesa, uma origem entre dez e um campo de história. Nada de conteúdo
protegido de sistema comercial.

Aplicação: `GameSystemRegistry` resolve o adaptador pelo identificador. Sem adaptador registrado,
responde `GAME_SYSTEM_NOT_SUPPORTED`; identificador ausente, `GAME_SYSTEM_REQUIRED`.

Integração: `CampaignService` passa a exigir o sistema no registro e a **derivar a família do
adaptador** — o corpo não decide mais a família; divergência devolve `GAME_SYSTEM_FAMILY_MISMATCH`.

Adaptadores: `EroleplaySystemsConfig` publica o D20 como bean; `GameSystemController` expõe o
catálogo público em `/roleplay/systems`.

### Decisões

- **A família vem do sistema, não do cliente.** Antes o corpo podia declarar família D20 para um
  sistema D100; agora a família é consequência do sistema registrado.
- **O adaptador é pequeno de propósito.** Validar valor de ficha e rolar teste entram com as fases
  3 e 4 — declarar métodos que ninguém chama seria fachada.
- **A procedência é contrato.** `author`, `license`, `officialUrl` e `builtIn` ficam à vista no
  catálogo: é o que permite respeitar direitos autorais em vez de copiar conteúdo.

### Testes

- `GameSystemRegistryTests` (5): resolução, sistema desconhecido, identificador vazio, listagem,
  procedência.
- `GameSystemEndpointTests` (1): catálogo público, sistema original com atributos.
- `CampaignFlowTests` ganhou 3 testes: sistema não registrado, família divergente e família derivada.
- Resultado real do escopo: **33 testes, 0 falhas** (13 de fluxo, 5 do registro, 1 do catálogo, 6 de permissões, 4 de segurança, 2 de categoria e 2 de área de notificação). Compilação verde.

### Pendências

- Fase 3 (fichas com privacidade por campo), 4 (motor de dados), 5 a 10.
