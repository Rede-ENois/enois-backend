# API Reference

Base URL: `http://localhost:8080`

Autenticação via header: `Authorization: Bearer <session_token>`

## Autenticação

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/auth/signup` | Cadastro de novo usuário | Não |
| POST | `/api/auth/login` | Login com email + senha | Não |
| POST | `/api/auth/logout` | Revogar sessão atual | Sim |
| GET | `/api/auth/me` | Retorna usuário autenticado | Sim |
| GET | `/api/auth/oauth/{provider}/start` | Iniciar fluxo OAuth | Não |
| GET | `/api/auth/oauth/{provider}/callback` | Callback OAuth | Não |
| POST | `/api/auth/oauth/{provider}/token` | Troca de token OAuth | Não |
| POST | `/api/auth/refresh` | Renovar sessão | Não |
| POST | `/api/auth/password/forgot` | Solicitar reset de senha | Não |
| POST | `/api/auth/password/reset` | Confirmar reset de senha | Não |

### POST /api/auth/signup

```json
{
  "name": "João Silva",
  "email": "joao@email.com",
  "password": "senha123"
}
```

### POST /api/auth/login

```json
{
  "email": "joao@email.com",
  "password": "senha123"
}
```

Resposta (signup e login):
```json
{
  "token": "abc123...",
  "refreshToken": "def456...",
  "user": {
    "id": "user_xxx",
    "username": "joao-silva",
    "name": "João Silva",
    "email": "joao@email.com"
  }
}
```

### POST /api/auth/refresh

```json
{
  "refreshToken": "def456..."
}
```

---

## Perfil

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/me` | Perfil completo do usuário logado | Sim |
| GET | `/api/users/{username}` | Perfil público por username | Sim |
| GET | `/api/users/by-id/{userId}` | Perfil por ID | Sim |
| GET | `/api/users/search` | Buscar usuários | Sim |
| GET | `/api/ranking` | Ranking por reputação | Sim |
| PATCH | `/api/me/profile` | Atualizar perfil | Sim |
| GET | `/api/me/experiences` | Listar experiências | Sim |
| POST | `/api/me/experiences` | Adicionar experiência | Sim |
| PUT | `/api/me/experiences/{id}` | Atualizar experiência | Sim |
| DELETE | `/api/me/experiences/{id}` | Remover experiência | Sim |
| GET | `/api/me/education` | Listar formações | Sim |
| POST | `/api/me/education` | Adicionar formação | Sim |
| PUT | `/api/me/education/{id}` | Atualizar formação | Sim |
| DELETE | `/api/me/education/{id}` | Remover formação | Sim |
| GET | `/api/me/certifications` | Listar certificações | Sim |
| POST | `/api/me/certifications` | Adicionar certificação | Sim |
| PUT | `/api/me/certifications/{id}` | Atualizar certificação | Sim |
| DELETE | `/api/me/certifications/{id}` | Remover certificação | Sim |
| POST | `/api/recommendations/{targetId}` | Recomendar usuário | Sim |
| DELETE | `/api/recommendations/{targetId}` | Remover recomendação | Sim |
| GET | `/api/recommendations/received/{userId}` | Recomendações recebidas | Sim |
| GET | `/api/recommendations/given/{userId}` | Recomendações dadas | Sim |

### PATCH /api/me/profile

```json
{
  "name": "João Silva",
  "headline": "Desenvolvedor Java",
  "company": "Social Coisa",
  "location": "São Paulo",
  "bio": "Descrição...",
  "availability": "disponivel",
  "portfolio": "https://...",
  "openToWork": true,
  "skills": ["Java", "Spring"],
  "goals": ["Aprender Kotlin"],
  "externalLinks": ["https://linkedin.com/in/..."]
}
```

---

