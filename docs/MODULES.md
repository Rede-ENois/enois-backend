# Módulos — Documentação Completa de Funções

## 1. RedeApplication (Main)

**Arquivo**: `src/main/java/com/socialcoisa/rede/RedeApplication.java`

| Função | Descrição |
|---|---|
| `main(String[] args)` | Entry point da aplicação Spring Boot |

---

## 2. Shared (Infraestrutura Compartilhada)

### InfrastructureConfig

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/application/InfrastructureConfig.java`

| Função | Descrição |
|---|---|
| `systemClock()` | Bean que fornece `Clock.systemUTC()` |
| `idGenerator()` | Bean `IdGenerator` que gera IDs no formato `post_<uuid>` |
| `oAuthProperties()` | Bean que carrega properties `rede.oauth.*` como `OAuthProperties` |

### CorsConfig

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/CorsConfig.java`

| Função | Descrição |
|---|---|
| `corsConfigurer()` | Configura CORS permitindo origens de `rede.cors.allowed-origins` |

### Processamento compartilhado de caminhos

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/RequestPath.java`

Os filtros web e o interceptor administrativo usam uma leitura compartilhada do caminho da
requisição. A leitura considera o contexto da aplicação e não reescreve URI, query ou corpo.
A seleção de permissões de administrador e moderador permanece no `AdminRouteInterceptor`. A classificação
usa os padrões e segmentos do Spring MVC. Entradas com escape inválido que alcancem a cadeia
de filtros recebem 400 antes do roteamento. Os testes integrados também conferem que uma
requisição sem permissão não executa o método protegido do controller.
As rotas públicas e o comportamento de caminhos fora da superfície continuam cobertos por testes.

### OpenApiConfig

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/OpenApiConfig.java`

| Função | Descrição |
|---|---|
| `redeOpenAPI()` | Configura info OpenAPI (título, versão) e tags |

### GlobalExceptionHandler

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/GlobalExceptionHandler.java`

| Função | Descrição |
|---|---|
| `handleApiException(ApiException)` | Trata erros 400/401/403/404/409 da `ApiException` |
| `handleBadRequest(Exception)` | Erro 400 para validação de entrada |
| `handleRateLimit(RequestNotPermitted)` | Erro 429 para rate limiting |
| `handleUnavailable(RuntimeException)` | Erro 503 para circuit breaker/bulkhead |
| `handleUnexpected(Exception)` | Erro 500 para exceções inesperadas |

### CurrentUserResolver

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/CurrentUserResolver.java`

| Função | Descrição |
|---|---|
| `resolve(String sessionToken)` | Resolve usuário a partir do token de sessão |
| `resolve(String userId, String sessionToken)` | Resolve a partir de userId ou token |
| `resolveById(String userId)` | Resolve usuário apenas pelo ID |

### RequestObservabilityFilter

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/RequestObservabilityFilter.java`

| Função | Descrição |
|---|---|
| `doFilterInternal(...)` | Loga cada requisição com `X-Request-Id` |
| `requestId(HttpServletRequest)` | Extrai ou gera ID de requisição |

### CursorCodec

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/CursorCodec.java`

| Função | Descrição |
|---|---|
| `decode(String cursor)` | Decodifica cursor base64 para Instant |
| `encode(Instant instant)` | Codifica Instant para string base64 |

### OperationalController

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/web/OperationalController.java`

| Função | Descrição |
|---|---|
| `health()` | GET /api/health → `{"status": "UP"}` |
| `openapi()` | GET /api/openapi.json → OpenAPI specification |

### Outbox

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/outbox/OutboxWorker.java`

