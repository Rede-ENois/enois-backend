# Logs do backend (CloudWatch)

O backend escreve **uma linha JSON por evento no stdout**, no formato **ECS** (Elastic Common
Schema) do Spring Boot. É o formato que o CloudWatch Logs Insights lê sem parser customizado:
cada campo vira uma coluna filtrável (`http.status`, `durationMs`, `requestId`, `traceId`...).

O perfil de produção/QA (`SPRING_PROFILES_ACTIVE=qa` ou `prd`) liga o formato:

```properties
logging.structured.format.console=ecs
```

No `dev` o formato continua o padrão legível (pattern) para não atrapalhar o desenvolvimento.

## Campos fixos

| Campo | Origem | Uso |
| --- | --- | --- |
| `@timestamp`, `log.level`, `message` | ECS | leitura normal |
| `service.name`, `service.environment` | `application.properties` (literal por perfil) | separar app/ambiente |
| `service.version` | não vem do ECS; a linha `app_started` traz a versão do build | correlacionar deploy |
| `requestId` | `RequestObservabilityFilter` (header `X-Request-Id` ou UUID) | seguir uma requisição |
| `traceId`, `spanId` | Micrometer/OpenTelemetry | correlacionar com traces |
| `clientIp`, `userId` | filtro (MDC) | quem chamou (sem e-mail/token) |
| `http.method`, `http.path`, `http.route`, `http.status`, `durationMs` | access log | agregações e latência |
| `userAgent` | filtro (truncado em 200, sem caracteres de controle) | diagnóstico |
| `deviceId` | header `X-Device-Id` (UUID gerado pelo app no localStorage) | correlacionar requisições/uploads do mesmo aparelho |

A versão do artefato vem de `REDE_VERSION` (o pipeline pode definir com o SHA da imagem; sem ela
fica `unknown`). O nível da aplicação é ajustável por `LOG_LEVEL` (default `INFO`).

## O que é logado

- **Toda requisição** (menos health probes) em `INFO`, com rota, status, duração, IP e usuário;
  `5xx` sobe para `ERROR` e requisições acima de **2s** geram um `WARN slow_request`.
- **Health probes** (`/livez`, `/readyz`, `/actuator/health`) ficam em `DEBUG` — em `INFO` elas
  bateriam a cada 10s e encareceriam o CloudWatch sem informação de produto.
- **Erros de API** (`GlobalExceptionHandler`): `ApiException` com status ≥ 500 vira `ERROR
  api_error` com `code`; exceções não tratadas viram `ERROR unhandled_exception` com stack trace.
  Erros de cliente (4xx) ficam em `DEBUG` para não inundar o log.
- **Auditoria de admin**: toda escrita em `/admin/**` gera uma linha no logger `audit`.
- **Eventos de negócio críticos**: divergência de reconciliação da faísca (`ERROR
  coin_reconciliation_divergence`), prêmios diários pagos, início/parada da aplicação
  (`app_started`/`app_stopping`).
- **E-mail**: falhas de envio são registradas; o corpo e o código de 2FA nunca são logados.
- **Uploads**: `media_upload_signed` (mediaId, ownerId, mime, bytes, purpose, provider) e
  `media_upload_signing_failed` — metadados, nunca o conteúdo do arquivo.

## Telegram channel (production)

The monitoring pipeline also feeds a private Telegram channel through the Kof Agent bot: one bulletin
every 10 s with WARN+ log lines, exceptions, every 5xx, startup/shutdown, health transitions, admin
audit and application state, each with the request's IP and user id. See
[`KOF_AGENT.md`](KOF_AGENT.md).

## Endereço MAC (por que não tem)

O MAC é um endereço de camada 2 (link): ele não atravessa roteadores nem chega em uma requisição
HTTP. O navegador também não expõe o MAC da máquina por segurança. O servidor, no máximo, veria
o MAC do próximo salto (o balanceador/roteador), não o do aparelho da pessoa — e um MAC pode ser
aleatorizado pelo próprio sistema operacional (randomização de Wi-Fi). Por isso o log usa
**`deviceId`**: um UUID aleatório criado pelo app no primeiro acesso, guardado no `localStorage`,
que identifica o mesmo navegador nas requisições sem coletar um identificador de hardware e sem
depender de algo que o protocolo não entrega. Limpar os dados do site troca o `deviceId`.

## O que NÃO é logado (de propósito)

Corpo de requisição/resposta, cabeçalhos de autenticação, tokens, senhas, e-mails completos,
CPF/documentos, conteúdo de posts/comentários e anexos. O access log registra apenas metadados.

## Envio para o CloudWatch

O app **não** fala com a AWS: ele só escreve no stdout. Quem entrega ao CloudWatch é o agente do
cluster (Fluent Bit / CloudWatch Agent) que coleta o stdout do container. Se os logs não aparecem
no grupo esperado, o problema é no agente/IRSA — não no formato da aplicação. O manifesto do
backend roda com `SPRING_PROFILES_ACTIVE=qa`, que já liga o ECS.

## Consultas úteis (Logs Insights)

```
# erros recentes
fields @timestamp, service.version, http.path, http.status, requestId, traceId, message
| filter log.level = "ERROR"
| sort @timestamp desc
| limit 100

# 5xx por rota
filter http.status >= 500
| stats count() as erros by http.route
| sort erros desc

# latência e requisições lentas
filter durationMs > 2000
| stats avg(durationMs) as media, max(durationMs) as pior, count() as total by http.route

# uma requisição específica
filter requestId = "COLE_O_ID"
| sort @timestamp asc

# quando a versão X subiu
filter message = "app_started" and service.version = "SHA"
```
