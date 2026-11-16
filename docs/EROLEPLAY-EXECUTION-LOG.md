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

## Rodada 4 — 2026-11-14 — Fase 4 (motor de dados)

**O que foi reutilizado (sem reinvenção):**

- A régua de visibilidade da Fase 3 (`SheetVisibility` / `SheetAudience`) governa também a rolagem —
  a mesa não ganhou uma segunda linguagem de privacidade.
- A matriz de capacidades da Fase 1 (`CampaignPermissions`) decide quem rola (`ROLL_DICE`, jogador e
  mestre) e quem pede (só mestre, conferido no serviço).
- `IdempotencyApplicationService` em toda escrita; `WriteRouteInterceptor` segue barrando escrita
  sem sessão (nenhuma rota de rolagem entrou na allowlist pública).
- Padrões de teste do módulo: `TestAuthHelper`, fixtures com prefixo próprio, H2 compartilhada.

**O que foi implementado:**

- `DiceExpression` (domínio): gramática `NdF` + constantes com sinal, interpretada NO SERVIDOR.
  Teto por grupo 100 dados, faces 1–1000, constante ±999.999, 8 grupos + 8 constantes, 60
  caracteres. Expressão só de constante não é rolagem. Erros com código próprio, nunca palpite.
- `DiceRoll` (domínio): o registro imutável. O resultado NUNCA vem do corpo — a única construção é
  o sorteio com a fonte injetada (`SecureRandom` em produção, fonte fixa no teste) ou a
  reconstituição do que foi gravado. Cada dado individual fica no registro, então a soma é sempre
  conferível. Fonte que devolve valor fora do intervalo vira erro (`DICE_SOURCE_BROKEN`), nunca
  registro.
- `RollRequest` / `RollRequestStatus` (domínio): o mestre pede, o jogador responde, PENDING →
  ANSWERED, uma única resposta (a segunda é 409 `ROLL_REQUEST_ALREADY_ANSWERED`). A resposta usa a
  expressão DO PEDIDO — o jogador pode fechar mais a visibilidade, nunca trocar o teste.
- `DiceService` (aplicação): rolar/pedir/responder/histórico/leitura, com o teto de visibilidade
  aplicado na gravação (`closestOf`) e o recorte aplicado na leitura (o público vem do papel na
  campanha e de quem rolou, nunca do corpo). IDOR barrado com papel conferido antes da busca e
  visibilidade depois dela.
- `DiceRollController` (API): `/roleplay/campaigns/{id}/rolls` — POST rolar, GET histórico, GET uma
  rolagem, POST/GET pedidos, GET um pedido, POST responder. Nenhuma rota pública.
- Notificação `ROLEPLAY_ROLL_REQUESTED` pelo mesmo `CampaignNotifier` da campanha: o jogador fica
  sabendo do pedido sem que o corpo da notificação leve expressão ou motivo (a caixa de
  notificações é lida fora da campanha; o teste do mestre fica dentro dela).
- Persistência V295 (`roleplay_dice_rolls`, `roleplay_roll_requests`): aditiva, `if not exists`,
  InnoDB, charset/collation explícitos, sem FK, dados individuais em JSON `longtext`. A porta não
  expõe atualização nem remoção — o registro é só-leitura até na assinatura da interface.

**Decisões de desenho:**

1. A visibilidade da rolagem reutiliza a régua da ficha; `PRIVATE` continua sendo a exceção do dono
   (nem o mestre lê).
2. O valor informado é TETO, não pedido: o que passa do teto é recuado, não rejeitado.
3. O histórico do jogador filtra em memória (as próprias + as `CAMPAIGN`); a rolagem fechada de
   outro nem entra na lista — sumir é a recusa, sem 403 por linha. A leitura por id recusa com 403.
4. Pedido responde uma vez e usa a expressão pedida; rótulo contraditório é 400
   `ROLL_LABEL_MISMATCH`.
5. Mestre pede rolagem a si mesmo é 400 (`ROLL_REQUEST_SELF`) — ele rola direto.
6. Estado do pedido deliberadamente sem cancelamento nem expiração: dois estados bastam e o que não
   existe não precisa de regra.
7. Sem letra nova de privacidade: quem lê rolagem é `SheetAudience`, calculado de papel + dono.
8. O porte do histórico é 100 por consulta; paginação fica para quando a mesa pedir.

**Testes (números reais):**

- `DiceExpressionTests` (20, domínio): interpretação, normalização, intervalo exaustivo, e todas as
  recusas de fronteira (vazia, longa, caractere fora da gramática, operador solto, constante
  sozinha, quantidade/faces/constante fora da faixa, mais de 8 termos, constante que não cabe em
  int, o menor long que escapa de `abs()`). **20/20 verdes na primeira execução.**