| Função | Descrição |
|---|---|
| `processPendingEvents()` | Scheduled task que processa eventos pendentes da outbox |

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/outbox/OutboxEventPersistenceAdapter.java`

| Função | Descrição |
|---|---|
| `append(String eventType, String aggregateId, String payload)` | Adiciona evento à tabela outbox_events |

### Audit

**Arquivo**: `src/main/java/com/socialcoisa/rede/shared/audit/AuditLogPersistenceAdapter.java`

| Função | Descrição |
|---|---|
| `record(UserId actorId, String action, String targetId, String metadata)` | Registra log de auditoria |

### ApiResponse / PageResponse / ErrorResponse

**Arquivos**: shared/web/

| Classe | Descrição |
|---|---|
| `ApiResponse<T>` | Resposta padrão da API |
| `PageResponse<T>` | Resposta paginada com `data` e `nextCursor` |
| `ErrorResponse` | Resposta de erro com `error`, `message`, `status` |
| `StatusResponse` | Resposta simples com status |
| `ApiException` | Exceção com status HTTP e mensagem |
| `CurrentUser` | Record contendo `UserId id` |

---

## 3. Identity (Autenticação)

### AuthService

**Arquivo**: `src/main/java/com/socialcoisa/rede/identity/application/AuthService.java`

Implementa: `LoginUseCase`, `SignupUseCase`, `LogoutUseCase`, `GetAuthenticatedUserUseCase`, `RefreshSessionUseCase`, `RequestPasswordResetUseCase`, `ResetPasswordUseCase`

| Função | Descrição |
|---|---|
| `signup(SignupCommand cmd)` | Valida dados, cria usuário, salva credenciais, retorna AuthResponse com sessão |
| `login(LoginCommand cmd)` | Valida credenciais (email + senha), retorna AuthResponse |
| `logout(String token)` | Revoga sessão pelo token |
| `getAuthenticatedUser(String token)` | Retorna usuário autenticado a partir do token |
| `requestReset(String email)` | Gera token de reset de senha e retorna |
| `reset(String token, String newPassword)` | Valida token e redefine a senha |
| `refreshSession(String refreshToken)` | Renova sessão usando refresh token |
| `refresh(String refreshToken)` | Valida refresh token e cria nova sessão |
| `createResponse(UserProfile user)` | Cria sessão e monta AuthResponse |
| `newToken()` | Gera token aleatório de 32 bytes (Base64url) |
| `newResetToken()` | Gera token de reset de senha |
| `uniqueUsername(String name, String email)` | Gera username único a partir de nome + email |
| `slug(String value)` | Cria slug URL-safe a partir de string |
| `validateSignup(SignupCommand cmd)` | Valida campos de cadastro |
| `validateLogin(LoginCommand cmd)` | Valida campos de login |

### SocialLoginService

**Arquivo**: `src/main/java/com/socialcoisa/rede/identity/application/SocialLoginService.java`

Implementa: `OAuthUseCase`

| Função | Descrição |
|---|---|
| `start(OAuthProvider provider)` | Retorna URL de autorização para o OAuth provider |
| `buildAuthorizationUrl(OAuthProvider provider, String state)` | Constrói URL de autorização Google/Apple |
| `callback(OAuthProvider provider, String code, String state)` | Processa callback OAuth: valida token, encontra ou cria usuário |
| `findOrCreateUser(OAuthProvider provider, TokenValidationResult result)` | Vincula identidade externa a conta existente ou cria nova |
| `createSession(String userId)` | Cria sessão para usuário OAuth |
| `uniqueUsername(...)` | Gera username único |
| `slug(String value)` | Slugifica string |

### PasswordHasher

**Arquivo**: `src/main/java/com/socialcoisa/rede/identity/application/PasswordHasher.java`

| Função | Descrição |
|---|---|
| `hash(String password)` | Hash PBKDF2 com HMAC-SHA256, 120k iterações, salt de 16 bytes |
| `matches(String password, String storedHash)` | Verifica senha contra hash armazenado |
| `pbkdf2(String password, byte[] salt)` | Cálculo interno PBKDF2 |

### OAuth Token Validators

**GoogleTokenValidator**: `src/main/java/com/socialcoisa/rede/identity/adapters/out/oauth/GoogleTokenValidator.java`

| Função | Descrição |
|---|---|
| `validate(OAuthProvider, String authorizationCode)` | Valida ID token Google ou troca código por tokens |
| `isJwt(String)` | Verifica se string é um JWT (contém 2 pontos) |
| `verifyIdToken(String)` | Verifica assinatura JWT usando JWKS do Google |
| `exchangeCodeForTokens(String)` | Troca código de autorização por tokens |
| `findRsaKey(SignedJWT)` | Encontra chave RSA no JWKS pelo kid |
| `refreshJwkSetIfStale()` | Cache JWKS por 1 hora |

**AppleTokenValidator**: `src/main/java/com/socialcoisa/rede/identity/adapters/out/oauth/AppleTokenValidator.java`

| Função | Descrição |
|---|---|
| `validate(OAuthProvider, String authorizationCode)` | Valida identity token da Apple |
| `verifyIdentityToken(String)` | Verifica JWT da Apple com JWKS |
| `findRsaKey(SignedJWT)` | Encontra chave RSA no JWKS da Apple |
| `refreshJwkSetIfStale()` | Cache Apple JWKS |

### AuthController

**Arquivo**: `src/main/java/com/socialcoisa/rede/identity/adapters/in/web/AuthController.java`

| Função | Rota | Descrição |
|---|---|---|
| `signup(...)` | POST /api/auth/signup | Cadastro de usuário |
| `login(...)` | POST /api/auth/login | Login |
| `logout(...)` | POST /api/auth/logout | Logout |
| `me(...)` | GET /api/auth/me | Usuário autenticado |
| `oauthStart(...)` | GET /api/auth/oauth/{provider}/start | Iniciar OAuth |
| `oauthCallback(...)` | GET /api/auth/oauth/{provider}/callback | Callback OAuth |
| `oauthToken(...)` | POST /api/auth/oauth/{provider}/token | Troca de token |
| `oauthWriteLink(...)` | POST /api/auth/oauth/github/write-link | Vínculo GitHub com escrita (revisar PRs/responder issues) |
| `refreshSession(...)` | POST /api/auth/refresh | Refresh de sessão |
| `forgotPassword(...)` | POST /api/auth/password/forgot | Solicitar reset |
| `resetPassword(...)` | POST /api/auth/password/reset | Resetar senha |
| `authScope(String email)` | Interna | Gera scope string para idempotência |
| `parseProvider(String)` | Interna | Parseia string para OAuthProvider |

### Persistence Adapters

**AuthSessionPersistenceAdapter**: Gerencia sessões (save, findActiveUserId, findActiveUserIdByRefreshToken, revoke, revokeByRefreshToken, revokeAllByUser)

**CredentialPersistenceAdapter**: Gerencia credenciais (save, findByEmail, findByUserId, existsByEmail)

**PasswordResetTokenPersistenceAdapter**: Gerencia tokens de reset (save, findByToken, markUsed)

**UserExternalIdentityPersistenceAdapter**: Gerencia identidades externas (save, findUserIdByProviderAndExternalId, existsByProviderAndExternalId)

### Domain Objects

| Classe | Descrição |
|---|---|
| `OAuthProvider` | Enum: `GOOGLE`, `APPLE` |
| `PasswordResetToken` | Entidade com token, userId, expiresAt, usado |
| `SignupCommand` | Record: name, email, password |
| `LoginCommand` | Record: email, password |
| `AuthResponse` | Record: token, refreshToken, user (AuthUserView) |
| `AuthUserView` | Record: id, username, name, email |

---

## 4. Feed (Postagens)

### PublishPostService

**Arquivo**: `src/main/java/com/socialcoisa/rede/feed/application/PublishPostService.java`

Implementa: `PublishPostUseCase`

| Função | Descrição |
|---|---|
| `publish(PublishPostCommand cmd)` | Cria post, salva no banco, distribui para seguidores, registra evento outbox |
| `notifyMentions(Post post, List<String> mentionedUsernames)` | Cria notificações para usuários mencionados |
| `validate(PublishPostCommand cmd)` | Valida campos do post (content obrigatório, type válido) |

### GetFeedService

**Arquivo**: `src/main/java/com/socialcoisa/rede/feed/application/GetFeedService.java`

Implementa: `GetFeedUseCase`, `GetProfilePostsUseCase`

| Função | Descrição |
|---|---|
| `getFeed(GetFeedQuery query)` | Retorna feed paginado para usuário, com abas: FOR_YOU, FOLLOWING, ALL, DISCUSSIONS, PROJECTS, RECENT |
| `getProfilePosts(GetProfilePostsQuery query)` | Retorna posts de um perfil específico |
| `getSavedFeed(UserId userId)` | Retorna posts salvos pelo usuário |
| `getCommunityFeed(String communityId, Instant cursor, int limit, UserId currentUser)` | Retorna feed de uma comunidade |
| `enrich(List<Post> posts, UserId currentUserId)` | Enriquece posts com dados do autor e reação do usuário |
| `filterByTab(List<Post> posts, FeedTab tab, UserId userId)` | Filtra posts por tipo de aba |
| `matchesTab(Post post, FeedTab tab)` | Verifica se post corresponde à aba |
| `fromCache(UserId userId, Instant cursor, int limit)` | Obtém feed do cache in-memory |
| `page(List<PostView> items, int limit)` | Cria PageResponse com cursor |
| `normalizeLimit(int limit)` | Normaliza limit entre 1 e 50 |

### DeletePostService

**Arquivo**: `src/main/java/com/socialcoisa/rede/feed/application/DeletePostService.java`

Implementa: `DeletePostUseCase`, `BulkDeletePostsUseCase`

| Função | Descrição |
|---|---|
| `delete(DeletePostCommand cmd)` | Soft-delete de um post (marca status=DELETED, deleted_at=now) |
| `delete(BulkDeletePostsCommand cmd)` | Bulk delete de múltiplos posts |
| `normalizePostIds(List<String> postIds)` | Valida lista de IDs |
| `normalizeReason(String reason)` | Normaliza razão da deleção |
| `requestMediaDeletion(Post post)` | Inicia job de deleção de mídia associada |

### PostInteractionService

**Arquivo**: `src/main/java/com/socialcoisa/rede/feed/application/PostInteractionService.java`

Implementa: `CommentOnPostUseCase`, `GetCommentsUseCase`, `ReactToPostUseCase`, `SavePostUseCase`, `SharePostUseCase`

| Função | Descrição |
|---|---|
| `comment(CommentOnPostCommand cmd)` | Adiciona comentário a um post, dispara notificação |
| `getComments(GetCommentsQuery query)` | Lista comentários com paginação por cursor |
| `react(ReactToPostCommand cmd)` | Adiciona/toggle reação em post |
| `removeReaction(ReactToPostCommand cmd)` | Remove reação do post |
| `save(PostActionCommand cmd)` | Salva post nos favoritos |
| `removeSave(PostActionCommand cmd)` | Remove post dos salvos |
| `share(PostActionCommand cmd)` | Compartilha post |
| `activePost(PostId postId)` | Encontra post ativo ou lança 404 |
| `normalizeContent(String content)` | Valida conteúdo do comentário |
| `normalizeParentComment(Comment parentComment, Post post)` | Valida parent comment |
| `normalizeLimit(int limit)` | Normaliza limit entre 1 e 50 |

### FeedDistributionService

**Arquivo**: `src/main/java/com/socialcoisa/rede/feed/application/FeedDistributionService.java`

| Função | Descrição |
|---|---|
| `distribute(Post post)` | Distribui post para o feed do autor, amigos e amigos-de-amigos |
| `limitFanout(Set<UserId> recipients)` | Limita fanout ao máximo configurado |
| `applyTagBoost(FeedItem item, Set<String> userTags)` | Aumenta score se tags do post correspondem às do usuário |
| `calculateEngagementBoost(Post post)` | Calcula boost de score baseado em engajamento |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Post` | Entidade principal: id, authorId, type, content, visibility, counters, status, createdAt |
| `PostId` | Value object para ID de post (`post_<uuid>`) |
| `UserId` | Value object para ID de usuário |
| `PostType` | Enum: `INSIGHT`, `ARTICLE`, `QUESTION`, `PROJECT`, `OPPORTUNITY` |
| `Visibility` | Enum: `PUBLIC`, `CONNECTIONS`, `COMMUNITY` |
| `PostStatus` | Enum: `ACTIVE`, `DELETED` |
| `Comment` | Entidade: id, postId, authorId, parentCommentId, content, status |
| `CommentStatus` | Enum: `ACTIVE`, `DELETED` |
| `ReactionType` | Enum: `LIKE`, `LOVE`, `CONGRATS`, `INSIGHTFUL`, `CURIOUS` |
| `FeedItem` | Record: postId, authorId, score, createdAt |
| `FeedReason` | Enum: `AUTHOR`, `FRIEND`, `FRIEND_OF_FRIEND`, `TAG`, `TRENDING` |
| `FeedTab` | Enum: `FOR_YOU`, `FOLLOWING`, `ALL`, `DISCUSSIONS`, `PROJECTS`, `RECENT` |
| `Counters` | Record: reactions, comments, saves, shares |

