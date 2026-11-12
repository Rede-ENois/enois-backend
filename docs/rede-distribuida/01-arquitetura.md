# 01 — Arquitetura da rede

## 1. Três camadas

```mermaid
flowchart TB
  subgraph CP["Control Plane — autoridade"]
    REG["Registro de nós<br/>identidade + API key"]
    AUTH["Autorização<br/>perfis e permissões"]
    CERT["Gestão de certificados<br/>emissão, rotação, revogação"]
    CFG["Configuração assinada"]
    DISC["Descoberta e saúde<br/>heartbeat"]
    CAP["Controle de capacidade"]
    SCHED["Scheduler<br/>onde cada tarefa roda"]
    VER["Gestão de versões"]
    AUD["Histórico e auditoria"]
  end

  subgraph DP["Data Plane — execução"]
    EDGE["NODE_EDGE<br/>tráfego, cache, entrega"]
    WORK["NODE_WORKER<br/>tarefas assíncronas"]
    MEDIA["NODE_MEDIA<br/>mídia e transcode"]
    REPL["NODE_READ_REPLICA<br/>leitura autorizada"]
    API["NODE_API<br/>instâncias auxiliares"]
    LOCAL["NODE_LOCAL<br/>infra do Énóis"]
    EMERG["NODE_EMERGENCY<br/>contingência"]
  end

  subgraph CORE["Infraestrutura principal — autoridade transacional"]
    DB[("MariaDB 11.4")]
    OUTBOX["Outbox + idempotência"]
    S3[("S3")]
  end

  REG --> AUTH --> CERT --> CFG
  CFG -->|config assinada| EDGE & WORK & MEDIA & REPL & API & LOCAL & EMERG
  DISC <-->|heartbeat| EDGE & WORK & MEDIA & REPL & API & LOCAL & EMERG
  CAP --> SCHED
  SCHED -->|tarefa| WORK & MEDIA & EDGE
  WORK -->|resultado + recibo| OUTBOX
  MEDIA -->|objeto| S3
  EDGE -->|cache| S3
  REPL -.->|réplica de leitura| DB
  AUD --- REG
```

**Regra dura:** a seta de escrita só existe de `Data Plane` para `Outbox`/`S3`. Nenhum nó externo
abre conexão de escrita no MariaDB principal. `NODE_READ_REPLICA` é a única exceção e é **somente
leitura**, com usuário próprio e escopo de tabelas.

## 2. Control Plane

Autoridade sobre identidade e permissão. Um nó auxiliar **jamais** modifica as próprias permissões,
declara-se confiável ou se promove a controlador.

| responsabilidade | onde vive na Fase 1 |
|---|---|
| registro de nós | tabela `network_nodes` (nova) |
| API key por nó | tabela `network_node_keys` (nova), só hash |
| autorização e perfis | `network_nodes.profiles` + `AdminGuard` |
| certificados | emissão no onboarding, rotação por job |
| configuração assinada | endpoint `GET /network/nodes/me/config` |
| descoberta e saúde | heartbeat + `last_seen_at` |
| capacidade | declarada no cadastro, observada no heartbeat |
| scheduler | Fase 2 |
| versões | `runtime_version` no heartbeat |
| revogação | `revoked_at` + negação imediata |
| auditoria | `audit_logs` existente |

## 3. Data Plane

Executa. Cada perfil é um contrato de permissão, não um binário diferente.

| perfil | faz | nunca faz |
|---|---|---|
| `NODE_EDGE` | cache, entrega de conteúdo, operações de borda | decidir autorização de usuário |
| `NODE_WORKER` | tarefas assíncronas, processamento intensivo | escrever no banco principal |
| `NODE_MEDIA` | transcode, otimização, conversão | ler dado pessoal fora do objeto de mídia |
| `NODE_READ_REPLICA` | leitura de dados autorizados | qualquer escrita |
| `NODE_API` | instâncias auxiliares de API | emitir sessão própria |
| `NODE_LOCAL` | infra do Énóis, absorver pico | qualquer tipo/classe de dado; **escolhido antes** dos demais entre os saudáveis ([ADR-010](../adr/ADR-010-node-local-prioridade-de-ordem.md)) | — |
| `NODE_EMERGENCY` | funções preparadas para incidente | assumir função não preparada |

Um servidor pode executar mais de um perfil se capacidade e permissões permitirem.

## 4. Node Runtime

Runtime padronizado que transforma uma máquina comum em participante. Alvos: Linux, Docker, Docker
Compose, VPS, servidor físico, máquina local, nuvem.

```mermaid
sequenceDiagram
  participant A as Admin (tela)
  participant CP as Control Plane
  participant P as Participante
  participant N as Node Runtime

  A->>CP: cria solicitação de ingresso (perfis, limites)
  CP-->>A: token de provisionamento (uso único, TTL)
  A->>P: entrega o token por canal privado
  P->>N: instala e roda com o token
  N->>CP: POST /network/nodes/enroll (token + CSR)
  CP->>CP: valida token, consome (nunca reutiliza)
  CP-->>N: certificado do nó + API key do nó (uma vez)
  N->>CP: heartbeat + capacidade observada
  CP->>CP: aprovação (manual ou política)
  CP-->>N: configuração assinada
  N->>CP: testes de conectividade e saúde
  CP-->>A: nó ativo no painel
```

## 5. Fluxo de uma tarefa (Fase 2)

```mermaid
sequenceDiagram
  participant CORE as Infra principal
  participant S as Scheduler
  participant W as NODE_WORKER
  participant O as Outbox

  CORE->>O: evento (transação local)
  S->>O: lê pendentes elegíveis
  S->>S: escolhe nó por capacidade, latência, saúde, restrição de dado
  Note over S: a capacidade é descontada dos despachos da própria rodada
  S->>W: despacha tarefa (id único, deadline, tentativa)
  W->>W: executa com idempotência
  W->>O: resultado + recibo do handler
  alt falha
    W-->>S: erro
    S->>S: backoff exponencial + jitter, ou DLQ
  end
```

## 6. Onde a rede toca o que já existe

| componente atual | muda? | como |
|---|---|---|
| `OutboxWorker` | não | continua sendo o consumidor local; o scheduler é um consumidor a mais |
| `IdempotencyApplicationService` | não | reusado como está |
| `AdminGuard` / `AdminRouteInterceptor` | não | `/admin/network/**` já nasce protegido |
| `WriteRouteInterceptor` | sim, aditivo | rotas de nó entram na lista branca com credencial própria |
| `MessageStreamHub` | sim, na Fase 3 | precisa de broadcast entre instâncias antes de réplica |
| `CacheConfig` | sim, na Fase 3 | cache distribuído ou TTL mais curto com invalidação por evento |
| `MediaFfmpegRunner` | sim, na Fase 3 | passa a poder executar em `NODE_MEDIA` |
| `HealthWatcher` | não | já transforma status em evento |

## 7. O que a rede **não** é

- Não é multi-master. A escrita continua num lugar só.
- Não é blockchain nem consenso próprio.
- Não é execução de conteúdo de usuário. HTML, CSS e KofScript continuam nos sandboxes atuais.
- Não é "mais VPS rodando o mesmo jar". Cada perfil tem contrato e permissão próprios.
