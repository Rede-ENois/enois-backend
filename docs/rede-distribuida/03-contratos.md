# 03 — Contratos entre nós

Contratos são **versionados e assinados**. O nó recusa qualquer envelope cuja versão não suporte ou
cuja assinatura não confira. Contrato novo é adição; contrato existente não muda de significado.

## 1. Convenções gerais

| item | regra |
|---|---|
| transporte | HTTPS obrigatório; mTLS entre nó e Control Plane |
| versão | header `X-Network-Contract: 1` em toda requisição de nó |
| identificação | `X-Node-Id` + `X-Node-Key` (API key do nó) + certificado do cliente |
| anti-replay | `X-Node-Nonce` (único por requisição), `X-Node-Timestamp` (ISO-8601), janela de 120 s |
| correlação | `X-Request-Id` propagado; entra no log estruturado |
| erro | envelope padrão do app (`ErrorResponse`), com `code` estável |
| corpo | JSON UTF-8; `Content-Type: application/json` |
| idempotência | `Idempotency-Key` nas rotas de efeito (reusa o serviço existente) |
| rate limit | por nó, além do global; resposta 429 com `Retry-After` |

Uma requisição de nó **sem** `X-Node-Id` válido, chave válida e certificado válido é recusada com
401. Uma requisição com os três mas sem permissão para o perfil é recusada com 403. Nunca 200.

## 2. Ciclo de vida do nó

### `POST /network/nodes/enroll`
Sem `X-Session-Token`. A credencial é o **token de provisionamento de uso único**.

```json
{
  "provisionToken": "…",
  "publicKey": "base64(spki)",
  "runtimeVersion": "0.1.0",
  "declared": { "cpuCores": 4, "memoryMb": 8192, "diskMb": 51200, "bandwidthMbps": 100 },
  "location": { "region": "sa-east-1", "country": "BR" }
}
```

A região também é aceita solta no topo (`"region": "…", "country": "…"`); se as duas formas
vierem, a solta vence. A região declarada aqui é o que o escalonador usa como **afinidade** quando
uma tarefa declara origem — ver §3.

Resposta (única vez que a API key em claro aparece):

```json
{
  "nodeId": "nd_…",
  "apiKey": "enk_…",
  "certificatePem": "…",
  "caPem": "…",
  "contract": 1,
  "status": "PENDING_APPROVAL"
}
```

Erros: `NODE_TOKEN_INVALID` (400), `NODE_TOKEN_EXPIRED` (400), `NODE_TOKEN_USED` (409),
`NODE_KEY_REQUIRED` (400), `NETWORK_DISABLED` (503).

### `POST /network/nodes/heartbeat`
Autenticado. Envia capacidade **observada** e versão.

```json
{
  "runtimeVersion": "0.1.0",
  "observed": { "cpuPercent": 12.4, "memoryUsedMb": 2100, "diskUsedMb": 8000, "runningTasks": 2 },
  "health": { "status": "UP", "checks": { "disk": "UP", "network": "UP" } }
}
```

Resposta: estado do nó, perfis e modo operacional vigente.

```json
{
  "nodeId": "nd_1a2b3c",
  "status": "ACTIVE",
  "profiles": ["NODE_MEDIA"],
  "serverTime": "2026-11-11T12:00:00Z",
  "mode": "NORMAL",
  "acceptTasks": true
}
```

`acceptTasks` acompanha o `mode` porque o nó precisa do freio em booleano, não de interpretar a
tabela de contingência: falso em `DEGRADED`/`EMERGENCY`. O campo é aditivo — nó que o ignora
continua funcionando e ainda recebe tarefas do Control Plane, que já não designa nada nesses modos.

### `GET /network/nodes/me/config`
Devolve a configuração **assinada** (credencial de nó: `X-Node-Id` + `X-Node-Key`). O nó confere a
assinatura antes de aplicar. Implementada (fase 5): o payload real está abaixo.

```json
{
  "nodeId": "nd_…",
  "status": "ACTIVE",
  "operable": true,
  "formatVersion": 2,
  "configVersion": 3,
  "profiles": ["NODE_MEDIA"],
  "limits": { "cpuCores": 4, "memoryMb": 8192, "diskMb": 51200, "bandwidthMbps": 100 },
  "allowedTasks": ["MEDIA_TRANSCODE", "MEDIA_THUMBNAIL"],
  "region": "sudeste", "country": "BR",
  "issuedAt": "2026-11-12T10:00:00Z",
  "signed": true,
  "signature": "base64(ed25519(linha canônica))",
  "algorithm": "Ed25519",
  "publicKey": "base64(X.509 da chave pública do Control Plane)",
  "term": 4
}
```