### Ports (In)

| Interface | Método principal |
|---|---|
| `PublishPostUseCase` | `publish(PublishPostCommand)` |
| `GetFeedUseCase` | `getFeed(GetFeedQuery)` |
| `GetProfilePostsUseCase` | `getProfilePosts(GetProfilePostsQuery)` |
| `DeletePostUseCase` | `delete(DeletePostCommand)` |
| `BulkDeletePostsUseCase` | `delete(BulkDeletePostsCommand)` |
| `CommentOnPostUseCase` | `comment(CommentOnPostCommand)` |
| `GetCommentsUseCase` | `getComments(GetCommentsQuery)` |
| `ReactToPostUseCase` | `react(ReactToPostCommand)` |
| `SavePostUseCase` | `save(PostActionCommand)` / `removeSave(PostActionCommand)` |
| `SharePostUseCase` | `share(PostActionCommand)` |

### Ports (Out)

| Interface | Métodos principais |
|---|---|
| `PostRepository` | `save`, `findById`, `findByIds`, `findActiveByAuthors`, `findActiveByAuthor`, `findActiveAll`, `findActiveByType`, `findActiveByCommunity` |
| `CommentRepository` | `save`, `findById`, `deleteById`, `findActiveByPost`, `findPostIdsByAuthor` |
| `PostInteractionRepository` | `addReaction`, `removeReaction`, `addSave`, `removeSave`, `addShare`, `addCommentReaction`, `removeCommentReaction`, `findReactionsByPostId`, `findReactionsByCommentId`, `findSavedPostIdsByUserId`, `findReactionsByUserAndPostIds` |
| `IdGenerator` | `generate()` |
| `ConnectionGraphRepository` | `findFriends`, `findFriendsOfFriends`, `findUserTags` |
| `FeedCacheRepository` | `prepend`, `get`, `removeEverywhere` |
| `AuditLogRepository` | `record` |
| `OutboxEventRepository` | `append` |

### Controllers

**FeedController**: `src/main/java/com/socialcoisa/rede/feed/adapters/in/web/FeedController.java`

| Função | Rota |
|---|---|
| `publish(...)` | POST /api/feed/posts |
| `feed(...)` | GET /api/feed |
| `userPosts(...)` | GET /api/users/{username}/posts |
| `delete(...)` | DELETE /api/feed/posts/{postId} |
| `bulkDelete(...)` | POST /api/feed/posts/bulk-delete |
| `comment(...)` | POST /api/feed/posts/{postId}/comments |
| `comments(...)` | GET /api/feed/posts/{postId}/comments |
| `deleteComment(...)` | DELETE /api/feed/posts/{postId}/comments/{commentId} |
| `communityNote(...)` | POST /api/feed/posts/{postId}/notes (nota de rodapé; vai para a moderação) |
| `communityNotes(...)` | GET /api/feed/posts/{postId}/notes (aprovadas + a do leitor) |
| `react(...)` | POST /api/feed/posts/{postId}/reactions |
| `removeReaction(...)` | DELETE /api/feed/posts/{postId}/reactions |
| `reactions(...)` | GET /api/feed/posts/{postId}/reactions |
| `save(...)` | POST /api/feed/posts/{postId}/saves |
| `removeSave(...)` | DELETE /api/feed/posts/{postId}/saves |
| `share(...)` | POST /api/feed/posts/{postId}/shares |
| `saved(...)` | GET /api/feed/saved |
| `reactToComment(...)` | POST /api/feed/posts/{postId}/comments/{commentId}/reactions |
| `commentReactions(...)` | GET /api/feed/posts/{postId}/comments/{commentId}/reactions |

---

## 5. Profile (Perfil)

### ProfileService

**Arquivo**: `src/main/java/com/socialcoisa/rede/profile/application/ProfileService.java`

Implementa: `GetMeUseCase`, `GetUserProfileUseCase`, `UpdateProfileUseCase`

| Função | Descrição |
|---|---|
| `getMe(String userId)` | Retorna perfil completo com reputação |
| `getByUsername(String username)` | Retorna perfil por username |
| `getById(String userId)` | Retorna perfil por ID |
| `populateCounts(UserProfile profile)` | Calcula e seta pontuação de reputação |
| `search(String query)` | Busca perfis |
| `getRanking(int limit)` | Top usuários por reputação |
| `update(UpdateProfileCommand cmd)` | Atualiza campos do perfil |

### ProfileExtensionService

**Arquivo**: `src/main/java/com/socialcoisa/rede/profile/application/ProfileExtensionService.java`

Implementa: `ManageProfileExtensionUseCase`

| Função | Descrição |
|---|---|
| `getExperiences(String userId)` | Lista experiências do usuário |
| `addExperience(String userId, Experience data)` | Adiciona experiência |
| `updateExperience(String userId, Long id, Experience data)` | Atualiza experiência |
| `removeExperience(String userId, Long id)` | Remove experiência |
| `getEducations(String userId)` | Lista formações |
| `addEducation(String userId, Education data)` | Adiciona formação |
| `updateEducation(String userId, Long id, Education data)` | Atualiza formação |
| `removeEducation(String userId, Long id)` | Remove formação |
| `getCertifications(String userId)` | Lista certificações |
| `addCertification(String userId, Certification data)` | Adiciona certificação |
| `updateCertification(String userId, Long id, Certification data)` | Atualiza certificação |
| `removeCertification(String userId, Long id)` | Remove certificação |
| `toExperienceView(Experience exp)` | Mapeia Experience para ExperienceView |
| `toEducationView(Education edu)` | Mapeia Education para EducationView |
| `toCertificationView(Certification cert)` | Mapeia Certification para CertificationView |

### RecommendationService

**Arquivo**: `src/main/java/com/socialcoisa/rede/profile/application/RecommendationService.java`

| Função | Descrição |
|---|---|
| `recommend(String recommenderId, String targetId, String comment)` | Cria recomendação de usuário |
| `remove(String recommenderId, String targetId)` | Remove recomendação |
| `listReceived(String userId)` | Lista recomendações recebidas |
| `listGiven(String userId)` | Lista recomendações dadas |
| `countReceived(String userId)` | Conta recomendações recebidas |

### Domain Objects

| Classe | Descrição |
|---|---|
| `UserProfile` | Entidade: id, username, name, headline, email, company, location, avatar, banner, bio, availability, portfolio, openToWork, isMentor, skills, goals, externalLinks, reputation |
| `Experience` | Entidade: id, title, company, location, description, currentlyHere, startDate, endDate, displayOrder |
| `Education` | Entidade: id, school, degree, field, description, currentlyHere, startDate, endDate, displayOrder |
| `Certification` | Entidade: id, name, issuer, description, issueDate, expirationDate, credentialUrl |
| `Recommendation` | Entidade: id, recommenderId, recommendedId, comment, createdAt |
| `UserProfileView` | View do profile para API |
| `UpdateProfileCommand` | Comando para atualização |

### Controllers

**ProfileController**: `src/main/java/com/socialcoisa/rede/profile/adapters/in/web/ProfileController.java`

| Função | Rota |
|---|---|
| `me(...)` | GET /api/me |
| `userById(...)` | GET /api/users/by-id/{userId} |
| `user(...)` | GET /api/users/{username} |
| `searchUsers(...)` | GET /api/users/search |
| `ranking(...)` | GET /api/ranking |
| `update(...)` | PATCH /api/me/profile |
| `getExperiences(...)` | GET /api/me/experiences |
| `addExperience(...)` | POST /api/me/experiences |
| `updateExperience(...)` | PUT /api/me/experiences/{id} |
| `removeExperience(...)` | DELETE /api/me/experiences/{id} |
| `getEducation(...)` | GET /api/me/education |
| `addEducation(...)` | POST /api/me/education |
| `updateEducation(...)` | PUT /api/me/education/{id} |
| `removeEducation(...)` | DELETE /api/me/education/{id} |
| `getCertifications(...)` | GET /api/me/certifications |
| `addCertification(...)` | POST /api/me/certifications |
| `updateCertification(...)` | PUT /api/me/certifications/{id} |
| `removeCertification(...)` | DELETE /api/me/certifications/{id} |