- `DiceRollTests` (10, domínio): cada dado guardado e a soma conferindo, fonte chamada dentro dos
  limites do dado (origem 1, limite exclusivo), fonte quebrada vira erro sem registro, construtor
  público inexistente (o total não se injeta), lista de dados imutável, reconstituição sem recálculo
  e a matriz dono/mestre/participante × três visibilidades. **10/10 verdes.**
- `RollRequestTests` (5, domínio): nasce pendente, responder marca com a rolagem, a segunda resposta
  é recusada, o pedido original não se altera ao responder, reconstituição do estado gravado.
  **5/5 verdes.**
- `DiceFlowTests` (11, ponta a ponta): rolar grava com cada dado e a soma; o histórico e a leitura
  por id devolvem o mesmo que foi sorteado; a rolagem fechada não aparece para outro jogador nem na
  lista nem por id (e o mestre também não lê a `PRIVATE`); pedido do mestre → resposta do jogador →
  `ANSWERED` com `answeredRollId`; segunda resposta 409; expressão do corpo ignorada (rola o pedido);
  rótulo contraditório 400; resposta não abre mais que o teto do pedido; mesma `Idempotency-Key` não
  rola duas vezes; listas por papel; expressão inválida recusada sem gravar.
- `DiceSecurityTests` (7, isolamento/integridade): todas as rotas 401 sem sessão; quem não participa
  não lê nem rola; jogador não pede rolagem (e mestre a si é 400); pedido não é respondido por
  terceiro (403 e o pedido continua `PENDING`); registro só-leitura na API (PUT/DELETE não têm rota —
  405 — e o registro sobrevive igual); resultado do corpo ignorado em 10 tentativas seguidas.
- Rodadas anteriores do módulo seguem verdes: `CharacterSheetTests` 20/20, `CampaignPermissionsTests`
  6/6, `GameSystemRegistryTests` 5/5, `CampaignFlowTests` 13/13, `CampaignSecurityTests` 4/4,
  `SheetFlowTests` 11/11, `SheetSecurityTests` 7/7, `AutomatedWritePathsTests` 2/2 (nenhuma rota de
  rolagem aceitou escrita sem sessão).

- **Suíte inteira executada depois da notificação integrada** (`./mvnw test`, 2026-11-14):
  **BUILD SUCCESS — 2733 testes, 0 falhas, 0 erros, 1 skip** (o skip é pré-existente, em
  `KofAgentLiveChannelTests`, e não é deste módulo). Todas as 13 classes do Éroleplay passaram
  dentro dela: `DiceExpressionTests` 20, `DiceRollTests` 10, `RollRequestTests` 5, `DiceFlowTests`
  11, `DiceSecurityTests` 7, `SheetFlowTests` 11, `SheetSecurityTests` 7, `CampaignFlowTests` 13,
  `CampaignSecurityTests` 4, `CampaignPermissionsTests` 6, `GameSystemRegistryTests` 5,
  `GameSystemEndpointTests` 1, `CharacterSheetTests` 20.

**Pendências:**

- Fases 5 a 10 (chat, voz/mesa virtual, world builder, biblioteca, front, endurecimento).
- A origem rastreável da frente ainda não foi registrada como issue no backlog.

## Rodada 5 — 2026-11-14 — Fase 5 (chat de campanha)

**O que foi reutilizado (sem reinvenção):**

- A matriz de capacidades da Fase 1 (`CampaignPermissions`) decide quem fala (`POST_IN_CAMPAIGN`)
  e quem lê (`VIEW_CAMPAIGN`); a `CampaignService` ganhou `requireCapability` para o resto do
  módulo perguntar pela régua sem reimplementá-la.
- O stream de mensagens (`MessageStreamHub`, SSE por usuário) agora também entrega o evento
  `campaign` — "o chat da campanha X mudou". O hub é a infraestrutura única de stream; o corpo só
  leva o id da campanha, o cliente recarrega pelo endpoint que autoriza de novo. Nenhuma mensagem
  viaja no stream.
- `IdempotencyApplicationService` em toda escrita; `WriteRouteInterceptor` segue barrando escrita
  sem sessão (nenhuma rota de chat entrou na allowlist pública).
- Padrões de teste do módulo: `TestAuthHelper`, fixtures com prefixo próprio, H2 compartilhada.

**O que foi implementado:**

