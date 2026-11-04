# Chamadas de áudio e vídeo (1:1 e "Tamo junto")

Chamadas dentro do Énóis, sem abrir aba nova nem sair para outro site: o painel da chamada é
embutido no próprio app (iframe do Jitsi dentro de um overlay da tela), com minimizar, microfone,
câmera e encerrar. A conversa da DM chama uma pessoa; o grupo chama **"Tamo junto"**.

A infraestrutura é **Jitsi próprio** (`docker-jitsi-meet`), com autenticação por token: o backend
assina um JWT HS256 e o Prosody só deixa entrar quem apresenta um token válido para aquela sala.
Nada de conta em serviço de terceiros, nada de sala pública.

## Como a sala é protegida

A segurança da chamada não vem do nome da sala. Ela vem de três coisas, todas conferidas no
servidor a cada pedido:

1. **Vínculo**: quem entra precisa ser participante da conversa ou membro do grupo *naquele
   momento* (lido do banco a cada verificação). Editar a URL ou o corpo do pedido não muda nada.
2. **Token assinado**: cada entrada recebe um JWT HS256 emitido pelo backend, válido só para aquela
   sala e por tempo limitado. O segredo de assinatura é o mesmo `JWT_APP_SECRET` dos containers e
   nunca sai do servidor — o navegador recebe o token pronto. O `room` vai dentro do token, então
   trocar a sala na URL invalida a credencial.
3. **Nome de sala aleatório**: `room_id` é um UUID gerado no servidor. O @ ou o nome de ninguém
   entra no identificador da sala; adivinhar o apelido de alguém não dá acesso a nada.

O Jitsi roda com `ENABLE_AUTH=1`, `ENABLE_GUESTS=0` e `AUTH_TYPE=jwt`: sem token válido não há
entrada, nem como convidado. Moderador é só quem iniciou a chamada. Gravação, transmissão ao vivo
e transcrição ficam **desligadas no próprio token** (`context.features`), então ninguém grava áudio
ou vídeo por padrão, nem por engano.

Sem `REDE_CALLS_JITSI_URL` e `REDE_CALLS_JITSI_JWT_SECRET` a API responde **503
`CALLS_NOT_CONFIGURED`** — ela nunca aponta o painel para uma instância aberta para "funcionar".

## Infra: Jitsi próprio

| | Jitsi próprio (escolhido) | JaaS (8x8) | meet.jit.si público |
|---|---|---|---|
| Infra para operar | JVB, Prosody, Jicofo, TLS, escala | Nenhuma | Nenhuma |
| Autenticação por token | Sim (`AUTH_TYPE=jwt`, HS256) | Sim (RS256 por app) | Não aceita JWT próprio |
| Custo | Servidor + banda de mídia | Plano por uso | Grátis, sem garantia |
| Dados da chamada | Só nos nossos servidores | Nos servidores do provedor | Em servidores de terceiros |
| Produção | Adequado | Adequado | **Não adequado** |

A stack fica em [`deploy/jitsi/`](../deploy/jitsi/README.md): o `docker-compose.yml` oficial do
`docker-jitsi-meet` com as variáveis de autenticação já fixadas, um `.env.example` com os valores
que precisam ser gerados e um `gen-secrets.sh` que produz o segredo compartilhado. O backend só
precisa de duas variáveis para falar com ela.

### Configuração do backend

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.calls.jitsi.url` | `REDE_CALLS_JITSI_URL` | vazio (chamadas desligadas) |
| `rede.calls.jitsi.jwt-secret` | `REDE_CALLS_JITSI_JWT_SECRET` | vazio (mínimo 32 bytes) |
| `rede.calls.jitsi.jwt-issuer` | `REDE_CALLS_JITSI_JWT_ISSUER` | `rede` |
| `rede.calls.jitsi.jwt-audience` | `REDE_CALLS_JITSI_JWT_AUDIENCE` | `meet.jitsi` |
| `rede.calls.jitsi.token-ttl-minutes` | `REDE_CALLS_JITSI_TOKEN_TTL_MINUTES` | `120` |
| `rede.rate.call-start-limit` | `REDE_CALL_START_LIMIT` | `20` por `REDE_CALL_START_WINDOW_MINUTES` (60) |

`REDE_CALLS_JITSI_URL` é a origem pública do Jitsi, com `https` e sem barra no fim (por exemplo
`https://chamadas.enois.ia.br`). O segredo é o mesmo `JWT_APP_SECRET` do `.env` da stack: entra por
variável de ambiente do deploy, nunca no repositório, em log ou em relatório.

