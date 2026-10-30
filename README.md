# Énóis — backend

A API do **Énóis** ([enois.ia.br](https://enois.ia.br)): a rede social brasileira para quem trabalha
com tecnologia. Aqui vivem as regras de negócio, a autorização, a moderação, a carteira (Faísca) e
as integrações — o frontend só pede, e é aqui que se decide.

**Spring Boot 4** (Java 25), arquitetura hexagonal (portas e adaptadores), um pacote por
funcionalidade, Flyway para migrações, H2 nos testes e **MariaDB 11.4** em produção.

O frontend fica no [`enois-frontend`](https://github.com/Rede-ENois/enois-frontend). O contrato entre
os dois é a API — mudar um lado sem o outro é a forma mais comum de quebrar a tela de alguém.

**Começando agora?** Leia o [`CONTRIBUTING.md`](CONTRIBUTING.md) (arquitetura, migrações, testes e
fluxo de contribuição) e o [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). Falha de segurança tem canal
próprio no [`SECURITY.md`](SECURITY.md) — não vira issue pública.

## O que o Énóis é

- Uma rede **de gente**: conversa, grupos, trabalho, estudo, brincadeira e construção juntos.
- Um lugar onde cada pessoa constrói a própria reputação, e onde a moderação é feita por gente.
- Software livre, sob GPL-3.0, sem plano pago, sem anúncio e sem paywall.

## O que o Énóis não é

- **Não é uma rede de recrutamento.** Vaga é uma das coisas que se posta aqui, não o motivo de a
  rede existir.
- **Não é um mercado de atenção.** Não existe métrica pública de engajamento, não existe
  impulsionamento pago e o feed não é vendido.
- **Não é um playground de automação.** Publicar, comentar e reagir por máquina é proibido
  (`docs/PUBLISHING_POLICY.md`).
- **Não é uma empresa que vende dado.** Minimização (LGPD) é regra de arquitetura, não promessa.

## Onde está cada assunto

| Assunto | Documento |
| --- | --- |
| Desenvolvimento local e onboarding | [`docs/LOCAL_DEVELOPMENT.md`](docs/LOCAL_DEVELOPMENT.md) |
| Arquitetura e módulos | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| API publicada | [`docs/API.md`](docs/API.md) |
| Banco, tabelas e migrações | [`docs/DATABASE.md`](docs/DATABASE.md) |
| Deploy, imagens e GitOps | [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) |
| Segurança, risco e antifraude | [`docs/SECURITY.md`](docs/SECURITY.md) |
| Identidade e contas | [`docs/IDENTITY.md`](docs/IDENTITY.md) |
| Jogos indie | [`docs/GAMES.md`](docs/GAMES.md) |
| Notificações: push por categoria e resumo semanal | [`docs/DIGEST.md`](docs/DIGEST.md) |
| Portal do desenvolvedor (API pública) | [`docs/DEVELOPERS_PORTAL.md`](docs/DEVELOPERS_PORTAL.md) |

## Licença

**GNU General Public License v3.0** — o texto completo está em [`LICENSE`](LICENSE).

Copyright (C) 2026 Énóis (Rede-ENois). Este programa é software livre: você pode redistribuí-lo e/ou
modificá-lo sob os termos da GPL-3.0, **sem nenhuma garantia**. O frontend segue a mesma licença, para
os dois repositórios ficarem sob o mesmo regime.

---


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
| `dev`   | H2 (memory) | `SPRING_PROFILES_ACTIVE=dev`             |
| `hml`   | MariaDB     | `SPRING_PROFILES_ACTIVE=hml`             |
| `prd`   | MySQL       | `SPRING_PROFILES_ACTIVE=prd`             |

There is no default profile: the packaged app refuses to boot without `SPRING_PROFILES_ACTIVE`.
`./mvnw spring-boot:run` runs as `dev`; pick another with `-Dspring-boot.run.profiles=<profile>`.

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
./mvnw spring-boot:run -Dspring-boot.run.profiles=hml
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
./mvnw spring-boot:run -Dspring-boot.run.profiles=prd
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

# Ferramentas opcionais de desenvolvimento (SMTP fake + UI do Mailpit):
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
# e-mails capturados em http://localhost:8025

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

## Two-factor with an authenticator app (TOTP)

Google Authenticator / Authy / 1Password support is **already implemented** in code — nothing to
write. What is missing is one environment variable, and without it the app correctly reports TOTP as
unavailable (the security screen hides the section instead of offering something that cannot work).

| What | Where |
|---|---|
| The only required setting | `REDE_SECURITY_ENCRYPTION_KEY` — base64 of 32 random bytes (AES-256-GCM) |
| How the app reads it | `SecretCipher` (used **only** by `TwoFactorService` today) |
| Status endpoint | `GET /auth/2fa/status` → `totpAvailable` |
| Setup / enable / disable | `POST /auth/2fa/totp/setup`, `POST /auth/2fa/totp/enable`, `POST /auth/2fa/totp/disable` (disable asks for the account password) |

**Generating the key:**

```bash
openssl rand -base64 32
```

Put the result in the backend's secret (same place as the other secrets in the cluster) as an
environment variable for the **pod** — the app resolves it as `REDE_SECURITY_ENCRYPTION_KEY`. There is
no database object and no migration to run.

**Keep this key stable.** It is the AES key that encrypts each user's TOTP secret: if it changes (or
is removed) after people have enabled the authenticator, their secrets become unreadable and they will
have to set the authenticator up again. Nothing else in the app uses this key today, so a rotation
affects only those users.

**Verifying it worked** (after the rollout):

1. `GET /api/auth/2fa/status` with a real session answers `"totpAvailable": true`.
2. On `/seguranca` the "authenticator app" section leaves the unavailable state and offers to
   configure it — it shows the QR/secret and one-time backup codes.
3. Enabling one, the next login stops at the 6-digit step (`POST /auth/2fa/verify`) before releasing
   the session. The code lives 30 seconds, the usual.

**Note:** do not confuse this with `VITE_IDENTITY_ENABLED` (front). That flag is about *civil identity*
verification (gov.br), a different feature; TOTP has no front flag at all — the screen reads the
status from the API and shows or hides itself.

## Media storage (ImageKit → S3)

Media has two storage providers and the choice is an **environment variable**, no code change:
`imagekit` (the current one) and `s3` (the new standard). Assets already stored keep their own URL in
the database, so **anything uploaded before the switch keeps being served by ImageKit** — that is the
fallback, and it is structural rather than a special code path.

> Status: the signed-upload path is implemented but **has not been run against a real bucket yet**,
> and migrating the existing objects is **not implemented yet**. First environment flip is the debut —
> read [docs/MIDIA-S3.md](docs/MIDIA-S3.md) (in Portuguese) before turning it on; it has the step by
> step, the checks and the rollback.

**What the bucket needs:** CORS allowing `PUT` from `https://enois.ia.br` (the browser uploads
straight to the bucket), public read on the prefix **or** CloudFront in front of it (the stored
`cdnUrl` has to be stable — a signed URL would expire), and `s3:PutObject`/`GetObject`/`DeleteObject`
for the pod. Credentials are never configured: the pod resolves them through the SDK's default chain
(IRSA in the service account).

| Variable | Example | Notes |
|---|---|---|
| `REDE_MEDIA_STORAGE` | `s3` | absent = `imagekit` |
| `REDE_MEDIA_S3_BUCKET` | `enois-media` | required when storage is `s3` — the app refuses to boot without it |
| `REDE_MEDIA_S3_REGION` | `sa-east-1` | defaults to `us-east-1` |
| `REDE_MEDIA_S3_PREFIX` | `uploads/` | defaults to `uploads/`; `/` means the bucket root |
| `REDE_MEDIA_S3_PUBLIC_BASE_URL` | `https://cdn.enois.ia.br` | empty = the bucket address |

**Turning it on:** set the variables → deploy → publish one photo on staging and check that
`POST /api/media/uploads` answers `"provider":"S3"` with an `uploadUrl` in the bucket/CDN, that the
browser does a `PUT` with the **exact** signed `Content-Type`, and that `cdnUrl` opens. **Rolling
back** is setting `REDE_MEDIA_STORAGE=imagekit` and restarting: objects already moved to S3 keep being
served through their stored address. A `403` on upload is almost always bucket CORS or a
`Content-Type` different from the signed one.

### Comunicado de recuperação (incidente do ImageKit, 19/10/2026)

Com o ImageKit comprometido, a escrita foi desativada (`UPLOADS_DISABLED` até o S3 estar
configurado). Quando o S3 entra (bean de upload assinado presente), o worker
`MediaRecoveryApologyWorker` envia **uma única vez** (marcador em `system_announcements`, V98)
uma notificação a todos os usuários pedindo desculpas pelo incidente e credita **100 faíscas**
por conta, com referência idempotente. Leitura das mídias antigas continua pelos URLs gravados
(ImageKit) e as novas passam a ir para o S3.

## Busca de GIF (seletor de comentário)

O seletor de GIF do comentário busca num provedor externo e devolve a URL da mídia — a mesma que o
comentário já aceita por link. **Renderizar GIF não precisa de chave** (é um `<img>` numa URL do
Giphy/Tenor, e isso continua valendo); o que precisa de chave é a **busca**.

| Variável | Observação |
|---|---|
| `REDE_GIFS_TENOR_KEY` | chave da **Tenor API v2**: Google Cloud → ativar a "Tenor API" → criar uma chave de API. É o provedor preferido. |
| `REDE_GIFS_GIPHY_KEY` | chave da **GIPHY API**: developers.giphy.com → *Create an App*. Entra quando só ele está configurado. |

Uma chave basta — com as duas, o Tenor responde primeiro. **A chave do GIPHY já está no default
do `application.properties`** (e no Secret `REDE_GIFS_GIPHY_KEY` do GitHub) — para ROTAR sem
redeploy, aplique o Secret `enois-gifs` no cluster com a chave nova (o manifesto já referencia
com `optional: true`). **Sem chave nenhuma nada quebra**: o
provedor da Comunidade responde — busca nos GIFs que a própria rede já compartilhou nos comentários
(mais recentes primeiro, dedupe por URL) — e o caminho de colar link segue funcionando ao lado.
Para a busca externa (Tenor v2 é grátis no Google Cloud → ativar a "Tenor API" → criar chave),
configure `REDE_GIFS_TENOR_KEY` e o provedor passa a responder primeiro.
A cota é do provedor, então o backend guarda o resultado por 10 minutos por termo (cache `gifSearch`)
e o endpoint exige sessão — anônimo não gasta a cota.

## Pendências de infraestrutura — o que precisa de quem opera

**A lista inteira, em ordem de urgência. Nada aqui é código: tudo já está no `main` esperando.**

| # | O que | Onde | Bloqueia |
|---|-------|------|----------|
| ~~0~~ | ~~Religar o GitHub Actions nos dois repositórios~~ — **feito em 18/10** | — | — |
| ~~0~~ | ~~Trust policy do front~~ — **resolvido em 18/10 pelo raposo**: os dois repositórios deployam automaticamente no push da `main` | — | — |
| 1 | Certificado (ACM) + DNS de `paginas.enois.ia.br` | ACM us-east-1 + DNS do `ia.br` | player dentro das páginas publicadas |
| 2 | Rotacionar a senha do banco (a antiga está no histórico do Git) | MariaDB + `git filter-repo` | segurança |
| 3 | `REDE_GIFS_TENOR_KEY` e `REDE_GIFS_GIPHY_KEY` | secrets do Deployment | busca externa de GIF (sem elas, só os GIFs da comunidade) |
| 4 | `REDE_SECURITY_ENCRYPTION_KEY` (base64 de 32 bytes) | secrets do Deployment | 2FA (a seção fica escondida sem ela) |
| 5 | `dump_rede.sql` no histórico do Git (dados pessoais reais) | `git filter-repo` + trocar as senhas dos afetados | LGPD |
| 6 | Sanitizar usuários e posts de teste em produção | precisa de acesso ao banco ou de um endpoint admin | — |

> **Pronto na main, esperando só os itens 0 e 0b (19 commits):** projetos no perfil (stack, repo,
> status, diário ou HTML), vulgo (aka), aplicações para empresas com marketplace, blogs, selos de
> post, reações novas (EYES/NOTE/FIRE/CLAP + remoção), o e-mail da denúncia com ações rápidas, a
> busca de GIF (chave do GIPHY embutida + fallback da comunidade), os controles de tema do front
> (a store faltara num commit), transparências, fixes de segurança e o 409 de páginas. No FRONT:
> a mesma lista + responsividade. Reexecutar os dois workflows depois de 0/0b — sem commit novo.

### 0.1 — GIFs: aplicar o Secret `enois-gifs` no cluster (opcional, rotação sem redeploy)

O manifesto já referencia (`optional: true`; sem ele, a busca funciona do mesmo jeito — a chave
do GIPHY está no default do `application.properties`):

```bash
kubectl -n <namespace> create secret generic enois-gifs \
  --from-literal=giphy-key='<chave nova do GIPHY>'
```

### 0.2 — Itens de segurança da borda (pentest de 18/10)

1. **TLS do ALB** — politica `ELBSecurityPolicy-TLS13-1-2-2021-06` (desliga TLS 1.0/1.1, liga 1.3,
   remove suites RSA estáticas: F-03/F-07).
2. **Ocultar `Server: awselb`** (F-08).
3. **AWS WAF** — não há WAF hoje; rate-based + managed rulesets.
4. **Rotacionar `LINKEDIN_CLIENT_SECRET`** — está em plaintext no manifest do GitOps; mover para
   Secret k8s (padrão do `enois-llm-secret`) e rotacionar no LinkedIn.
5. **DNS `developers.enois.ia.br`** — CNAME + TLS no ingress (hoje NXDOMAIN; o portal está pronto).

### 1. Content origin for post pages — `paginas.enois.ia.br`

**Why:** a post can be a "page" (HTML/CSS authored by the user, rendered in a phone-sized frame). The
frame needs `allow-scripts` so third-party players (YouTube and friends) can mount — and that is only
safe if the page document comes from an **origin separate from the app**, so user HTML can never reach
a reader's session. Until this host exists the front falls back to the embedded mode, which runs no
script at all (safe, but without inline players).

**Already done in the repo (nothing for you to do):** the backend serves the document at
`GET /api/pages/{postId}` with the policy in the **response headers** (`script-src 'none'` for the
page itself, `frame-src` limited to the known providers, `frame-ancestors` limited to the app,
plus `nosniff`/`no-referrer`). The gateway route for the new host is prepared in the GitOps repo
(`enois-content-httproute.yaml`, routing everything to the backend).

**What you need to do:**

1. **ACM (us-east-1)** — open the certificate `arn:aws:acm:us-east-1:818928662779:certificate/80055fdf-3de2-40a8-b23c-048d3f244f3c`
   and add the name **`paginas.enois.ia.br`**. ACM answers with a **DNS validation record**
   (`_<token>.paginas.enois.ia.br` → `_<token>.acm-validations.aws.`). Create exactly that record at
   the `ia.br` DNS provider, TTL 300, and wait for the certificate to be issued.
2. **DNS** — create **`paginas.enois.ia.br` as a CNAME to `enois.ia.br`**, TTL 300 (it then resolves to
   the same ALB; today `enois.ia.br` answers on 44.208.152.234 and 184.192.246.24). An A record with
   both IPs also works, but the CNAME follows changes.
3. **Then tell the dev session to flip the front:** set `VITE_CONTENT_ORIGIN=https://paginas.enois.ia.br`
   in the front deployment. That is the switch — with it the frame loads the served document (and
   players work); without it, the embedded mode stays.

**Verifying:** `curl -sI https://paginas.enois.ia.br/pages/<postId>` should answer `200` with
`content-type: text/html` and the `content-security-policy` header; and a YouTube link inside a
published page should play inside the frame.

### 2. Front deploy key (informational, already applied)

The front's workflow updates the GitOps manifest through a **deploy key** (`enois-frontend-gitops
(workflow do front)`) with write access **only** to `florestaencantada/integration_gitops`; the private
half lives in the front repository's `GITOPS_SSH_KEY` secret and nowhere else. Deployment keys had to
be enabled at the organisation level for this (`gh api -X PATCH orgs/florestaencantada -f
deploy_keys_enabled_for_repositories=true`). **To rotate:** delete that key in the GitOps repository,
generate another pair, add the public half as a deploy key (write) and update the `GITOPS_SSH_KEY`
secret in the front repo. Nothing else depends on it.

### Still to build (product work, not infrastructure)

The **content integrity system** (copy/spam detection, credibility ledger, appeals) is designed and
audited but not implemented; the migration draft for it needs a fresh version number, since migrations
have already reached V51.

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

Aviso de segurança decorrente disso: como o gateway encaminha `/api/*` **sem autenticação**, tudo que o
backend expõe fica público em `https://enois.ia.br/api/...` — inclusive `/api/actuator/*` (que chega em
`/actuator/*`) e `/api/api/openapi.json`. Por isso os defaults abaixo são restritivos.

## Configuração e ações manuais

### Variáveis de ambiente (runtime do Deployment)

| Variável | Obrigatória | Observação |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sim | `qa` no cluster |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | sim | vêm do Secret `socialthing-db-secrets` |
| ~~`REDE_ADMIN_EMAILS`~~ | não | **ignorada**. Admin é a flag `users.is_admin` (V171), nunca username nem e-mail (ver passo 2). |
| `REDE_FRONTEND_URL` | sim | base do front; o callback OAuth volta em `${REDE_FRONTEND_URL}/auth/callback` |
| `REDE_BACKEND_URL` | sim | base pública do back; monta os redirect URIs do OAuth |
| `FRONTEND_URL` | sim | link do e-mail de reset de senha: `${FRONTEND_URL}/auth/reset?token=...` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | sim | SMTP dos e-mails de reset de senha e de confirmação de cadastro. Sem isso nenhum e-mail sai (o erro é logado e engolido). |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | p/ login Google | |
| `APPLE_CLIENT_ID` / `APPLE_TEAM_ID` / `APPLE_KEY_ID` / `APPLE_PRIVATE_KEY` | p/ login Apple | `APPLE_PRIVATE_KEY` é o **conteúdo** da chave `.p8` (PEM PKCS#8), com `\n` ou quebras reais. Ela é usada para montar o `client_secret` (JWT ES256) exigido na troca do `code`. |
| `IMAGEKIT_URL_ENDPOINT` / `IMAGEKIT_PUBLIC_KEY` / `IMAGEKIT_PRIVATE_KEY` | p/ mídia | |
| `REDE_GIFS_TENOR_KEY` / `REDE_GIFS_GIPHY_KEY` | p/ busca de GIF | chave de um dos provedores (Tenor ou GIPHY). Sem nenhuma, o seletor de GIF do comentário avisa que a busca está indisponível — colar link continua funcionando. Como obter: seção "Busca de GIF" |
| `CORS_ALLOWED_ORIGINS` | não | padrão `*` |
| `SPRINGDOC_ENABLED` | não | padrão `false`. `true` liga `/api/openapi.json` + Swagger UI (use só em dev/VPN) |
| `MANAGEMENT_ENDPOINTS` | não | padrão `health`. Para métricas use `health,prometheus` **e bloqueie `/api/actuator/*` no gateway** |
| `SUPPORT_EMAIL` | não | e-mail do time que recebe os pedidos de suporte. Sem ele o pedido ainda fica registrado e visível em `/admin/support` |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | p/ push no celular | par de chaves do Web Push (ver passo 7 abaixo). Sem elas o push fica desligado e só as notificações dentro do app funcionam |
| `REDE_SECURITY_ENCRYPTION_KEY` | **sim** (fora do `dev`) | chave de 32 bytes em base64 (`openssl rand -base64 32`) que cifra os segredos TOTP em repouso (AES-GCM) e assina o desafio do cadastro. **Fora do perfil `dev` o pod não sobe sem ela** (o mesmo valor em todas as réplicas) |
| `VAPID_SUBJECT` | não | contato do emissor VAPID, no formato `mailto:contato@dominio` (padrão `mailto:suporte@enois.ia.br`) |

### Passo a passo das ações manuais

1. **Criar/atualizar o Secret do banco** (`socialthing-db-secrets`) com as chaves `host`, `port`, `dbname`,
   `username`, `password`. Sem isso o pod não sobe (Flyway falha no boot).
2. **Admins são a flag `users.is_admin`** (V171 marcou as contas da equipe existentes). Conferir com
   `select id, username from users where is_admin = true`. Um admin promove/revoga outro pelo painel
   (`POST /admin/admins`, `POST /admin/admins/{username}/revoke`); o primeiro admin de uma base nova é
   `update users set is_admin = true where id = '<user id>'`. `REDE_ADMIN_EMAILS` não concede mais nada.
3. **Configurar o SMTP** (`MAIL_*`) e `FRONTEND_URL` para o reset de senha funcionar de ponta a ponta.
4. **Configurar o OAuth**: criar as credenciais no Google Cloud e no Apple Developer e cadastrar os
   redirect URIs exatamente como
   `${REDE_BACKEND_URL}/api/auth/oauth/google/callback` e `.../apple/callback`. Para Apple, use
   `response_mode=form_post` (o backend aceita o callback via POST) e informe a chave `.p8` em
   `APPLE_PRIVATE_KEY` — sem ela a troca do `code` responde `invalid_client` e o login Apple não
   conclui. Depois de configurar, valide um login real: o backend não simula provedor.
5. **Rotacionar segredos vazados** (ver seção "Segurança").
6. Verificar o rollout: `kubectl -n forest-apps get pods -l app=socialcoiso-back` e
   `kubectl -n forest-apps logs deploy/socialcoiso-back`.
7. **Ligar as notificações push no celular (Web Push)**: gerar um par de chaves VAPID
   (`npx web-push generate-vapid-keys`), guardar a privada como Secret e definir `VAPID_PUBLIC_KEY`,
   `VAPID_PRIVATE_KEY` (e `VAPID_SUBJECT`) no Deployment. A chave **pública** é entregue ao app por
   `GET /push/config` — não precisa de variável no front. Depois de trocar as chaves, todas as inscrições
   antigas param de receber push (o usuário reativa no menu da conta); as inscrições expiradas (404/410 do
   serviço de push) são removidas sozinhas. Vale para Chrome/Edge/Firefox no Android e desktop; no iOS só
   funciona com o app **instalado na tela de início** (iOS 16.4+).

### Migrações travadas (rollout parado servindo versão antiga)

O DDL do MariaDB não é transacional: quando uma migração quebra **no meio**, o Flyway deixa a versão marcada
como `FAILED` em `flyway_schema_history` e passa a **recusar toda migração seguinte nesse banco**. O pod novo
não sobe, o ArgoCD não consegue promover e o cluster segue servindo a imagem antiga — sem erro visível de fora
(foi o que aconteceu com a V17, que tentava criar uma FK entre collations diferentes).

`FlywayMigrationConfig` limpa esse marcador no boot: se houver alguma migração em estado `FAILED`, roda
`repair()` (só remove entradas `FAILED` e realinha checksums) e migra de novo. Se ainda assim o pod não subir,
o caminho manual é:

```bash
kubectl -n forest-apps logs deploy/socialcoiso-back | grep -i -A20 flyway   # ver o erro real
delete from flyway_schema_history where version = '<versao que falhou>';    # ou flyway repair
```

## Fluxos de produto (referência rápida)

- **Vagas**: `POST /jobs` (exige reputação **> 150**) cria; `GET /jobs` lista (com `applied`/`saved`/`owner` por
  usuário); `GET /jobs/mine` = vagas que publiquei (traz `applicationsCount`); `GET /jobs/applications` = vagas
  em que me candidatei; `POST /jobs/{id}/applications` é a inscrição (`409` se repetida); `PUT`/`DELETE /jobs/{id}`
  são do autor. No app: `/vagas` (listar/criar/inscrever), `/vagas/{id}` (detalhe + inscrever/salvar/excluir) e
  `/vagas/minhas` (minhas vagas e minhas candidaturas).
- **Erros com o motivo certo**: a API sempre devolve o `code`/`message` do backend — antes, qualquer 401/403
  sem sessão virava "É necessário entrar para ver isso" e era assim que **senha errada no login** aparecia
  (a resposta já dizia "Email ou senha inválidos"). Também: 403 não desconecta mais o usuário (era o efeito
  colateral de tratar 403 como sessão), e `PATCH /me/profile` valida o tamanho de cada campo devolvendo
  `400 FIELD_TOO_LONG` **com o nome do campo** ("O campo bio pode ter no máximo 1024 caracteres") em vez de
  estourar a coluna e virar um 409 genérico.
- **Eventos vencidos são sanitizados**: o fim do evento vem da própria data (ISO `yyyy-MM-dd`, ou
  `dd/MM/yyyy`; o horário é opcional e sem ele vale o fim do dia). Passado o fim, o evento **sai da vitrine**
  (`GET /events` só devolve o que ainda vai acontecer; `includePast=true` traz o histórico com `expired: true`)
  e não aceita mais confirmação de presença (`400 EVENT_FINISHED`). Depois de **24h de folga**, o
  `EventSanitizationWorker` (agendado, roda no boot e a cada hora) apaga as presenças — dado pessoal de quem
  confirmou — guardando apenas o total agregado em `events.final_attendees_count`, e marca `sanitized_at`
  (idempotente). Após **365 dias** de retenção o evento é expurgado por inteiro. Data que não pode ser
  interpretada **não vence**: apagar dado pessoal por palpite seria pior que manter.
- **Candidatura com formulário próprio**: quem publica define campos personalizados (`applicationFields` no
  corpo de `POST /jobs`: `label`, `type` — TEXT, TEXTAREA, NUMBER ou LINK — e `required`), até 25, sem rótulos
  repetidos. `POST /jobs/{jobId}/applications` recebe `{message?, answers:[{fieldId,value}]}`: campos
  obrigatórios barram o envio (`400 MISSING_REQUIRED_ANSWERS`), o valor é validado pelo tipo
  (`400 INVALID_ANSWER`) e a candidatura grava um **retrato do perfil do candidato** naquele momento (nome,
  headline, local, habilidades, **tempo de experiência**, reputação e verificação). O empregador recebe por
  **e-mail** e na **conversa direta** (criada na hora entre os dois) e vê tudo em
  `GET /jobs/{jobId}/applications` (só o autor). Candidatar-se à própria vaga é recusado
  (`400 CANNOT_APPLY_TO_OWN_JOB`) e repetir devolve `409 ALREADY_APPLIED`. O detalhe da vaga traz
  `author` (nome, usuário, reputação e verificação) e os campos do formulário.
- **Busca booleana nas vagas**: `GET /jobs?query=...` entende `AND`/`E`, `OR`/`OU`, `NOT`/`NÃO`/`NAO`,
  `"frase exata"`, `-termo` (exclui), `+termo` (exige) e parênteses — sem operador, os termos valem como E
  (precedência: NOT > AND > OR). Os termos casam com título, empresa, local, tipo, nível e habilidades.
  A entrada nunca dá erro de sintaxe (parêntese/aspas soltos viram texto literal); o que existe é um teto de
  64 termos e 12 níveis de parênteses, e `%`/`_` são escapados para não virar coringa. Os filtros de nível,
  tipo, local e habilidades são **combinados** com a busca (antes eram um `else if` em cadeia e se anulavam).
- **Moeda da vaga**: o corpo de `POST /jobs` aceita `currency` (`BRL` padrão, `USD`, `EUR`) e `salary` só com os
  números — a aplicação remove o símbolo que o autor digitar e a exibição remonta o texto com a moeda escolhida
  (`R$ 8.000 – R$ 12.000`, `US$ 90,000`). O símbolo nunca fica duplicado nem trocado, inclusive em vagas antigas.
- **Acordo com a comunidade**: além da reputação, `POST /jobs` exige `communityAgreement: true` — a declaração de
  que a vaga é real e respeita as regras da comunidade. Sem ela a resposta é `400 COMMUNITY_RULES_NOT_ACCEPTED`,
  e o instante da declaração fica em `jobs.community_agreed_at`.
- **Bloqueio de usuário**: `POST /network/blocks/{userId}` bloqueia (definitivo), `DELETE …/{userId}` desbloqueia e
  `GET /network/blocks` lista. Bloquear esconde os dois lados em todo lugar — feed, comentários, notificações,
  busca e sugestões de conexão — derruba a conexão existente e impede nova solicitação (`403 BLOCKED`). O status
  de conexão (`GET /network/connections/{userId}/status`) devolve `blocked` para a UI alternar o botão.
  No app: botão no perfil (`/perfil/{username}`) e a lista em `/bloqueados`, no menu da conta.
- **Suporte**: `POST /support/requests` (autenticado) abre o pedido; `GET /admin/support` (admin) lista a fila em
  aberto. No app: `/suporte`, alcançável pelo menu da conta.
- **Consentimento**: `POST /auth/signup` exige `acceptedTerms: true` (termos + 18 anos); o instante do aceite é
  gravado em `users.terms_accepted_at`. Sem esse campo a resposta é `400 TERMS_NOT_ACCEPTED`.
- **Link compartilhado**: `GET /feed/posts/{postId}` e `GET /feed/posts/{postId}/reactions` são **públicos** —
  é para onde o link de compartilhamento aponta e o destinatário não tem sessão. A sessão, quando existe, só
  acrescenta o estado do visitante (reação) e esconde quem ele bloqueou. Escrever (comentar, reagir, salvar,
  compartilhar) continua exigindo sessão.
- **Confirmação de e-mail**: o cadastro **não** emite sessão. `POST /auth/signup` responde
  `{email, verificationRequired: true, emailSent}` e manda um link (`/auth/verify?token=...`) com token de
  hash SHA-256, validade de 24h e uso único; o instante da confirmação fica em `users.email_verified_at`.
  `POST /auth/email/verify` confirma **e já devolve a sessão**; `POST /auth/email/resend` reenvia (3 por hora
  por usuário, contando o cadastro, e no máximo 5 por 15 min por IP) e responde 202 mesmo para e-mail
  inexistente, para não virar oráculo de contas. Login de conta pendente responde `403 EMAIL_NOT_VERIFIED`, e
  qualquer endpoint autenticado responde o mesmo via `CurrentUserResolver` (segunda barreira). Contas criadas
  **antes** desta migração foram marcadas como verificadas (ver V22) para ninguém perder o acesso; quem entra
  por Google/Apple nasce verificado, porque o provedor confirma o e-mail.
- **Segundo fator pelo provedor**: quem entra por Google/Apple fica com `users.federated_second_factor_at`
  preenchido — conta verificada e 2FA satisfeito, mas **sem** um código nosso para desafiar (por isso o
  `requiredMethod` do login considera apenas TOTP e código por e-mail; o 2FA do provedor aparece como
  `providerSecondFactor` no `GET /auth/2fa/status`).
- **Login social**: todo fluxo começa em `GET /auth/oauth/{provider}/start`, que registra `state` + `nonce` no
  servidor (`oauth_authorization_requests`) e devolve a URL de autorização. O callback confere o `state`
  (existe, mesmo provedor, dentro de 15 min) e o `nonce` dentro do id_token, além de assinatura, emissor,
  audience e validade. **Vínculo com conta existente**: se já existe conta com o e-mail **e o provedor afirma
  que ele é verificado** (claim `email_verified`), o login vincula as duas — a conta entra com o e-mail
  confirmado e com o segundo fator delegado ao provedor (Google/Apple responde pela segunda etapa), sem
  duplicar conta e sem pedir configuração. E-mail apenas *declarado*, sem essa garantia, **não** vincula:
  responde `409 ACCOUNT_EXISTS_UNVERIFIED_EMAIL`. `POST
  /auth/oauth/{provider}/link` inicia a vinculação (o callback que usar esse state vincula em vez de entrar),
  `GET /auth/oauth/providers` lista os vínculos e `DELETE /auth/oauth/{provider}/link` desvincula — recusando
  com `409 LAST_LOGIN_METHOD` quando seria o último jeito de entrar (conta social não tem senha utilizável:
  `user_credentials.password_usable = false`). `POST /auth/oauth/{provider}/token` passou a exigir `state`.
- **Conta verificada e credibilidade**: conta verificada = **e-mail confirmado OU segundo fator ativo**
  (`users.email_verified_at`, `totp_enabled_at`, `email_otp_enabled_at`). Sem nenhum dos dois a reputação é
  **multiplicada por zero** (regra em `ReputationService`, fonte única: vale para o perfil, o ranking e a regra
  de publicar vaga) e as funcionalidades protegidas respondem `403 EMAIL_NOT_VERIFIED` no `CurrentUserResolver`.
- **Segundo fator (2FA)**: `GET /auth/2fa/status` mostra o estado. Por aplicativo authenticator (TOTP RFC 6238,
  segredo cifrado com `REDE_SECURITY_ENCRYPTION_KEY`): `POST /auth/2fa/totp/setup` devolve o segredo e a URI
  `otpauth://`, `POST /auth/2fa/totp/enable` confirma um código e devolve 8 códigos de recuperação (uso único,
  guardados como hash, mostrados uma vez). Por e-mail: `POST /auth/2fa/email/request` envia um código de 6
  dígitos e `POST /auth/2fa/email/confirm` ativa. Desativar exige a senha. No login, quem tem 2FA recebe `202`
  com `{twoFactorRequired, challengeId, method}` em vez da sessão e conclui em `POST /auth/2fa/verify`
  (`POST /auth/2fa/resend` reenvia o código por e-mail). O desafio vale 10 minutos, é de uso único e morre
  depois de 5 tentativas; um código de recuperação também conclui o login. No app: `/seguranca`, alcançável
  pelo menu da conta e pelo botão "Verificar identidade" no próprio perfil.
- **Push no celular**: `GET /push/config` (público) informa se o push está ligado e devolve a chave pública
  VAPID; `POST /push/subscriptions` (autenticado) registra o dispositivo (`endpoint` + `keys.p256dh`/`keys.auth`)
  e `DELETE /push/subscriptions?endpoint=...` remove. Toda notificação criada por `NotificationService` também
  é enviada como push depois do commit (`PushNotificationListener` → `WebPushSender`); falha de push é logada e
  nunca quebra o fluxo que gerou a notificação. No app: menu da conta → "Ativar notificações no celular".

## Credibilidade (reputação) — modelo e antifraude

Fonte única: `profile/application/ReputationService.java` (recalculada na leitura; a coluna
`users.reputation` é só a foto usada pelo ranking, atualizada periodicamente por
`ReputationRefreshWorker`).

**Componentes internos (a nota exibida continua 0–1000):**

| Componente | Regra | Teto |
|---|---|---|
| T — trajetória | experiência/formação/certificação com retorno decrescente (√) | 195 |
| R — relações | conexões (√) + recomendações **não recíprocas** (√) | 300 |
| C — contribuição | **pessoas distintas** que interagiram (√), comunidades, eventos, mentoria concluída | 420 |
| K — consistência | meses desde a criação (máx. 48) | 120 |
| I — integridade | **só fato confirmado** (APPROVED/AUTO_PENALIZED), dedup por conteúdo, com teto | −600 |

Conta verificada (e-mail **ou** 2FA) é requisito de identidade: sem ela, credibilidade = 0.

**Regras antifraude (auditoria de 19/10/2026):** denúncia pendente não penaliza; N denúncias do
mesmo conteúdo contam como um fato; recurso aceito marca a denúncia como DISMISSED (a nota
volta); recomendações mútuas valem 0; autointeração não conta; comentário apagado devolve o
contador; presenças de eventos são agregadas antes da purga de PII; tetos por categoria no
currículo (10 por tipo); limite de frequência por conta em denúncias (10/h) e recomendações
(20/dia) via `shared/security/ActionThrottle`.

**Limiares:** publicar vaga exige **> 150** (JobService/JobPublishingPolicy/front alinhados);
candidatar-se a moderador exige **pelo menos 200** (`rede.moderation.moderator-min-reputation`, padrão 200 desde 19/10).

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

### Já corrigido

- **Credibilidade zerada sem verificação**: `ReputationService` passou a multiplicar a reputação por zero
  quando a conta não tem e-mail confirmado **nem** segundo fator — a regra fica numa fonte única, então
  perfil, ranking e o gate de publicar vaga concordam.
- **Nenhum e-mail era enviado**: `InfrastructureConfig` declarava um `JavaMailSender` com
  `new JavaMailSenderImpl()` e sem host/porta. Por ser um bean do usuário, ele fazia o
  `MailSenderAutoConfiguration` recuar (`@ConditionalOnMissingBean`) e o envio caía nos padrões do
  JavaMail (`localhost:25`), **ignorando `spring.mail.*`**. Resultado: reset de senha e confirmação de
  cadastro nunca saíam, com o erro apenas logado. O bean foi removido e o SMTP configurado passou a
  valer. O indicador `management.health.mail` também foi desligado: ele abria conexão com o SMTP a cada
  consulta e, com o servidor fora do ar, `/actuator/health` respondia 503 — tirando do ar uma aplicação
  saudável por causa de e-mail.
- **Mentoria sem agendamento**: `nextSlot` era sempre `null` (hardcoded) e o botão "Agendar" do app não
  tinha ação — agora o campo traz o próximo horário livre real e a agenda pode ser publicada e usada.
- **Login social vinculava conta pelo e-mail**: `findOrCreateUser` ligava a identidade a qualquer conta
  que tivesse o mesmo e-mail declarado pelo provedor, sem prova de posse. Agora esse caso responde
  `409 ACCOUNT_EXISTS_LINK_REQUIRED` e a vinculação é explícita, com `state`/`nonce` no servidor.

- **Doc da API público**: `GET /api/openapi.json` (stub manual) e `GET /api/api/openapi.json` + Swagger UI
  (springdoc) respondiam 200 sem autenticação, entregando o mapa completo da API. Agora o stub foi removido
  e o springdoc fica desligado por padrão (`SPRINGDOC_ENABLED=false`).
- **Actuator público**: `/api/actuator/prometheus` devolvia ~110 KB de métricas (JVM, pool de conexões,
  URIs) e `/api/actuator/metrics` idem. Agora só `health` é exposto e o índice `/actuator` também.
- **Sem headers de segurança**: adicionados HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Permissions-Policy` e CSP restritiva (`default-src 'none'`) nas respostas
  da API — ver `SecurityHeadersFilter`.
- **Email de terceiros** em `GET /users/{username}`, `/users/by-id/{id}`, `/users/search` e `/ranking`
  (era público, sem login): removido da projeção pública.
- **`/admin/reports/**`** exigia apenas sessão: agora exige admin (`REDE_ADMIN_EMAILS`).

Testes de regressão cobrem os dois primeiros itens em `ContractControllerTests`.

## Verificação de identidade e Login gov.br

O Énóis tem uma camada de **verificação de identidade civil** independente de fornecedor
(`IdentityVerificationProvider`), com estados explícitos e **fail-safe**: sem provedor autorizado
configurado, a verificação aparece como **indisponível** e nada é marcado como verificado. O Énóis
**não** guarda CPF, documentos, selfie, biometria nem tokens do provedor — só o estado, o provedor e
as datas.

O provedor do **Login Único do gov.br** (OAuth 2.0 / OpenID Connect, Authorization Code) já está
implementado em `GovBrIdentityProvider`. Ele **só fica ativo com credenciais**; sem elas, o registro
o considera indisponível.

### O que já está implementado
- `GovBrIdentityProvider`: monta a URL de autorização com `state` **imprevisível e de uso único**,
  troca o `code` por token **no backend** (Basic `client_id:client_secret`) e lê `/userinfo`. Só o
  `sub` confirmado leva a `VERIFIED`; falha técnica vira `ERROR` (nunca aprova).
- Endpoints: `GET /identity` (estado), `POST /identity/verification` (inicia → devolve
  `redirectUrl`), `POST /identity/verification/refresh` (provedores por consulta),
  `DELETE /identity/verification` (desvincula), `GET /identity/eligibility` (política de vaga) e
  **`GET /identity/govbr/callback`** (retorno do provedor — público de propósito; a tentativa é
  identificada pelo `state`).
- Política central: publicar vaga exige `reputação >= 150` **E** identidade verificada — a exigência
  de identidade fica atrás de `IDENTITY_ENFORCEMENT_ENABLED` (padrão `false`).

### Configuração (variáveis de ambiente)
| Variável | Descrição |
|---|---|
| `GOVBR_BASE_URL` | `https://sso.staging.acesso.gov.br` (homologação) ou `https://sso.acesso.gov.br` (produção) |
| `GOVBR_CLIENT_ID` | `client_id` da credencial do gov.br |
| `GOVBR_CLIENT_SECRET` | `client_secret` da credencial |
| `GOVBR_REDIRECT_URI` | URL de retorno cadastrada: `https://enois.ia.br/api/identity/govbr/callback` |
| `IDENTITY_ENFORCEMENT_ENABLED` | `true` para exigir identidade verificada ao publicar vaga (padrão `false`) |
| `VITE_IDENTITY_ENABLED` (front) | `true` para **mostrar** a seção de identidade no app. Padrão oculto: toda a UI fica pronta mas invisível até a funcionalidade estar homologada |

Sem `GOVBR_CLIENT_ID`/`SECRET`/`REDIRECT_URI` preenchidos, o provedor fica **indisponível** — e é
isso que aparece no app.

### Como habilitar (passo a passo)
1. **Formalizar o pedido** ao programa gov.br por um **representante legal** da Énóis (órgãos e
   entidades públicas/privadas com serviço de relevância/benefício social). É a etapa administrativa.
2. Receber as credenciais de **homologação** (`client_id`/`client_secret`) e **cadastrar a
   redirect URI** `https://enois.ia.br/api/identity/govbr/callback`.
3. Preencher as variáveis acima no ambiente (staging) e validar o fluxo: botão → gov.br → retorno →
   estado `VERIFIED` só após confirmação do provedor.
4. **Homologação**: enviar telas/fluxo para validação oficial e obter as credenciais de **produção**;
   trocar `GOVBR_BASE_URL` para `https://sso.acesso.gov.br`.
5. Ligar `IDENTITY_ENFORCEMENT_ENABLED=true` quando quiser exigir identidade para publicar vaga.

> Observação: o **Login Único (SSO)** usa OAuth 2.0/OIDC com `client_id`/`client_secret` e
> `redirect_uri`. As **chaves PGP** e o manual de **assinatura eletrônica (ITI)** são de outra
> integração (assinatura de documentos), não do Login Único.

> Status: **implementado no backend e desligado** até existirem credenciais. A integração **não** é
> declarada concluída enquanto não passar pela homologação do gov.br. Não há simulação em produção.
