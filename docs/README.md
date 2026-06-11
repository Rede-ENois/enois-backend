# Rede - Social Professional Network

**Rede** (Portuguese for "Network") é um backend de rede social profissional (similar ao LinkedIn) desenvolvido por **Social Coisa** (socialcoisa).

## Tech Stack

| Tecnologia | Versão | Propósito |
|---|---|---|
| Java | 25 | Linguagem principal |
| Spring Boot | 4.0.6 | Framework web |
| Maven | - | Build e dependências |
| JPA / Hibernate | - | ORM |
| Flyway | - | Migrações de banco |
| H2 | - | Banco dev (in-memory) |
| MySQL | - | Banco produção |
| MariaDB | - | Banco QA/HML |
| Resilience4j | 2.4.0 | Rate limiting, circuit breaker, retry |
| Nimbus JOSE + JWT | 9.37.3 | Verificação OAuth JWT |
| Springdoc OpenAPI | 2.8.6 | Documentação Swagger |
| Micrometer + Prometheus | - | Métricas |
| Lombok | - | Redução de boilerplate |

## Funcionalidades

- **Perfis profissionais** com experiência, educação, certificações, reputação
- **Feed de postagens** com tipos (insight, artigo, pergunta, projeto, oportunidade)
- **Rede de conexões** entre usuários
- **Communidades** temáticas
- **Eventos** com confirmação de presença
- **Vagas de emprego** com candidatura
- **Mentoria** com agendamento de sessões
- **Mensagens diretas** em conversas
- **Notificações** de interações sociais
- **Busca global** entre usuários, comunidades, vagas, eventos e mentores
- **Moderação** de conteúdo com reports
- **Autenticação** por email/senha e OAuth (Google, Apple)
- **Sistema de reputação** baseado em atividade

## Arquitetura

```
┌──────────────────────────────────────────────┐
│           REST Controllers                     │
│  (adapters/in/web)                            │
├──────────────────────────────────────────────┤
│          Application Services                  │
│  (application/) - Casos de uso                │
├──────────────────────────────────────────────┤
│            Domain Models                       │
│  (domain/) - Entidades puras                  │
├──────────────────────────────────────────────┤
│            Port Interfaces                     │
│  (application/port/in - use cases)            │
│  (application/port/out - repositories)        │
├──────────────────────────────────────────────┤
│     Persistence / Outbound Adapters           │
│  (adapters/out/persistence) - JPA            │
│  (adapters/out/storage) - ImageKit/dev       │
│  (adapters/out/oauth) - Google/Apple         │
│  (adapters/out/cache) - Feed cache           │
└──────────────────────────────────────────────┘
```

O projeto segue **Arquitetura Hexagonal** (Ports & Adapters), organizada em módulos de domínio independentes.

## Módulos

| Módulo | Descrição |
|---|---|
| `identity` | Autenticação, cadastro, OAuth, sessões |
| `profile` | Perfil do usuário, experiência, educação, certificações |
| `feed` | Postagens, comentários, reações, feed |
| `network` | Conexões entre usuários |
| `media` | Upload e gerenciamento de mídia |
| `communities` | Comunidades temáticas |
| `jobs` | Vagas de emprego |
| `events` | Eventos |
| `mentoring` | Mentoria |
| `messaging` | Mensagens diretas |
| `notifications` | Notificações |
| `search` | Busca global |
| `moderation` | Reports e moderação |
| `idempotency` | Suporte a idempotência |
| `shared` | Infraestrutura compartilhada (CORS, OpenAPI, Outbox, Audit) |

## Pré-requisitos

- JDK 25+
- Maven 3.9+
- Docker (opcional, para ambientes com banco externo)

## Setup rápido (desenvolvimento)

```bash
# Compilar
./mvnw clean compile

# Rodar testes
./mvnw test

# Rodar aplicação (H2 in-memory)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Acessar:
# - API: http://localhost:8080/api/health
# - Swagger: http://localhost:8080/swagger-ui.html
# - H2 Console: http://localhost:8080/h2-console
```

## Perfis de ambiente

| Perfil | Banco | Propósito |
|---|---|---|
| `dev` | H2 in-memory | Desenvolvimento local |
| `qa` | MariaDB | Testes |
| `hml` | MariaDB | Homologação |
| `prd` | MySQL | Produção |

## Docker Compose

```bash
# Dev (H2)
docker compose --profile dev up

# HML (MariaDB)
docker compose --profile hml up

# Produção (MySQL)
docker compose --profile prd up
```

## Variáveis de ambiente

| Variável | Obrigatória | Descrição |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Não | Perfil ativo (dev, qa, hml, prd) |
| `REDE_OAUTH_GOOGLE_CLIENT_ID` | Sim (se OAuth Google) | Google OAuth client ID |
| `REDE_OAUTH_GOOGLE_CLIENT_SECRET` | Sim (se OAuth Google) | Google OAuth client secret |
| `REDE_OAUTH_APPLE_CLIENT_ID` | Sim (se OAuth Apple) | Apple OAuth client ID |
| `REDE_OAUTH_APPLE_TEAM_ID` | Sim (se OAuth Apple) | Apple Team ID |
| `REDE_OAUTH_APPLE_KEY_ID` | Sim (se OAuth Apple) | Apple Key ID |
| `REDE_OAUTH_APPLE_PRIVATE_KEY` | Sim (se OAuth Apple) | Apple private key |
| `REDE_STORAGE_IMAGEKIT_PRIVATE_KEY` | Sim (se storage ImageKit) | ImageKit private key |
| `REDE_STORAGE_IMAGEKIT_PUBLIC_KEY` | Sim (se storage ImageKit) | ImageKit public key |
| `REDE_STORAGE_IMAGEKIT_URL_ENDPOINT` | Sim (se storage ImageKit) | ImageKit URL endpoint |
| `REDE_CORS_ALLOWED_ORIGINS` | Não | Origens CORS permitidas |
| `DB_HOST` | Sim (qa/hml/prd) | Host do banco |
| `DB_PORT` | Sim (qa/hml/prd) | Porta do banco |
| `DB_NAME` | Sim (qa/hml/prd) | Nome do banco |
| `DB_USER` | Sim (qa/hml/prd) | Usuário do banco |
| `DB_PASSWORD` | Sim (qa/hml/prd) | Senha do banco |

## CI/CD

- **GitHub Actions**: Build com JDK 25, `mvn verify`, upload de artefato
- **Fly.io**: Deploy configurado via `fly.toml` (perfil qa, 2 vCPU, 2GB RAM)
