# Arquitetura Hexagonal (Ports & Adapters)

## Visão Geral

A Rede segue o padrão **Arquitetura Hexagonal** (também conhecida como Ports & Adapters), que isola o núcleo da aplicação (domínio + casos de uso) de tecnologias externas (banco de dados, APIs HTTP, serviços de storage, etc.).

## Estrutura de um Módulo

Cada módulo de domínio segue esta estrutura:

```
modulo/
├── domain/               # Núcleo puro - sem dependências externas
│   ├── Model.java        # Entidade de domínio
│   └── ValueObject.java  # Objetos de valor (enums, records)
├── application/
│   ├── Service.java      # Casos de uso / serviços da aplicação
│   └── port/
│       ├── in/           # Interfaces de caso de uso (driving ports)
│       │   ├── UseCase.java
│       │   └── Command.java
│       └── out/          # Interfaces de repositório (driven ports)
│           └── Repository.java
└── adapters/
    ├── in/web/           # Adaptadores HTTP (controllers)
    │   └── Controller.java
    └── out/persistence/  # Adaptadores JPA / banco
        ├── EntityJpa.java
        └── PersistenceAdapter.java
```

## Camadas

### 1. Domain (Núcleo)

Contém as **entidades de domínio** e **objetos de valor**. São classes Java puras, sem anotações de framework, sem dependências externas.

**Arquivos típicos**: entidades de domínio, enums, records de ID.

Exemplos:
- `Post.java`, `Comment.java`, `Community.java`, `Message.java`
- `PostId.java`, `UserId.java` (value objects)
- `PostType.java`, `Visibility.java`, `MemberRole.java` (enums)

### 2. Application (Casos de Uso)

Implementa a **lógica de negócio** da aplicação. Contém:

- **Services**: Classes que orquestram casos de uso, dependem apenas de interfaces de porta
- **Ports (In)**: Interfaces que definem contratos de casos de uso (ex: `PublishPostUseCase`, `LoginUseCase`)
- **Ports (Out)**: Interfaces que definem contratos de repositórios externos (ex: `PostRepository`, `AuthSessionRepository`)

### 3. Adapters (Adaptadores)

Conectam o núcleo ao mundo externo:

- **Inbound Adapters** (`adapters/in/web`): Controllers REST que recebem requisições HTTP e chamam os casos de uso
- **Outbound Adapters** (`adapters/out/persistence`): Implementações JPA das interfaces de repositório
- **Outbound Adapters** (`adapters/out/storage`): Gateways de storage (ImageKit, dev)
- **Outbound Adapters** (`adapters/out/oauth`): Validadores de token OAuth (Google, Apple)
- **Outbound Adapters** (`adapters/out/cache`): Cache de feed in-memory
- **Outbound Adapters** (`adapters/out/network`): Grafo de conexões (JPA)

### 4. Shared (Infraestrutura Compartilhada)

Componentes transversais usados por todos os módulos:

| Componente | Arquivo | Função |
|---|---|---|
| CORS | `CorsConfig.java` | Configuração de Cross-Origin |
| OpenAPI | `OpenApiConfig.java` | Documentação Swagger |
| Tratamento de Erros | `GlobalExceptionHandler.java` | Handler global de exceptions |
| Resolução de Usuário | `CurrentUserResolver.java` | Extrai usuário autenticado do token |
| Observabilidade | `RequestObservabilityFilter.java` | Log de requisições com X-Request-Id |
| Paginação | `CursorCodec.java` | Codificação/decodificação de cursores |
| Outbox | `OutboxWorker.java` | Processamento de eventos em background |
| Auditoria | `AuditLogPersistenceAdapter.java` | Log de auditoria |
| Idempotência | `IdempotencyApplicationService.java` | Garantia de idempotência |
| Respostas | `ApiResponse.java`, `PageResponse.java`, `ErrorResponse.java` | Padronização de respostas |

## Fluxo de uma Requisição

```
Cliente HTTP
     │
     ▼
Controller (adapters/in/web)
     │  Recebe DTO (request body, params, headers)
     │  Extrai usuário via CurrentUserResolver
     ▼
Service (application/)
     │  Valida regras de negócio
     │  Chamada a portas de saída (repositories)
     ▼
PersistenceAdapter (adapters/out/persistence)
     │  Converte domínio ↔ JPA entity
     │  Persiste no banco via Spring Data JPA
     ▼
     │  (opcional) OutboxEventPersistenceAdapter
     │  Adiciona evento à tabela outbox_events
     ▼
Service retorna resultado
     │
     ▼
Controller monta resposta HTTP
```

## Padrões Arquiteturais

### Outbox Pattern

Eventos de domínio (ex: "post comentado", "post reagido") são escritos na tabela `outbox_events` dentro da mesma transação da operação principal. O `OutboxWorker` (scheduled task) processa eventos pendentes em background, garantindo entrega eventual.

### Idempotência

Operações críticas (POST, PUT, DELETE) podem usar idempotency key. Se a mesma chave for reenviada, a resposta original é reutilizada, prevenindo duplicatas.

### Cursor-Based Pagination

Feeds e listas usam paginação baseada em cursor (timestamp) ao invés de offset, garantindo performance consistente mesmo com dados em constante mudança.

### Rate Limiting

Endpoints de autenticação e busca são protegidos por rate limiting via Resilience4j.

### Circuit Breaker

Operações com ImageKit (purge CDN, delete object) são protegidas por circuit breaker + retry.

## Diagrama de Módulos e Dependências

```
                    ┌──────────┐
                    │  Shared  │
                    └────┬─────┘
                         │
     ┌───────────────────┼───────────────────┐
     │                   │                   │
     ▼                   ▼                   ▼
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│ Identity │     │    Feed      │     │   Profile    │
│ Auth     │◄───►│ Posts        │◄───►│ User Profile │
│ Sessions │     │ Comments     │     │ Experience   │
│ OAuth    │     │ Reactions    │     │ Education    │
└──────────┘     └──────┬───────┘     │ Certific.   │
                        │             └──────────────┘
┌──────────┐            │                    │
│ Network  │◄───────────┤                    │
│ Conexões │            │                    │
└──────────┘            │                    │
                        ▼                    ▼
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│ Messages │     │ Notifications│     │   Search     │
│ Chat     │◄────┤ Outbox events├────►│ Full-text    │
└──────────┘     └──────────────┘     └──────────────┘

┌──────────┐     ┌──────────────┐     ┌──────────────┐
│Communities│    │    Jobs      │     │    Events    │
└──────────┘     └──────────────┘     └──────────────┘

┌──────────┐     ┌──────────────┐     ┌──────────────┐
│ Mentoring│     │    Media     │     │ Moderation   │
└──────────┘     └──────────────┘     └──────────────┘
```

**Observações sobre dependências:**
- `Feed` usa `Network` para obter grafo de conexões (amigos, amigos-de-amigos)
- `Notifications` escuta eventos do `Outbox` para criar notificações
- `Search` consulta múltiplos módulos via SQL nativo
- `Moderation` pode excluir posts do `Feed`
- `Media` é usado por `Feed` (post media) e `Profile` (avatar)