- `ChatChannel` (domínio): IC (na voz do personagem) e OOC (coordenação da mesa). A diferença é
  semântica; o par (campanha, canal) é o endereço — não há tabela de canais para configurar.
- `CampaignMessage` (domínio): conteúdo aparado de 1 a 2000 caracteres, criado uma vez e sem
  reescrita; o único estado que muda depois é a FIXAÇÃO, decisão do mestre que não altera uma
  vírgula do que está escrito. O aparte privado nasce com destinatário (recusa destinatário igual
  ao autor). O recorte de leitura é regra de domínio: `visibleTo(readerId, readerIsMaster)`.
- `CampaignChatService` (aplicação): publicar, histórico do canal, fixados, leitura por id,
  fixar/desfixar e presença. Participação conferida em toda leitura; aparte visível só para
  remetente, destinatário e mestre; na listagem a mensagem alheia some, na leitura por id a
  recusa é 404 (não confirma existência); a fixação exige mestre (403).
- `CampaignChatController` (API): `/roleplay/campaigns/{id}/chat` — POST publicar, GET histórico
  (`?channel=IC|OOC`), GET `/pinned`, GET `/{messageId}`, POST `/{messageId}/pin`, POST
  `/{messageId}/unpin`, GET `/presence`. Nenhuma rota pública.
- Presença: `MessageStreamHub.hasOpenStream` cruzado com a lista de participantes — a fronteira da
  campanha é conferida ANTES do cruzamento com o hub; quem tem aba aberta é só visível a quem já
  passou pela autorização da campanha.
- Persistência V296 (`roleplay_campaign_messages`): aditiva, `if not exists`, InnoDB,
  charset/collation explícitos, sem FK; índices por (campanha, canal, criado em) e (campanha,
  fixado em). A mensagem é criada uma vez; só o par de fixação muda.

**Decisões de desenho:**

1. O canal é par de valores (`IC`/`OOC`), não entidade: configurar canal é trabalho de front
   disfarçado de back, e a mesa não pediu isso.
2. O aparte é tri-lateral (remetente, destinatário, mestre): o mestre é da mesa, e esconder cena
   dele romperia a função de quem arbitra o jogo. Não é ficha nem segredo de mestre — é conversa.
3. Recusa de leitura alheia é 404, não 403: existência não se confirma.
4. Fixação é idempotente nos dois sentidos (fixar o fixado e desfixar o solto devolvem o mesmo
   estado) e nunca reescreve conteúdo.
5. O evento `campaign` segue o padrão `CallChanged` do hub: publicado dentro da transação,
   entregue depois do commit, corpo só com ids.
6. O histórico é teto de 100 por consulta; fixados, teto de 20 — pin que vira segundo histórico
   perde o sentido.
7. Presença é leitura da campanha (não escrita) e devolve participante + papel + entrada — nunca
   duração de sessão nem endereço.
8. O hub virou público (classe e `hasOpenStream`) com a fronteira documentada: é o registro único
   de "quem está com a aba aberta" da plataforma.

**Testes (números reais):**

- `CampaignMessageTests` (15, domínio): criação válida, canal OOC, recusas (canal nulo, vazia/
  branca, acima do teto, aparte para si), teto exato com aparagem, recorte do aparte por leitor
  (pontas e mestre; terceiro de fora), fixação preservando conteúdo/autoria/criação, idempotência
  da fixação nos dois sentidos, reconstituição e tradução `wire` nos dois sentidos.
- `CampaignChatFlowTests` (8, ponta a ponta): publicar grava e o histórico devolve o mesmo;
  mesmo Idempotency-Key não publica duas vezes; canais separados; participante lê e quem não
  participa leva 403; aparte visível só para pontas e mestre (terceiro não vê na lista e leva 404
  por id); mestre fixa e jogador não (403); fixado aparece com conteúdo preservado; desfixar tira
  do fixado; fixação preserva conteúdo; presença exige participação.
- `CampaignChatSecurityTests` (7, isolamento/integridade): todas as 7 rotas 401 sem sessão; não
  participante 403 nas quatro leituras/escritas; mensagem alheia não confirma existência (404 por
  id, 403 no pin); aparte para não-participante recusado; id de mensagem de outra campanha não
  serve (404 na leitura e no pin cruzados); registro sem rota de edição nem remoção (405 e o
  conteúdo sobrevive); canal inválido e conteúdo vazio recusados sem gravar.
- **Escopo ampliado na árvore final** (`./mvnw -Dtest='...17 classes...' test`): **152 testes, 0
  falhas, 0 erros** — o módulo inteiro mais `AutomatedWritePathsTests` (2/2: nenhuma rota de chat
  aceitou escrita sem sessão).
