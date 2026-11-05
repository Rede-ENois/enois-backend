# Chamadas de áudio e vídeo (1:1 e "Tamo junto")

Chamadas dentro do Énóis, sem abrir aba nova nem sair para outro site: o painel da chamada é
embutido no próprio app (iframe do Epapo dentro de um overlay da tela), com minimizar, microfone,
câmera e encerrar. A conversa da DM chama uma pessoa; o grupo chama **"Tamo junto"**.

A infraestrutura é a **Epapo**, a videoconferência própria da rede (fork do Jitsi Meet). O token de
entrada **não é assinado aqui**: o backend pede ao *auth gateway* da Epapo, que só emite depois de
o Énóis confirmar quem entra em qual chamada. O Prosody da Epapo só deixa entrar quem apresenta um
token válido para aquela sala. Nada de conta em serviço de terceiros, nada de sala pública.

## Como a sala é protegida

A segurança da chamada não vem do nome da sala. Ela vem de quatro coisas, todas conferidas no
servidor a cada pedido:

1. **Vínculo**: quem entra precisa ser participante da conversa ou membro do grupo *naquele
   momento* (lido do banco a cada verificação). Editar a URL ou o corpo do pedido não muda nada.
2. **Autorização explícita ao gateway**: o backend chama `POST /auth/token` no gateway da Epapo com
   `{callId, userId}` e o segredo interno. O gateway pergunta ao Énóis (`POST
   /calls/{callId}/gateway/entry/{userId}`) se aquela pessoa pode entrar; só com um `200` do Énóis
   é que um JWT é assinado. Sem autorização não existe token — nem adivinhando o nome da sala.
3. **Token preso à sala**: o JWT HS256 vem do gateway, com `room` dentro. Trocar a sala na URL
   invalida a credencial. O segredo de assinatura (`JITSI_APP_SECRET`) nunca sai do servidor da
   Epapo.
4. **Nome de sala aleatório**: `room_id` é um UUID gerado aqui. O @ ou o nome de ninguém entra no
   identificador da sala; adivinhar o apelido de alguém não dá acesso a nada.

A Epapo roda com `ENABLE_AUTH=1`, `ENABLE_GUESTS=0`, `JWT_ALLOW_EMPTY=0` e `AUTH_TYPE=jwt`: sem
token válido não há entrada, nem como convidado. O token traz o nome e o papel vindos daqui: só
quem iniciou a chamada é moderador. Gravação, transmissão ao vivo, chamada externa e transcrição
ficam **desligadas no próprio token** (`context.features`), então ninguém grava áudio ou vídeo por
padrão, nem por engano.

Sem `REDE_CALLS_EPAPO_URL` **ou** sem o gateway configurado (`REDE_CALLS_EPAPO_GATEWAY_URL` +
`REDE_CALLS_EPAPO_INTERNAL_TOKEN`) a API responde **503 `CALLS_NOT_CONFIGURED`** — ela nunca aponta
o painel para uma instância aberta para "funcionar".

## Infra: Epapo (fork do Jitsi)

| | Jitsi próprio (escolhido) | JaaS (8x8) | meet.jit.si público |
|---|---|---|---|
| Infra para operar | A Epapo (JVB, Prosody, Jicofo, TLS, escala) | Nenhuma | Nenhuma |
| Autenticação por token | Sim (`AUTH_TYPE=jwt`, HS256) | Sim (RS256 por app) | Não aceita JWT próprio |
| Custo | Servidor + banda de mídia | Plano por uso | Grátis, sem garantia |
| Dados da chamada | Só nos nossos servidores | Nos servidores do provedor | Em servidores de terceiros |
| Produção | Adequado | Adequado | **Não adequado** |

