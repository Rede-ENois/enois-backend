# 00 — Diagnóstico da arquitetura atual

Tudo aqui foi lido do código nesta branch (`feat/rede-distribuida`, a partir de `origin/qa`
@ `81c1ac02`). Nenhuma afirmação vem de suposição: cada item traz o arquivo que o comprova.

## 1. Forma do serviço

| número | valor | como foi medido |
|---|---|---|
| módulos de domínio | 54 | `ls -d src/main/java/com/socialcoisa/rede/*/` |
| arquivos Java de produção | 1588 | `find src/main/java -name '*.java'` |
| arquivos de teste | 511 | `find src/test/java -name '*.java'` |
| controllers REST | 104 | `grep -rl '@RestController' src/main/java` |
| mapeamentos HTTP | 692 | `grep -rho '@\(Get\|Post\|Put\|Patch\|Delete\)Mapping'` |
| jobs `@Scheduled` | 56 | `grep -rn '@Scheduled' src/main/java` |
| migrações Flyway | 247 | `ls src/main/resources/db/migration` |
| `create table` nas migrações | 208 | `grep -c 'create table'` |

É **um** monólito Spring Boot hexagonal, implantado como **um** container. Não existe processo
secundário, fila externa nem cache de rede.

## 2. Deploy e topologia

Fonte: `docs/DEPLOYMENT.md`.

| ambiente | host | stack | porta |
|---|---|---|---|
| produção | `enois-node2` (6 vCPU, 11,68 GB) | `enois-prod-backend` (Portainer) | 8081 |
| QA | `vmi935062` / docker-prod4 (4 vCPU, 7,76 GB) | `enois-qa-backend` | 8105 |

- Deploy por **Portainer API** (`build-and-push.yml` → Harbor → stack), **não** Kubernetes nem Fly.
- `fly.toml` e `clusters/forest-dev/…/enois-app.yaml` são fósseis; não descrevem o que roda.
- Heap controlado por `-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError` (produção 6 GB de
  container, heap até 3,6 GB).
- Pool de conexões: produção `DB_POOL_MAX=30`, QA `maximum-pool-size=5`.

**Consequência para a rede distribuída:** hoje há exatamente um lugar onde a plataforma existe. O
host de QA é *compartilhado com três outros projetos* — ou seja, o ambiente de testes já demonstra a
disputa de recursos que a rede cooperativa pretende resolver.

## 3. Persistência e consistência

- MariaDB 11.4, charset `utf8mb4_uca1400_ai_ci`, H2 nos testes, Flyway com 247 migrações.
- **Outbox transacional já existe e já é correto** (`shared/outbox/OutboxWorker.java`):
  - claim atômico por instância antes de executar, com expiração (`rede.outbox.claim-stale-seconds:60`);
  - recibo por **par (evento, handler)** em `processed_event_handlers` (V252), gravado na mesma
    transação dos efeitos — retomar um evento que falhou no meio não repete o que já concluiu;
  - backoff exponencial limitado a 5 min, `max-attempts` 20, lote de 50;
  - erro de handler não é engolido: o evento continua `PENDING` com `last_error`.
- **Idempotência de comando já existe** (`idempotency/application/IdempotencyApplicationService.java`):
  chave por `(user, method, route, Idempotency-Key)` com hash do corpo, retenção própria.
- **Auditoria existe** (`shared/audit/`, tabela `audit_logs` com índice `(actor_id, created_at)`).

**Isto é a fundação da Fase 2.** O scheduler distribuído não precisa inventar entrega de eventos: ele
reusa o outbox, e o nó que processa grava o recibo do handler exatamente como um handler local.

## 4. Identidade e autorização

- Sessão por token de acesso + `X-Session-Token` (`CurrentUserResolver`).
- Admin por coluna **imutável** `users.is_admin` (V170), via `AdminGuard` — nunca por username/e-mail.
- **Trava central** `AdminRouteInterceptor`: todo `/admin/**` exige admin antes do MVC, mesmo que o
  controller esqueça. `/moderation/**` tem regra própria (admin ou moderador aprovado).
- **Fail-closed na escrita** `WriteRouteInterceptor`: todo POST/PUT/PATCH/DELETE exige sessão exceto
  uma lista branca explícita, comparada por `AutomatedWritePathsTests`. Rotas de serviço
  (`/internal/novidades-deploy`, webhooks GitHub/Didit) entram nessa lista com credencial própria,
  **fail-closed**: sem token configurado responde 503.
- Hash de segredo de uso único: `shared/security/Hashes.sha256Hex` — só o hash é persistido.

**É exatamente o padrão que a API key por nó vai seguir** (`04-identidade-e-autorizacao.md`).

## 5. Estado em memória (o que impede escalar hoje)

Três componentes assumem **um único processo**:

1. **`messaging/adapters/in/web/MessageStreamHub.java`** — registro de streams SSE em
   `ConcurrentHashMap`, com o próprio javadoc dizendo: *"in-memory registry, correct while production
   runs a single backend instance. A second instance needs a broadcast between instances (Redis
   pub/sub or similar) before scaling out."*
2. **`shared/application/CacheConfig.java`** — cache Caffeine por processo; `CacheInvalidator` avisa
   que invalidar num pod não invalida os outros e quem garante o limite é o TTL.
3. **`shared/outbox/OutboxWorker.java`** — já é seguro entre instâncias (claim atômico), mas roda
   **tudo** em qualquer instância que subir.

**Consequência:** hoje, subir uma segunda réplica do backend já quebra os avisos de mensagem em tempo
real. Isso é um requisito duro para a Fase 3 (NODE_EDGE/NODE_API) e precisa de decisão explícita
(ADR), não de improviso.

## 6. Mídia e storage

- `MediaService` valida tipo/tamanho; `MediaStorageProxyController` recebe o `PUT`, sanitiza
  (`MediaMetadataSanitizer`) e grava no S3 (`S3StorageConfig`, condicional a `rede.media.storage=s3`).
- Leitura já é streaming por `Range` (206) direto do bucket.
- `ffmpeg`/`ffprobe` rodam **dentro do pod**, com semáforo de concorrência
  (`rede.media.ffmpeg.max-concurrent:2`) e orçamento de tempo.

**Consequência:** o perfil `NODE_MEDIA` tem alvo óbvio — o transcode é CPU pura, isolável e já
delimitado por contrato (`clean(input, output, mime, timeout)`). É o primeiro candidato real a sair
da infraestrutura principal sem tocar em dado de usuário.

## 7. Observabilidade

- Actuator + Micrometer/Prometheus (`management.endpoints.web.exposure.include` default `health`;
  Prometheus habilitado), `rede_outbox_pending`, `rede_outbox_failed_attempts`,
  `rede_outbox_events_total`, `rede_outbox_failed_terminal_total`, `rede_media_upload_bytes`.
- Tracing por OpenTelemetry (`management.tracing.sampling.probability`), `/health`, `/livez`, `/readyz`.
- Monitoramento próprio: `ops/monitoring` (HealthWatcher em thread própria, `kofagent` com spool em
  arquivo e transporte Telegram).

**Consequência:** a rede não precisa inventar métrica nova para o painel — o modelo de gauges de
cardinalidade fixa já está provado na outbox. Nó entra como mais um conjunto de gauges com label de
nó, e o `HealthWatcher` já sabe transformar mudança de status em evento.

## 8. Gateway

`Api-gateway` é um reverse proxy em Go, **já com gatekeeper por API key** (`X-Client-Token`/
`X-API-Key`), concorrência por rota, rate limit por `ip`/`api_key`/`ip_api_key`, body cap, timeout e
mapeamento de erro. Hoje ele atende **só** o serviço de moderação de ML.

**Consequência:** para a rede, o gateway é o precedente técnico do "nó autenticado por chave" — a
diferença é que lá a chave é estática em YAML, e aqui precisa ser **uma chave por nó, emitida por
admin, com hash no banco e revogação imediata**.

## 9. O que já serve de fundação (não reconstruir)

| necessidade | o que já existe |
|---|---|
| entrega de evento entre nós | `OutboxWorker` + recibo por handler (V252) |
| idempotência de comando | `IdempotencyApplicationService` |
| autorização de admin | `AdminGuard` + `AdminRouteInterceptor` |
| segredo persistido só como hash | `Hashes.sha256Hex` |
| credencial de serviço fail-closed | padrão `X-Deploy-Token` |
| métricas de cardinalidade fixa | `OutboxMetrics` |
| saúde → evento | `HealthWatcher` |
| limite por rota por chave | `Api-gateway` gatekeeper |
| transcode isolável | `MediaFfmpegRunner` |

## 10. Lacunas que a rede precisa fechar

1. Não existe conceito de **nó** (nem tabela, nem identidade, nem heartbeat).
2. Não existe **chave por nó** — só segredos globais por variável de ambiente.
3. Não existe **tls mútuo** entre partes: só HTTPS público.
4. Não existe **scheduler** que escolha onde rodar uma tarefa.
5. Não existe **modo operacional** (NORMAL/HIGH_LOAD/DEGRADED/EMERGENCY/RECOVERY).
6. Não existe **painel** de nós.
7. Não existe **broadcast entre instâncias** (bloqueia réplica e, portanto, NODE_API/NODE_EDGE).
8. Não existe **quarentena** nem revogação em runtime de um participante.

Cada lacuna acima tem fase e documento de destino — ver [`07-plano-incremental.md`](07-plano-incremental.md).