- **Suíte inteira executada** (`./mvnw test`, 2026-11-14): **BUILD SUCCESS — 2767 testes, 0
  falhas, 0 erros, 1 skip** (o skip é pré-existente, em `KofAgentLiveChannelTests`, e não é deste
  módulo). Todas as 16 classes do Éroleplay passaram dentro dela, as 30 novas do chat inclusas.

---

## Rodada 6 — 2026-11-14 — Fase 6 (voz da mesa e estado vivo)

### O que já existia e foi reaproveitado

- `CallService` e o contrato de chamadas do Épapo (`/calls`, `join`, gateway de autorização): a
  voz da mesa NÃO inventa sala — é uma quarta vinculação de chamada (`table_id` em
  `call_sessions`), do mesmo jeito do encontro (V256) e do tópico (V250).
- `CallAudience` como fonte única de autorização de entrada: quem decide se entra é a porta
  `TableCallLookup` (implementada pelo lado do Éroleplay), não o controller.
- `MessageStreamHub` (SSE): o aviso "a mesa mudou" sai pelo mesmo evento `campaign` do chat
  (`CampaignChatChanged`); o estado nunca viaja no stream, o cliente recarrega por GET.
- `NotificationService` com aviso contado por chamada (`call_live:<callId>`) — não repete a cada
  clique; kind novo `CALL_TABLE_LIVE` mapeado em `NotificationCategory` (GROUPS), como exige o
  teste varredor.
- `IdempotencyApplicationService` em toda escrita; `TestAuthHelper` com data de nascimento já
  declarada (o age gate de chamadas passa sem setup); `FakeEpapoGateway` para o laço completo.

### O que foi implementado

Domínio: `TableState` (ordem de fala com teto 20 e sem repetição, vez que exige presença na ordem
quando a ordem existe, cena com teto 600; quem sai da ordem perde a vez) e `TableToken` (nome/
cor hex/posição em células −9999..9999; token de personagem move o dono, cenário só o mestre).

Aplicação: `TableStateService` (estado nasce vazio na primeira leitura; ordem/vez/cena do mestre;
jogador assume e limpa a própria vez; tokens com dono ou do cenário; `openVoice` por porta
`VoiceOpener` que devolve o id da chamada). `CallService.startTable`/`openOfTable` (chamada de
mesa: GROUP, avisa uma vez, entra quem participa da campanha).

Portas: `TableCallLookup` (calls → eroleplay) e `VoiceOpener` (eroleplay → calls) — a fronteira
fica nas duas mãos, sem dependência cíclica de módulo.

Persistência: `TableStateJpaEntity`/`TableTokenJpaEntity` + repositórios Spring Data +
`saveTable` agora devolve a mesa gravada.

API (`CampaignTableController`, tudo sob Idempotency-Key nas escritas): `GET /state`,
`POST /state/turn-order`, `POST /state/turn`, `POST /state/turn/clear`, `POST /state/scene`,
`GET /tokens`, `POST /tokens`, `POST /tokens/{id}/move`, `POST /tokens/{id}/remove`,
`POST /voice`, `GET /voice/open`.

Migração: `V297__eroleplay_table_state.sql` (aditiva, `if not exists`, InnoDB, charset/collation
explícitos, sem FK) — `call_sessions.table_id` + índice, `roleplay_table_states` (uma linha por
mesa; o id é o id da mesa) e `roleplay_table_tokens`.

### Correções de causa-raiz feitas na rodada

- `setTurn`/`clearTurn` exigiam mestre ANTES do ramo que permitiria o jogador assumir a própria
  vez — o ramo era inalcançável. Agora a checagem de mestre é só para passar/limpar a vez de
  OUTREM, e o teste de prova cobre o jogador assumindo sem ser mestre.
- `CALL_TABLE_LIVE` não estava mapeado em `NotificationCategory` (cairia no fallback SYSTEM e
  ignoraria a preferência de grupos da pessoa). Mapeado para GROUPS.
- Coluna `turn_order` nasceu como `json` nativo: no H2 o driver gravava o texto como literal de
  JSON e a leitura quebrava com `MismatchedInputException`. Virou `longtext` com JSON de
  aplicação — a mesma convenção do `dice_json` (V295). A migração ainda não tinha sido aplicada
  em ambiente nenhum (branch não empurrada), então a correção foi na migração nova.

### Testes (números reais)

- `TableStateTests` (5, domínio): estado nasce vazio com o id da mesa; ordem sem repetição e com
  teto; quem sai da ordem perde a vez; vez exige presença na ordem (409 `NOT_IN_TURN_ORDER`);
  cena com teto, aparada, vazia = nula.
