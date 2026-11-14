# Éroleplay — RFC técnico

Status: em construção (Fase 0/1)
Escopo: plataforma universal de RPG de mesa e RPG textual dentro do ecossistema Énóis.

## 1. Objetivo

O Éroleplay é um módulo do Énóis onde pessoas criam campanhas, mestres de jogo montam o
cenário, jogadores trazem uma ficha de personagem, o grupo se encontra em mesa virtual e
convoca a voz — sem que o produto precise conhecer, hard-coded ou copiar as regras de um
sistema de RPG específico.

### 1.1 Regra central (não negociável)

> O Éroleplay não pode depender de um sistema de RPG específico. O sistema escolhido é que
> determina como a experiência funciona.

Consequências práticas:

- **Dados são compartilháveis.** Personagem, campanha, mesa, mensagem e rolagem são entidades
  do Éroleplay e independem do sistema de regras.
- **Regras não são compartilháveis nem copiáveis.** Cada sistema tem um *System Adapter*
  próprio; o *System Registry* resolve o adaptador pelo identificador do sistema. Regras de
  sistema comercial (Tormenta 20, D&D 5e, Cyberpunk RED, Ordem Paranormal, BRB/ Savage Worlds)
  não são reproduzidas aqui.
- **Licenciamento.** O registro guarda apenas metadados públicos do sistema (identificador,
  nome, família, autoria, licença, link oficial) e regras *originais* escritas pela comunidade.
  Conteúdo protegido não é importado, copiado nem exibido.

## 2. Reconhecimento do ecossistema

Levantamento feito no repositório do back-end (`/home/mel/Documentos/rede`) e no front
(`/home/mel/Documentos/connect-grow`), na branch `qa`.

### 2.1 O que já existe e é reaproveitado

| Peça existente | Reaproveitamento no Éroleplay |
|---|---|
| Sessão por header + resolução de usuário (`currentUserResolver.resolve`) | Autorização de toda escrita, sempre no back-end |
| `ApiException(status, "CODIGO", "mensagem")` + `GlobalExceptionHandler` | Erros explícitos com código próprio |
| `IdempotencyApplicationService` / `Idempotency-Key` | Escritas repetíveis (rolagem, criação de mesa) |
| `NotificationService` + `NotificationCategory` + `NotificationModule` | Avisos de campanha, pedido de entrada, convite, transferência de mestria |
| Módulo `groups` | Padrão de isolamento estrutural por tabelas próprias + capability/permission switch |
| `MessageStreamHub` (SSE) | Presença e rolagem em tempo real nas salas de chat |
| Épapo (Jitsi fork) com `auth-gateway` | Voz da mesa, sem vídeo |
| Design system `@rede-enois/design-system` | Componentes de UI; nada de markup cru no front |

### 2.2 O que **não** existe (e portanto precisa ser construído)

- Não há WebSocket no back-end: só SSE em memória (`MessageStreamHub`). O realtime do
  Éroleplay nasce sobre SSE, sem introduzir um canal novo nesta fase.
- Não há multi-tenancy no Énóis. O isolamento do Éroleplay é **estrutural por módulo**
  (tabelas próprias + política de acesso), seguindo o módulo `groups`.
- Não há engine de RPG, registro de sistemas, motor de dados nem ficha de personagem.
- Não há chat de campanha, mesa virtual nem integração de voz por sala.

### 2.3 Limite do gateway de Épapo

O `auth-gateway` do Épapo aceita apenas `callId` e `sessionId` e assina o JWT do Embedded
Meeting. Portanto a mesa do Éroleplay **não** inventa uma sala de voz: ela referencia uma
sessão de chamada já existente (`voiceRoomId`) e o front embute a chamada com os mesmos
contratos já usados em `/chamadas`. Ampliar o Épapo para "sala permanente de campanha" é
decisão de infraestrutura separada, fora do escopo do módulo.

## 3. Arquitetura

O módulo segue a organização por pacote já usada no repositório:

```
com.socialcoisa.rede.eroleplay
├── domain                     # entidades e regras puras, sem Spring
├── application                # casos de uso (serviços)
│   └── port/out               # interfaces de saída (EroleplayRepository)
└── adapters
    ├── in/web                 # controllers REST
    └── out/persistence        # entidades JPA, repositórios Spring Data, adaptador
```

### 3.1 Módulos de domínio (independentes entre si)

| Módulo | Responsabilidade | Independência |
|---|---|---|
| **Campaigns** | Campanha, membros, papéis, pedidos de entrada, visibilidade, arquivamento | Base sobre a qual tudo se apoia |
| **Game Systems** | Registro de sistemas + `SystemAdapter` por sistema | Não conhece campanha nem ficha |
| **Character Sheets** | Modelo de ficha definido pelo mestre, preenchimento pelo jogador, validação do servidor, privacidade por campo | Só conversa com Campaigns via identificadores |
| **Dice Engine** | Rolagem no servidor, registro imutável, requisição de rolagem pelo mestre | Independente de sistema e campanha |
| **Chats** | Canais IC/OOC, privados, fixados, presença | Reaproveita o padrão de mensagens existente |
| **Virtual Tabletop** | Estado de mesa, turnos, tokens e posições | Só polvos do mundo e tokens |
| **World Builder** | Mundo, localizações, pistas, NPCs | Entidades próprias, referenciadas por campaigns |
| **Library** | Publicação/compartilhamento de campagne e fichas | Leitura pública com opt-in |