**RecommendationController**: `src/main/java/com/socialcoisa/rede/profile/adapters/in/web/RecommendationController.java`

| Função | Rota |
|---|---|
| `recommend(...)` | POST /api/recommendations/{targetId} |
| `remove(...)` | DELETE /api/recommendations/{targetId} |
| `received(...)` | GET /api/recommendations/received/{userId} |
| `given(...)` | GET /api/recommendations/given/{userId} |

### Persistence Adapters

**UserProfilePersistenceAdapter**: Implementa UserProfileRepository com JPA (save, findById, findAllByIds, findByUsername, findByEmail, existsByUsername, existsByEmail, count methods, searchByQuery, findTopByReputation)

**ProfileExtensionPersistenceAdapter**: CRUD para Experience, Education, Certification

**RecommendationPersistenceAdapter**: CRUD para Recommendation

---

## 6. Network (Conexões)

### NetworkService

**Arquivo**: `src/main/java/com/socialcoisa/rede/network/application/NetworkService.java`

| Função | Descrição |
|---|---|
| `requestConnection(String requesterId, String receiverId)` | Cria ou reativa solicitação de conexão, envia notificação |
| `notifyConnectionRequest(String requesterId, String receiverId)` | Envia notificação de solicitação |
| `acceptConnection(String userId, String requesterId)` | Aceita conexão pendente |
| `removeConnection(String userId, String targetUserId)` | Remove/soft-delete conexão |
| `getConnectionStatus(String userId, String targetUserId)` | Retorna status (PENDING, ACCEPTED, BLOCKED, NONE) |
| `getConnections(String userId)` | Conexões aceitas |
| `getPendingRequests(String userId)` | Solicitações pendentes |
| `countConnections(String userId)` | Conta conexões aceitas |
| `getSuggestions(String userId, int limit)` | Sugere conexões baseadas em conexões mútuas |
| `findFriends(UserId userId)` | Retorna IDs dos amigos (para feed) |
| `findFriendsOfFriends(UserId userId)` | Retorna IDs de amigos-de-amigos (para feed) |
| `findUserTags(UserId userId)` | Retorna skills do usuário como tags |

### ConnectionPersistenceAdapter

**Arquivo**: `src/main/java/com/socialcoisa/rede/network/adapters/out/persistence/ConnectionPersistenceAdapter.java`

| Função | Descrição |
|---|---|
| `save(UserConnection)` | Salva conexão |
| `findBetweenUsers(String userA, String userB)` | Busca conexão entre dois usuários |
| `findConnections(String userId, ConnectionStatus status)` | Conexões por status |
| `findRequests(String userId, ConnectionStatus status)` | Solicitações recebidas |
| `countByUserAndStatus(String userId, ConnectionStatus status)` | Conta por status |
| `existsActiveConnection(String userId, String targetUserId)` | Verifica se conexão ativa existe |
| `findAcceptedFriendIds(UserId userId)` | Retorna set de IDs de amigos |
| `findSuggestions(String userId, int limit)` | Sugestões via native query |
| `countMutualConnections(String userIdA, String userIdB)` | Conta conexões mútuas |

### Domain Objects

| Classe | Descrição |
|---|---|
| `UserConnection` | Entidade: id, requesterUserId, receiverUserId, status, createdAt, acceptedAt, removedAt |
| `ConnectionStatus` | Enum: `PENDING`, `ACCEPTED`, `BLOCKED` |
| `ConnectionSuggestion` | Record: suggestedUserId, mutualConnections, name, headline, avatar |

### NetworkContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/network/adapters/in/web/NetworkContractController.java`

| Função | Rota |
|---|---|
| `users(...)` | GET /api/network/users |
| `suggestions(...)` | GET /api/network/suggestions |
| `summary(...)` | GET /api/network/summary |
| `requests(...)` | GET /api/network/requests |
| `connect(...)` | POST /api/network/connections/{targetUserId} |
| `accept(...)` | PUT /api/network/connections/{targetUserId}/accept |
| `status(...)` | GET /api/network/connections/{targetUserId}/status |
| `remove(...)` | DELETE /api/network/connections/{targetUserId} |

---

## 7. Media (Mídia)

### MediaService

**Arquivo**: `src/main/java/com/socialcoisa/rede/media/application/MediaService.java`

Implementa: `CreateMediaUploadUseCase`, `GetMediaAssetUseCase`, `RequestMediaDeletionUseCase`, `RequestPostMediaDeletionUseCase`, `ValidateMediaOwnershipUseCase`

| Função | Descrição |
|---|---|
| `createUpload(CreateMediaUploadCommand cmd)` | Cria contrato de upload (media_asset com status PENDING) |
| `getMedia(String mediaId)` | Retorna metadados da mídia (URL do CDN) |
| `requestDeletion(RequestMediaDeletionCommand cmd)` | Solicita deleção de uma mídia |
| `requestPostMediaDeletion(UserId ownerId, PostId postId, List<String> mediaIds)` | Solicita deleção de mídias associadas a um post |
| `validateOwnedBy(UserId userId, List<String> mediaIds)` | Valida que todas as mídias pertencem ao usuário |
| `requestDeletion(MediaAsset media, PostId postId)` | Cria job de deleção interno |
| `findMedia(String mediaId)` | Busca mídia ou lança 404 |
| `validate(CreateMediaUploadCommand cmd)` | Valida parâmetros de upload (fileName, mimeType, sizeBytes) |

### MediaDeletionWorker

**Arquivo**: `src/main/java/com/socialcoisa/rede/media/application/MediaDeletionWorker.java`

| Função | Descrição |
|---|---|
| `processPendingJobs()` | Scheduled task: processa jobs de deleção pendentes (purge CDN + delete object) |

### Domain Objects

| Classe | Descrição |
|---|---|
| `MediaAsset` | Entidade: id, ownerId, fileName, mimeType, sizeBytes, purpose, objectKey, cdnUrl, status, deleteStatus |
| `MediaStatus` | Enum: `PENDING`, `ACTIVE`, `EXPIRED`, `DELETED` |
| `MediaDeleteStatus` | Enum: `NONE`, `REQUESTED`, `DELETED` |
| `MediaDeletionJob` | Entidade: id, mediaId, postId, ownerId, status, attempts |
| `MediaAssetView` | View: mediaId, cdnUrl, mimeType, status, purpose |
| `MediaUploadView` | View: mediaId, cdnUrl, uploadUrl, status |

### Storage Gateways

**DevMediaStorageGateway**: Implementação dev (no-op)

**DevStorageUrlFactory**: Gera URLs `/dev/cdn/...` e `/dev/uploads/...`

**ImageKitMediaStorageGateway**: Gateway real ImageKit

| Função | Descrição |
|---|---|
| `purgeCdn(MediaAsset)` | Chama API de purge do ImageKit |
| `deleteObject(MediaAsset)` | Chama API de delete do ImageKit |

**ImageKitStorageUrlFactory**: Gera URLs para ImageKit

| Função | Descrição |
|---|---|
| `objectKey(MediaAsset)` | `uploads/{mediaId}/{fileName}` |
| `cdnUrl(MediaAsset)` | URL de CDN para ImageKit |
| `uploadUrl(MediaAsset)` | URL de upload para ImageKit |

### MediaContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/media/adapters/in/web/MediaContractController.java`

| Função | Rota |
|---|---|
| `upload(...)` | POST /api/media/uploads |
| `media(...)` | GET /api/media/{mediaId} |
| `delete(...)` | DELETE /api/media/{mediaId} |

---

## 8. Communities (Comunidades)

### CommunityService

**Arquivo**: `src/main/java/com/socialcoisa/rede/communities/application/CommunityService.java`