- `TableTokenTests` (4, domínio): nome e cor obrigatórios com formato próprio (400
  `TOKEN_LABEL_REQUIRED`/`TOKEN_LABEL_TOO_LONG`/`TOKEN_COLOR_INVALID`); grid finito (400
  `TOKEN_POSITION_INVALID`); mover preserva identidade; regra de quem move (dono × mestre ×
  cenário).
- `TableStateFlowTests` (5, ponta a ponta): estado nasce vazio e persiste ordem/vez/cena; jogador
  assume a própria vez sem ser mestre e não entrega a vez de terceiro (403); mesmo Idempotency-Key
  não regrava; ciclo completo de tokens (criar cenário/personagem, mover, negado, remover).
- `TableSecurityTests` (5): sem sessão 401 nas três rotas; visitante 403 em leitura e escrita e a
  mesa segue intocada; jogador não faz trabalho de mestre (ordem/cena/token de cenário 403); IDOR
  — token da mesa A não é movido pela rota da mesa B (404 `TOKEN_NOT_FOUND`) e a lista de B não
  vaza; mesa inexistente 404 `TABLE_NOT_FOUND`.
- `TableVoiceFlowTests` (5, com gateway falso do Épapo): a chamada nasce com `tableId` e GROUP;
  quem abriu entra pelo `join` de sempre (token assinado); segunda abertura devolve a MESMA
  chamada; participante entra e visitante não (403) nem vê a sala; sala de uma mesa não vaza para
  a outra; aviso `CALL_TABLE_LIVE` chega ao participante e não ao estranho, e ninguém é chamado
  com `CALL_INCOMING`.
- Escopo ampliado na árvore final (6 classes novas + `NotificationCategoryTests` 3/3 +
  `AutomatedWritePathsTests` 2/2): **29 testes, 0 falhas, 0 erros** — nenhuma rota nova de mesa
  aceitou escrita sem sessão.
- **Suíte inteira executada** (`./mvnw test`, 2026-11-14, ~5h de relógio): **BUILD SUCCESS —
  2796 testes, 0 falhas, 0 erros, 1 skip** (o skip é pré-existente e não é deste módulo). Todas
  as classes do Éroleplay passaram dentro dela, as 24 novas da Fase 6 inclusas.

### Próxima tarefa

Fase 7 — World Builder (mundo, localizações, pistas, NPCs por campanha).

## Rodada 7 — 2026-11-15 — Fase 7 (World Builder)

### Estado encontrado

`git fetch` no início da rodada: a PR #721 (Fase 6) tinha sido MERGEADA e a `qa` agora TOPA no
commit dela. #704/#713/#716 também mergeadas; #702 (fundação) segue OPEN. Nenhum outro agente na
frente do Éroleplay. Branch nova `feat/eroleplay-world` cortada de `enois/qa`.

### O que já existia e foi reaproveitado

- A matriz de capacidades (`CampaignPermissions`/`CampaignService.requireCapability`) como única
  fonte de autorização — o World Builder não criou régua própria: criar é `POST_IN_CAMPAIGN`
  (participante), editar/apagar é autor-ou-mestre (`MANAGE_CAMPAIGN` para o mestre), ler é
  `VIEW_CAMPAIGN`.
- O fluxo de fichas da Fase 3 para a vinculação opcional (`findSheetById` nova na porta,
  reutilizando `CharacterSheetJpaEntity` e o `SpringDataCharacterSheetJpaRepository` existente).
- Convenções de API do módulo: `Idempotency-Key` em toda escrita, `ApiException` com códigos
  próprios, projeções `record` no controller, guard fail-closed de escrita (o módulo não tocou
  em `PUBLIC_WRITES`).

### O que foi implementado

- **Domínio**: `WorldKind` (5 naturezas) e `WorldEntry` — título 1–120, corpo até 20.000 (vazio =
  nulo), tags minúsculas sem repetição somando até 600, vinculação opcional de ficha, regra de
  edição autor-ou-mestre. O mundo é do GRUPO por decisão de produto: anotação de jogador é memória
  de mesa tanto quanto lore do mestre.
- **Persistência**: migração **V298** (`roleplay_world_entries` — aditiva, `if not exists`,
  InnoDB, `utf8mb4_uca1400_ai_ci` explícito, sem FK, índices por campanha+atualização, tipo e
  autor); entidade JPA, repositório Spring Data, porta `EroleplayRepository` estendida
  (save/find/list/remove) e adapter.