Três números, de propósito:

- `formatVersion` é a versão do **formato** do documento — sobe quando o runtime antigo não
  entenderia o payload novo. Hoje é `2`: o formato 2 acrescentou `term` ao payload e à linha
  canônica (fase 6). Runtime de formato 1 não lê o termo; runtime de formato 2 reconstrói a linha
  com ele.
- `term` é o **termo da autoridade** que assinou (V288, doc 06 §4). Só sobe na promoção de Control
  Plane e o nó recusa config de termo menor que o último aceito — config de termo velho é uma
  autoridade deposta tentando voltar a mandar.
- `configVersion` é a versão da **permissão** do nó — nasce `1` no enroll e soma `1` a cada
  transição administrativa (aprovar, suspender, quarentenar, revogar). É o número que o runtime
  compara para saber que a permissão mudou.

`issuedAt` é o instante em que a permissão vigente passou a valer, **não** o instante da consulta:
duas leituras seguidas sem mudança devolvem o mesmo documento (e a mesma assinatura — Ed25519 é
determinística). A config é um evento, não um relógio.

A **linha canônica** assinada junta os campos na ordem do contrato:
`formatVersion|configVersion|nodeId|profiles|cpuCores|memoryMb|diskMb|bandwidthMbps|allowedTasks|issuedAt|term`,
com listas ordenadas em JSON compacto. Assina-se o canônico, não o JSON — reordenar chaves do corpo
não muda a assinatura. O `term` fecha a linha: o formato 1 não o tinha, e quem reconstrói a linha do
formato 2 sempre o acrescenta por último. É por estar dentro da assinatura que o nó consegue recusar
termo que regride conferindo contra o pino, em vez de confiar no transporte.

`publicKey` é o **pino**: o instalador grava esta chave no install e toda config seguinte é conferida
contra ela, sem depender do transporte desta resposta.

`signed` diz a verdade sobre `signature`: sem chave de assinatura configurada no Control Plane a
resposta sai `signed:false` e `signature:null` — nunca `true` com campo vazio. Quem decide aceitar
config não assinada é o runtime, e o padrão dele é recusar.

O nó **pendente de aprovação também lê a própria config** (200, com `operable:false` e
`allowedTasks` vazio): 403 aqui deixaria o instalador sem resposta justamente enquanto ele espera a
aprovação humana.

## 3. Tarefas

### `POST /network/tasks` (Control Plane → nó)
Despacho. O nó designado é o único que aceita.

```json
{
  "taskId": "tk_…",
  "type": "MEDIA_TRANSCODE",
  "attempt": 1,
  "deadline": "2026-11-11T12:05:00Z",
  "idempotencyKey": "tk_…",
  "payload": { "objectKey": "uploads/…", "mimeType": "video/mp4", "maxDurationSeconds": 180, "maxBytes": 10485760 },
  "dataClass": "PUBLIC_MEDIA"
}
```

Regras:
- `taskId` é único e estável entre tentativas; `attempt` sobe.
- `dataClass` diz o que o nó pode fazer com o payload. `PUBLIC_MEDIA` não contém dado pessoal.
- Tarefa com `dataClass` sensível **não sai** da infraestrutura autorizada.
- O nó recusa tarefa com `deadline` no passado (`TASK_EXPIRED`) e tarefa cujo `type` não está em
  `allowedTasks` (`TASK_NOT_ALLOWED`, 403).

### `POST /network/tasks/{taskId}/result`
```json
{
  "taskId": "tk_…",
  "attempt": 1,
  "status": "DONE",
  "output": { "objectKey": "uploads/…", "bytes": 4123456, "sha256": "…" },
  "startedAt": "…", "finishedAt": "…"
}
```

Status terminal: `DONE` ou `FAILED` (com `error.code`, `error.message`, `retryable`).
Resultado de tarefa cujo nó foi revogado no meio é **descartado** e registrado em auditoria.
Resultado de tarefa **drenada** (voltou para a fila porque o nó foi suspenso, posto em quarentena ou
revogado) cai no mesmo descarte: status `PENDING` ou `attempt` diferente.

### Drenagem (interno do Control Plane)