Se `REDE_CALLS_JITSI_JWT_ISSUER` for diferente de `rede`, ajuste `JWT_ACCEPTED_ISSUERS` na stack
para o mesmo valor.

O `aud` é **obrigatório**: o plugin de token do Prosody recusa (`'aud' claim is missing`) quando a
lista de audiências aceitas está preenchida e o token não traz `aud`, e o navegador fica preso em
`passwordRequired` sem entrar na sala. Por isso o backend sempre emite `aud` e o padrão é
`meet.jitsi` — o `VirtualHost` interno dos containers, não a URL pública. Os dois lados têm que
dizer o mesmo: `REDE_CALLS_JITSI_JWT_AUDIENCE` no backend e `JWT_ACCEPTED_AUDIENCES` no `.env` da
stack. Se um dia mudar o `VirtualHost` do Prosody, mude nos dois.

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
| POST | `/calls/{id}/join` | Autoriza a entrada e devolve `host`, `room`, `jwt` e validade |
| POST | `/calls/{id}/leave` | Sair (grupo segue vivo) |
| POST | `/calls/{id}/end` | Encerrar para todos |
| POST | `/calls/{id}/fail` | A tela perdeu a mídia |

O stream `/conversations/stream` ganha o evento `call` com `{"callId","state","conversationId"?,`
`"groupId"?}`; o cliente busca o estado real na rota de chamadas, que autoriza de novo.

Erros são explícitos: `NOT_PARTICIPANT`, `NOT_GROUP_MEMBER`, `CALL_STATE_CONFLICT`,
`CALL_CANCELLED`, `CALL_DECLINED`, `CALL_MISSED`, `CALL_ENDED`, `NOT_CALL_INITIATOR`,
`NOT_A_DIRECT_CONVERSATION`, `CALLS_NOT_CONFIGURED`.

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
| `CallExpiryWorker` | Fecha o que venceu a cada 5s |
| `JwtMeetingTokenIssuer` | JWT HS256 do Jitsi próprio; o segredo só existe no servidor |
| `CallController` | Rotas `/calls/**` |
| `CallPersistenceAdapter` | Transições condicionais em `call_sessions`/`call_participants` (V240) |

## No app (front)

| Onde | O que faz |
|---|---|
| `CallsProvider` (raiz) | Escuta o evento `call` do stream, confere o estado pelas rotas e desenha o painel — overlay interno, minimizável, sem aba nova |
| `ConversationThread` | Ligações de áudio/vídeo e "entrar" na conversa 1:1 (só conversa de duas pessoas) |
| Grupo (`/grupos/{slug}`) | Botão "Tamo junto" e banner "🔴 O grupo está ao vivo!" com quantas pessoas estão na chamada |
| Notificação e push (`/call/{id}`) | A rota pede a entrada ao provider e devolve a pessoa para a conversa de origem |
| Toque | Sintetizado no navegador (Web Audio), só em 1:1, nunca em modo lite/BATATA |

Minimizar mantém o painel montado; reabrir pede uma credencial nova ao servidor em vez de reusar
o token. Fechar a aba durante a chamada avisa o servidor (`pagehide` → `POST /calls/{id}/leave`).

## Prova de que a mídia passa

Teste de unidade prova a regra, não a mídia. A prova de ponta a ponta usa a stack de
`deploy/jitsi/` rodando localmente e dois navegadores de verdade:

1. `CallMediaProbe` (em `src/test/java/com/socialcoisa/rede/calls/`) sobe o app apontado para a
   stack, cria duas contas, abre e atende uma chamada 1:1 pelos mesmos endpoints do app e grava
   `alex.json`/`bruno.json` com `host`, `room` e `jwt`. Só roda sob demanda:
   `./mvnw -o -Dtest=CallMediaProbe -Dcall.probe.url=<url pública> -Dcall.probe.secret=<JWT_APP_SECRET> -Dcall.probe.out=/tmp/call-probe test`
2. Dois Chrome headless com `--use-fake-device-for-media-stream` entram na mesma sala com os dois
   tokens e ficam conectados.
3. O que se confere: os dois são vistos na mesma sala com o nome certo, o `jvb` registra
   `ICE connected`, `DTLS handshake complete` e `SCTP connection is ready` para **cada** endpoint,
   e o contador de bytes do `jvb` cresce enquanto a chamada está aberta.
4. O controle negativo: a mesma sala **sem** token e **com** token assinado por outra chave é
   recusada (`token required` / entrada negada), sem ninguém entrar.

Foi assim que o `aud` obrigatório apareceu: sem ele o Prosody recusa com `'aud' claim is missing`
e o navegador fica em `passwordRequired`. O teste do backend agora assere o `aud` para isso não
voltar sem ninguém perceber.