- **Aplicação**: `WorldService` — create/edit/remove/read/atlas. Busca por texto no serviço sobre
  o atlas recente (teto 100); vinculação de ficha conferida no servidor (ficha tem de ser da
  MESMA campanha; recusa igual para id inexistente e id alheia — não enumera).
- **API**: `CampaignWorldController` — 5 rotas sob `/roleplay/campaigns/{c}/world`
  (`POST`, `GET` com `kind`+`q`, `GET /{entryId}`, `PUT /{entryId}`, `DELETE /{entryId}`). O id da
  ficha vinculada NUNCA sai na resposta: sai `linkedSheetName` + `linkedToSheet`.

### Correções de causa-raiz no caminho (com prova)

- **Query JPQL morta derrubava o boot**: o primeiro rascunho do repositório Spring Data tinha um
  método `search` com `lower()`/`concat()` sobre a coluna de corpo (longtext → CLOB), que o
  Hibernate recusa na validação da query — e o serviço já filtrava em memória. Removido; o boot
  do contexto de teste voltou a subir (a falha era reproduzida por TODAS as classes
  `@SpringBootTest` da rodada, não só as novas).
- **Fixture de teste**: o teste de tags usava duas tags idênticas (o `distinct()` do domínio as
  colapsa antes do teto) — fixture reescrito com conteúdo distinto; e a criação de ficha do teste
  de fluxo foi refeita pelo contrato real (modelo com `forca`/`historia` abertos + `PUT
  /sheets/me` com os 11 campos do D20), com asserção de diagnóstico no `id` devolvido.

### Testes executados (números reais)

- `WorldEntryTests` (5): título obrigatório/teto; corpo teto e vazio→nulo; tags limpas/teto;
  edição preserva identidade/autoria/criação; regra de quem edita.
- `WorldFlowTests` (4): jogador registra e o nome da ficha sai SEM o id dela (dupla checagem no
  create e na re-leitura); atlas filtra por tipo e por texto; autor edita e apaga o que escreveu,
  mesmo Idempotency-Key não grava duas; entrada inválida (sem tipo, tipo inventado, sem título,
  vínculo de ficha falso) volta 400 SEM gravar nada.
- `WorldSecurityTests` (4): 401 sem sessão nas 4 operações; visitante 403 em leitura e escrita e
  o atlas segue intocado; jogador não edita nem apaga entrada alheia (e o mestre pode); IDOR —
  entrada da campanha A é 404 nas rotas da campanha B e segue intacta.
- Escopo: **13 testes, 0 falhas, 0 erros**. Vizinhança (mesa, chat, guarda de escrita): **27
  testes, 0 falhas, 0 erros** — total 40.
- Compilação `./mvnw -DskipTests compile` verde antes dos testes.
- **Suíte inteira nesta árvore** (após o escopo final): **3.071 testes em 583 classes**.
  Resultado: **3.056 verdes, 15 quebrados em exatamente 2 classes fora do módulo**
  (`NetworkReplicaTests` — 5 falhas; `NetworkMediaTests` — 10 erros). Classificação com prova
  executada: (a) rodando as duas classes SOZINHAS nesta branch, 23 de 24 passam — só
  `paginaDeComunidadesCustaOMesmoNumeroDeConsultasComUmaOuComQuatro` segue vermelha; (b) esse
  mesmo método isolado **também falha na base limpa `enois/qa`** (mesma asserção,
  `expected: 8 but was: 6`) — falha pré-existente da base, guard ambiental, não regressão desta
  PR; (c) as outras 14 são o padrão de interferência entre classes no H2 compartilhado já
  relatado na issue #682 e tratado na PR #727 de outro agente — escopo exclusivo de teste,
  fora do alcance desta rodada. As duas classes não tocam nenhum código do Éroleplay. Escoteiro:
  a vermelhidão está registrada na #682/#727 em vez de silenciada aqui.

### PR

`feat/eroleplay-world` → **PR #739** para `qa`, aberta sem merge, referenciando a origem
(roadmap/RFC §4.7) e descrevendo a verificação acima.

### Próxima tarefa

Fase 8 — biblioteca e compartilhamento (publicação de campanha/ficha com opt-in do autor,
leitura pública sem vazar dados de membro).

## Rodada 8 — 2026-11-15 — Fase 8 (biblioteca de personagens)

### Estado encontrado