| Função | Descrição |
|---|---|
| `create(String name, String description, String topic, String cover, String createdBy)` | Cria comunidade com criador como ADMIN |
| `update(String communityId, String name, String description, String topic, String cover, String userId)` | Atualiza comunidade (apenas ADMIN) |
| `join(String communityId, String userId)` | Usuário entra na comunidade como MEMBER |
| `leave(String communityId, String userId)` | Usuário sai da comunidade; se ADMIN, transfere ou deleta |
| `list(String topic, int limit)` | Lista comunidades, opcional filtro por tópico |
| `getById(String communityId)` | Busca comunidade por ID |
| `isMember(String communityId, String userId)` | Verifica se é membro |
| `countMembers(String communityId)` | Conta membros |
| `findMembers(String communityId)` | Lista membros |

### CommunityPersistenceAdapter

| Função | Descrição |
|---|---|
| CRUD para `Community` e `CommunityMember` via JPA |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Community` | Entidade: id, name, description, topic, cover, avatar, createdBy |
| `CommunityMember` | Entidade: communityId, userId, role |
| `MemberRole` | Enum: `ADMIN`, `MODERATOR`, `MEMBER` |

### CommunityContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/communities/adapters/in/web/CommunityContractController.java`

| Função | Rota |
|---|---|
| `communities(...)` | GET /api/communities |
| `community(...)` | GET /api/communities/{communityId} |
| `create(...)` | POST /api/communities |
| `update(...)` | PATCH /api/communities/{communityId} |
| `join(...)` | POST /api/communities/{communityId}/members |
| `leave(...)` | DELETE /api/communities/{communityId}/members |
| `members(...)` | GET /api/communities/{communityId}/members |
| `communityFeed(...)` | GET /api/communities/{communityId}/feed |

---

## 9. Jobs (Vagas)

### JobService

**Arquivo**: `src/main/java/com/socialcoisa/rede/jobs/application/JobService.java`

| Função | Descrição |
|---|---|
| `create(String title, String company, String logo, String location, String type, String level, String salary, List<String> skills, String postedBy)` | Cria vaga (requer reputação >= 300) |
| `update(String jobId, ...)` | Atualiza vaga (apenas owner) |
| `delete(String jobId, String userId)` | Deleta vaga (apenas owner) |
| `apply(String jobId, String userId)` | Candidata-se a vaga |
| `save(String jobId, String userId)` | Salva/bookmark vaga |
| `removeSave(String jobId, String userId)` | Remove vaga salva |
| `list(String type, String level, int limit)` | Lista vagas com filtros |
| `getById(String jobId)` | Busca vaga por ID |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Job` | Entidade: id, title, company, logo, location, type, level, salary, skills, postedBy |

### JobContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/jobs/adapters/in/web/JobContractController.java`

| Função | Rota |
|---|---|
| `create(...)` | POST /api/jobs |
| `jobs(...)` | GET /api/jobs |
| `job(...)` | GET /api/jobs/{jobId} |
| `update(...)` | PUT /api/jobs/{jobId} |
| `delete(...)` | DELETE /api/jobs/{jobId} |
| `apply(...)` | POST /api/jobs/{jobId}/applications |
| `save(...)` | POST /api/jobs/{jobId}/saves |
| `removeSave(...)` | DELETE /api/jobs/{jobId}/saves |

---

## 10. Events (Eventos)

### EventService

**Arquivo**: `src/main/java/com/socialcoisa/rede/events/application/EventService.java`

| Função | Descrição |
|---|---|
| `create(String title, String date, String time, String format, String host, String cover, String createdBy)` | Cria evento |
| `attend(String eventId, String userId)` | Confirma presença |
| `cancelAttendance(String eventId, String userId)` | Cancela presença |
| `list(String format, int limit)` | Lista eventos com filtro de formato |
| `getById(String eventId)` | Busca evento por ID |
| `countAttendees(String eventId)` | Conta participantes |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Event` | Entidade: id, title, date, time, format, host, cover, createdBy |
| `EventAttendee` | Entidade: eventId, userId |

### EventContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/events/adapters/in/web/EventContractController.java`

| Função | Rota |
|---|---|
| `events(...)` | GET /api/events |
| `event(...)` | GET /api/events/{eventId} |
| `create(...)` | POST /api/events |
| `attend(...)` | POST /api/events/{eventId}/attendees |
| `cancel(...)` | DELETE /api/events/{eventId}/attendees |

---

## 11. Mentoring (Mentoria)

### MentorService

**Arquivo**: `src/main/java/com/socialcoisa/rede/mentoring/application/MentorService.java`

| Função | Descrição |
|---|---|
| `create(String userId, String expertise, double price)` | Registra como mentor |
| `update(String mentorId, String expertise, Double price)` | Atualiza perfil de mentor |
| `createSlot(String mentorId, Instant startsAt, Instant endsAt)` | Cria horário disponível |
| `scheduleSession(String mentorId, String menteeId, String slotId, String message)` | Agenda sessão de mentoria |
| `delete(String mentorId, String userId)` | Remove perfil de mentor |
| `list(String expertise, int limit)` | Lista mentores, filtro por expertise |
| `getById(String mentorId)` | Busca mentor por ID |
| `getSlots(String mentorId)` | Lista horários do mentor |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Mentor` | Entidade: user, expertise, rating, sessionsCount, price |
| `MentorSlot` | Entidade: mentor, startsAt, endsAt, available |
| `MentorSession` | Entidade: mentor, mentee, slot, message, status |
| `SessionStatus` | Enum: `SCHEDULED`, `COMPLETED`, `CANCELLED` |

### MentoringContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/mentoring/adapters/in/web/MentoringContractController.java`

| Função | Rota |
|---|---|
| `mentors(...)` | GET /api/mentors |
| `mentor(...)` | GET /api/mentors/{mentorId} |
| `slots(...)` | GET /api/mentors/{mentorId}/slots |
| `create(...)` | POST /api/mentors |
| `update(...)` | PATCH /api/mentors/{mentorId} |
| `delete(...)` | DELETE /api/mentors/{mentorId} |
| `createSlot(...)` | POST /api/mentors/{mentorId}/slots |
| `session(...)` | POST /api/mentors/{mentorId}/sessions |

---

## 12. Messaging (Mensagens)

### MessagingService

**Arquivo**: `src/main/java/com/socialcoisa/rede/messaging/application/MessagingService.java`

| Função | Descrição |
|---|---|
| `createConversation(String kind, String title, String createdBy, List<String> participantIds)` | Cria conversa com participantes |
| `sendMessage(String conversationId, String senderId, String text)` | Envia mensagem, notifica participantes |
| `markRead(String conversationId, String userId)` | Marca conversa como lida para o usuário |
| `listConversations(String userId)` | Lista conversas do usuário |
| `isParticipant(String conversationId, String userId)` | Verifica se é participante |
| `getConversation(String conversationId)` | Busca conversa por ID |
| `getParticipants(String conversationId)` | Lista participantes |
| `countUnread(String conversationId, String userId)` | Conta mensagens não lidas |
| `getMessages(String conversationId, Instant cursor, int limit)` | Lista mensagens com cursor |
| `hideMessages(String conversationId, String userId, List<String> messageIds)` | Valida o lote e oculta mensagens somente para o solicitante |
| `getLastMessageText(String conversationId)` | Texto da última mensagem |
| `getLastMessageTime(String conversationId)` | Timestamp da última mensagem |
| `addReaction(String messageId, String userId, String reactionType)` | Adiciona/toggle reação em mensagem |
| `getReactions(String messageId)` | Lista reações da mensagem |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Conversation` | Entidade: id, kind (DIRECT/GROUP), title, createdBy |
| `ConversationParticipant` | Entidade: conversationId, userId, lastReadAt |
| `Message` | Entidade: id, conversationId, senderId, text, createdAt |

### MessagingContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/messaging/adapters/in/web/MessagingContractController.java`

| Função | Rota |
|---|---|
| `conversations(...)` | GET /api/conversations |
| `search(...)` | GET /api/conversations/search |
| `conversation(...)` | GET /api/conversations/{conversationId} |
| `messages(...)` | GET /api/conversations/{conversationId}/messages |
| `hideMessages(...)` | POST /api/conversations/{conversationId}/messages/hide |
| `send(...)` | POST /api/conversations/{conversationId}/messages |
| `read(...)` | POST /api/conversations/{conversationId}/read |
| `create(...)` | POST /api/conversations |
| `reactToMessage(...)` | POST /api/conversations/{conversationId}/messages/{messageId}/reactions |
| `listMessageReactions(...)` | GET /api/conversations/{conversationId}/messages/{messageId}/reactions |