## Feed

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/feed/posts` | Publicar post | Sim |
| GET | `/api/feed` | Feed principal | Sim |
| GET | `/api/users/{username}/posts` | Posts do usuário | Sim |
| DELETE | `/api/feed/posts/{postId}` | Deletar post | Sim |
| POST | `/api/feed/posts/bulk-delete` | Deletar posts em lote | Sim |
| POST | `/api/feed/posts/{postId}/comments` | Comentar em post | Sim |
| GET | `/api/feed/posts/{postId}/comments` | Listar comentários | Sim |
| DELETE | `/api/feed/posts/{postId}/comments/{commentId}` | Deletar comentário | Sim |
| POST | `/api/feed/posts/{postId}/reactions` | Reagir a post | Sim |
| DELETE | `/api/feed/posts/{postId}/reactions` | Remover reação | Sim |
| GET | `/api/feed/posts/{postId}/reactions` | Listar reações | Sim |
| POST | `/api/feed/posts/{postId}/saves` | Salvar post | Sim |
| DELETE | `/api/feed/posts/{postId}/saves` | Remover post salvo | Sim |
| POST | `/api/feed/posts/{postId}/shares` | Compartilhar post | Sim |
| GET | `/api/feed/saved` | Posts salvos | Sim |
| POST | `/api/feed/posts/{postId}/comments/{commentId}/reactions` | Reagir a comentário | Sim |
| GET | `/api/feed/posts/{postId}/comments/{commentId}/reactions` | Reações do comentário | Sim |

### POST /api/feed/posts

```json
{
  "type": "INSIGHT",
  "content": "Conteúdo da postagem",
  "visibility": "PUBLIC",
  "tags": ["java", "spring"],
  "mediaIds": ["media_xxx"]
}
```

Tipos: `INSIGHT`, `ARTICLE`, `QUESTION`, `PROJECT`, `OPPORTUNITY`

### GET /api/feed

Query params: `tab=FOR_YOU|FOLLOWING|ALL|DISCUSSIONS|PROJECTS|RECENT`, `cursor=<base64>`, `limit=<1-50>`

Resposta:
```json
{
  "data": [
    {
      "id": "post_xxx",
      "type": "INSIGHT",
      "content": "...",
      "author": { "id": "user_xxx", "name": "João", "username": "joao", "avatar": "..." },
      "createdAt": "2025-01-01T00:00:00Z",
      "reactions": 5,
      "comments": 2,
      "saves": 1,
      "shares": 0,
      "userReaction": "LIKE",
      "userSaved": true,
      "tags": ["java"],
      "media": [...]
    }
  ],
  "nextCursor": "base64encodedtimestamp"
}
```

### POST /api/feed/posts/{postId}/comments

```json
{
  "content": "Excelente post!",
  "parentCommentId": "comment_yyy" // opcional, para respostas
}
```

### POST /api/feed/posts/{postId}/reactions

```json
{
  "type": "LIKE"
}
```

Tipos de reação: `LIKE`, `LOVE`, `CONGRATS`, `INSIGHTFUL`, `CURIOUS`

### POST /api/feed/posts/bulk-delete

```json
{
  "postIds": ["post_xxx", "post_yyy"],
  "reason": "Conteúdo duplicado"
}
```

---

## Rede / Conexões

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/network/users` | Listar conexões | Sim |
| GET | `/api/network/suggestions` | Sugestões de conexão | Sim |
| GET | `/api/network/summary` | Resumo da rede | Sim |
| GET | `/api/network/requests` | Solicitações pendentes | Sim |
| POST | `/api/network/connections/{targetUserId}` | Solicitar conexão | Sim |
| PUT | `/api/network/connections/{targetUserId}/accept` | Aceitar conexão | Sim |
| DELETE | `/api/network/connections/{targetUserId}` | Remover conexão | Sim |
| GET | `/api/network/connections/{targetUserId}/status` | Status da conexão | Sim |

Status: `PENDING`, `ACCEPTED`, `BLOCKED`, `NONE`

---

## Mídia

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/media/uploads` | Criar contrato de upload | Sim |
| GET | `/api/media/{mediaId}` | Obter metadados da mídia | Sim |
| DELETE | `/api/media/{mediaId}` | Solicitar deleção | Sim |

### POST /api/media/uploads

```json
{
  "fileName": "foto.jpg",
  "mimeType": "image/jpeg",
  "sizeBytes": 1024000,
  "purpose": "POST_IMAGE"
}
```

Propósitos: `AVATAR`, `POST_IMAGE`, `COVER`, `BANNER`, `COMMUNITY_AVATAR`, `EVENT_COVER`, `JOB_LOGO`

Resposta:
```json
{
  "mediaId": "media_xxx",
  "cdnUrl": "https://ik.imagekit.io/rede/...",
  "uploadUrl": "https://ik.imagekit.io/rede/...",
  "status": "PENDING"
}
```

---

## Comunidades

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/communities` | Listar comunidades | Sim |
| POST | `/api/communities` | Criar comunidade | Sim |
| GET | `/api/communities/{communityId}` | Detalhes da comunidade | Sim |
| PATCH | `/api/communities/{communityId}` | Atualizar comunidade | Sim |
| POST | `/api/communities/{communityId}/members` | Entrar na comunidade | Sim |
| DELETE | `/api/communities/{communityId}/members` | Sair da comunidade | Sim |
| GET | `/api/communities/{communityId}/members` | Listar membros | Sim |
| GET | `/api/communities/{communityId}/feed` | Feed da comunidade | Sim |

---