Mesma branch `feat/eroleplay-world`, com a PR #739 aberta. Durante a rodada ANTERIOR o revisor
automatizado (`kof-agent-worker`) deixou `CHANGES_REQUESTED` na #739: o boot de produção quebrava
com `ddl-auto=validate` porque `WorldEntryJpaEntity` declarava a coluna de corpo como
`@Column(length = 2147483647)` enquanto a migração V298 cria `longtext`. Conserto de causa-raiz
feito nesta rodada (`@Column(columnDefinition = "longtext")`, a convenção do módulo), commit
próprio, e a prova: `RedeApplicationTests` passou de 3 erros para **3 testes, 0 falhas, 0 erros**,
com o escopo do World Builder intocado (13/0/0). O rebase em `enois/qa` foi refeito limpo
(a base da branch tinha ficado para trás das migrações V319–V324 — por isso a biblioteca é
**V325**, número conferido contra a ponta remota antes de criar — e renomeada para
**V326** na rodada seguinte: a `qa` recebeu uma V325 própria (observabilidade por nó) no
intervalo, e migração duplicada só aparece no merge).

### O que já existia e foi reaproveitado

- A matriz de capacidades e `CampaignService.requirePermission` (publicar exige participação;
  a leitura do catálogo é pública).
- O fluxo de fichas da Fase 3: `findSheetById`, `CharacterSheet.visibleTo(SheetAudience.MEMBER)`
  — o recorte público da biblioteca É o recorte que o dono já abriu à campanha, nenhuma régua
  nova de privacidade foi inventada.
- O sinal `campaign` (`CampaignChatChanged`) para os participantes verem a novidade no chat;
  `Idempotency-Key` nas duas escritas; `ApiException` com códigos próprios; projeção `record`.

### O que foi implementado

- **Domínio**: `LibrarySheet` — a CÓPIA pública de uma ficha (estampa do recorte MEMBER), com
  nome (teto 120), sistema, teto de 200 campos com valor até 2.000, e recusa de cópia vazia
  (`LIBRARY_EMPTY`). Quem publica é o DONO da ficha; mestre não publica ficha alheia.
- **Persistência**: migração **V326** (`roleplay_published_sheets` — aditiva, `if not exists`,
  InnoDB, `utf8mb4_uca1400_ai_ci` explícito, sem FK, unique por ficha — uma publicação por ficha,
  índices por publicação e dono); entidade JPA com `fields_json longtext`, repositório Spring
  Data com busca JPQL só sobre colunas curtas (nome e sistema — a coluna de JSON é CLOB e não
  participa de `lower()`), porta e adapter estendidos.
- **Aplicação**: `LibraryService` — publish (dono; ficha inexistente e ficha alheia dão o MESMO
  404), republish (mesma publicação: mesmo id, mesmo `publishedAt`), unpublish (apaga a CÓPIA,
  nunca a ficha), leitura e listagem públicas com busca e cursor.
- **API**: `LibraryController` — `GET /roleplay/library` (busca + cursor), `GET
  /roleplay/library/{publicationId}`, `POST`/`DELETE
  /roleplay/campaigns/{c}/sheets/{sheetId}/publish` com `Idempotency-Key`. A projeção pública
  carrega nome, sistema, campos e instantes — NUNCA o id da ficha, da campanha ou do dono.

### Testes executados (números reais)

- `LibrarySheetTests` (5): identidade/dados mínimos; nome e sistema obrigatórios e valores vazios
  descartados; tetos de nome, campos e valor; `LIBRARY_EMPTY` sem nada aberto; `restore`
  preserva e `fields()` é cópia defensiva.
- `LibraryFlowTests` (3): dono publica e a cópia sai com o recorte aberto SEM o campo privado e
  SEM vazar ficha/campanha/dono no corpo; republicar atualiza sem duplicar e despublicar apaga a
  cópia sem tocar na ficha; catálogo lista e busca por personagem, e ficha despublicada sai.
- `LibrarySecurityTests` (3): 401 sem sessão nas duas escritas com as duas leituras públicas;
  mestre não publica nem despublica ficha alheia, inexistente e alheia dão o mesmo 404; o campo
  privado do mestre nunca vira público nem após republicação.
- Escopo: **11 testes, 0 falhas, 0 erros**. Vizinhança (guarda de escrita automática, World
  Builder, fichas): **28 testes, 0 falhas, 0 erros**. Boot com schema validado contra as
  migrações: **3 testes, 0 falhas, 0 erros**.
- Compilação `./mvnw -DskipTests compile` verde antes dos testes.

### Docs e PR

RFC ganhou §4.8 e a fase 8 marcada como concluída; MODULES ganhou as classes da biblioteca e a
seção própria; API ganhou o capítulo da biblioteca com rotas, exemplo e códigos `LIBRARY_*`;
ROADMAP marca a fase 8 como feita e aponta a Fase 9 como próxima. Tudo no mesmo commit da
implementação. Push em `feat/eroleplay-world` carregado pela PR #739 — sem merge, como sempre.

