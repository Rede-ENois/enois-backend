# Tarefas Bloqueadas - Dependências Externas

Tarefas que exigem integração com serviços externos, credenciais reais ou decisões de infraestrutura.

## Prioridade Alta

### 1. Validar token real do Google OAuth

**Arquivos afetados:** `AuthController.java`, `SocialLoginService.java`

O endpoint `GET /api/auth/oauth/google/callback` recebe `code` e `state` mas não valida com o Google.
Implementação necessária:
- Adicionar dependência `com.google.api-client:google-api-client` no pom.xml
- Criar `GoogleTokenValidator` implementando `TokenValidator` interface
- Trocar código de autorização por token de acesso via Google OAuth2 API
- Validar `id_token` ou buscar perfil na Google People API
- Extrair `sub` (externalUserId), `email`, `name`
- Chamar `SocialLoginService.processOAuthCallback()`

### 2. Validar token real da Apple OAuth

**Arquivos afetados:** `AuthController.java`, `SocialLoginService.java`

`GET /api/auth/oauth/apple/callback` - mesmo fluxo do Google.
Implementação necessária:
- Adicionar `com.apple:apple-signin-java` ou implementar validação manual do JWT da Apple
- Validar `id_token` JWT com chave pública Apple (.well-known/jwks.json)
- Extrair `sub`, `email` do token
- Chamar `SocialLoginService.processOAuthCallback()`

### 3. Envio de email real para reset de senha

**Arquivos afetados:** `AuthService.java`, `application.properties`

`POST /api/auth/password/forgot` gera token mas não envia email.
Implementação necessária:
- Adicionar `spring-boot-starter-mail` no pom.xml
- Configurar `spring.mail.*` no application.properties (SMTP real)
- Criar `EmailService` com template de email contendo link de reset
- O link deve ser: `{FRONTEND_URL}/auth/reset?token={token}`
- Configurar `FRONTEND_URL` como variável de ambiente

### 4. CDN purge real + Object Store

**Arquivos afetados:** `MediaDeletionWorker.java`, `DevMediaStorageGateway.java`

`DELETE /api/media/{mediaId}` marca para deleção mas não executa purge real.
Implementação necessária:
- Implementar `MediaStorageGateway` real com S3 SDK (`aws-java-sdk-s3`)
- Implementar `StorageUrlFactory` real com URLs de CDN
- Configurar `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_BUCKET`, `CDN_DOMAIN`
- Configurar presigned URLs reais para upload
- Workers de deleção com purge real no CDN CloudFront/Cloudflare

## Prioridade Média

### 5. Configuração do banco MySQL/MariaDB

**Arquivos afetados:** `application.properties`, `application-prod.properties`, `application-qa.properties`

Configurar conexão real com banco gerenciado.
Tarefas:
- Criar `application-prod.properties` com URL JDBC MySQL
- Criar `application-qa.properties` com URL JDBC MariaDB
- Configurar pool de conexões (HikariCP)
- Configurar Flyway com `baseline-on-migrate=true` em produção

### 6. Redis como cache distribuído

**Arquivos afetados:** `pom.xml`, `RedisFeedCacheRepository.java`, `application.properties`

Substituir `InMemoryFeedCacheRepository` por Redis quando houver múltiplas instâncias.
Tarefas:
- Adicionar `spring-boot-starter-data-redis` no pom.xml
- Criar `RedisFeedCacheRepository` implementando `FeedCacheRepository`
- Configurar `spring.data.redis.*` no application.properties
- Alternar entre implementações via `@ConditionalOnProperty`

### 7. OAuth state validation com Redis/banco

**Arquivos afetados:** `AuthController.java`, `OAuthStateRepository.java`

Armazenar `state` gerado em `oauth/{provider}/start` para validar no callback.
Tarefas:
- Criar tabela `oauth_states` ou usar Redis com TTL
- Armazenar `state`, `provider`, `redirectUri`, criado em
- Validar no callback e remover após uso
- Prevenir ataques CSRF no fluxo OAuth

### 8. Rate limiter real com Redis

**Arquivos afetados:** `application.properties`, rede/infra

Rate limiter atual é em memória. Com múltiplas instâncias, precisa ser distribuído.
Tarefas:
- Configurar Redis como backend do Resilience4j RateLimiter
- Substituir `io.github.resilience4j.ratelimiter` por implementação com Redis

## Prioridade Baixa

### 9. Monitores de saúde externos

**Arquivos afetados:** `OperationalController.java`

`GET /api/health` retorna apenas `{"status":"UP"}` sem verificar dependências.
Tarefas:
- Adicionar health checks para banco, Redis, S3, CDN
- Usar `HealthContributorRegistry` do Spring Boot Actuator
- Adicionar `spring-boot-starter-actuator` no pom.xml

### 10. Geração de notificações para mais eventos

**Arquivos afetados:** `NotificationOutboxEventHandler.java`

Expandir notificações para: nova conexão, novo seguidor, post de amigo, etc.
Tarefas:
- Adicionar handlers para `ConnectionAccepted`, `UserFollowed`, etc.
- Criar eventos de domínio específicos no módulo network
- Configurar preferências de notificação por usuário

### 11. Atualizar OpenAPI manual (OperationalController)

**Arquivos afetados:** `OperationalController.java`

O OpenAPI manual em `/api/openapi.json` está desatualizado.
Tarefas:
- Sincronizar com endpoints reais
- Ou remover em favor do springdoc automático em `/v3/api-docs`