A ocultação afeta somente o solicitante e aceita mensagens da conversa informada. Seleções
inválidas são rejeitadas integralmente. A operação exige autenticação e chave de idempotência.

---

## 13. Notifications (Notificações)

### NotificationService

**Arquivo**: `src/main/java/com/socialcoisa/rede/notifications/application/NotificationService.java`

| Função | Descrição |
|---|---|
| `create(String userId, String kind, String actorId, String title, String body, String href, String ctaLabel)` | Cria notificação |
| `markRead(String notificationId, String userId)` | Marca como lida |
| `markAllRead(String userId)` | Marca todas como lidas |
| `list(String userId, int limit)` | Lista notificações |

### NotificationOutboxEventHandler

**Arquivo**: `src/main/java/com/socialcoisa/rede/notifications/application/NotificationOutboxEventHandler.java`

| Função | Descrição |
|---|---|
| `handle(String eventType, String aggregateId, String payload)` | Processa evento outbox e cria notificação |
| `handlePostCommented(String payload)` | Cria notificação de comentário |
| `handlePostReacted(String payload)` | Cria notificação de reação |
| `extractValue(String json, String key)` | Extrai valor simples de JSON string |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Notification` | Entidade: id, userId, kind, actorId, title, body, href, ctaLabel, read, createdAt |

### NotificationContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/notifications/adapters/in/web/NotificationContractController.java`

| Função | Rota |
|---|---|
| `notifications(...)` | GET /api/notifications |
| `read(...)` | POST /api/notifications/{notificationId}/read |
| `readAll(...)` | POST /api/notifications/read-all |

### Preferências e resumo semanal

Detalhes em [`DIGEST.md`](DIGEST.md).

| Classe | Descrição |
|---|---|
| `NotificationCategory` | Mapa único kind → categoria de push (`ALWAYS` para segurança/moderação/sanção) |
| `NotificationPreferenceService` | Lê/grava as preferências (`notification_preferences`); `allowsPush(userId, kind)` |
| `NotificationPreferenceController` | GET/PUT /api/me/notification-preferences, GET /api/notifications/digest/unsubscribe |
| `WeeklyDigestService` | Agendamento, conteúdo, envio único por semana ISO e token de descadastro |

---

## 14. Search (Busca)

### SearchService

**Arquivo**: `src/main/java/com/socialcoisa/rede/search/application/SearchService.java`

| Função | Descrição |
|---|---|
| `search(String query, List<String> types, int limit)` | Busca global entre types especificados |

### SearchPersistenceAdapter

**Arquivo**: `src/main/java/com/socialcoisa/rede/search/adapters/out/persistence/SearchPersistenceAdapter.java`

| Função | Descrição |
|---|---|
| `searchUsers(String query, int limit)` | Busca usuários por nome, username, headline com relevância |
| `searchCommunities(String query, int limit)` | Busca comunidades por nome, descrição, tópico |
| `searchJobs(String query, int limit)` | Busca vagas por título, empresa |
| `searchEvents(String query, int limit)` | Busca eventos por título, host |
| `searchMentors(String query, int limit)` | Busca mentores por nome, expertise |
| `mapResults(List<Object[]> rows)` | Mapeia resultados para SearchResult records |
| `mapUserResults(List<Object[]> rows)` | Formato específico para usuários |

### SearchContractController

**Arquivo**: `src/main/java/com/socialcoisa/rede/search/adapters/in/web/SearchContractController.java`

| Função | Rota |
|---|---|
| `search(...)` | GET /api/search |

---

## 15. Moderation (Moderação)

### ModerationService

**Arquivo**: `src/main/java/com/socialcoisa/rede/moderation/application/ModerationService.java`

| Função | Descrição |
|---|---|
| `report(String reporterId, String targetType, String targetId, String reason, String description)` | Cria report de conteúdo |
| `approve(String reportId, String reviewerId)` | Aprova report, deleta post alvo (soft-delete) |
| `dismiss(String reportId, String reviewerId)` | Rejeita report |
| `listPending(int limit)` | Lista reports pendentes |

### Domain Objects

| Classe | Descrição |
|---|---|
| `Report` | Entidade: id, reporterId, targetType, targetId, reason, description, status |
| `ReportReason` | Enum: `AI_GENERATED`, `FAKE_CONTENT`, `SCAM`, `DISCRIMINATION`, `PREJUDICE`, `HARASSMENT`, `SPAM`, `VIOLENCE`, `SEXUAL_CONTENT`, `COPYRIGHT`, `OTHER` |
| `ReportStatus` | Enum: `PENDING`, `AUTO_PENALIZED`, `APPROVED`, `DISMISSED` |
| `TargetType` | Enum: `POST`, `COMMENT`, `USER`, `JOB`, `GAME`, `GROUP_POST`, `GROUP_COMMENT`, `MESSAGE` |

### ReportController

**Arquivo**: `src/main/java/com/socialcoisa/rede/moderation/adapters/in/web/ReportController.java`

| Função | Rota |
|---|---|
| `report(...)` | POST /api/reports |
| `listPending(...)` | GET /api/admin/reports |
| `approve(...)` | POST /api/admin/reports/{reportId}/approve |
| `dismiss(...)` | POST /api/admin/reports/{reportId}/dismiss |

### SanctionService (fila de penalidades do admin)

**Arquivo**: `src/main/java/com/socialcoisa/rede/moderation/application/SanctionService.java`

Correção manual de penalidades aplicadas erradas: o admin lista quem está penalizado, remove a
sanção (conta liberada, denúncia de origem descartada) ou corrige o tipo/motivo sem remover.
Rebaixar um ban libera o login de volta — o conteúdo purgado pelo ban não volta.

| Função | Rota |
|---|---|
| `adminSanctions(status, limit)` | GET /api/admin/sanctions?status=ACTIVE\|REMOVED |
| `revokeSanction(adminId, sanctionId, reason)` | POST /api/admin/sanctions/{sanctionId}/revoke |
| `editSanction(adminId, sanctionId, type, reason)` | POST /api/admin/sanctions/{sanctionId}/edit |

### ContentQuarantineReviewService

**Arquivo**: `src/main/java/com/socialcoisa/rede/moderation/application/ContentQuarantineReviewService.java`

Fila de revisão do que a barreira de conteúdo automatizado tirou do ar. Liberar devolve o post ao
ar; remover apaga o post. Nenhuma das duas pune a conta. Ver `docs/MODERACAO-CONTEUDO.md`.

| Função | Rota |
|---|---|
| `pending(limit)` | GET /api/moderation/quarantine |
| `release(reviewerId, postId)` | POST /api/moderation/quarantine/{postId}/release |
| `remove(reviewerId, postId)` | POST /api/moderation/quarantine/{postId}/remove |

---

## 16. Idempotency

### IdempotencyApplicationService

**Arquivo**: `src/main/java/com/socialcoisa/rede/idempotency/application/IdempotencyApplicationService.java`

| Função | Descrição |
|---|---|
| `execute(UserId userId, String method, String route, String idempotencyKey, String requestHash, Supplier<CommandResponse> operation)` | Execução idempotente para usuários autenticados |
| `execute(String userScope, String method, String route, String idempotencyKey, String requestHash, Supplier<CommandResponse> operation)` | Execução idempotente com escopo string |
| `hash(Object value)` | SHA-256 do payload JSON |
| `replayOrReject(IdempotencyKeyEntity existing)` | Replay de resposta cacheada ou rejeição por conflito |
| `json(int status, String body)` | Constrói ResponseEntity |
| `writeJson(Object body)` | Serializa para JSON |

### Domain Objects

| Classe | Descrição |
|---|---|
| `IdempotencyStatus` | Enum: `IN_PROGRESS`, `COMPLETED`, `CONFLICT` |
| `CommandResponse` | Record: statusCode, body |
| `IdempotencyKeyEntity` | JPA entity para tabela idempotency_keys |

---

## 17. Coin (Carteira e disputas)