Não é contrato de nó — o nó que perdeu o direito de operar não é consultado. A varredura do
scheduler devolve à fila o que estiver designado a nó com `status <> 'ACTIVE'` e claim ainda
vigente, audita cada devolução como `NETWORK_TASK_DRAINED` e designa de novo no mesmo ciclo. O
painel (`GET /admin/network/tasks/overview`) expõe `awaitingDrain`: quantas do `dispatched` estão
presas a nó fora de operação entre a suspensão e a varredura seguinte.

### Região no escalonamento (ADR-012)

A ordem de escolha do nó é **degradação → origem (`NODE_LOCAL`, ADR-010) → região → folga →
jitter**. Região significa: quem enfileirou a tarefa pode declarar uma região de origem, e o nó
daquela região vem antes **dentro do mesmo grupo de saúde e de origem**.

Três propriedades que o contrato fixa:

- **É ordenação, nunca filtro.** Tarefa com região `sa-east-1` roda em nó de `us-east-1` quando não
  há `sa-east-1` vivo com vaga — a rede cooperativa existe para não ter ponto único regional.
- **Sem região no pedido, nada muda.** Toda tarefa sem `region` (e é o valor de todas as que
  existem antes desta fase) escala exatamente como antes.
- **Região compara por igualdade sem diferenciar maiúscula; nó sem região declarada empatA** no
  termo — não é punido por não ter declarado.

No painel, `GET /admin/network/tasks` devolve `region` por tarefa: é a única explicação visível de
por que uma tarefa foi para um nó distante.

## 3b. Tarefa com alvo (`targetNodeId`)

`POST /admin/network/nodes/{nodeId}/edge-cache` enfileira a aquisição de cache de borda **daquele**
nó; `GET` na mesma rota lista os pedidos em aberto. Resposta: `{"taskId","nodeId","status","attempt",
"deadline","createdAt"}`.

O alvo é o oposto da região: **filtro, nunca ordenação.** A tarefa só é designada ao nó alvo, e se
ele não está entre os candidatos (offline, suspenso, sem perfil, sem vaga) a tarefa permanece na
fila — mandá-la a outro nó não seria executar pior, seria concluir sem ter feito o trabalho, porque
o estado que ela continua (o cursor do cache) vive no disco daquele nó.

| código | status | quando |
|---|---|---|
| `NODE_UNKNOWN` | 404 | id não existe |
| `NODE_PROFILE_REQUIRED` | 422 | nó sem `NODE_EDGE` nem `NODE_LOCAL` |
| `NODE_NOT_OPERABLE` | 409 | nó suspenso, pendente ou revogado |

Idempotência por `edge:<nó>:<janela de 10 min>`: pedido repetido na mesma janela devolve a tarefa
existente. Tarefa sem alvo continua exatamente como antes: qualquer nó elegível.

### `EDGE_DELIVER` — entrega verificada

`POST /admin/network/nodes/{nodeId}/edge-deliver?kind=PROFILE&contentId=<id>` enfileira, **com
alvo**, a prova de que aquele nó entrega aquele conteúdo agora; `GET` na mesma rota lista os
pedidos de entrega em aberto. Resposta: `{"taskId","nodeId","kind","contentId","status","attempt",
"deadline","createdAt"}`.

Não é ouvinte HTTP: o nó não expõe porta e o modelo é pull, então a tarefa pede que o nó
**revalide** a entrada do cache contra `GET /network/content/{kind}/{id}?version=`, baixe o corpo
quando estiver velho, reconfirme em `/synced` e responda um veredicto:

```json
{"kind":"PROFILE","contentId":"ana","version":4,"contentHash":"…","deliverable":true,"source":"refetched"}
```

Negativo: `{"kind":…,"contentId":…,"deliverable":false,"reason":"CONTENT_GONE|EDGE_ORIGIN_UNREADABLE|EDGE_CONFIRM_FAILED|EDGE_DISK_FAILED"}`.
Payload da tarefa: só `{"kind":…,"contentId":…}` — campo que o executor não lê não é mandado.

O resultado não é aceito pela declaração do nó. O Control Plane compara o veredicto com o
snapshot que o **próprio nó** gravou em `/synced` (`network_content_snapshots`):

| status | quando | retenta |
|---|---|---|
| `EDGE_DELIVER_INVALID` | veredicto sem `kind`/`contentId`/`version`/`contentHash`/`deliverable` | sim |
| `EDGE_DELIVER_NOT_DELIVERED` | `deliverable=false` — concluir afirmando "não entreguei" seria contar entrega que não houve | não |
| `EDGE_DELIVER_UNCONFIRMED` | o nó nunca confirmou ter o conteúdo | não |
| `EDGE_DELIVER_MISMATCH` | versão ou hash diferentes do confirmado | sim |
| `EDGE_DELIVER_VERIFIER_UNAVAILABLE` | conferência ausente no processo | sim |

