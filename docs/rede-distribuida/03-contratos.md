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

Resposta: estado do nó, config version e modos operacionais vigentes.

```json
{ "status": "ACTIVE", "configVersion": 7, "mode": "NORMAL", "serverTime": "2026-11-11T12:00:00Z" }
```

### `GET /network/nodes/me/config`
Devolve a configuração **assinada**. O nó confere a assinatura antes de aplicar.

```json
{
  "configVersion": 7,
  "profiles": ["NODE_MEDIA"],
  "limits": { "cpuPercent": 60, "memoryMb": 4096, "bandwidthMbps": 50 },
  "allowedTasks": ["MEDIA_TRANSCODE"],
  "issuedAt": "2026-11-11T11:00:00Z",
  "signature": "base64(ed25519(configVersion|profiles|limits|allowedTasks|issuedAt))"
}
```

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