### Próxima tarefa

Fase 9 — front: identidade visual própria do Éroleplay no design system e as telas
(campanhas, mesa, fichas, chat, mundo, biblioteca).

## Rodada 11 — 2026-11-15 — Fase 10 (trilha de auditoria dos atos de campanha)

### Estado encontrado

Fase 9 (front) segue com a PR #898 aberta e CI verde, com dois comentários de revisão do
revisor automatizado ainda sem resposta. Ninguém estava na Fase 10. A rodada começou da ponta
`enois/qa` (`160d9034`) em worktree e branch novos (`feat/eroleplay-auditoria`), sem colisão.

### O que já existia e foi reaproveitado

- **A trilha única do sistema**: `audit_logs` (V1, índice de alvo na V315) e a porta
  `AuditLogRepository` com o `AuditTrail` (`record`/`recentForTarget`) que `NetworkModeService`,
  `NetworkRegionService` e `StorageScrubber` já usam. O RFC (§7) pede atos auditáveis; a trilha
  já existia — não cabe trilha própria do módulo.
- O padrão de leitura administrativa do `AdminNetworkController` (`/{id}/history`): dupla trava
  (interceptador de `/admin/**` + `adminGuard.requireAdmin`), teto de lote, 404 para alvo
  desconhecido em vez de lista vazia.
- A matriz de capacidades da campanha e o `CampaignNotifier`, que já concentram quem pode cada
  ato — a auditoria entrou exatamente onde o ato acontece.

### O que foi implementado

- `CampaignAuditTrail`: o vocabulário `ROLEPLAY_*` dos onze atos (criação, edição, entrada,
  pedido de entrada, decisão, convite, saída, remoção, transferência de mestria, arquivamento,
  criação de mesa), cada um gravado na trilha única com a campanha como alvo e metadata JSON
  mínimo (ids, papéis, mudanças de estado). O conteúdo livre da campanha (descrição, ambientação)
  nunca entra; a criação traz nome/slug porque identificam a campanha no painel. Classe separada
  do serviço para o `CampaignService` não estourar o teto de 500 linhas (444 → 467).
- `AdminRoleplayController`: `GET /admin/roleplay/campaigns/{id}/history`, exclusivo de
  administrador (401 sem sessão, 403 sem admin, 404 para campanha desconhecida — não lista
  vazia), lote com padrão 50 e teto 200, resposta `{id, actorId, action, metadata, createdAt}`
  do mais novo para o mais antigo.
- `CampaignService.requireExists`: a confirmação de existência para a leitura administrativa,
  sem carregar a entidade no controller.
- **Nenhuma migração**: a trilha é a tabela existente; nada de schema novo.

### Testes executados (números reais)

- `CampaignAuditTests` (4): os atos ficam na trilha na ordem do mais novo para o mais antigo com
  teto de lote; o metadata nomeia os envolvidos sem levar a descrição nem a ambientação; convite,
  transferência e remoção gravam; o histórico é exclusivo de administrador (401/403/404).
- Módulo Éroleplay inteiro (`com.socialcoisa.rede.eroleplay.**`): **27 classes, 208 testes,
  0 falhas, 0 erros**.
- Suíte completa: **3.197 testes, 6 falhas e 10 erros — todos em módulos fora do diff**
  (mentoring e network/media). As três classes envolvidas passam isoladas na MESMA árvore
  (**26 testes, 0 falhas, 0 erros**); os erros de network/media são violação de FK no `setUp`
  por contaminação da base H2 compartilhada entre classes, e o CI da ponta `qa` (3d6905d8) está
  verde na mesma base — guarda ambiental local, não regressão desta mudança.
- Compilação `./mvnw -DskipTests compile` verde antes dos testes.

### Docs e PR

ROADMAP marca o primeiro item da Fase 10 como feito e aponta o que resta (métricas e revisão de
segurança por fase); MODULES ganhou `CampaignAuditTrail` e `AdminRoleplayController` na tabela do
§43 e a regra da trilha única; API ganhou o capítulo do histórico de auditoria. Tudo no mesmo
commit da implementação. Branch `feat/eroleplay-auditoria` com PR para `qa` — sem merge, como
sempre.

### Próxima tarefa

Fase 10 continua: métricas de eventos do módulo (observabilidade) e a revisão de segurança por
fase. Em paralelo, a Fase 9 (front) aguarda os comentários da revisão em #898.
