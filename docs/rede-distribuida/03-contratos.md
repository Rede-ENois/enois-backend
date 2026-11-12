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
  "formatVersion": 1,
  "configVersion": 3,
  "profiles": ["NODE_MEDIA"],
  "limits": { "cpuCores": 4, "memoryMb": 8192, "diskMb": 51200, "bandwidthMbps": 100 },
  "allowedTasks": ["MEDIA_TRANSCODE", "MEDIA_THUMBNAIL"],
  "region": "sudeste", "country": "BR",
  "issuedAt": "2026-11-12T10:00:00Z",
  "signed": true,
  "signature": "base64(ed25519(linha canônica))",
  "algorithm": "Ed25519",
  "publicKey": "base64(X.509 da chave pública do Control Plane)"
}
```

Duas versões, de propósito:

- `formatVersion` é a versão do **formato** do documento — sobe quando o runtime antigo não
  entenderia o payload novo. Hoje é `1`.
- `configVersion` é a versão da **permissão** do nó — nasce `1` no enroll e soma `1` a cada
  transição administrativa (aprovar, suspender, quarentenar, revogar). É o número que o runtime
  compara para saber que a permissão mudou.

`issuedAt` é o instante em que a permissão vigente passou a valer, **não** o instante da consulta:
duas leituras seguidas sem mudança devolvem o mesmo documento (e a mesma assinatura — Ed25519 é
determinística). A config é um evento, não um relógio.

A **linha canônica** assinada junta os campos na ordem do contrato:
`formatVersion|configVersion|nodeId|profiles|cpuCores|memoryMb|diskMb|bandwidthMbps|allowedTasks|issuedAt`,
com listas ordenadas em JSON compacto. Assina-se o canônico, não o JSON — reordenar chaves do corpo
não muda a assinatura.

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