### 3.2 System Adapter e System Registry

O contrato de um adaptador (`SystemAdapter`) entrega, nesta fase:

- identidade do sistema (`id`, `label`) e a família do motor (`D20 / D100 / CUSTOM`);
- a **procedência** (`author`, `license`, `officialUrl`, `builtIn`), que é o que permite respeitar
  direitos: só conteúdo original ou autorizado é registrado, com autoria e licença à vista;
- os **atributos da ficha** que o sistema define, como descritores (`SheetAttribute`: chave,
  rótulo, tipo, obrigatoriedade e opções, quando é uma escolha).

O primeiro adaptador é o sistema D20 **original** do Éroleplay (`d20-generico`): seis atributos,
nível, pontos de vida, defesa, uma origem entre dez e um campo de história. Não é cópia de sistema
comercial — nomes de perícia, magia, classe e progressão de um sistema publicado ficam fora até
haver autorização de quem detém os direitos.

O registro (`GameSystemRegistry`) resolve o adaptador pelo identificador. Sistemas sem adaptador
registrado respondem com erro explícito (`GAME_SYSTEM_NOT_SUPPORTED`), nunca com um comportamento
genérico inventado — é o que mantém a regra central honesta. Um identificador ausente devolve
`GAME_SYSTEM_REQUIRED`. A **família** que a campanha grava vem do adaptador, não do pedido: se o
corpo informar uma família que não bate com a do sistema registrado, a criação é recusada
(`GAME_SYSTEM_FAMILY_MISMATCH`).

Os adapters entram por injeção do Spring (`EroleplaySystemsConfig`): registrar um sistema novo é
publicar um bean, não editar o registro. O catálogo é público em `GET /roleplay/systems`.

As peças mais pesadas do contrato — validar um valor de ficha e rolar um teste — entram com as
fases que precisam delas (3 e 4), sem inventar regra que ninguém pediu.

## 4. Modelo de domínio (Fase 1 entregue)

### 4.1 Campanha

`Campaign`: `id`, `slug`, `name`, `description`, `setting`, `gameSystem`, `systemFamily`,
`visibility` (PRIVATE / INVITE_ONLY / PUBLIC), `status` (ACTIVE / ARCHIVED / CLOSED),
`ownerId`, `memberCount`, `createdAt`, `updatedAt`.

- `slug` é gerado a partir do nome, com dobra de acento (NFD), reservado `mine`, único.
- `visibility` decide a entrada: `PUBLIC` entra direto, `INVITE_ONLY` cria pedido de entrada,
  `PRIVATE` recusa entrada alheia.
- `status` decide a visibilidade: `ARCHIVED`/`CLOSED` saem da descoberta e recusam entrada.

### 4.2 Papéis e capacidades

`CampaignRole` = GAME_MASTER | PLAYER.
`CampaignCapability` = VIEW_CAMPAIGN, MANAGE_CAMPAIGN, MANAGE_MEMBERS, TRANSFER_MASTERSHIP,
MANAGE_TABLES, POST_IN_CAMPAIGN, ROLL_DICE.

`CampaignPermissions.can(role, capability)` é a única fonte de verdade: GAME_MASTER pode tudo;
PLAYER pode ver a campanha, postar e rolar dados; visitante (papel nulo) não pode nada.

### 4.3 Membros, pedidos e transferências

- `CampaignMember` (identidade = par campanha+usuário), `CampaignMembership` (campanha + papel).
- `CampaignJoinRequest` com estado `PENDING / APPROVED / REJECTED / CANCELLED`. A decisão é
  atômica: só a primeira decisão efetiva muda o estado; a segunda devolve
  `REQUEST_ALREADY_DECIDED`.
- Só um GAME_MASTER por campanha. Um mestre não sai sem antes transferir a mestria
  (`MASTERSHIP_TRANSFER_REQUIRED`).

### 4.4 Mesas e lugares

- `GameTable`: mesa da campanha, com `voiceRoomId` opcional apontando para uma sessão de
  chamada do Épapo.
- `TableSeat`: identidade = par mesa+usuário; uma pessoa só pode ocupar um lugar por mesa.

## 5. Contratos de integração

### 5.1 Notificações

Kinds próprios (mapeados em `NotificationCategory` → área `GROUPS`):

| Kind | Quando |
|---|---|
| `ROLEPLAY_CAMPAIGN_MEMBER_JOINED` | Alguém entrou na campanha |
| `ROLEPLAY_CAMPAIGN_JOIN_REQUEST` | Pedido de entrada pendente |
| `ROLEPLAY_CAMPAIGN_INVITED` | Convite para campanha |
| `ROLEPLAY_CAMPAIGN_JOIN_APPROVED` | Pedido aprovado |
| `ROLEPLAY_CAMPAIGN_JOIN_REJECTED` | Pedido recusado |
| `ROLEPLAY_CAMPAIGN_MASTERSHIP_TRANSFERRED` | Mestria transferida |