Carteira em "faíscas" (a moeda da comunidade) com transferências diretas, prêmios, contestação de
transações (golpe) e fila de disputas para a moderação. A contagem e o formato dos valores usam o
mesmo formato pt-BR do frontend (`50.000 mf` = "50").

| Rota | Descrição |
|---|---|
| `GET /carteira` | Carteira do usuário logado (saldo, histórico, prizes) |
| `POST /carteira/transferencias` | Transferência direta para outro usuário |
| `POST /carteira/premios` | Crédito de prêmio |
| `POST /carteira/transacoes/{refId}/contestar` | Contestar uma transação (suspeita de golpe) |
| `GET /carteira/contestacoes` | Minhas contestações |
| `GET /coin/cotacao` / `POST /coin/cotacao` | Cotação da moeda (leitura pública / atualização) |
| `GET /admin/carteira/economia` | Métricas do cálculo automático da cotação (uso, emissão, índice e valor sugerido) |
| `POST /admin/carteira/economia/recalcular` | Roda o cálculo agora e publica se o valor mudou (admin) |
| `GET /admin/carteira/disputas` | Fila de disputas para a moderação |
| `POST /admin/carteira/disputas/{id}/deferir` | Disputa verdadeira: reverte a transferência e restringe o golpista |
| `POST /admin/carteira/disputas/{id}/rejeitar` | Disputa falsa: restringe quem contestou de má-fé |
| `GET /admin/carteira/usuarios/{username}/extrato` | Extrato completo de um usuário (moderação) |

Arquivos: `CoinController`, `CoinService`, `CoinRateService`, `CoinEconomyService`,
`CoinEconomyCalculator`, `coin/dispute/CoinDisputeService`.

A cotação de referência é **recalculada automaticamente pelo uso** da moeda e a **emissão
automática é convertida** por ela (mesmo valor em reais quando a faísca sobe ou desce). Fórmula,
parâmetros e salvaguardas em [`COIN_ECONOMY.md`](COIN_ECONOMY.md).

## 18. Marketplace

Loja de itens digitais: anúncios criados pela comunidade, compra com escrow (o valor só é liberado ao
vendedor após a confirmação do comprador) e sanitização pela moderação.

| Rota | Descrição |
|---|---|
| `GET /marketplace` / `GET /marketplace/mine` / `GET /marketplace/vendas` | Catálogo, meus anúncios, minhas vendas |
| `POST /marketplace/items` / `POST /marketplace/{listingId}/buy` | Criar anúncio / comprar (escrow) |
| `GET /marketplace/compras` / `GET /marketplace/purchases/{id}/content` | Minhas compras / conteúdo da compra |
| `POST /marketplace/purchases/{id}/confirm` / `.../refund` | Confirmar recebimento / reembolso |
| `POST /admin/marketplace/sanitize` | Sanitizar anúncio (moderação) |

## 19. Games (Jogos da comunidade)

Catálogo de jogos criados pela comunidade, jogáveis no site (sandbox), com biblioteca, favoritos,
avaliações, comentários, analíticos de uso e fila de revisão pela moderação.

| Rota | Descrição |
|---|---|
| `GET /games` / `GET /games/colecoes` / `GET /games/hoje` / `GET /games/library` | Catálogo, coleções, jogados hoje, biblioteca |
| `POST /games` (implícito no painel) / `DELETE /games/{gameId}` | Publicar / excluir jogo |
| `GET /games/{gameId}/stats` / `.../analytics` / `.../updates` | Uso (jogadores distintos), analíticos, novidades |
| `GET /comments` / `DELETE /comments/{commentId}` | Comentários do jogo |
| `GET /admin/games` | Fila de revisão (moderação) |

O jogo roda em `sandboxed-html-frame` com tela cheia; o dono tem painel de administração em
`/games/painel` (ver a documentação do frontend).

## 20. Services (Serviços: contratar e gerenciar)

Serviços profissionais entre membros: ofertas com preço em faíscas (escrow) ou BRL (direto),
contratos com aceite/entrega/reembolso e chat por oferta.

| Rota | Descrição |
|---|---|
| `GET /servicos` / `GET /servicos/minhas` | Catálogo com busca / minhas ofertas |
| `POST /servicos/ofertas` / `PATCH /{serviceId}` / `DELETE /{serviceId}` | Criar, editar, excluir oferta |
| `GET /{username}/{serviceSlug}` | Página pública da oferta |
| `POST /servicos/ofertas/{listingId}/contratar` / `.../chat` | Contratar / abrir conversa |
| `POST /servicos/contratos/{contractId}/aceitar` / `.../entregar` / `.../reembolso` | Ciclo do contrato |
| `POST /{serviceId}/resubmit` | Reenviar oferta rejeitada pela moderação |

## 21. Freelances

Vagas freelancer da comunidade (separadas das vagas de emprego em `/vagas`): sem barreira de
reputação, mesmas regras de moderação.

| Rota | Descrição |
|---|---|
| `GET /freelances` / `GET /me/freelances` | Catálogo / meus freelances |
| `POST /freelances` / `POST /freelances/{id}/close` | Publicar / encerrar |

## 22. Support (Suporte e tickets)

Canal de contato categorizado: pedidos de suporte viram tickets com status, resposta da equipe,
integração com issues do GitHub e log de e-mails no painel.

| Rota | Descrição |
|---|---|
| `GET /support/config` | Configuração pública (e-mail do time, categorias) |
| `POST /support/requests` / `GET /support/requests/mine` | Abrir pedido / meus pedidos |
| `GET /admin/support` / `PATCH /admin/support/{id}` / `POST /admin/support/{id}/reply` | Fila, atualizar status, responder (equipe de moderação) |
| `GET /admin/support/{id}/messages` | Conversa do ticket: respostas do painel + comentários da issue |
| `GET /admin/support/github` / `PUT /admin/support/github` / `POST /webhooks/github` | Integração de issues (espelho, comentários e estado) |
| `GET /admin/emails` | Log de metadados de e-mails enviados (sem conteúdo de 2FA) |

## 23. Ops (Monitoramento e operação)

Painel de monitorabilidade: KPIs de tráfego, latência, recursos do pod, pool do banco, e a lista de
eventos/logs/erros. Documentação completa em [`MONITORAMENTO.md`](MONITORAMENTO.md).

| Rota | Descrição |
|---|---|
| `GET /admin/monitoring/overview` | KPIs, séries por minuto, recursos, eventos recentes |
| `GET /admin/monitoring/events` | Lista de eventos com filtros (nível, tipo, busca, **intervalo de data/hora**, paginação por cursor) |
| `GET /admin/emails` | Log de e-mails enviados |

O painel de log aceita "mostrar mais", cópia individual e cópia completa, e o modo pânico (chave de
sistema que responde 429 em toda escrita) fica no módulo shared (`PanicController`).

## 24. Themes + Cosmetics (Aparência)

Temas da galeria (criados pela comunidade, instaláveis com um clique) e cosméticos do perfil
(bordas, name-style, itens equipáveis comprados na loja).

| Rota | Descrição |
|---|---|
| `GET /temas/galeria` / `.../mine` / `.../{themeId}` | Galeria pública / meus temas / detalhe |
| `POST /temas/galeria` / `.../install` / `.../hide` / `.../report` | Publicar, instalar, ocultar, denunciar |
| `GET /cosmetics/catalog` / `.../mine` | Community cosmetics catalog, my items |
| `GET /loja` / `/loja/colecoes` | Native shop catalog / seasonal collections and looks |
| `POST /loja/{itemId}/comprar` / `.../equipar` / `.../desequipar`, `POST /loja/looks/{lookId}/comprar` | Buy, equip, unequip, buy a look |
| `PUT /me/status` | Profile status bubble |
| `GET`/`POST /eventos/saci-halloween`, `/eventos/caca-saci` | Free cosmetic events (duel, hunt) |
| `PUT /cosmetics/profile/name-style` | Estilo do nome no perfil e em todo lugar onde o nome aparece |

## 25. DailyGames (Jogos diários)

Jogos com desafio por dia (palavra, labirinto, blocos) e recompensas em faíscas com scheduler de
distribuição.

Arquivos: `DailyWordController`, `DailyMazeController`, `DailyBlocksController`,
`DailyGameRewardsController` + `DailyGameRewardsScheduler`.

