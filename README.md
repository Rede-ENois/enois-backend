# Rede — Social Platform Backend

Rede is a social platform backend built with **Spring Boot 4** (Java 25) following **hexagonal architecture** (ports & adapters).

## Prerequisites

- **JDK 25** (Eclipse Temurin recommended)
- **Maven** (or use the bundled `mvnw` wrapper)
- **Docker** (optional — for MariaDB/MySQL or running via docker-compose)

## Quick Start (dev profile)

```bash
./mvnw spring-boot:run
```

This starts with the `dev` profile using an **H2 in-memory database**. Swagger UI is available at [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html).

## Profiles

| Profile | Database    | How to activate                          |
|---------|-------------|------------------------------------------|
| `dev`   | H2 (memory) | `SPRING_PROFILES_ACTIVE=dev` (default)   |
| `hml`   | MariaDB     | `SPRING_PROFILES_ACTIVE=hml`             |
| `prd`   | MySQL       | `SPRING_PROFILES_ACTIVE=prd`             |

### hml (MariaDB)

```bash
# Start MariaDB
docker run -d --name rede-mariadb \
  -e MARIADB_ROOT_PASSWORD=root \
  -e MARIADB_DATABASE=rede_hml \
  -e MARIADB_USER=rede_hml \
  -e MARIADB_PASSWORD=rede_hml \
  -p 3306:3306 mariadb:11

# Run with hml profile
SPRING_PROFILES_ACTIVE=hml ./mvnw spring-boot:run
```

### prd (MySQL 8+)

```bash
# Start MySQL
docker run -d --name rede-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=rede_prd \
  -e MYSQL_USER=rede_prd \
  -e MYSQL_PASSWORD=rede_prd \
  -p 3307:3306 mysql:8

# Run with prd profile
SPRING_PROFILES_ACTIVE=prd ./mvnw spring-boot:run
```

## Building

```bash
./mvnw clean package -DskipTests
```

Produces `target/rede-0.0.1-SNAPSHOT.jar`.

## Running Tests

```bash
./mvnw test
```

All 24 integration tests must pass.

## Docker

```bash
# Build and start (defaults to dev profile with H2)
docker compose up --build

# With hml profile and MariaDB
docker compose --profile hml up --build
```

## API Documentation

