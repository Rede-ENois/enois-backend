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

Migração: `V293__eroleplay_campaigns.sql` (era V291; renumerada quando a `qa` andou) — cinco tabelas (`roleplay_campaigns`,
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

---

## Rodada 3 — 2026-11-14 — Fase 3 (fichas de personagem)

### O que já existia e foi reaproveitado

- `CampaignService.requirePermission(...)`, que já devolve o `CampaignRole` do solicitante — a
  ficha não precisou de nova checagem de participação.
- `CampaignCapability` (com `POST_IN_CAMPAIGN` para preencher e `MANAGE_SHEET_TEMPLATE` para o
  modelo) e a matriz de permissões, que ganhou a prova de que o jogador não administra o modelo.
- O `SystemAdapter` da Fase 2, cujos `SheetAttribute` já descrevem tipo, rótulo, obrigatoriedade e
  opções — o modelo de ficha nasce deles, sem inventar campo.
- `DerivedAttribute` e `D20System.derivedAttributes()`, que já declaravam os seis modificadores.
- O padrão de command com idempotência (`IdempotencyApplicationService`) usado por toda escrita.
- `ApiException` com código próprio e `TestAuthHelper` para a autenticação real nos testes.

### O que foi implementado (Fase 3)

**Domínio.** `SheetVisibility` (máximo que um campo pode abrir, por campo), `SheetAudience` (quem
está lendo: dono, mestre, outro participante), `SheetField` (campo do modelo, já com o máximo de
abertura), `SheetEntry` (valor preenchido + visibilidade efetiva), `SheetTemplate` (modelo da
campanha, nascido dos atributos do sistema) e `CharacterSheet` (a ficha que valida e normaliza
contra o modelo).

**Aplicação.** `SheetService`: define/ajusta/remove o modelo, preenche a ficha, monta o quadro do
grupo, entrega a leitura recortada por público e calcula os derivados. `defineTemplate` é
exclusivo do mestre e acontece uma vez; depois o que se ajusta é a abertura de cada campo.

**Persistência.** `V294__eroleplay_sheets.sql` (aditiva, `if not exists`, InnoDB, charset/collation
explícitos, sem FK); `SheetTemplateJpaEntity` e `CharacterSheetJpaEntity` com os valores em JSON
`longtext`; as duas Spring Data repositories; e os métodos correspondentes na porta
`EroleplayRepository` e no `EroleplayPersistenceAdapter`.

**API.** `CharacterSheetController` em `/roleplay/campaigns/{id}/sheets`: `template` (GET/POST/PUT/
DELETE), `me` (GET/PUT), `board`, a lista do mestre e a ficha de alguém por `userId`. Nenhuma rota
pública; toda escrita responde sob `Idempotency-Key`.

### Decisões

- **Duas réguas, e não uma.** `SheetVisibility` é o máximo que o **modelo** permite; `SheetAudience`
  é quem está **lendo**. Confundir as duas é o erro clássico: daria para concluir que "privado é o
  degrau mais fechado", quando na verdade é a exceção do dono — nem o mestre lê o segredo do
  jogador. Por isso a régua de público é um `switch` por caso, e não comparação de ordinal.
- **Filtro na resposta, não na tela.** `SheetReading` anda sempre junto com o público, então não
  existe caminho que devolva a ficha sem saber para quem. A gravação devolve a leitura do dono pelo
  mesmo caminho, e por isso não há como a escrita vazar um campo.
- **Ficha alheia não é recurso do jogador.** `read(...)` recusa jogador lendo jogador com `403` (e
  não `404`, porque o que se pediu é proibido, não inexistente). O que o grupo enxerga é o
  `board`, que sempre traz o nome do personagem — uma ficha inteiramente fechada não desaparece da
  lista, porque sumir revelaria que há segredo.
- **Derivado nunca é gravado.** É recalculado na leitura, e só aparece quando o público também
  enxerga o campo de origem. Derivado digitado à mão seria a ficha mentindo sobre o sistema.
- **Fechar um campo no modelo fecha o que já existe.** `updateTemplate` encolhe as fichas da
  campanha na mesma transação; sem isso, a regra nova só valeria na próxima gravação.
- **Troca de sistema com ficha preenchida é recusada** (`SHEET_SYSTEM_LOCKED`): ficha de D20 não
  vira ficha de D100 sozinha, e regravar valores noutro sistema seria inventar dado do jogador.
- **Remover o modelo exige que ninguém tenha preenchido** (`SHEET_TEMPLATE_IN_USE`), senão a ficha
  fica órfã de contrato de validação.

### Testes

- `CharacterSheetTests` (20, domínio): modelo nascido do sistema, fechamento de campo, matriz
  completa dono/mestre/participante × três visibilidades, campo fechado que não volta nem com a
  chave, jogador que não abre acima do máximo e que fecha mais, obrigatório ausente, opcional em
  branco como ausência, número inválido/fora do teto/normalizado, escolha sem diferenciar
  maiúsculas, caractere de controle em TEXT de linha única (e quebra legítima em LONGTEXT), valor
  acima do teto, campo desconhecido, campo duplicado, nome obrigatório, derivado calculado e não
  gravado, derivado de origem fechada invisível ao mestre.
- `SheetFlowTests` (11, ponta a ponta): modelo a partir do sistema com abertura escolhida pelo
  mestre; jogador preenche e lê a ficha inteira; recorte do mestre; privado do jogador que não
  volta nem ao mestre; quadro do grupo só com o aberto à campanha; lista do mestre; fechamento
  retroativo; modelo em uso que não sai; validação do servidor (obrigatório, desconhecido, número,
  escolha, nome); visibilidade acima do máximo recusada; derivado calculado e não regravado.
- `SheetSecurityTests` (7, isolamento): todas as rotas de ficha respondem 401 sem sessão; quem não
  participa não lê nem escreve; jogador não lê ficha de outro; jogador não administra o modelo;
  jogador não lista as fichas do grupo; modelo duplicado recusado e remoção bloqueada com ficha;
  identificador de pessoa de outra campanha não revela ficha (404 sem conteúdo).
- `CampaignPermissionsTests` ganhou a prova de que o jogador não tem `MANAGE_SHEET_TEMPLATE`.

Resultado real do escopo na primeira execução: **49 testes, 4 falhas** — três asserções minhas
escritas contra o desenho (dois cenários de fluxo deixavam de abrir `historia` no modelo, e o teste
de caractere de controle usava um campo LONGTEXT, que aceita quebra de linha por projeto) e uma
asserção de privacidade que esperava lista vazia do mestre quando o correto é ele perder só o campo
fechado. Todas corrigidas no teste, sem afrouxar a regra de produção.

### Pendências

- Fases 4 a 10 (motor de dados, chat, voz/mesa virtual, world builder, biblioteca, front,
  endurecimento).
- A origem rastreável da frente ainda não foi registrada como issue no backlog.