## Vagas

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/jobs` | Listar vagas | Sim |
| POST | `/api/jobs` | Criar vaga | Sim |
| GET | `/api/jobs/{jobId}` | Detalhes da vaga | Sim |
| PUT | `/api/jobs/{jobId}` | Atualizar vaga | Sim |
| DELETE | `/api/jobs/{jobId}` | Deletar vaga | Sim |
| POST | `/api/jobs/{jobId}/applications` | Candidatar-se | Sim |
| POST | `/api/jobs/{jobId}/saves` | Salvar vaga | Sim |
| DELETE | `/api/jobs/{jobId}/saves` | Remover vaga salva | Sim |

Nota: Criar vaga requer reputação >= 300.

---

## Eventos

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/events` | Listar eventos | Não (viewer opcional) |
| POST | `/api/events` | Criar evento | Sim |
| GET | `/api/events/{eventId}` | Detalhes do evento | Não (viewer opcional) |
| POST | `/api/events/{eventId}/attendees` | Confirmar presença | Sim |
| DELETE | `/api/events/{eventId}/attendees` | Cancelar presença | Sim |

`EventView` inclui `attending: boolean` — `true` quando o requester autenticado confirmou presença naquele evento; sem sessão, sempre `false`. A listagem permanece pública; com `X-Session-Token` (ou `X-User-Id` + sessão válida) o campo reflete o viewer.

---

## Mentoria

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/mentors` | Listar mentores | Sim |
| POST | `/api/mentors` | Registrar como mentor | Sim |
| PATCH | `/api/mentors/{mentorId}` | Atualizar perfil de mentor | Sim |
| DELETE | `/api/mentors/{mentorId}` | Remover perfil de mentor | Sim |
| GET | `/api/mentors/{mentorId}/slots` | Listar horários | Sim |
| POST | `/api/mentors/{mentorId}/slots` | Criar horário | Sim |
| POST | `/api/mentors/{mentorId}/sessions` | Agendar sessão | Sim |

---

## Mensagens

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/conversations` | Listar conversas | Sim |
| POST | `/api/conversations` | Criar conversa | Sim |
| GET | `/api/conversations/search` | Buscar conversas | Sim |
| GET | `/api/conversations/{conversationId}` | Detalhes da conversa | Sim |
| POST | `/api/conversations/{conversationId}/messages` | Enviar mensagem | Sim |
| GET | `/api/conversations/{conversationId}/messages` | Listar mensagens | Sim |
| POST | `/api/conversations/{conversationId}/read` | Marcar como lida | Sim |
| POST | `/api/conversations/{conversationId}/messages/{messageId}/reactions` | Reagir a mensagem | Sim |
| GET | `/api/conversations/{conversationId}/messages/{messageId}/reactions` | Reações da mensagem | Sim |

---

## Notificações

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/notifications` | Listar notificações | Sim |
| POST | `/api/notifications/{notificationId}/read` | Marcar como lida | Sim |
| POST | `/api/notifications/read-all` | Marcar todas como lidas | Sim |

---

## Busca

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/search` | Busca global | Sim |

Query params: `q=<termo>`, `types=users,communities,jobs,events,mentors`, `limit=<1-50>`

---

## Moderação

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/reports` | Reportar conteúdo | Sim |
| GET | `/api/admin/reports` | Listar reports pendentes | Sim |
| POST | `/api/admin/reports/{reportId}/approve` | Aprovar report (deletar alvo) | Sim |
| POST | `/api/admin/reports/{reportId}/dismiss` | Rejeitar report | Sim |

Razões: `SPAM`, `INAPPROPRIATE`, `ABUSIVE`, `MISINFORMATION`, `COPYRIGHT`, `OTHER`

Target types: `POST`, `COMMENT`, `USER`, `COMMUNITY`, `JOB`, `EVENT`

---

## Operacionais

| Método | Rota | Descrição |
|---|---|---|
| GET | `/api/health` | Health check |
| GET | `/api/openapi.json` | Documentação OpenAPI |
| GET | `/actuator/health` | Actuator health |
| GET | `/actuator/metrics` | Métricas |
| GET | `/actuator/prometheus` | Métricas Prometheus |

---

## Paginação

Listas paginadas retornam:

```json
{
  "data": [...],
  "nextCursor": "base64encoded"
}
```

Envie `cursor` como query param para próxima página. Use `limit` para controlar tamanho (default 20, máx 50).

## Idempotência

Para garantir que operações não sejam executadas mais de uma vez, envie o header:

```
Idempotency-Key: <uuid>
```

A resposta será cacheadada e reutilizada se a mesma chave for reenviada.

## Tratamento de Erros

```json
{
  "error": "tipo_do_erro",
  "message": "Descrição do erro",
  "status": 400
}
```

| Status | Significado |
|---|---|
| 400 | Bad Request (validação) |
| 401 | Não autenticado |
| 403 | Não autorizado |
| 404 | Recurso não encontrado |
| 409 | Conflito (ex: já existe) |
| 429 | Rate limited |
| 500 | Erro interno |
| 503 | Serviço indisponível (circuit breaker) |

## Rate Limiting

| Endpoint | Limite |
|---|---|
| `/api/auth/*` | 10 requisições/minuto |
| `/api/search*` | 30 requisições/minuto |
