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

## EKS com RDS MariaDB (perfil QA)

O Deployment em `clusters/forest-dev/applications/enois-app.yaml` usa `SERVER_PORT=8080`, alinhado ao
Service e aos probes. A configuração padrão da aplicação usa a porta 8081;
`PORT` sozinho não altera `server.port`.

O banco é configurado pelo Secret `socialthing-db-secrets` (chaves `host`,
`port`, `dbname`, `username` e `password`). O perfil QA usa `DB_PORT`, com
3306 como padrão. Configure também `REDE_FRONTEND_URL` e `REDE_BACKEND_URL`
com as URLs públicas dos ambientes: o perfil QA usa essas variáveis nos
callbacks OAuth, e elas não estão definidas no manifesto de exemplo.

O startup probe aguarda até aproximadamente 5 minutos pela prontidão da
aplicação, incluindo a inicialização JPA. Só depois começam os probes de
readiness e liveness do Actuator. O liveness verifica o estado da aplicação,
sem depender da disponibilidade do RDS.

Os recursos iniciais são 250m CPU/512Mi de memória solicitados e limites de
1 CPU/1Gi. O heap Java pode usar até 60% da memória do container; ajuste
esses valores conforme métricas de consumo e carga.

Para identificar a causa de reinícios, consulte o estado anterior e os eventos:

```bash
kubectl -n forest-apps get pods -l app=socialcoiso-back
kubectl -n forest-apps describe pod <pod-name>
kubectl -n forest-apps logs <pod-name> --previous --tail=100
```

`OOMKilled` indica que o container excedeu o limite de memória. Eventos de
falha de liveness seguidos por `Killing` indicam reinício pelo kubelet.
Um código de saída 137 sozinho não distingue essas causas. Um erro de
inicialização Java deve aparecer nos logs anteriores.

O aviso `Unknown system variable 'WSREP_ON'` pode ocorrer na detecção de
Galera pelo Flyway em MariaDB RDS. O Flyway trata a ausência dessa variável
como Galera desativado. Se a verificação/migração termina normalmente, esse
aviso não é a causa do encerramento.

Copie as alterações de manifesto para o repositório GitOps. A alteração de
`application-qa.properties` exige reconstruir a imagem e atualizar a referência
no Deployment. Prefira uma tag imutável ou digest: `latest` com
`imagePullPolicy: IfNotPresent` pode reutilizar uma imagem antiga do nó.

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