Regras: o corpo nunca carrega conteúdo da campanha; o `href` aponta para
`/roleplay/campaigns/{slug}`; o autoexílio do destinatário é sempre aplicado.

### 5.2 Voz (Épapo)

`voiceRoomId` referencia uma sessão de chamada do Épapo. O front embute a chamada pelos
contratos existentes; nenhum endpoint de sala de voz novo é criado pelo Éroleplay nesta fase.

### 5.3 Superfície HTTP

Prefixo: `/roleplay/campaigns` (o gateway remove `/api`). Todas as escritas exigem sessão e
passam por `idempotency.execute(...)`.

| Método | Rota | Quem pode |
|---|---|---|
| GET | `/roleplay/campaigns` | público — só campanhas `PUBLIC` e `ACTIVE` |
| GET | `/roleplay/campaigns/mine` | membro |
| GET | `/roleplay/campaigns/{slugOuId}` | membro ou público, conforme visibilidade |
| POST | `/roleplay/campaigns` | autenticado (cria e vira mestre) |
| PATCH | `/roleplay/campaigns/{id}` | mestre |
| POST | `/roleplay/campaigns/{id}/join` | autenticado |
| POST | `/roleplay/campaigns/{id}/invite` | mestre |
| DELETE | `/roleplay/campaigns/{id}/members/me` | membro |
| GET | `/roleplay/campaigns/{id}/members` | membro |
| DELETE | `/roleplay/campaigns/{id}/members/{membroId}` | mestre |
| POST | `/roleplay/campaigns/{id}/members/{membroId}/transfer-master` | mestre |
| GET | `/roleplay/campaigns/{id}/requests` | mestre |
| POST | `/roleplay/campaigns/{id}/requests/{reqId}/approve` | mestre |
| POST | `/roleplay/campaigns/{id}/requests/{reqId}/reject` | mestre |
| POST | `/roleplay/campaigns/{id}/archive` | mestre |
| GET | `/roleplay/campaigns/{id}/tables` | membro |
| POST | `/roleplay/campaigns/{id}/tables` | mestre |
| GET | `/roleplay/campaigns/{id}/tables/{tableId}/seats` | membro |
| POST | `/roleplay/campaigns/{id}/tables/{tableId}/sit` | membro |
| DELETE | `/roleplay/campaigns/{id}/tables/{tableId}/sit` | membro |

## 6. Segurança e integridade

- **Autorização no back-end, sempre.** O `resolve` de sessão lança sem sessão (correto para
  escrita); a resolução tolerante é usada só em leitura pública.
- **IDOR e enumeração.** Listagem de membros, pedidos e mesas exige membership; a listagem
  pública só expõe campanhas `PUBLIC`/`ACTIVE`.
- **Privacidade de ficha (Fase 3).** A ficha do jogador é privada por padrão: o jogador não lê
  a ficha de outro, a visibilidade é por campo, e a validação do modelo é do servidor. Isso é
  aplicado na API, não apenas na interface.
- **Rolagem no servidor.** O cliente nunca decide resultado; o servidor sorteia e registra.
- **Anti-enumeração da plataforma.** Caminhos fora da superfície continuam respondendo 200 vazio.
- **Sem segredo.** Nenhum token, chave ou dado pessoal em código, log, teste ou commit.

## 7. Observabilidade

Reaproveita `system_events` e `AuditLogRepository`: criação/arquivamento de campanha, entrada,
convite, decisão de pedido e transferência de mestria são eventos auditáveis.

## 8. Faseamento

| Fase | Entrega | Estado |
|---|---|---|
| 0 | RFC, modelo de domínio, contratos de integração, roadmap | concluída |
| 1 | Campanhas, papéis, visibilidade, membros, pedidos, mesas e lugares | concluída |
| 2 | Registro de sistemas + primeiro `SystemAdapter` (D20 genérico) | concluída |
| 3 | Character Sheet Engine (modelo do mestre, preenchimento, privacidade por campo) | pendente |
| 4 | Dice Engine (servidor, registro imutável, rolagem solicitada pelo mestre) | pendente |
| 5 | Chat de campanha (IC/OOC, privado, fixado, presença) | pendente |
| 6 | Voz por mesa via Épapo + mesa virtual | pendente |
| 7 | World Builder | pendente |
| 8 | Biblioteca e compartilhamento | pendente |
| 9 | Front do Éroleplay (identidade visual própria, tema por campanha) | pendente |
| 10 | Observabilidade, endurecimento e revisão de segurança | pendente |

O roadmap vivo, com o estado e a próxima tarefa, está em `docs/EROLEPLAY-ROADMAP.md`.

## 9. Identidade visual

O Éroleplay tem identidade própria, distinta da interface do Énóis: clima escuro, vocabulário de
fantasia e tema por campanha. Nenhum componente de UI é reimplementado no front: a base de
componentes vem do design system e o que for específico do Éroleplay nasce **dentro** do design
system, com token, contraste AA e acessibilidade. O front ainda é uma fase própria (9).
