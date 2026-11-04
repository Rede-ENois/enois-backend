# Chamadas de áudio e vídeo (1:1 e "Tamo junto")

Chamadas dentro do Énóis, sem abrir aba nova nem sair para outro site: o painel da chamada é
embutido no próprio app (iframe do Jitsi dentro de um overlay da tela), com minimizar, microfone,
câmera e encerrar. A conversa da DM chama uma pessoa; o grupo chama **"Tamo junto"**.

## Como a sala é protegida

A segurança da chamada não vem do nome da sala. Ela vem de três coisas, todas conferidas no
servidor a cada pedido:

1. **Vínculo**: quem entra precisa ser participante da conversa ou membro do grupo *naquele
   momento* (lido do banco a cada verificação). Editar a URL ou o corpo do pedido não muda nada.
2. **Token assinado**: cada entrada recebe um JWT RS256 emitido pelo backend, válido só para aquela
   sala e por tempo limitado. A chave privada nunca sai do servidor — o navegador recebe o token
   pronto. O `room` vai dentro do token, então trocar a sala na URL invalida a credencial.
3. **Nome de sala aleatório**: `room_id` é um UUID gerado no servidor. O @ ou o nome de ninguém
   entra no identificador da sala; adivinhar o apelido de alguém não dá acesso a nada.

Moderador é só quem iniciou a chamada. Gravação, transmissão ao vivo, transcrição e chamada
externa ficam **desligadas no próprio token** (`context.features`), então ninguém grava áudio ou
vídeo por padrão, nem por engano.

Sem `REDE_CALLS_JAAS_APP_ID`, `REDE_CALLS_JAAS_KEY_ID` e `REDE_CALLS_JAAS_PRIVATE_KEY` a API
responde **503 `CALLS_NOT_CONFIGURED`** — ela nunca cai numa sala pública aberta para "funcionar".

## Infra: serviço gerenciado (JaaS) e o caminho para o Jitsi próprio

Escolhemos o **JaaS (Jitsi as a Service, da 8x8)** em vez de hospedar a infraestrutura.

| | JaaS (escolhido) | Jitsi self-hosted | meet.jit.si público |
|---|---|---|---|
| Infra para operar | Nenhuma | JVB, Prosody, Jicofo, TURN, TLS, escala | Nenhuma |
| Autenticação por token | Sim (RS256 por app) | Sim (`AUTH_TYPE=jwt` + plugin) | Não aceita JWT próprio |
| Custo | Plano por uso | Servidor + banda de mídia | Grátis, sem garantia |
| Produção | Adequado | Adequado depois de operar | **Não adequado** |

Incorporar o Jitsi (iframe) não é hospedar o Jitsi: a mídia trafega pelos servidores do JaaS. Se um
dia a gente migrar para infraestrutura própria, o caminho é o mesmo `MeetingTokenIssuer`
(`calls/application/port/out`) apontando para a instância `docker-jitsi-meet` com `AUTH_TYPE=jwt`,
`ENABLE_AUTH=1` e a chave pública do app no Prosody — o resto do módulo não muda.

### Configuração

| Propriedade | Env | Padrão |
|---|---|---|
| `rede.calls.jaas.app-id` | `REDE_CALLS_JAAS_APP_ID` | vazio (chamadas desligadas) |
| `rede.calls.jaas.key-id` | `REDE_CALLS_JAAS_KEY_ID` | vazio |
| `rede.calls.jaas.private-key` | `REDE_CALLS_JAAS_PRIVATE_KEY` | vazio (PEM PKCS#8) |
| `rede.calls.jaas.host` | `REDE_CALLS_JAAS_HOST` | `8x8.vc` |
| `rede.calls.jaas.token-ttl-minutes` | `REDE_CALLS_JAAS_TOKEN_TTL_MINUTES` | `120` |
| `rede.rate.call-start-limit` | `REDE_CALL_START_LIMIT` | `20` por `REDE_CALL_START_WINDOW_MINUTES` (60) |

A chave é segredo de assinatura: entra por variável de ambiente do deploy, nunca no repositório,
em log ou em relatório.

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
| POST | `/calls/{id}/join` | Autoriza a entrada e devolve host, sala, AppID, token e validade |
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
| `JaasMeetingTokenIssuer` | JWT RS256 do JaaS (chave só no servidor) |
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