## 26. Push (Notificações web push)

Notificações push no navegador (VAPID): inscrição, cancelamento e configuração.

| Rota | Descrição |
|---|---|
| `GET /push/config` | Chave pública VAPID |
| `POST /push/subscriptions` / `DELETE /push/subscriptions` | Inscrever / cancelar dispositivo |

`PushNotificationListener` espelha cada notificação criada como push depois do commit, **exceto** quando
a pessoa desligou a categoria do kind (`NotificationPreferenceService.allowsPush`). A notificação
in-app é criada do mesmo jeito; segurança, moderação e sanção sempre viram push. Ver [`DIGEST.md`](DIGEST.md).

## 27. Gifs (Busca de GIFs)

Busca de GIFs para os comentários, via provedores externos (Tenor/Giphy).

| Rota | Descrição |
|---|---|
| `GET /gifs/search` | Busca por termo (resultados do provedor configurado) |

## 28. Groups (Grupos)

Grupos da comunidade: membership, tópicos, posts, comentários, reações, posts fixados e moderação
interna (remover membro, remover post).

| Rota | Descrição |
|---|---|
| `GET /{slug}` | Página pública do grupo |
| `GET /{groupId}/posts` / `.../comments` / `.../reactions` | Conteúdo do grupo |
| `GET /{groupId}/members` / `.../requests` / `.../topics` | Membros, pedidos de entrada, tópicos |
| `PATCH /{groupId}` / `DELETE /{groupId}/members/{memberId}` | Editar grupo / remover membro |
| `DELETE /{groupId}/posts/{postId}` / `.../pin` | Remover / fixar post (moderação interna) |

## 29. Companies + Corporate (Empresas)

Páginas de empresa (derivadas das vagas ou criadas diretamente), membros, microprojetos e perfil
corporativo do usuário (importável do LinkedIn).

| Rota | Descrição |
|---|---|
| `GET /empresas` / `GET /empresa/{slug}` / `GET /empresas/mine` | Catálogo (derivado de vagas), página, minhas |
| `POST /empresas` / `PATCH /empresas/{companyId}` / `.../members` | Criar, editar, membros |
| `GET /microprojetos` / `POST /empresas/{companyId}/microprojetos` | Catálogo / criar microprojeto |
| `POST /microprojetos/{projectId}/proposals` / `.../complete` / `.../select` | Propostas, concluir, selecionar |
| `POST /me/business-profile` / `.../import` / `.../activate` | Perfil corporativo, importar, ativar |
| `POST /me/business-profile/username` | Troca o nametag (o `@` e o fim de `/profissional/{nametag}`) |

O **nametag** do perfil profissional vive em namespace próprio (índice único no banco) e é
independente do `@` social: a mesma conta pode ser `aminadojava` no social e `melsantos` no
profissional. É ele que aparece como `@` e no fim da URL pública; trocar muda o endereço (o antigo
deixa de responder) sem tocar no perfil social. A importação do perfil social aceita
`name`, `headline`, `bio`, `avatar`, `banner`, `bannerHtml` e `location` — campo a campo, e a
comparação do nome do campo ignora maiúsculas (`bannerHtml` chega como o cliente escreve).

## 30. CommunityProjects + Projects (Projetos)

Projetos da comunidade (páginas públicas com moderação própria) e projetos no perfil do usuário.

| Rota | Descrição |
|---|---|
| `GET /community-projects` / `.../{username}` / `.../{username}/{projectSlug}` | Catálogo e páginas públicas |
| `POST /community-projects` / `PATCH /{projectId}` / `POST /{projectId}/resubmit` | Criar, editar, reenviar |
| `GET /moderation/community-projects` / `POST /moderation/.../approve` / `.../reject` | Fila de revisão |
| `POST /admin/.../suspend` / `.../unsuspend` | Suspender (admin) |
| `GET /me/projects` / `POST /me/projects` / `PUT /me/projects/{projectId}` | Projetos do perfil |

## 31. Preferences (Preferências)

Preferências do usuário: tema, modo de exibição, notificações e toggles de experiência.

| Rota | Descrição |
|---|---|
| `GET /me/preferences` / `PUT /me/preferences` | Ler / salvar preferências |
| `PUT /me/theme` | Salvar tema selecionado |

## 32. Stats (Estatísticas públicas)

Números públicos da comunidade para a página inicial e o hero.

| Rota | Descrição |
|---|---|
| `GET /stats/community` | Estatísticas agregadas da comunidade |

## 33. Age (Faixa etária)

Fluxo de faixa etária e responsabilidade parental (ECA/Lei 15.211): aceite, convite de responsável,
aceite/recusa/revoada. O admin vê o estado e a faixa, nunca a data exata.

| Rota | Descrição |
|---|---|
| `POST /age/parental/accept` / `.../invite` / `.../reject` / `.../revoke` | Fluxo de responsável |
| `GET /age/parental/mine` | Estado do fluxo |

## 34. Apiv1 (API versionada)

A versão fixada da API: `/api/v1/...` responde o mesmo que `/api/...` (o filtro reescreve o caminho),
e `GET /api/v1` lista o que existe — montado do roteador em tempo real, nunca de lista manual.

Arquivos: `V1ApiController`, `V1AliasFilter`.

## 35. Blog

Blogs com assinatura: posts por usuário, página pública, assinantes e newsletter por e-mail no
publish.

| Rota | Descrição |
|---|---|
| `GET /blogs/{slug}` | Blog público |
| `POST /me/posts` / `PATCH /me/posts/{postId}` / `DELETE /me/posts/{postId}` | Publicar, editar, excluir |
| `POST /{slug}/subscription` / `DELETE /{slug}/subscription` | Assinar / cancelar assinatura |

## 36. Import (LinkedIn e currículo)

Importação de dados do LinkedIn (ZIP do arquivo exportado) e do currículo (PDF) para preencher o
perfil e o diário, com tetos de tamanho e dedupe.

O `Connections.csv` do mesmo arquivo de dados vira uma lista de contatos: cada um é casado por
e-mail exato ou nome completo idêntico (sem ambiguidade) com uma conta do Énóis, e o botão de
convite automático manda a solicitação de conexão para quem ainda não é conexão. O LinkedIn não
expõe a lista de conexões por API (escopo restrito a parceiros aprovados), então o arquivo
exportado é o caminho sancionado. Convites já resolvidos não se repetem em reimportações.

| Rota | Descrição |
|---|---|
| `POST /me/import/linkedin` | Importar o ZIP do export do LinkedIn (prévia) |
| `POST /me/import/linkedin/texto` | Importar texto do export do LinkedIn |
| `POST /me/import/linkedin/contatos` | Importar `Connections.csv` (CSV avulso ou dentro do ZIP) e convidar quem casar |
| `GET /me/import/linkedin/contatos/resumo` | Contagem por estado e o botão de convite automático |
| `PUT /me/import/linkedin/contatos/auto-convite` | Ligar/desligar o convite automático (ligar processa a fila) |
| `GET /me/import/linkedin/contatos` | Lista dos contatos importados e o motivo de cada estado |
| `POST /me/import/resume` | Importar currículo |
| `POST /me/integrations/linkedin` | Integração com a conta do LinkedIn |

Documentação detalhada do fluxo de import em [`JOB_IMPORT_JOBRADAR.md`](JOB_IMPORT_JOBRADAR.md).

## 37. Emotes (Figurinhas de chat)

Catálogo de figurinhas montado **no backend**, em memória, e atualizado de 3 em 3 horas: coleções
globais do BTTV e do FFZ, as mais usadas do FFZ (com o recorte animado) e um retrato popular
embarcado como reserva. O navegador lê uma página por vez e recebe as imagens pelo proxy deste
backend — era o CDN do FFZ que deixava metade da grade vazia (30% de falha, timeout de 12s).

| Rota | Descrição |
|---|---|
| `GET /emotes?page&limit&q&provider` | Página do seletor (40 por padrão, máx. 100) |
| `GET /emotes/index` | Índice compacto `código → imagem` para o texto |
| `GET /emotes/image/{provider}/{id}?variant=thumb\|full` | Imagem com cache em memória |

Todas são leitura pública. Documentação detalhada em [`FIGURINHAS.md`](FIGURINHAS.md).
