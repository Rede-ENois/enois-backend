# Deploy

## Como o Énois é implantado hoje (Portainer + Docker)

O deploy real **não** é Fly.io nem ArgoCD/Kubernetes: a esteira (`build-and-push.yml`) constrói a
imagem, sobe no Harbor e **atualiza uma stack do Portainer** por API, em dois hosts:

| ambiente | host | stack/container | porta |
|---|---|---|---|
| produção | `enois-node2` (6 vCPU, 11,68 GB) | stack `enois-prod-backend` | 8081 |
| QA | `vmi935062` / docker-prod4 (4 vCPU, 7,76 GB) | container `enois-qa-backend` | 8105 |

A produção e a QA sao **linhas de branch separadas**: o job de QA roda em push na `qa` e o de
produção em push na `main`.

### Memória do backend

Medido no host em 2026-10-24 (API do Portainer, `/info` e `/stats`):

| | limite do container | em uso | heap da JVM |
|---|---|---|---|
| produção | 6 GB (antes 4 GB) | 0,79 GB | até 3,6 GB (antes 1 GB) |
| QA | 2 GB (antes 1 GB) | 0,55 GB | até 1,2 GB (antes 256 MB) |

**O ponto que costuma passar batido:** o limite do container não é o heap. Sem
`JAVA_TOOL_OPTIONS`, a JVM usa o padrão `MaxRAMPercentage=25%` — ou seja, com 4 GB de container a
JVM só podia usar **1 GB** de heap e os outros 3 GB ficavam ociosos. Agora os dois ambientes têm
`-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError`: o heap cresce sob demanda e, quando
acaba, a JVM morre com erro claro em vez de ser morta pelo kernel sem deixar rastro (o
`ExitOnOutOfMemoryError` não cobre OOMKilled do kernel, que é o caso do ffmpeg — processo fora da
JVM — estourando o limite).

A QA ficou em 2 GB, e não 4, porque o host dela é **compartilhado** com outros três projetos
(`apbackend_medcare`, `zmbackend` e companhia) que rodam **sem limite de memória** e já usavam
~4,3 GB somados; o total em uso era 5,5 GB de 7,76 GB. Cabe 2 GB com folga; 4 GB disputaria memória
com o vizinho e o OOM killer poderia escolher o container do Énois como vítima.

### Fósseis (não são o deploy)

- `fly.toml` — sobrou do Fly.io (`memory_mb = 2048`, `-Xmx1536m`); ninguém implanta por ele.
- `clusters/forest-dev/applications/enois-app.yaml` — manifesto ArgoCD do cluster **dev**, removido
  do `integration_gitops` em 2026-10-22 porque o Énois passou a ser implantado pelo Portainer. Os
  `resources.limits.memory: 1Gi` de lá não descrevem nada que esteja rodando hoje.

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

## Telegram observability channel (production only)

| Variable | GitHub secret | Example |
|---|---|---|
| `KOF_AGENT_API_TOKEN` | `KOF_AGENT_API_TOKEN` | Kof Agent bot token |
| `KOF_AGENT_CHAT_ID` | `KOF_AGENT_CHAT_ID` | `-1001234567890` (numeric id, not the invite link) |

Only the production stack receives them; without both the channel is inert. Details in
[`KOF_AGENT.md`](KOF_AGENT.md).

## Variáveis de Ambiente Obrigatórias (OAuth)

| Variável | Descrição |
|---|---|
| `REDE_OAUTH_GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `REDE_OAUTH_GOOGLE_CLIENT_SECRET` | Google OAuth client secret |
| `REDE_OAUTH_APPLE_CLIENT_ID` | Apple OAuth client ID |
| `REDE_OAUTH_APPLE_TEAM_ID` | Apple Team ID |
| `REDE_OAUTH_APPLE_KEY_ID` | Apple Key ID |
| `REDE_OAUTH_APPLE_PRIVATE_KEY` | Apple private key (PKCS#8) |

### URIs de redirecionamento do Google (Console → Credentials)

O backend manda o Google para o callback da **API** (`application-prd.properties` e
`application-qa.properties` montam `${REDE_BACKEND_URL}/api/auth/oauth/google/callback`). Esses
URIs precisam estar cadastrados no **mesmo OAuth 2.0 Client ID** (tipo "Web application"), senão o
Google responde `400 redirect_uri_mismatch`:

- Produção: `https://api.enois.ia.br/api/auth/oauth/google/callback`
- QA: `https://enois-api-qa.b7cloud.tech/api/auth/oauth/google/callback`

O URI `https://enois.ia.br/auth/callback?provider=GOOGLE` é a tela do app, **não** o redirect que o
Google recebe neste fluxo — cadastrar só ele não resolve. A comparação é exata (esquema, host,
caminho; sem barra final). Se cada ambiente usar um client próprio, cadastre o URI correspondente
no client daquele ambiente.

## Variáveis de Ambiente (Storage ImageKit)

| Variável | Descrição |
|---|---|
| `REDE_STORAGE_IMAGEKIT_PRIVATE_KEY` | ImageKit private key |
| `REDE_STORAGE_IMAGEKIT_PUBLIC_KEY` | ImageKit public key |
| `REDE_STORAGE_IMAGEKIT_URL_ENDPOINT` | ImageKit URL endpoint |

## Metadados de mídia (privacidade)

A mídia S3 passa pelo proxy `GET/PUT /api/storage/{chave}`. Na **escrita**, o backend remove
EXIF/GPS/XMP/IPTC antes de gravar no bucket; na **leitura**, objetos antigos são limpos de forma
oportunista e regravados com a mesma chave. Imagens/PDF são processados em memória (até 40 MB);
vídeo/áudio usam `ffmpeg -map_metadata -1 -c copy` (a imagem instala o ffmpeg) e arquivos maiores
passam por arquivo temporário. A mídia legada do ImageKit não é reescrita — ela não passa pelo
proxy do bucket.

| Variável | Descrição |
|---|---|
| `REDE_MEDIA_FFMPEG_PATH` | Caminho do ffmpeg (padrão `ffmpeg`) |
| `TURNSTILE_TEST_KEY_POLICY` | O que fazer com chaves de teste `1x000…`: `degrade` (padrão — sobe com o captcha externo desligado e ERROR no log) ou `fatal` (recusa o boot). Chave de teste nunca conta como captcha ativo |
| `REDE_VERSION_DETAILS_TOKEN` | Token do header `X-Version-Details` para ver uptime/Flyway/storage em `/version` |

## Migrações de Banco

As migrações Flyway em `src/main/resources/db/migration/` são executadas automaticamente na inicialização da aplicação.

### Scripts auxiliares

Origem e destino nao tem default (escolha o alvo a cada execucao) e as senhas vem de variaveis de
ambiente, nunca da linha de comando. Veja `--help` de cada script.

```bash
# Exportar banco MariaDB (dumps/ e ignorado pelo git)
./scripts/db-export.sh > dumps/backup.sql

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
