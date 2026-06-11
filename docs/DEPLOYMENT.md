# Deploy

## Fly.io

A aplicação está configurada para deploy no Fly.io via `fly.toml`:

```toml
app = "rede-qa"
primary_region = "iad"
```

**Recursos**: 2 vCPU, 2048MB RAM

```bash
# Fazer deploy
fly deploy

# Ver logs
fly logs

# Abrir app
fly open
```

## Docker

### Construção Multi-stage

O `Dockerfile` usa multi-stage build:
1. **Stage 1**: Compilação com `mvnw -Pproduction package` usando Eclipse Temurin JDK 25
2. **Stage 2**: Runtime com Eclipse Temurin JRE 25, copia o JAR

```bash
# Construir imagem
docker build -t rede .

# Rodar com Docker Compose
docker compose --profile dev up
docker compose --profile hml up
docker compose --profile prd up
```

### Perfis Docker Compose

**Dev** (H2 in-memory, sem banco externo):
```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=dev
    profiles: ["dev"]
```

**HML** (MariaDB):
```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=hml
      - DB_HOST=mariadb
      - DB_PORT=3306
      - DB_NAME=rede
      - DB_USER=rede
      - DB_PASSWORD=senha
    depends_on:
      - mariadb
    profiles: ["hml"]

  mariadb:
    image: mariadb:11
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: rede
      MYSQL_USER: rede
      MYSQL_PASSWORD: senha
```

**PRD** (MySQL):
```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prd
      - DB_HOST=mysql
      - DB_PORT=3306
      - DB_NAME=rede
      - DB_USER=rede
      - DB_PASSWORD=senha
    depends_on:
      - mysql
    profiles: ["prd"]

  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: rede
      MYSQL_USER: rede
      MYSQL_PASSWORD: senha
```

## Variáveis de Ambiente Obrigatórias (QA/HML/PRD)

| Variável | Exemplo |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prd` |
| `DB_HOST` | `mysql.internal` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `rede` |
| `DB_USER` | `rede` |
| `DB_PASSWORD` | `...` |
| `REDE_CORS_ALLOWED_ORIGINS` | `https://app.exemplo.com` |

## Variáveis de Ambiente Obrigatórias (OAuth)

| Variável | Descrição |
|---|---|
| `REDE_OAUTH_GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `REDE_OAUTH_GOOGLE_CLIENT_SECRET` | Google OAuth client secret |
| `REDE_OAUTH_APPLE_CLIENT_ID` | Apple OAuth client ID |
| `REDE_OAUTH_APPLE_TEAM_ID` | Apple Team ID |
| `REDE_OAUTH_APPLE_KEY_ID` | Apple Key ID |
| `REDE_OAUTH_APPLE_PRIVATE_KEY` | Apple private key (PKCS#8) |

## Variáveis de Ambiente (Storage ImageKit)

| Variável | Descrição |
|---|---|
| `REDE_STORAGE_IMAGEKIT_PRIVATE_KEY` | ImageKit private key |
| `REDE_STORAGE_IMAGEKIT_PUBLIC_KEY` | ImageKit public key |
| `REDE_STORAGE_IMAGEKIT_URL_ENDPOINT` | ImageKit URL endpoint |

## Migrações de Banco

As migrações Flyway em `src/main/resources/db/migration/` são executadas automaticamente na inicialização da aplicação.

### Scripts auxiliares

```bash
# Exportar banco MariaDB remoto
./scripts/db-export.sh

# Importar dump para banco local
./scripts/db-import.sh

# Migração: exportar remoto → importar local
./scripts/db-migrate.sh
```

## CI/CD

**GitHub Actions** (`.github/workflows/ci.yml`):
- JDK 25 (temurin)
- Cache Maven
- `mvn verify` (compila + testes)
- Upload de artefato (JAR)