A stack da Epapo fica no repositório dela ([`Rede-ENois/epapo`](https://github.com/Rede-ENois/epapo),
pasta `deploy/`): `docker-compose.yml` com web, auth-gateway, edge (TLS), prosody, jicofo e jvb, mais
um `.env.example` com os valores que precisam ser gerados. O contrato completo entre os dois
projetos está em `deploy/INTEGRACAO-ENOIS.md` da Epapo.

### Configuração do backend

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.calls.epapo.url` | `REDE_CALLS_EPAPO_URL` (legado: `REDE_CALLS_JITSI_URL`) | vazio (chamadas desligadas) |
| `rede.calls.epapo.gateway-url` | `REDE_CALLS_EPAPO_GATEWAY_URL` | vazio (chamadas desligadas) |
| `rede.calls.epapo.internal-token` | `REDE_CALLS_EPAPO_INTERNAL_TOKEN` | vazio |
| `rede.calls.epapo.timeout-millis` | `REDE_CALLS_EPAPO_TIMEOUT_MILLIS` | `5000` |
| `rede.calls.epapo.token-ttl-minutes` | `REDE_CALLS_TOKEN_TTL_MINUTES` | `120` |
| `rede.calls.gateway.authorize-token` | `REDE_CALLS_GATEWAY_AUTHORIZE_TOKEN` | vazio |
| `rede.rate.call-start-limit` | `REDE_CALL_START_LIMIT` | `20` por `REDE_CALL_START_WINDOW_MINUTES` (60) |

`REDE_CALLS_EPAPO_URL` é a origem pública da Epapo, com `https` e sem barra no fim (por exemplo
`https://meet.enois.ia.br`). O nome antigo `REDE_CALLS_JITSI_URL` continua sendo lido quando o novo
não está definido, então a ordem entre renomear a variável no deploy e subir o binário não importa.
A mesma origem é o endereço padrão das salas de mentoria. `REDE_CALLS_EPAPO_GATEWAY_URL` é o gateway na rede privada
(`http://auth-gateway:8090`), **nunca** a URL pública — o gateway não é superfície do navegador.

Três segredos, todos só entre servidores e todos por variável de ambiente do deploy (nunca no
repositório, em log ou em relatório):

- `REDE_CALLS_EPAPO_INTERNAL_TOKEN` = `EPAPO_INTERNAL_TOKEN` da Epapo (Énóis → gateway).
- `REDE_CALLS_GATEWAY_AUTHORIZE_TOKEN` = `ENOIS_AUTHORIZE_SECRET` da Epapo (gateway → Énóis).
- `JITSI_APP_SECRET` fica **só na Epapo**: quem assina o JWT é o gateway, este backend não tem mais
  essa chave.

O `aud` é **obrigatório**: o plugin de token do Prosody recusa (`'aud' claim is missing`) quando a
lista de audiências aceitas está preenchida e o token não traz `aud`, e o navegador fica preso em
`passwordRequired` sem entrar na sala. Quem emite é o gateway, com `aud=jitsi` e `iss=epapo`, que
precisam bater com `JWT_ACCEPTED_AUDIENCES` e `JWT_ACCEPTED_ISSUERS` do Prosody. O `sub` é o
domínio XMPP (`EPAPO_XMPP_DOMAIN`), não o usuário — o Prosody usa esse claim para casar a sala com
o domínio.

## Estados

| Estado | Significado | Quem sai dele |
|---|---|---|
| `RINGING` | 1:1 tocando para quem foi chamado | atender, recusar, cancelar, prazo de 40s |
| `CONNECTING` | Alguém vai entrar (1:1 atendido, ou grupo aberto) | primeira entrada → `ACTIVE` |
| `ACTIVE` | Tem gente na sala | encerrar, sair (grupo), falha, abandono (30 min sem sinal) |
| `DECLINED` | Quem foi chamado recusou | — |
| `MISSED` | Ninguém atendeu a tempo | — |
| `CANCELLED` | Quem ligou desistiu antes de atender | — |
| `ENDED` | Terminou normalmente | — |
| `FAILED` | Falha de conexão, ou grupo aberto que ninguém entrou em 5 min | — |

Transições são conferidas no domínio (`CallState.canMoveTo`) e gravadas com **compare-and-set**:
a linha só muda se ainda estiver no estado esperado. Dois pedidos simultâneos (atender e recusar,
ou dois "entrar") não deixam a chamada em dois estados nem contam a mesma pessoa duas vezes.

## Diferenças entre 1:1 e "Tamo junto"

| | 1:1 | "Tamo junto" (grupo) |
|---|---|---|
| Toca no aparelho | Sim (toque sintetizado no cliente) | **Nunca** |
| Aviso | `CALL_INCOMING` | `CALL_GROUP_LIVE`, uma vez por chamada ("🔴 O grupo esta ao vivo! Entre na chamada quando quiser.") |
| Sair da sala | Encerra a chamada | Só a pessoa sai; encerra quando a última sair |
| Prazo | 40s tocando | 5 min sem ninguém entrar |
| Repetir "começar" | Devolve a chamada já aberta | Devolve a chamada já aberta |

## Rotas

| Método | Rota | Descrição |
|---|---|---|
| POST | `/conversations/{id}/calls` | Começa (ou devolve) a chamada 1:1. Corpo opcional `{"kind":"AUDIO"\|"VIDEO"}` |
| POST | `/groups/{id}/calls` | Começa (ou devolve) o "Tamo junto" |
| GET | `/conversations/{id}/calls/open` | Chamada aberta da conversa (ou `null`) |
| GET | `/groups/{id}/calls/open` | Chamada aberta do grupo (ou `null`) |
| GET | `/calls/open` | Tudo que está aberto para a pessoa (voltar de outra tela) |
| GET | `/calls/{id}` | Estado da chamada |
| POST | `/calls/{id}/answer` | Atender (só 1:1). Idempotente |
| POST | `/calls/{id}/decline` | Recusar. Idempotente |
| POST | `/calls/{id}/cancel` | Quem iniciou desiste. Idempotente |
| POST | `/calls/{id}/join` | Autoriza a entrada e devolve `host`, `room`, `jwt` e validade (o `jwt` vem do gateway da Epapo) |
| POST | `/calls/{id}/leave` | Sair (grupo segue vivo) |
| POST | `/calls/{id}/end` | Encerrar para todos |
| POST | `/calls/{id}/fail` | A tela perdeu a mídia |
| GET | `/calls/{id}/reportable` | Quem entrou na sala e pode ser denunciado (sem quem denuncia) |
| POST | `/calls/{id}/reports` | Denúncia da chamada: `reportedUserId`, `reason`, `description`, `mediaId` |

### Denúncia de chamada

Quem está na sala denuncia outra pessoa que **entrou** nela. O servidor não confia na lista que o
cliente manda: `reportable` sai de `joined_at` (quem atendeu ou passou pelo gateway conta como
entrada) e o `POST` recusa denunciar quem não entrou (`NOT_IN_CALL`), denunciar a si mesmo
(`INVALID_REPORTED_USER`) e repetir a mesmo denúncia (`409 ALREADY_REPORTED` — uma por quem
denuncia × chamada × denunciado).

A denúncia é a mesma das outras (teto `report` por hora, aviso ao suporte, triagem por IA), com
alvo `CALL`. O contexto gravado em `call_reports` (V241) guarda a sala, o denunciado, quem estava
na sala no momento, a captura e o horário — é o que a moderação lê na revisão. A captura é uma
imagem enviada antes com propósito `REPORT_CAPTURE`: ela **não passa pela checagem de imagem
pública** (a prova mostra a própria violação) e **só é servida a quem modera** (sem sessão `401`,
sessão sem cargo `403`, `Cache-Control: private, no-store`).

A triagem por IA lê o relato, os participantes e a captura, e **nunca penaliza sozinha** uma
denúncia de chamada: penalidade e banimento sobre quem foi apontado são decisão humana no painel de
revisão (`/moderacao/{reportId}`), que mostra a captura.

O stream `/conversations/stream` ganha o evento `call` com `{"callId","state","conversationId"?,`
`"groupId"?}`; o cliente busca o estado real na rota de chamadas, que autoriza de novo.

Erros são explícitos: `NOT_PARTICIPANT`, `NOT_GROUP_MEMBER`, `CALL_STATE_CONFLICT`,
`CALL_CANCELLED`, `CALL_DECLINED`, `CALL_MISSED`, `CALL_ENDED`, `NOT_CALL_INITIATOR`,
`NOT_A_DIRECT_CONVERSATION`, `CALLS_NOT_CONFIGURED` e, na denúncia, `NOT_IN_CALL`,
`INVALID_REPORTED_USER`, `INVALID_REASON`, `DESCRIPTION_TOO_LONG`, `ALREADY_REPORTED`.

## Notificações

| Tipo | Quando | Destino |
|---|---|---|
| `CALL_INCOMING` | 1:1 começa | quem foi chamado |
| `CALL_MISSED` | 1:1 não atendida | quem foi chamado |
| `CALL_ENDED` | chamada encerrada/recusada | todos os outros |
| `CALL_GROUP_LIVE` | "Tamo junto" começa | membros do grupo (uma por chamada) |

Todas levam a `/call/{callId}`. No celular, o Web Push é *melhor esforço*: o push de uma chamada
chega pelo mesmo caminho das mensagens, e com o app fechado o sistema operacional pode atrasar ou
descartar a entrega — por isso o toque só existe com o app aberto, e a chamada perdida também gera
notificação na caixa.

## Arquivos

| Classe | Papel |
|---|---|
| `CallSession`, `CallState`, `CallParticipant`, `CallParticipantState` | Domínio e máquina de estados |
| `CallService` | Começar, atender, recusar, cancelar, entrar, sair, encerrar, falhar, expirar |
| `CallAudience` | Autorização pelo vínculo (conversa/grupo) |
| `CallRoomAuthorization` | Porta do gateway: achar a chamada pela sala e autorizar a entrada |
| `EpapoMeetingTokenIssuer` | Fala com o gateway da Epapo (`POST /auth/token`); este backend não assina JWT |
| `CallGatewayController` | Rotas internas do gateway (`/calls/rooms/{room}`, `/calls/{id}/gateway/entry/{userId}`) |
| `CallExpiryWorker` | Fecha o que venceu a cada 5s |
| `CallController` | Rotas `/calls/**` |
| `CallPersistenceAdapter` | Transições condicionais em `call_sessions`/`call_participants` (V240) |
| `CallReportService` / `CallReportController` | Quem pode ser denunciado e a denúncia da sala |
| `CallReportContext` / `CallReportPersistenceAdapter` | Contexto da denúncia em `call_reports` (V241) |

A rota `/calls/{id}/gateway/entry/{userId}` exige o header `X-Enois-Authorize` com
`REDE_CALLS_GATEWAY_AUTHORIZE_TOKEN` (comparação em tempo constante; sem segredo configurado, 503
`GATEWAY_NOT_CONFIGURED`). Ela **não é só leitura**: registra a entrada do participante e move a
chamada para `ACTIVE`, como o `join` faz. Por isso ela está na lista de escritas que dispensam
sessão de usuário — o chamador é o gateway, autenticado pelo segredo, não uma pessoa.

## No app (front)

| Onde | O que faz |
|---|---|
| `CallsProvider` (raiz) | Escuta o evento `call` do stream, confere o estado pelas rotas e desenha o painel — overlay interno, minimizável, sem aba nova |
| `ConversationThread` | Ligações de áudio/vídeo e "entrar" na conversa 1:1 (só conversa de duas pessoas) |
| Grupo (`/grupos/{slug}`) | Botão "Tamo junto" e banner "🔴 O grupo está ao vivo!" com quantas pessoas estão na chamada |
| Notificação e push (`/call/{id}`) | A rota pede a entrada ao provider e devolve a pessoa para a conversa de origem |
| Toque | Sintetizado no navegador (Web Audio), só em 1:1, nunca em modo lite/BATATA |
| "Denunciar chamada" (painel ao vivo) | Escolhe quem, motivo e relato; pede a captura do momento à sala e envia tudo junto |

A captura do momento é pedida **à própria sala**, por `postMessage`: o painel é uma iframe de outro
domínio, sem `allow-same-origin`, então o app não lê nada de dentro dela. O Epapo responde uma única
vez, com uma folha JPEG das telas visíveis (`enois:call-capture-request` →
`enois:call-capture-response`), e só para a origem registrada em `EPAPO_CAPTURE_ORIGIN`. Sem imagem
legível (só áudio, câmera desligada) a denúncia segue só com o relato, e a tela diz isso.

Minimizar mantém o painel montado; reabrir pede uma credencial nova ao servidor em vez de reusar
o token. Fechar a aba durante a chamada avisa o servidor (`pagehide` → `POST /calls/{id}/leave`).

## Prova de que a mídia passa

Teste de unidade prova a regra, não a mídia. A prova de ponta a ponta usa a stack da Epapo rodando
de verdade (Prosody, Jicofo e JVB) e dois navegadores:

1. O caminho servidor a servidor tem prova própria em Java, sem depender de infraestrutura:
   `CallGatewayBridgeTests` sobe um gateway falso (HTTP real, em `127.0.0.1`) e confere o contrato
   inteiro — pedido sem o segredo interno, usuário de fora, chamada encerrada, Énóis fora do ar,
   sala vinda do chamador ignorada, e que o corpo enviado ao gateway carrega só `{callId, userId}`
   (nunca a sala, o nome ou um segredo). `CallFlowTests` confere que o `join` devolve o `jwt` do
   gateway e que o pedido vai com `X-Epapo-Internal`.
2. A prova com infraestrutura real sobe a stack da Epapo localmente e usa o gateway de verdade:
   ele autoriza cada entrada contra o Énóis, assina o JWT, e dois Chrome headless com
   `--use-fake-device-for-media-stream` (cada um com um padrão de vídeo diferente) entram na sala
   pelo mesmo caminho do app.
3. O que se confere: os dois se veem na mesma sala com o nome que o Énóis devolveu, a imagem
   remota **difere** da câmera local (prova de que é o vídeo do outro, não um espelho), e os
   contadores de `RTCPeerConnection` mostram bytes de áudio e vídeo saindo e chegando, com quadros
   decodificados em 640x480. O `jvb` registra `ICE connected`, `DTLS handshake complete` e
   `SCTP connection is ready` para **cada** endpoint.
4. Controles negativos, todos com a stack real: pedido sem o segredo interno (`401`), usuário que
   não participa (`403`, sem token), chamada encerrada (`403`), Énóis fora do ar (`502`, sem token),
   token de outra sala (o Prosody recusa: "não está autorizado a entrar nesta chamada") e token
   assinado com outro segredo (não autentica em socket nenhum).

Foi assim que o `aud` obrigatório apareceu: sem ele o Prosody recusa com `'aud' claim is missing`
e o navegador fica em `passwordRequired`. O teste do backend agora assere o `aud` para isso não
voltar sem ninguém perceber.