Erros do pedido: os mesmos `NODE_*` do cache, mais `400 CONTENT_ID_REQUIRED`, `400
CONTENT_KIND_UNKNOWN` (allowlist `PROFILE`/`COMMUNITY`) e o 404 da projeção quando o agregado não
existe (`USER_NOT_FOUND` para perfil). Idempotência por
`deliver:` + sha-256 de `<nó>:<tipo>:<id>:<janela de 5 min>`: concatenar estouraria a coluna de 80
caracteres (id de nó até 40, de conteúdo até 64), e o digest preserva a propriedade que importa —
mesma combinação na mesma janela é a mesma chave.

## 3a. Interface do participante (`/network/participant`)

Rotas autenticadas por **sessão de navegador** (`X-Session-Token`), não por chave de nó: quem fala
aqui é a pessoa dona do servidor, não o runtime. A fronteira é `owner_user_id` — nenhuma rota
devolve nó de outro participante, e nó alheio (mesmo com o id na mão) é `404 NODE_UNKNOWN`, não
403, para não confirmar existência. A chave de API **nunca** aparece aqui: ela é emitida pelo
admin, exibida uma única vez a ele.

### `POST /network/participant/join`

Solicitação de ingresso — abre o onboarding sem passar pelo painel do admin. Corpo:
`{"displayName":"vps-da-casa"}`. Resposta: `{"token", "expiresAt", "nextStep"}` — o token de
provisionamento de uso único (ADR-003) que o participante leva ao instalador (`deploy/node-runtime`).

O que a rota **não** aceita do corpo: dono (é sempre a conta da sessão) e perfis (o participante
declara o servidor; o que ele executa é negociado com o admin na aprovação — o nó nasce sem
perfil, e `allowTasks` vazio até o admin atribuir). Limite de **3 tokens vivos** por conta
(não usados e não vencidos): o quarto pedido é `429 NODE_JOIN_LIMIT`. Consumir ou deixar vencer
um token abre a vaga. Auditada como `NODE_JOIN_REQUESTED` com o participante como ator —
distinta do `NODE_PROVISION_TOKEN_ISSUED` do painel, para o histórico dizer quem pediu o quê.

### `GET /network/participant/nodes`

Lista de nós do participante (limite 200, mais novos primeiro), cada um com: `id`, `displayName`,
`status`, `profiles`, `region`, `country`, `runtimeVersion`, `lastSeenAt`, capacidade **declarada**
(`declaredCpuCores`, `declaredMemoryMb`, `declaredDiskMb`, `declaredBandwidthMbps`) ao lado da
**observada** (`observedCpuPercent`, `observedMemoryMb`, `observedRunningTasks`, `latencyMs`),
`tasksDone` (tarefas com desfecho `DONE` designadas ao nó — designada não é contribuição, é
promessa), `operable` e `quarantineReason`.

### `GET /network/participant/nodes/{id}`

Um nó, com o mesmo corpo; nó de outro dono é `404 NODE_UNKNOWN`.

### `POST /network/participant/nodes/{id}/disconnect`

Desconexão voluntária. O nó vai para `SUSPENDED` — **reversível pelo admin**: religar o servidor
do participante é decisão humana, não do runtime que voltou a ligar — e as chaves vigentes caem na
mesma transação, como na revogação. A ação é auditada como `NODE_DISCONNECTED` com
`{"voluntary":true}` e o ator é o próprio participante. Idempotente: nó já suspenso não reaudita
nem revoga de novo. Revogado é `409 NODE_REVOKED`. O participante **não** revoga sozinho;
revogação continua sendo decisão administrativa.

## 3c. Autoridade do Control Plane (`/admin/network/authority`)

Promoção controlada de Control Plane (fase 6, doc 06 §4). Todos os endpoints exigem sessão de
admin. O termo vigente vive na linha global de `network_state` (V288) e o eleitorado em
`network_authorities`.

```json
GET /admin/network/authority
{ "term": 4, "authorityId": "cp-1", "promotedAt": "2026-11-12T10:00:00Z", "declared": ["cp-1", "cp-2", "cp-3"] }
```

```json
POST /admin/network/authority/declared   { "instanceId": "cp-2" }
DELETE /admin/network/authority/declared/cp-2
POST /admin/network/authority/promote    { "instanceId": "cp-2", "reason": "cp-1 perdeu a região" }
```

