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