- **Swagger UI**: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)
- **OpenAPI JSON**: [http://localhost:8081/api/openapi.json](http://localhost:8081/api/openapi.json)

## Monitoring

- **Health**: `GET /actuator/health`
- **Metrics**: `GET /actuator/metrics`
- **Prometheus**: `GET /actuator/prometheus`

## Architecture

The project follows **hexagonal architecture**:

```
src/main/java/com/socialcoisa/rede/
├── {domain}/domain/         # Entities, value objects, domain services
├── {domain}/application/    # Use cases / ports (inbound)
├── {domain}/adapters/in/    # REST controllers (inbound adapters)
└── {domain}/adapters/out/   # JPA/HTTP repositories (outbound adapters)
```

Modules: `identity`, `feed`, `media`, `network`, `profile`, `messaging`, `notifications`, `search`, `community`, `admin`.

## Deploy (EKS via GitOps)

O deploy **não** se faz por `fly deploy`. O fluxo é:

1. Push em `main` de `florestaencantada/enois-backend`.
2. GitHub Actions (`.github/workflows/build-and-push.yml`) builda a imagem Docker, publica no ECR
   (`818928662779.dkr.ecr.us-east-1.amazonaws.com/enois/backend:<sha>`) e atualiza o manifesto em
   `florestaencantada/integration_gitops` → `clusters/forest-dev/applications/enois-app.yaml`.
3. O Argo CD (Application `forest-dev-applications`) sincroniza e faz o rollout no cluster `forest-dev`,
   namespace `forest-apps`, Deployment `socialcoiso-back`.

### Roteamento (importante)

O `HTTPRoute` do gateway faz `URLRewrite ReplacePrefixMatch: /` para o caminho `/api`, ou seja, **remove o
prefixo `/api` antes de encaminhar ao backend**. Por isso os controllers deste projeto são mapeados na
**raiz** (`/auth/...`, `/feed`, `/health`) e o prefixo `/api` é responsabilidade do gateway. O front sempre
chama `https://enois.ia.br/api/...`. Esse é o mesmo padrão do app irmão (skillify) — não adicione `/api`
nos `@RequestMapping` do backend.

## Configuração e ações manuais

### Variáveis de ambiente (runtime do Deployment)

| Variável | Obrigatória | Observação |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sim | `qa` no cluster |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | sim | vêm do Secret `socialthing-db-secrets` |
| `REDE_ADMIN_EMAILS` | sim | allowlist (separada por vírgula) para `/admin/reports/**`. **Vazio = ninguém é admin.** |
| `REDE_FRONTEND_URL` | sim | base do front; o callback OAuth volta em `${REDE_FRONTEND_URL}/auth/callback` |
| `REDE_BACKEND_URL` | sim | base pública do back; monta os redirect URIs do OAuth |
| `FRONTEND_URL` | sim | link do e-mail de reset de senha: `${FRONTEND_URL}/auth/reset?token=...` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | sim | SMTP do "esqueci minha senha". Sem isso o e-mail não sai (o erro é logado e engolido). |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | p/ login Google | |
| `APPLE_CLIENT_ID` / `APPLE_TEAM_ID` / `APPLE_KEY_ID` / `APPLE_PRIVATE_KEY` | p/ login Apple | |
| `IMAGEKIT_URL_ENDPOINT` / `IMAGEKIT_PUBLIC_KEY` / `IMAGEKIT_PRIVATE_KEY` | p/ mídia | |
| `CORS_ALLOWED_ORIGINS` | não | padrão `*` |
| `OAUTH_STATE_COOKIE_SECURE` | não | padrão `true`; use `false` só em dev http |

### Passo a passo das ações manuais

1. **Criar/atualizar o Secret do banco** (`socialthing-db-secrets`) com as chaves `host`, `port`, `dbname`,
   `username`, `password`. Sem isso o pod não sobe (Flyway falha no boot).
2. **Definir `REDE_ADMIN_EMAILS`** no Deployment com os e-mails que podem moderar. Enquanto estiver vazio,
   `/admin/reports/**` responde 403 para todos.
3. **Configurar o SMTP** (`MAIL_*`) e `FRONTEND_URL` para o reset de senha funcionar de ponta a ponta.
4. **Configurar o OAuth**: criar as credenciais no Google Cloud e no Apple Developer e cadastrar os
   redirect URIs exatamente como
   `${REDE_BACKEND_URL}/api/auth/oauth/google/callback` e `.../apple/callback`. Para Apple, use
   `response_mode=form_post` (o backend aceita o callback via POST).
5. **Rotacionar segredos vazados** (ver seção "Segurança").
6. Verificar o rollout: `kubectl -n forest-apps get pods -l app=socialcoiso-back` e
   `kubectl -n forest-apps logs deploy/socialcoiso-back`.

## Segurança — pendências que exigem ação manual

- **`dump_rede.sql` está versionado** com dados pessoais reais (e-mails, hashes de senha) — foi commitado
  com a mensagem "foda-se a LGPD". Remover do HEAD **não** apaga do histórico do Git. Ação: remover o
  arquivo, reescrever o histórico (`git filter-repo`) ou considerar o repositório comprometido, **trocar as
  senhas dos usuários afetados** e avaliar notificação conforme a LGPD.
- **Segredos em texto puro**: conferir e rotacionar `DB_PASSWORD`, credenciais de e-mail, chaves do
  ImageKit e credenciais OAuth caso tenham vazado (o `.env` do front já vazou a `IMAGEKIT_PRIVATE_KEY`).
- **`/admin/reports/**`** agora exige admin via `REDE_ADMIN_EMAILS`; antes qualquer usuário logado
  aprovava/descartava denúncias.
- **Recomendações** (`GET /recommendations/received|given/{userId}`) seguem **sem autenticação** por serem
  exibidas em perfis públicos — avalie se devem exigir login.