A promoção só acontece quando, e só quando:

1. a instância pedinte está **declarada** no eleitorado (`network_authorities`, decisão humana);
2. ela **deu sinal** dentro da janela de vivacidade (`network_instance_readers.last_seen_at`,
   `rede.network.authority.liveness-seconds`, padrão 30 s);
3. a maioria do **eleitorado inteiro** está viva (`vivos * 2 > declarados`). Sem maioria, ninguém
   promove: melhor ficar sem controlador novo do que ter dois;
4. o compare-and-set `update ... where term = <lido>` ganha a corrida. Quem lê o mesmo termo e grava
   depois atualiza zero linhas e recebe `NETWORK_AUTHORITY_PROMOTION_LOST`.

Auditoria: `NETWORK_AUTHORITY_DECLARED`, `NETWORK_AUTHORITY_REVOKED` e
`NETWORK_AUTHORITY_PROMOTED` (metadata `{"term": N, "from": M, "reason": "…"}`).

O eleitorado é o **declarado**, nunca "quem está respondendo agora": derivar de quem responde faria
a metade isolada de uma partição se achar 100% dos vivos e promover a si mesma — exatamente o
split-brain que a fase 6 existe para impedir.

## 4. Catálogo de erros estável

| código | HTTP | significado |
|---|---|---|
| `NODE_UNKNOWN` | 401 | nó não existe |
| `NODE_KEY_INVALID` | 401 | chave não confere |
| `NODE_KEY_REVOKED` | 401 | chave revogada |
| `NODE_SUSPENDED` | 403 | nó suspenso |
| `NODE_QUARANTINED` | 403 | nó em quarentena |
| `NODE_REVOKED` | 403 | nó revogado |
| `NODE_PENDING_APPROVAL` | 403 | aprovado ainda não |
| `TASK_NOT_ALLOWED` | 403 | perfil não cobre o tipo |
| `TASK_EXPIRED` | 400 | deadline vencido |
| `TASK_ALREADY_CLAIMED` | 409 | outra tentativa já concluiu |
| `NETWORK_DISABLED` | 503 | rede desligada por config (fail-closed) |
| `NETWORK_MODE_NOT_MANUAL` | 400 | `NORMAL`/`HIGH_LOAD` não são declarados à mão |
| `NETWORK_MODE_REASON_REQUIRED` | 400 | transição manual sem motivo |
| `NETWORK_MODE_UNKNOWN` | 400 | modo inexistente em `POST /admin/network/mode` |
| `NODE_JOIN_LIMIT` | 429 | três tokens de ingresso vivos por conta |
| `NETWORK_AUTHORITY_INSTANCE_REQUIRED` | 400 | declaração de autoridade sem `instanceId` |
| `NETWORK_AUTHORITY_REASON_REQUIRED` | 400 | promoção sem motivo — promover muda quem manda na rede |
| `NETWORK_AUTHORITY_NOT_ALIVE` | 400 | instância pedinte não está declarada no eleitorado ou não deu sinal na janela |
| `NETWORK_AUTHORITY_NO_QUORUM` | 409 | maioria viva do eleitorado declarado não foi atingida: ninguém promove |
| `NETWORK_AUTHORITY_PROMOTION_LOST` | 409 | outra promoção gravou o termo primeiro (compare-and-set perdeu) |
| `NETWORK_AUTHORITY_NOT_DECLARED` | 404 | revogar instância que não está no eleitorado |

## 5. Versionamento

- Adicionar campo opcional: mesma versão.
- Remover campo, mudar tipo ou mudar significado: versão nova; a antiga continua aceita pelo menos
  um ciclo de atualização.
- Nó com versão não suportada recebe `NETWORK_DISABLED` com motivo, e aparece no painel como
  "versão incompatível".

## 6. Onde isto vive no código (Fase 1)

```
network/                        ← módulo novo, arquitetura hexagonal como os demais
├── domain/                     ← NetworkNode, NodeKey, NodeStatus, NodeProfile, NetworkMode
├── application/
│   ├── NetworkNodeService.java ← enroll, heartbeat, aprovação, transição de estado
│   ├── NodeKeyService.java     ← geração e hash da API key por nó
│   └── port/{in,out}/
└── adapters/
    ├── in/web/                 ← NodeEnrollmentController, NodeHeartbeatController,
    │                             AdminNetworkController
    └── out/persistence/        ← JPA espelhando os padrões de outbox/idempotency
```

Nada disso existe hoje — é a entrega da Fase 1, descrita em `07-plano-incremental.md`.
