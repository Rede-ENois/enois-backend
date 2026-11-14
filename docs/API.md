# API Reference

Base URL: `http://localhost:8080`

Navegadores usam cookies HttpOnly `enois_session` e `enois_refresh`. Escritas com cookie exigem
`X-CSRF-Protection: 1`. Clientes não navegador usam `X-Session-Token`; não há bearer novo.

## Autenticação

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/auth/signup` | Cadastro de novo usuário | Não |
| POST | `/api/auth/login` | Login com email + senha | Não |
| POST | `/api/auth/logout` | Revogar sessão atual | Sim |
| GET | `/api/auth/me` | Retorna usuário autenticado (inclui `onboardingCompleted`) | Sim |
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

Resposta de login concluído (o cadastro inicia confirmação por e-mail, sem sessão):
```json
{
  "expiresAt": "2026-11-14T13:00:00Z",
  "refreshExpiresAt": "2026-12-14T12:00:00Z",
  "accessScope": "FULL",
  "user": {
    "id": "user_xxx",
    "username": "joao-silva",
    "name": "João Silva",
    "email": "joao@email.com"
  }
}
```

### POST /api/auth/refresh

No navegador, o refresh vem do cookie HttpOnly, sem corpo. Sessões `SANCTIONS_ONLY` não têm refresh.
Tokens de sessão não aparecem no JSON.

### Sanções e autenticação limitada

`GET /api/me/sanctions` preserva sanções/recursos e acrescenta `serverNow`, `deadline`, `evidence`
e `canAppeal`. `POST /api/me/sanctions/appeals` abre recurso do próprio titular.
Suspensos autenticam com todos os fatores e recebem somente `accessScope: "SANCTIONS_ONLY"`, não
uma sessão normal. `GET /api/auth/me` recusa esse escopo com `403 ACCOUNT_SUSPENDED`.
O contrato completo de campos, prazos, erros, OAuth e `GET /api/auth/2fa/challenge` está em
[`SANCOES.md`](SANCOES.md).

---

## Perfil

### Presença

| Método | Rota | Resultado | Autenticação |
|---|---|---|---|
| GET | `/api/me/presence` | Escolha da própria conta | Sim |
| PUT | `/api/me/presence` | Salva `{"status":"AVAILABLE|AWAY|DO_NOT_DISTURB|INVISIBLE"}` | Sim |
| POST | `/api/me/presence/heartbeat` | Atualiza atividade; resposta 204 | Sim |
| GET | `/api/users/{username}/presence` | Estado público | Não |

As leituras retornam `{"status":"..."}`. Após dois minutos sem atividade, o estado público
é `OFFLINE`; `INVISIBLE` também aparece como `OFFLINE`. `DO_NOT_DISTURB` impede o push das
categorias configuráveis (segurança, moderação, sanção e recurso continuam), mas mantém a
notificação no aplicativo. O status de texto/ícone de `PUT /api/me/status`
é independente.

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/me` | Perfil completo do usuário logado | Sim |
| GET | `/api/users/{username}` | Perfil público por username | Sim |
| GET | `/api/users/by-id/{userId}` | Perfil por ID | Sim |
| GET | `/api/users/{username}/connections` | Conexões aceitas do perfil (`limit` até 100, mais recentes primeiro) | Opcional |
| GET | `/api/users/{username}/followers` | Quem enviou conexão aceita ao perfil (mesmo formato) | Opcional |
| GET | `/api/users/{username}/experiences` | Experiências do perfil público | Não |
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

Conta banida responde 404 no perfil (por username e por id), nas conexões, nos seguidores e nas
experiências; conta banida ou excluída não aparece na lista de conexões de ninguém. As páginas
públicas laterais seguem a mesma regra e respondem 404 para conta banida: `/users/{username}/posts`,
`/projects`, `/seals` (e evidências), `/games/{username}` (página de criador e jogos),
`/community-projects/{username}`, `/services/{username}/{slug}`, `/corporate/{username}` (e
`/posts`) e `/recommendations/received|given/{userId}`; recomendação de ou para conta banida some
da lista das outras pessoas.

Listagens e páginas públicas identificam pessoas pelo @ (`username`), não pelo id interno da conta:
freelas, página de criador, projetos da comunidade, anúncios do marketplace, pedidos de serviço e
itens do catálogo comunitário de cosméticos não trazem mais `userId`/`sellerId`/`requesterId`/
`creatorId`. Com sessão, quem lê também não vê
as contas que bloqueou (ou que o bloquearam); o perfil bloqueado responde 404 nas listas.

### Profile garden

`GET /api/users/{username}`, `GET /api/users/by-id/{userId}` and `GET /api/me` carry the reputation
garden next to `reputation`, computed on read in the same pass as the counts (never stored):

```json
"gardenStyle": "garden-cerrado",
"garden": { "trajectory": 2, "relations": 1, "contribution": 0, "tenure": 3, "health": 2 }
```

- `trajectory`, `relations`, `contribution` and `tenure` are stages 0..3 from the fraction of each
  reputation component's cap (195, 300, 420 and 120): below 10% is 0, below 35% is 1, below 70% is
  2, otherwise 3.
- `health` is 0..2 from the integrity penalty: none is 2, up to 150 points is 1, more is 0. It only
  dims the garden colors.
- Every account gets a garden, verified or not; the numeric `reputation` keeps its rule (0 without
  a verified identity).
- Lists and write responses (search, ranking, status and badge updates, profile edit) carry
  `garden: null`.
- `gardenStyle` is the equipped `GARDEN` item id, `null` for the default garden.

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
  "externalLinks": ["https://linkedin.com/in/..."],
  "city": "Belo Horizonte",
  "state": "MG",
  "showCity": false
}
```

Cidade e UF (`city`, `state`) são estruturadas e separadas do `location` livre, que continua. Regras:

- Os dois juntos ou nenhum: só um preenchido devolve `400 CITY_STATE_INCOMPLETE`. Os dois ausentes
  mantêm o valor atual; os dois como `""` limpam.
- `state` é a sigla de uma das 27 UFs (maiúscula ou minúscula): fora disso, `400 INVALID_STATE`.
  `city` vem aparada, até 80 caracteres (`400 CITY_TOO_LONG`).
- Privacidade: `showCity` nasce `false`. `GET /api/me` sempre devolve `city`, `state` e `showCity`;
  o perfil público (`/api/users/{username}`, busca, ranking) só devolve `city`/`state` quando
  `showCity` é `true` (senão os campos ficam ausentes do JSON).

---

## Feed

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/feed/posts` | Publicar post | Sim |
| GET | `/api/feed` | Feed principal | Sim |
| POST | `/api/feed/seen` | Avança o marcador "Você está em dia" | Sim |
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
| POST | `/api/feed/posts/{postId}/accepted-answer` | Aceitar a resposta de uma dúvida | Sim |
| DELETE | `/api/feed/posts/{postId}/accepted-answer` | Desfazer a resposta aceita | Sim |

### POST /api/feed/posts

```json
{
  "type": "INSIGHT",
  "content": "Conteúdo da postagem",
  "topic": "TECNOLOGIA",
  "visibility": "PUBLIC",
  "tags": ["java", "spring"],
  "mediaIds": ["media_xxx"]
}
```

Tipos: `INSIGHT`, `ARTICLE`, `QUESTION`, `PROJECT`, `OPPORTUNITY`, `PAGE`, `NEWS`, `TUTORIAL`,
`REVIEW`, `DIVERTIDO`, `MOMENT`, `BLOG`, `BUSINESS`, `POLL`

Assunto (`topic`): obrigatório em post avulso, um da taxonomia (`ContentTaxonomy.TOPICS`). Sem ele,
`400 TOPIC_REQUIRED`; fora da lista, `400 INVALID_TOPIC`. Post de comunidade ou canal herda o assunto
dela e não manda `topic` (`400 TOPIC_FROM_COMMUNITY` / `TOPIC_FROM_CHANNEL`). A mesma regra vale no
agendamento (`POST /api/feed/scheduled`). Publicações do sistema (blog, jogos) e posts antigos podem
seguir sem assunto.

#### Enquete (`type: "POLL"`)

`content` é o **título da enquete** (até 400 caracteres) e `pollOptions` traz as alternativas: de
**2 a 6**, cada uma com até 120 caracteres, sem repetição (a comparação ignora maiúsculas).

```json
{
  "type": "POLL",
  "content": "Qual linguagem você usa no trabalho?",
  "pollOptions": ["Java", "Kotlin", "Go", "TypeScript"]
}
```

Fora de `POLL` o campo `pollOptions` é ignorado (não vira linha no banco). O post volta com
`poll: { options: [...], counts: [0,0,0,0], myVote: null, totalVotes: 0 }`.

### POST /api/feed/posts/{postId}/poll/vote

```json
{ "option": 2 }
```

`option` é a **posição** da alternativa (0..5). O voto é **único e definitivo**: uma pessoa, um
voto, sem troca depois. O segundo voto — na mesma alternativa ou em outra — responde
**409 `ALREADY_VOTED`**, e a contagem não muda. Alternativa fora do intervalo (ou corpo sem
`option`) responde 400 `INVALID_POLL_OPTION`; post que não é enquete responde 400 `NOT_A_POLL`.
Sem sessão: 401. A resposta é o estado atualizado da enquete:

```json
{ "options": ["Java","Kotlin","Go","TypeScript"], "counts": [1,0,0,0], "myVote": 0, "totalVotes": 1 }
```

Todo `PostView` de um post `POLL` traz o campo `poll` (nas leituras de feed e de post único); a
contagem é derivada de `poll_votes`, nunca um contador acumulado.

### GET /api/feed

Query params: `tab=FOR_YOU|FOLLOWING|ALL|DISCUSSIONS|PROJECTS|RECENT`, `cursor=<base64>`, `limit=<1-50>`

Filtro por tipo (aba `ALL`): `type=QUESTION` (repetível). Com `resolved=true` só vêm as dúvidas com
resposta aceita; com `resolved=false`, só as abertas; sem o parâmetro, todas. Ex.:
`GET /api/feed?tab=ALL&type=QUESTION&resolved=false`.

Todo post traz `acceptedCommentId` (string ou `null`) e `resolved` (boolean, `true` quando há
resposta aceita). Todo comentário de `GET /api/feed/posts/{postId}/comments` traz `accepted`
(boolean).

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
      "mediaIds": ["media_b", "media_a"],
      "media": [
        { "id": "media_b", "cdnUrl": "https://api.enois.ia.br/api/storage/uploads/media_b/file.mp4",
          "mimeType": "video/mp4", "width": null, "height": null },
        { "id": "media_a", "cdnUrl": "https://api.enois.ia.br/api/storage/uploads/media_a/file.jpg",
          "mimeType": "image/jpeg", "width": 1200, "height": 800 }
      ]
    }
  ],
  "nextCursor": "base64encodedtimestamp",
  "lastSeenAt": "2025-01-01T00:00:00Z"
}
```

`media`: the attachments of `mediaIds`, in the same order, with what the card needs to draw them at
their final size before the files load. It holds only attachments the reader can still see (a media whose
deletion was requested is left out, so `media` can be shorter than `mediaIds`). `width`/`height` are
pixels as displayed (EXIF orientation applied); `null` for videos, audio, PDFs and images not measured yet.
Every post response carries it (feed pages, single post, profile, community, channel, saved, corporate,
publish/edit/restore responses, and group posts under `/api/groups/{id}/posts`), resolved in one query
per page. `mediaIds` stays for older clients.

`lastSeenAt` (ISO-8601 or `null`): the person's "Você está em dia" marker **as it was before this
visit**. Only `tab=FOR_YOU` ("Para você") carries it, and only on the first page (no `cursor`); cursor
pages and every other tab send `null`, and the front keeps the value it got on the first page. `null` on
the first `FOR_YOU` page means the person never marked anything (do not draw the divider).

`FOR_YOU` pages are date windows: page N holds the `limit` newest visible posts older than the cursor, and
the liked-topics boost only reorders posts inside the page. So no post is skipped or repeated, and a later
page never holds a post newer than an earlier one.

`UNSEEN` excludes the reader's own posts and posts they reacted to or commented on **before** the
page limit. `INTERACTED` likewise selects posts with a reaction or comment before applying the limit;
its history is not capped at three pages. Both keep their existing chronological ISO-8601 cursors.

`TRENDING` orders posts by reaction count, creation time and post ID (all descending). Its opaque
`nextCursor` carries that ranking position and fixes the 24-hour window from the first request.
Pass it back unchanged; a null cursor means the query has no next page. Other tabs keep their existing
cursor format. Malformed or legacy ISO-8601 cursors sent to `TRENDING` return `400 INVALID_CURSOR`;
restart that tab without a cursor after upgrading. Reactions remain live: a post whose count changes
between requests can move in the ranking, so this is not a snapshot of reaction counts.

Front contract (`FOR_YOU` only): show the loaded posts with `createdAt` newer than `lastSeenAt` first,
newest first ("new since your last visit"), then the "Você está em dia" divider, then the remaining posts
in API order. Draw the divider only when there is at least one new post above it and one older post loaded
below it. When the person scrolls past the newest post, call `POST /api/feed/seen` with that post's
`createdAt` (once per visit is enough), only from `FOR_YOU`.

### POST /api/feed/seen

```json
{ "seenUntil": "2025-01-01T12:00:00Z" }
```

Responde `204` sem corpo. O marcador só anda para frente (um valor mais antigo que o atual é ignorado) e
nunca passa do relógio do servidor (valor no futuro vira "agora"). Sem sessão: `401`. Sem `seenUntil`:
`400 INVALID_SEEN_UNTIL`.

### POST /api/feed/posts/{postId}/comments

```json
{
  "content": "Excelente post!",
  "parentCommentId": "comment_yyy" // opcional, para respostas
}
```

### POST /api/feed/posts/{postId}/accepted-answer

Só quem publicou a dúvida (`type=QUESTION`) escolhe a resposta. Pode trocar a resposta quando quiser.

```json
{ "commentId": "comment_yyy" }
```

Resposta `200`:
```json
{ "postId": "post_xxx", "acceptedCommentId": "comment_yyy", "resolved": true }
```

O comentário precisa ser desta dúvida (raiz ou resposta), estar ativo e ser de outra pessoa. Quem
respondeu recebe a notificação `ANSWER_ACCEPTED` e, na primeira resposta aceita da dúvida, a
recompensa de ajuda (ver `docs/COIN_ECONOMY.md`).

Erros: `400 NOT_A_QUESTION`, `400 INVALID_ANSWER_COMMENT`, `400 OWN_ANSWER`,
`403 NOT_QUESTION_AUTHOR`, `404 POST_NOT_FOUND`.

Se o comentário aceito for apagado (pelo autor, pela limpeza de banimento ou pela purga de spam), a
dúvida volta a ficar aberta (`acceptedCommentId: null`, `resolved: false`). A recompensa já paga não
volta.

`DELETE /api/feed/posts/{postId}/accepted-answer` reabre a dúvida (mesmas regras de autoria e tipo) e
responde `{ "postId": "post_xxx", "acceptedCommentId": null, "resolved": false }`.

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

## Onboarding (primeira entrada)

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/me/onboarding/starter-pack?topics=A,B&skills=x,y` | Sugestões para começar | Sim |
| POST | `/api/me/onboarding/complete` | Aplica as escolhas e conclui | Sim |
| POST | `/api/me/onboarding/skip` | Conclui sem mudar nada | Sim |

`onboardingCompleted` (boolean) vem em `/auth/me` e nas respostas de login/verificação: `false`
enquanto a conta ainda não concluiu nem pulou. Vale para toda conta, inclusive as anteriores à
V210 (a V224 desfez o preenchimento que as marcava como concluídas).

`starter-pack` devolve até 6 comunidades (sem as que a pessoa já participa), até 6 grupos ativos
de entrada aberta (sem os que já participa) e até 8 pessoas (contas ativas, verificadas e adultas,
sem conexão, pedido pendente ou bloqueio em qualquer direção, e sem quem a pessoa silenciou; nada é sugerido a quem é menor).
Primeiro o que casa com os assuntos/habilidades, depois o mais popular, então a lista não fica
vazia quando ninguém casa. `topics` usa a taxonomia de `/me/preferences` (desconhecido = `400`).

```json
{
  "communities": [{ "id": "", "name": "", "description": "", "topic": "PETS", "avatar": null, "memberCount": 12 }],
  "groups": [{ "id": "", "slug": "", "name": "", "description": "", "topic": "PETS", "avatar": null, "memberCount": 4 }],
  "people": [{ "id": "", "username": "", "name": "", "headline": null, "avatar": null }]
}
```

`complete` recebe (todos opcionais):

```json
{ "topics": ["PETS"], "skills": ["java"], "communityIds": [], "groupIds": [], "connectUserIds": [] }
```

Assuntos entram em "quero ver" e habilidades no perfil, somando ao que já existe. Entra nas
comunidades e nos grupos de entrada aberta pelas mesmas regras da ação manual (grupo com aprovação
é ignorado, sem pedido) e manda pedido de conexão pela rede. Tetos: 6 comunidades, 6 grupos, 8
pedidos, 20 habilidades de até 60 caracteres; acima disso `400` (`TOO_MANY_COMMUNITIES`,
`TOO_MANY_GROUPS`, `TOO_MANY_CONNECTIONS`, `TOO_MANY_SKILLS`, `SKILL_TOO_LONG`) e nada muda. Id
inválido (inexistente, a própria conta, bloqueio) é pulado. Repetir a chamada não duplica nada. A
resposta lista o que ficou valendo (já existia ou foi feito agora):

```json
{ "onboardingCompleted": true, "joinedCommunityIds": [], "joinedGroupIds": [], "requestedUserIds": [] }
```

`skip` devolve o mesmo formato com as listas vazias.

---

## Rede / Conexões

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/network/users` | Listar conexões | Sim |
| GET | `/api/network/suggestions` | Sugestões de conexão. Amigos de amigos primeiro, ordenados por conexões em comum (`mutualConnections`); completa com outras pessoas. Pessoa bloqueada ou conta removida fica de fora | Sim |
| GET | `/api/network/summary` | Resumo da rede | Sim |
| GET | `/api/network/requests` | Solicitações pendentes (enviadas e recebidas, mais nova primeiro), cada uma com `direction` (`INCOMING`/`OUTGOING`) e `peer` (a pessoa do outro lado). Pessoa bloqueada ou conta removida fica de fora; o `pendingRequests` do `/summary` conta só as recebidas visíveis | Sim |
| POST | `/api/network/connections/{targetUserId}` | Solicitar conexão | Sim |
| PUT | `/api/network/connections/{targetUserId}/accept` | Aceitar conexão | Sim |
| DELETE | `/api/network/connections/{targetUserId}` | Remover conexão | Sim |
| GET | `/api/network/connections/{targetUserId}/status` | Status da conexão | Sim |

Status: `PENDING`, `ACCEPTED`, `REMOVED`, `BLOCKED`, `NONE`

`GET .../status` responde também `requestedByMe`: `true` quando o pedido pendente foi enviado por
quem consulta, `false` quando foi recebido e em qualquer outro status. O `status` é o mesmo para os
dois lados da conexão; é esse campo que diferencia "cancelar" de "aceitar".

Pedir conexão a uma conta que não existe responde `404 USER_NOT_FOUND`.

---

## Miniatura de link

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/link-preview?url=...` | Cartão de um link publicado (página da casa ou site de fora) | Não (sessão opcional) |

Resposta: `200` com `preview` preenchido ou `preview: null` quando não há o que mostrar. A sessão,
quando existe, só muda o que uma página interna privada mostra. Detalhes em
[LINK_PREVIEW.md](LINK_PREVIEW.md).

```json
{
  "preview": {
    "kind": "EXTERNAL",
    "url": "https://exemplo.com/artigo",
    "title": "Título do artigo",
    "description": "Resumo que o site declara para compartilhamento.",
    "imageUrl": "https://exemplo.com/capa.png",
    "siteName": "Exemplo"
  }
}
```

---

## Mídia

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/media/uploads` | Criar contrato de upload | Sim |
| GET | `/api/media/{mediaId}` | Obter metadados da mídia | Não |
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

Propósitos: `AVATAR`, `POST_IMAGE`, `COVER`, `BANNER`, `COMMUNITY_AVATAR`, `EVENT_COVER`, `JOB_LOGO`,
`RESUME` (só PDF) e `REPORT_CAPTURE` (só imagem; captura de denúncia de chamada, servida apenas a
moderadores e nunca publicada).

Resposta:
```json
{
  "mediaId": "media_xxx",
  "cdnUrl": "https://ik.imagekit.io/rede/...",
  "uploadUrl": "https://ik.imagekit.io/rede/...",
  "status": "PENDING"
}
```

### GET /api/media/{mediaId}

Public read (the id only appears in content anyone can already read). Response:

```json
{
  "id": "media_xxx",
  "ownerId": "user_xxx",
  "fileName": "foto.jpg",
  "mimeType": "image/jpeg",
  "size": 1024000,
  "purpose": "POST_IMAGE",
  "objectKey": "uploads/media_xxx/file.jpg",
  "cdnUrl": "https://api.enois.ia.br/api/storage/uploads/media_xxx/file.jpg",
  "status": "PENDING",
  "deleteStatus": "NONE",
  "createdAt": "2026-11-03T12:00:00Z",
  "expiresAt": "2026-11-03T13:00:00Z",
  "deletedAt": null,
  "width": 1200,
  "height": 800
}
```

`width`/`height`: pixels as displayed (EXIF orientation applied). Measured from the sanitized bytes when
`PUT /api/storage/{key}` stores an image; older images are filled by the dimensions backfill (see
[`MIDIA-S3.md`](MIDIA-S3.md)). `null` for non-images and for images that could not be measured.

---

## Figurinhas (emotes de texto)

Leitura pública (sem sessão): post público desenha figurinha para quem nem entrou. O catálogo é
montado no backend e atualizado de 3 em 3 horas; ver [`FIGURINHAS.md`](FIGURINHAS.md).

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/emotes?page=&limit=&q=&provider=` | Página do seletor (40 por padrão, máx. 100) | Não |
| GET | `/api/emotes/index` | `código → endereço da imagem` para a leitura do texto | Não |
| GET | `/api/emotes/image/{provider}/{id}?variant=thumb\|full` | Imagem (cache do backend) | Não |

`/api/emotes` responde:

```json
{
  "items": [
    {
      "code": "catJAM",
      "provider": "FFZ",
      "id": "720564",
      "animated": true,
      "popularRank": 41,
      "image": "/emotes/image/FFZ/720564?variant=full&a=1",
      "thumb": "/emotes/image/FFZ/720564?variant=thumb"
    }
  ],
  "page": 1,
  "limit": 40,
  "total": 1127,
  "totalPages": 29,
  "generatedAt": "2026-10-29T01:53:14.352Z"
}
```

Os caminhos de imagem são **relativos ao host da API** (o gateway tira o `/api`). O `&a=1` marca
figurinha com versão em movimento.

---

## Comunidades

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/communities` | Listar comunidades | Sim |
| POST | `/api/communities` | Criar comunidade | Sim |
| GET | `/api/communities/{communityId}` | Detalhes da comunidade | Sim |
| PATCH | `/api/communities/{communityId}` | Atualizar comunidade | Sim |
| POST | `/api/communities/{communityId}/members` | Entrar na comunidade (`404 COMMUNITY_NOT_FOUND` se não existe) | Sim |
| DELETE | `/api/communities/{communityId}/members` | Sair da comunidade | Sim |
| GET | `/api/communities/{communityId}/members` | Listar membros | Sim |
| GET | `/api/communities/{communityId}/feed` | Feed da comunidade | Sim |
| GET | `/api/communities/local` | Comunidades perto de mim (cidade/UF do perfil) | Sim (sessão obrigatória) |

### Comunidades por cidade

`POST` e `PATCH /api/communities` aceitam `city` e `state` opcionais, com as mesmas regras do
perfil (os dois ou nenhum, UF válida, `""` nos dois limpa no PATCH, ausentes mantêm). O
`CommunityView` devolve `city` e `state` (ausentes quando a comunidade não tem lugar).

- `GET /api/communities?city=<cidade>&state=<UF>`: filtra pela cidade sem acento e sem caixa
  ("sao paulo" acha "São Paulo"). `state` sozinho filtra a UF; `city` sem `state` é
  `400 CITY_STATE_INCOMPLETE`. Combina com `topic`, `q` e `cursor`.
- `GET /api/communities/local?limit=20` (máx. 50): usa a cidade/UF do **próprio** perfil. Vêm as
  comunidades da mesma cidade primeiro, depois as do resto do estado; dentro de cada grupo, por
  número de membros (desc). Perfil sem cidade devolve `{"items": [], "nextCursor": null}`.

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

Nota: Criar vaga requer reputação > 150.

`POST /api/jobs` accepts an optional `companyId`: the poster must be RECRUITER or above on that
company page (403 otherwise) and the job takes the page name and logo. Job views (list and detail)
carry `companySlug` (null when the job is not linked to a page).

---

## Company pages

| Method | Route | Description | Auth |
|---|---|---|---|
| GET | `/api/empresas/diretorio?query=&sector=&limit=60` | Directory (limit 1..200): pages first, then names from jobs, each by open jobs + projects | No |
| GET | `/api/empresas/setores` | Distinct sectors of active pages | No |
| GET | `/api/empresa/{slug}` | Page with projects, jobs, stats, reviews, viewer state | No (session adds viewer state) |

Página fechada (removida pela moderação ou pela própria empresa) sai do diretório (`GET /api/empresas`)
e responde `404 COMPANY_NOT_FOUND` em `/api/empresa/{slug}`; operações sobre ela dão `409 COMPANY_CLOSED`.
| POST / DELETE | `/api/empresas/{companyId}/follow` | Follow / unfollow (idempotent) | Yes |
| PUT / DELETE | `/api/microprojetos/{id}` | Editar ou remover da vitrine um projeto aberto sem candidaturas | Yes (EDITOR or above) |
| POST | `/api/microprojetos/{id}/proposals` | Proposal from the active professional profile | Yes |
| POST | `/api/microprojetos/{id}/complete` | Complete, optionally with a review | Yes (EDITOR or above) |

```text
PersonView     { id, username, name, headline, avatar, identity: "CORPORATE" | "SOCIAL", href }
Directory item { kind: "PAGE" | "JOBS" | "EXTERNAL", slug | null, name, logo | null, sector | null,
                 location | null, openJobs, openProjects, followers }
Page adds      jobs: [{ id, title, location, type, level, createdAt }] (max 20)
               stats: { openProjects, completedProjects, openJobs, followers }
               following: boolean
               reviews: [{ projectId, projectTitle, rating, text | null, createdAt, person }] (latest 6)
               canPropose: "OK" | "SIGN_IN" | "PROFESSIONAL_REQUIRED" | "TEAM"
Follow         -> { following, followers }
Proposal       -> { id, status, conversationId }   (403 NO_ACTIVE_CORPORATE_PROFILE without an
                  active professional profile)
Team proposals { id, userId, message, status, proposedAt, person, conversationId }
Complete body  { rating?: 1..5, text?: <= 500 }     (400 INVALID_RATING)
```

---

## Eventos

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/events` | Listar eventos | Não (viewer opcional) |
| POST | `/api/events` | Criar evento | Sim |
| GET | `/api/events/{eventId}` | Detalhes do evento | Não (viewer opcional) |
| POST | `/api/events/{eventId}/attendees` | Confirmar presença | Sim |
| DELETE | `/api/events/{eventId}/attendees` | Cancelar presença | Sim |
| PATCH | `/api/events/{eventId}/recurrence` | Ligar, trocar ou desligar a recorrência (só quem criou) | Sim |
| PATCH | `/api/events/{eventId}` | Editar o evento (título, data, hora, formato, host, capa, stream, local; só quem criou) | Sim |
| DELETE | `/api/events/{eventId}` | Excluir o evento (só quem criou) | Sim |

`PATCH /api/events/{eventId}` valida exatamente igual à criação (`TextFieldLimit`, horário realista,
`EVENT_CITY_NOT_IN_PERSON`) e devolve o `EventView`. Fora do dono: `403 NOT_EVENT_OWNER`.

`POST /api/events` aceita `city` e `state` opcionais (mesmas regras do perfil). Evento `ONLINE` com
cidade é `400 EVENT_CITY_NOT_IN_PERSON`. `GET /api/events?city=<cidade>&state=<UF>` lista só
encontros não online (`IN_PERSON`, `HYBRID`) daquele lugar, com a cidade comparada sem acento;
`state` sozinho filtra a UF. O `EventView` devolve `city` e `state`.

`EventView` inclui `attending: boolean` — `true` quando o requester autenticado confirmou presença naquele evento; sem sessão, sempre `false`. A listagem permanece pública; com `X-Session-Token` (ou `X-User-Id` + sessão válida) o campo reflete o viewer.

### Rituais (eventos recorrentes)

- `POST /api/events` aceita `recurrence`: `NONE` (padrão, quando ausente), `WEEKLY`, `BIWEEKLY` ou `MONTHLY`.
  Valor fora da lista: `400 INVALID_RECURRENCE`. Evento recorrente precisa de horário `HH:MM`: `400 INVALID_EVENT_TIME`.
- `PATCH /api/events/{eventId}/recurrence` com `{"recurrence": "WEEKLY"}` devolve o `EventView`. Só quem criou
  (`403 NOT_EVENT_OWNER`). A recorrência vale para a série inteira; `NONE` encerra a série.
- `GET /api/events?recurring=true` lista só eventos com recorrência (combina com `format`, `city`, `state`, `includePast`, `cursor`). A ocorrência nova herda `city`/`state` da anterior.
- `EventView` ganha `recurrence: string` (`NONE|WEEKLY|BIWEEKLY|MONTHLY`), `seriesId: string|null` (id da primeira
  ocorrência), `nextOccurrenceDate: string|null` (`AAAA-MM-DD`, calculada; `null` para evento avulso) e
  `canManageRecurrence: boolean` (`true` só quando o viewer autenticado criou o evento; `false` para anônimo e
  para os demais). O id de quem criou não é exposto.
- De hora em hora (`rede.events.recurrence.*`, fuso `America/Sao_Paulo`), a ocorrência mais recente que já terminou
  agenda a próxima (mesmo título, formato, host, capa, link e local; presenças **não** são copiadas) e quem confirmou
  presença recebe a notificação `EVENT_NEXT_OCCURRENCE` com `href` `/eventos?evento={id}`. Mensal conta a partir
  da primeira ocorrência: dia 31 cai no último dia dos meses curtos e volta ao 31 depois.
- A série para (recorrência volta a `NONE`) quando o dono desliga, quando uma ocorrência é apagada (pelo dono ou
  pela moderação), quando a conta do dono foi excluída ou está sancionada, ou quando o evento cai no filtro de
  texto suspeito.

---

## Busca de blogs

`GET /api/blogs` aceita `query` opcional para buscar por título ou descrição antes de aplicar
`limit`. Uma busca em branco mantém a listagem sem filtro. Os caracteres `%`, `_` e `!` são
tratados como texto literal, assim como na busca de mentorias.

## Feed RSS/Atom do blog

`GET /api/blogs/{slug}/feed.xml` devolve o feed Atom 1.0 de um blog (`application/atom+xml`), um
feed por blog, para quem acompanha por um leitor de RSS. É público e **não exige sessão nem
`Origin`** (leitor de RSS não é navegador); as demais rotas de dados do blog continuam exigindo
origem. Mesmas regras de visibilidade da página do blog: conta banida ou título suspeito = `404
BLOG_NOT_FOUND`.

- Os 20 posts mais recentes, cada um com título, link para `/blog/{slug}/{postId}`, datas e um
  **resumo em texto puro** (até 300 caracteres). O corpo completo não vai no feed: ele pode ser
  HTML de tema próprio e o leitor é um cliente que não controlamos.
- Os links usam `rede.frontend.url` (`FRONTEND_URL`) como base.
- Resposta com `Cache-Control: public, max-age=300`.

## Mentoria

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/mentors` | Listar mentores; `query` opcional busca por nome, username ou especialidade, combinado com `expertise`, `cursor` e `limit` | Sim |
| POST | `/api/mentors` | Registrar como mentor | Sim |
| PATCH | `/api/mentors/{mentorId}` | Atualizar perfil de mentor | Sim |
| DELETE | `/api/mentors/{mentorId}` | Remover perfil de mentor | Sim |
| GET | `/api/mentors/{mentorId}/slots` | Listar horários | Sim |
| POST | `/api/mentors/{mentorId}/slots` | Criar horário | Sim |
| POST | `/api/mentors/{mentorId}/sessions` | Agendar sessão | Sim |
| GET | `/api/mentors/{mentorId}/sessions` | Inscrições recebidas (só o dono) | Sim |
| GET | `/api/mentors/sessions/mine` | Minhas sessões como mentorado | Sim |
| POST | `/api/mentors/{mentorId}/sessions/{sessionId}/complete` | Concluir sessão (libera o escrow) | Sim |
| POST | `/api/mentors/{mentorId}/sessions/{sessionId}/cancel` | Cancelar sessão (devolve o escrow) | Sim |
| GET | `/api/mentors/{mentorId}/room` | Sala configurada (só o dono) | Sim |
| POST | `/api/mentors/{mentorId}/sessions/{sessionId}/join` | Entrada na sala da sessão (participantes) | Sim |
| POST | `/api/mentoring/{sessionId}/gateway/entry/{userId}` | Autorização pedida pelo servidor de reunião | Segredo de servidor |

### Sala da sessão

`roomMode` no corpo de `POST`/`PATCH /api/mentors` decide de onde vem a sala:

| `roomMode` | Sala |
|---|---|
| `EPAPO_SESSION` (padrão) | Uma sala do Epapo por sessão, criada no agendamento; nada a configurar |
| `EPAPO_PERMANENT` | Uma sala fixa do Epapo (`roomSlug` opcional; sem ele o nome vem do id) |
| `CUSTOM` | Sala própria (`meetingUrl`, https) — Meet, Zoom, o que o mentor já usa |

Sem `roomMode`, o campo é deduzido: link presente mantém a sala própria (compatibilidade com quem já
tinha uma configurada) e nenhum link cai na sala do Epapo por sessão. Ao voltar para um modo Epapo, o
`meetingUrl` antigo sai do perfil (e mandar `meetingUrl` vazio também devolve a sessão ao Epapo).

A sala é resolvida e gravada na sessão no agendamento (`roomUrl`/`roomMode` em `/mentors/sessions/mine`
e nas inscrições recebidas), então trocar a sala do perfil não move uma sessão já marcada. A base é
`rede.mentoring.room.base-url` (`REDE_MENTORING_ROOM_BASE_URL`); sem ela, vale a origem pública do
Epapo (`rede.calls.epapo.url`). Sem nenhuma das duas a lista de sessões continua respondendo, só a
entrada na sala responde 503.

`POST .../join` é a entrada da tela: confere que quem pede participa da sessão e devolve
`{sessionId, roomUrl, jwt, tokenTtlSeconds}`. O `jwt` é emitido pelo gateway do Epapo depois de ele
perguntar a esta aplicação (`POST /mentoring/{sessionId}/gateway/entry/{userId}`, com o segredo
compartilhado) se aquela pessoa entra naquela sala; o segredo de assinatura nunca sai do servidor.
Sala própria do mentor (`CUSTOM`) volta só com o link e `jwt` nulo — o link externo já é a entrada.

---

## Par de estudo

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/study-pairs/requests` | Procurar par para um objetivo (`{goal, topic?}`) | Sim |
| DELETE | `/api/study-pairs/requests/current` | Cancelar o pedido aberto | Sim |
| GET | `/api/study-pairs/me` | Pedido aberto, pares ativos e check-ins das últimas 4 semanas | Sim |
| POST | `/api/study-pairs/{pairId}/checkins` | Check-in da semana (`{note}`, até 500); repetir na semana edita | Sim (membro) |
| POST | `/api/study-pairs/{pairId}/end` | Encerrar o par | Sim (membro) |
| GET | `/api/study-pairs/goals/popular` | Objetivos com pedidos abertos (sem dado pessoal) | Sim |
| GET | `/api/study-pairs/search?q=&limit=` | Buscar objetivos abertos por texto (anônimo, sem revelar quem pediu) | Sim |

Contrato completo, regras e códigos de erro em [`STUDY_PAIRS.md`](STUDY_PAIRS.md).

---

## Mensagens

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/conversations` | Listar conversas (`?context=SOCIAL\|PROFESSIONAL`, see SOCIAL_PROFESSIONAL_CONTEXTS.md); `lastMessageFrom` is the account id of whoever sent the preview | Sim |
| POST | `/api/conversations` | Criar conversa | Sim |
| GET | `/api/conversations/search` | Buscar conversas | Sim |
| GET | `/api/conversations/{conversationId}` | Detalhes da conversa | Sim |
| POST | `/api/conversations/{conversationId}/messages` | Enviar mensagem | Sim |
| GET | `/api/conversations/{conversationId}/messages` | Listar mensagens | Sim |
| POST | `/api/conversations/{conversationId}/read` | Marcar como lida (as notificações de mensagem da conversa também ficam lidas) | Sim |
| POST | `/api/conversations/{conversationId}/viewing` | Heartbeat da conversa aberta na tela (a cada ~20s): enquanto vale (45s), mensagens novas dela não notificam nem mandam push para quem está olhando. 204 | Sim |
| DELETE | `/api/conversations/{conversationId}/viewing` | A conversa saiu da tela: volta a notificar. 204 | Sim |
| POST | `/api/conversations/{conversationId}/messages/{messageId}/reactions` | Reagir a mensagem | Sim |
| GET | `/api/conversations/{conversationId}/messages/{messageId}/reactions` | Reações da mensagem | Sim |
| GET | `/api/conversations/stream` | Stream SSE (`text/event-stream`) da caixa de entrada: evento `conversation` com `{"conversationId"}` a cada mensagem nova, leitura ou reação; comentário `ping` a cada 25s | Sim (cookie) |

O stream só avisa **qual** conversa mudou; o conteúdo (cifrado ou não) é buscado pelas rotas acima,
com a autorização de sempre. O aviso sai depois do commit, então o refetch já enxerga a mudança. Até
5 streams por usuário (abas/aparelhos); o sexto fecha o mais antigo. Sem sessão a resposta é o status
puro (401), sem corpo. O registro de streams vive na memória da instância: correto enquanto a
produção roda um backend só; uma segunda instância exige difusão entre instâncias antes de escalar.

---

## Chamadas de áudio e vídeo

1:1 na conversa e **"Tamo junto"** no grupo. O painel é embutido no app (sem aba nova), e a
entrada na sala é autorizada no servidor com token assinado — detalhes em
[`CHAMADAS.md`](CHAMADAS.md).

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/conversations/{conversationId}/calls` | Começa (ou devolve) a chamada 1:1. Corpo opcional `{"kind":"AUDIO\|VIDEO"}` (padrão `AUDIO`) | Sim |
| POST | `/api/groups/{groupId}/calls` | Começa (ou devolve) a chamada "Tamo junto" do grupo. Quem abre já entra | Sim |
| POST | `/api/groups/{groupId}/topics/{topicId}/calls` | Começa (ou devolve) o "Tamo junto" do tópico. Corpo opcional `{"kind":"AUDIO\|VIDEO"}` | Sim |
| GET | `/api/conversations/{conversationId}/calls/open` | Chamada aberta da conversa (corpo `null` quando não há) | Sim |
| GET | `/api/groups/{groupId}/calls/open` | Chamada aberta do grupo (só a sala geral, não as de tópico) | Sim |
| GET | `/api/groups/{groupId}/topics/{topicId}/calls/open` | Chamada aberta do tópico | Sim |
| GET | `/api/calls/open` | Chamadas abertas em que a pessoa está — para voltar de outra tela | Sim |
| GET | `/api/calls/{callId}` | Estado da chamada | Sim |
| POST | `/api/calls/{callId}/answer` | Atender (só 1:1) | Sim |
| POST | `/api/calls/{callId}/decline` | Recusar | Sim |
| POST | `/api/calls/{callId}/cancel` | Quem iniciou desiste | Sim |
| POST | `/api/calls/{callId}/join` | Autoriza e devolve `host`, `room`, `jwt` (emitido pelo gateway da Epapo) e `call` | Sim |
| POST | `/api/calls/{callId}/leave` | Sair (no grupo a chamada continua) | Sim |
| POST | `/api/calls/{callId}/end` | Encerrar para todos | Sim |
| POST | `/api/calls/{callId}/fail` | A tela perdeu a mídia: falha explícita em vez de sessão pendurada | Sim |
| GET | `/api/calls/{callId}/reportable` | Quem entrou na sala e pode ser denunciado (`[{userId, displayName}]`, sem quem denuncia) | Sim |
| POST | `/api/calls/{callId}/reports` | Denúncia da chamada: `{"reportedUserId","reason","description","mediaId"?}` | Sim |

A denúncia de chamada só aceita quem **entrou** na sala (`NOT_IN_CALL`), recusa apontar a si mesmo
(`INVALID_REPORTED_USER`) e a repetição (`409 ALREADY_REPORTED`). O `mediaId` é a captura do momento,
enviada antes com propósito `REPORT_CAPTURE`; ela não é publicada e só é servida a moderadores.

Estados: `RINGING` (1:1 tocando), `CONNECTING`, `ACTIVE`, `DECLINED`, `MISSED`, `CANCELLED`,
`ENDED`, `FAILED`. Repetir `answer`/`decline`/`cancel`/`start` é idempotente; transições inválidas
respondem `409` com `CALL_STATE_CONFLICT` (ou o código específico do estado, como `CALL_MISSED`).
O stream `/api/conversations/stream` também emite `event: call` com
`{"callId","state","conversationId"?,"groupId"?}`.

Duas rotas internas, para o gateway da Epapo (não são para o app):

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/calls/rooms/{room}` | A qual chamada e a que estado a sala pertence | `X-Enois-Authorize` |
| POST | `/api/calls/{callId}/gateway/entry/{userId}` | Autoriza (e registra) a entrada de uma pessoa na chamada | `X-Enois-Authorize` |

Sem `REDE_CALLS_EPAPO_URL`, ou sem o gateway configurado (`REDE_CALLS_EPAPO_GATEWAY_URL` +
`REDE_CALLS_EPAPO_INTERNAL_TOKEN`), a API responde `503 CALLS_NOT_CONFIGURED` — nunca abre sala sem
controle de acesso.

---

## Notificações

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/notifications` | Listar notificações (`?context=SOCIAL\|PROFESSIONAL`) | Sim |
| POST | `/api/notifications/{notificationId}/read` | Marcar como lida | Sim |
| POST | `/api/notifications/read-all` | Marcar todas como lidas | Sim |
| GET | `/api/notifications/unread-summary` | Unread count per area for menu indicators (`?context=SOCIAL\|PROFESSIONAL`) | Sim |
| GET | `/api/me/notification-preferences` | Preferências de push, resumo semanal e avisos de projetos | Sim |
| PUT | `/api/me/notification-preferences` | Atualização parcial (campo ausente/nulo mantém o valor) | Sim |
| GET | `/api/notifications/digest/unsubscribe?token=` | Descadastro do resumo semanal (link do e-mail, HTML) | Não (token) |
| POST | `/api/notifications/digest/unsubscribe?token=` | Descadastro em um clique (RFC 8058, cliente de e-mail): 200/400 sem corpo | Não (token) |
| POST | `/api/blogs/descadastrar?token=` | Mesmo descadastro em um clique para a newsletter do blog | Não (token) |

Preferências (GET e resposta do PUT):

```json
{ "weeklyDigest": true, "pushSocial": true, "pushMessages": true, "pushGroups": true,
  "pushJobs": true, "pushSystem": true, "communityProjects": false,
  "updatedAt": "2026-10-30T12:00:00Z" }
```

`updatedAt` é `null` enquanto a pessoa nunca mudou nada. `communityProjects` é `false` por padrão,
inclusive para contas existentes; os demais padrões continuam `true`. O PUT autenticado altera só
a conta da sessão: campos ausentes ou nulos preservam o valor, inclusive para clientes anteriores
que não enviam `communityProjects`. Segurança, moderação e sanções sempre viram push.
Regras e categorias em [`DIGEST.md`](DIGEST.md).

Com `communityProjects: true`, a pessoa recebe `NEW_COMMUNITY_PROJECT` para novos projetos
publicados pela primeira aprovação da moderação. A entrega é assíncrona e paginada pela outbox;
o autor não recebe o próprio aviso, bloqueios nos dois sentidos são respeitados e a adesão é
conferida na entrega. Edição e restauração de suspensão não são novos lançamentos.
O aviso tem `context: "SOCIAL"`, `module: "COMMUNITY_PROJECTS"` e `href: "/projetos/{username}/{slug}"`.

Each item of `GET /api/notifications` carries `module`, the interface area of its kind (same
`NotificationModule` names as below, e.g. `EVENTS`, `OTHER`), so the app hides or flags notifications
by area without guessing from the kind.

Each item also carries `place` and `eventDate`, resolved at read time from its `href` (one query per
kind of target for the whole page):

- `place`: `{ "type": "COMMUNITY" | "GROUP", "id": string, "name": string, "color": null }` or
  `null`. `/comunidades/{id}` and `/api/feed/posts/{postId}` of a post published in a community give
  the community; `/grupos/{slug}` gives the group. A deleted community or a closed group gives
  `null`. `color` is `null` while communities and groups have no color of their own.
- `eventDate`: `"YYYY-MM-DD"`, the day of the event occurrence an `/eventos?evento={id}` href points
  at (each occurrence of a recurring event is its own event), or `null` (other hrefs, deleted event,
  unreadable date).

```json
{ "id": "notification_...", "kind": "GROUP_NEW_POST", "module": "GROUPS", "href": "/grupos/roda-bh",
  "place": { "type": "GROUP", "id": "group_...", "name": "Roda BH", "color": null }, "eventDate": null }
```

Unread summary (`GET /api/notifications/unread-summary`): counts the person's unread notifications,
leaving out those whose actor is blocked (either way) and applying the same `context` filter as the
list. Messages (`NEW_MESSAGE`, `NEW_WORK_MESSAGE`) and `CONNECTION_REQUEST` are not counted: they have
their own unread signals and the notices panel does not list them. Only areas with unread notifications appear in `byModule`; an area clears when its
notifications are read (`/read` or `/read-all`). The kind to area mapping is
`NotificationModule` (`MESSAGES`, `EVENTS`, `JOBS`, `COMPANIES`, `MENTORING`, `DAILY_GAMES`,
`COMMUNITY_GAMES`, `COMMUNITY_PROJECTS`, `GROUPS`, `STUDY_PAIRS`, `BLOGS`, `NETWORK`, `OTHER`).

`byCommunity` counts the same unread notifications per community id, for those whose `place` is a
community (only communities with unread ones appear). `eventsToday` is the number of events happening
today (America/Sao_Paulo) whose attendance the person confirmed; events are social, so it is `0` with
`context=PROFESSIONAL`.

```json
{ "total": 5, "byModule": { "MESSAGES": 3, "GROUPS": 1, "OTHER": 1 },
  "byCommunity": { "community_...": 1 }, "eventsToday": 1 }
```

Mudança de estado da candidatura também gera a notificação `JOB_APPLICATION_STATUS`
(`href` = `/vagas/{jobId}`), além da mensagem direta e do e-mail.

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
| POST | `/api/admin/reports/{reportId}/approve` | Aprovar report (remove o alvo quando é post ou comentário do feed) | Sim |
| POST | `/api/admin/reports/{reportId}/dismiss` | Rejeitar report | Sim |

Razões: `AI_GENERATED`, `FAKE_CONTENT`, `SCAM`, `DISCRIMINATION`, `PREJUDICE`, `HARASSMENT`, `SPAM`, `VIOLENCE`, `SEXUAL_CONTENT`, `COPYRIGHT`, `OTHER`

Target types: `POST`, `COMMENT`, `USER`, `JOB`, `GAME`, `GROUP_POST`, `GROUP_COMMENT`, `MESSAGE`, `COMPANY`.
Alvo `COMPANY` se remove por `POST /api/admin/reports/{reportId}/remove-company` (fecha a página, sem tocar
na conta do dono); `ban`/`penalize` recusam esse alvo com `400 COMPANY_TARGET`.

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

## Cosmetics shop

| Method | Route | Description | Authentication |
|---|---|---|---|
| GET | `/api/loja?order=oldest\|newest` | The shop of the day: items on sale (collections in season, classics and the permanent one); with a session, also what the person owns (default `oldest`) | No; with a session, adds `owned` and `equipped` |
| GET | `/api/loja/colecoes` | Collections in season, their sale windows and ready-made looks | No; with a session, each look also carries `owned` (every piece is the person's) and `equipped` |
| POST | `/api/loja/{itemId}/comprar` | Buy one item | Yes |
| POST | `/api/loja/looks/{lookId}/comprar` | Buy the missing pieces of a look and wear it | Yes |
| POST | `/api/loja/looks/{lookId}/equipar` / `.../desequipar` | Wear / take off a look the person already owns (`403 NOT_OWNED` without every piece); taking it off clears only the slots still holding the look's own pieces and its status | Yes |
| POST | `/api/loja/{itemId}/equipar` / `.../desequipar` | Equip / unequip an owned item (`none` unequips the frame) | Yes |
| PUT | `/api/me/status` | Status bubble `{ text, icon }` | Yes |
| PUT | `/api/cosmetics/profile/name-style` | Name theme (free preset or owned `NAME_STYLE` id), font, color | Yes |
| GET / POST | `/api/eventos/saci-halloween` / `.../escolha` | Saci x Halloween duel | Read: no; choice: yes |
| GET / POST | `/api/eventos/caca-saci` / `.../achados/{spot}` | Saci hunt | Read: no; find: yes |

The catalog lives in `src/main/resources/cosmetics/catalog.json` and is validated at startup
(unique ids and release orders, known collections, parseable windows, look items that exist and
are for sale); a broken file stops the boot. Each item has `id`, `kind` (`FRAME`, `BADGE`,
`NAME_STYLE`, `NAMEPLATE`, `BANNER`, `CARD_THEME`, `PROFILE_EFFECT`, `GARDEN`), `collection`, `name`,
`description`, `priceMf`, `cssRing`, `releaseOrder`, `available` and `purchasable`.

- `releaseOrder` is the positive, unique order of entry in the catalog. The 26 original frames
  keep 1 to 26 and have `collection: null` (always on sale); the seasonal items follow.
- `essenciais` is the permanent collection: its window covers the whole year (`01-01` to `12-31`),
  so its items and its eleven ready-made looks stay on sale every day. The looks are
  `look-papel`, `look-noite-limpa`, `look-jardim`, `look-fim-de-tarde`, `look-manha-de-domingo`,
  `look-carta-na-gaveta`, `look-sombra-fresca`, `look-quintal-ao-meio-dia`, `look-pedra-fria`,
  `look-nevoeiro` and `look-mar-de-pedra`; each one pairs the Essenciais pieces with a different
  original frame, so no two looks are the same bundle.
- `aparencias` is the second permanent collection: one `CARD_THEME` for each of the 85 appearance
  themes of the design system (`tema-blahaj` … `tema-neumorfismo`, 4 faíscas each) and one
  ready-made look for each of them (85 looks). Every look pairs its theme with an original frame
  and the Essenciais name, plate and effect, so all its pieces are permanent and the look stays on
  sale every day; it is listed last, after `essenciais`.
- `GARDEN` items (`garden-cerrado`, `garden-mata-atlantica`, `garden-caatinga`, `garden-pampa`)
  style the profile garden. They have `collection: null`, so they are always on sale; the equipped
  one is `gardenStyle` on the profile (`users.garden_style`), and none equipped is the default garden.
- Seasons are month-day windows in America/Sao_Paulo, inclusive, and may wrap the year. A
  collection outside its window is not in either listing — it does not appear before the
  opening and is gone after the closing, so the shop only ever shows the season it is in. Buying
  an out-of-season item still answers `409 OUT_OF_SEASON` (the shop no longer offers it, but an
  old client or a stale page still gets a clear answer). A piece the person already owns stays
  in their own listing (`available: false`) so it can always be equipped, and event rewards are
  never for sale (`403 NOT_FOR_SALE`).
- An equipped `BANNER` draws over the person's own cover. Changing the own cover (`banner` or
  `bannerHtml` on `PATCH /api/me/profile`) unequips it; the item stays owned.
- Owning = a PURCHASE ledger entry with ref `cosmetico:{userId}:{itemId}` or a row in
  `cosmetic_grants` (free event rewards). The listing loads ownership in two queries, never per item.
- A look costs 75% of its missing pieces, rounded to whole faíscas; each piece is still charged
  under its own ref, so buying the same look twice never charges twice. It equips every piece,
  replaces the badge list with the look badges and sets the look status.
- A collection may hold several looks. Each look entry takes an optional `id` (a slug such as
  `look-manha-de-domingo`); without it the id stays `look-<collection>`, so the original look of
  every collection keeps its address. The shipped look ids are unique and validated at startup.
  Looks follow their collection's season: the seasonal collections ship one look each, and the
  ready-made looks live in the permanent `essenciais` and `aparencias` collections.
- Badges equip at the front of the list, deduplicated, up to 5 (`409 MAX_BADGES`). Official badges
  (`emblema-*`) can only be shown by who owns them, also through `PUT /me/badges` and the profile
  edit (`403 NOT_OWNED`).
- Status: `text` up to 60 characters (cleaned; empty clears), `icon` is `cafe`, `estrela`,
  `coracao`, `chip` or the icon of an official badge the person owns (`400 INVALID_STATUS_ICON`).
- Events run in the windows set by `COSMETICS_DUEL_STARTS_AT` / `COSMETICS_DUEL_ENDS_AT` and
  `COSMETICS_HUNT_STARTS_AT` / `COSMETICS_HUNT_ENDS_AT` (local date-time in America/Sao_Paulo,
  default 2026-10-31 00:00:00 to 23:59:59). One duel choice per person (`409 ALREADY_CHOSEN`),
  writes only while open (`409 EVENT_CLOSED`); the team badge comes with the choice and, after the
  end, the first reads grant the victory badge to the winning team (a tie: both teams). The hunt
  has 7 spots; the seventh grants `emblema-achei-saci`.
- The equipped nameplate travels with the name style (`nameStyle` / `authorStyle` / `actorStyle` /
  `sellerStyle`: `{ theme, font, color, nameplate }`) in every projection that shows a person,
  community members included; the style is `null` only when all four are empty. Lists load their
  people in one query (`findAllByIds`), never one lookup per row; the blog directory also
  loads bans and post counts for the page in one query each.

`order` accepts only `oldest` and `newest`; anything else returns `400 INVALID_CATALOG_ORDER`. A
session that is sent and invalid returns an authentication error instead of an anonymous read.

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

## Extrato da carteira

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/carteira/extrato?page=0&size=20` | Lançamentos da própria carteira, mais recentes primeiro | Sim |

`page` começa em zero e aceita até 10.000; `size` aceita de 1 a 100 (padrão 20).
Valores fora dessas faixas retornam `400`, código `INVALID_EXTRACT_PAGE`.
Os parâmetros opcionais `from` e `to` usam `YYYY-MM-DD`, podem ser enviados separadamente
e incluem o dia inteiro no fuso `America/Sao_Paulo`, o mesmo usado nas datas do frontend.
Datas inválidas e intervalos invertidos retornam `400`, código `INVALID_EXTRACT_DATE`.

A resposta contém `entries`, `page`, `size` e `hasNext`. Os campos de cada lançamento
são os mesmos do extrato de `/carteira`. A consulta filtra e pagina no banco,
ordenando por `created_at DESC, id DESC`; `hasNext` considera um lançamento adicional
sem alterar o deslocamento entre páginas. Uma última página cheia retorna `hasNext: false`.

Este endpoint só lê os lançamentos existentes: não cria conta nem credita presença ou
distribuição inicial. O contrato de `GET /carteira` permanece igual. Sem migração:
as colunas e o índice por `account_id, created_at` já existem no ledger.

## Sequência de presença

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/me/streak` | Sequência de presença da própria conta | Sim |

Resposta:

```json
{ "currentDays": 4, "longestDays": 9, "todayDone": true, "freezesAvailable": 0, "lastFrozenDay": "2026-10-28" }
```

- `currentDays` (int): dias seguidos até hoje; um ontem faltado e coberto pelo congelamento já aparece
  aplicado, e uma sequência quebrada aparece como `0`.
- `longestDays` (int): maior sequência registrada.
- `todayDone` (boolean): a presença de hoje (fuso `America/Sao_Paulo`) já foi contada.
- `freezesAvailable` (int, 0 ou 1): congelamento ainda disponível na semana ISO atual.
- `lastFrozenDay` (`YYYY-MM-DD` ou `null`): último dia salvo pelo congelamento.

`GET /api/carteira` traz o mesmo objeto no campo `streak` (aditivo). Ler a sequência não credita
nada; a presença é contada pelas ações descritas em `docs/COIN_ECONOMY.md`.

## Release notes ("O que há de novo no Énóis")

| Method | Route | Description | Auth |
|---|---|---|---|
| GET | `/api/novidades/atual` | Current release notes; `204` with no body when nothing is published | No |
| PUT | `/api/admin/novidades` | Publishes or replaces the release notes | Admin |
| DELETE | `/api/admin/novidades` | Takes the release notes down (`204`, idempotent) | Admin |

`PUT` body and `GET`/`PUT` response:

```json
{ "items": [{ "title": "Enquetes", "description": "Agora dá para criar enquete no feed." }] }
```

```json
{
  "id": "ativo",
  "items": [{ "title": "Enquetes", "description": "Agora dá para criar enquete no feed." }],
  "publishedAt": "2026-10-31T13:00:00Z",
  "updatedAt": "2026-10-31T13:00:00Z"
}
```

There is a single release at a time (fixed id `ativo`): publishing replaces it. `publishedAt` is the
first publication and survives edits; `updatedAt` changes on every publish and is the version key
the front uses to show the popup once per release. Items are plain text, trimmed and passed through
the same defense as post content (`PlainText.defuse`). `400` codes: `INVALID_RELEASE_ITEMS` (missing,
empty or more than 8 items), `RELEASE_ITEM_TITLE_REQUIRED`, `RELEASE_ITEM_TITLE_TOO_LONG` (80),
`RELEASE_ITEM_DESCRIPTION_REQUIRED`, `RELEASE_ITEM_DESCRIPTION_TOO_LONG` (240).

## Éroleplay — sistemas de regras

O catálogo público dos sistemas registrados. Leitura sem sessão; é o que a tela usa para montar o
seletor na criação da campanha. Só expõe o que cada adaptador declara — identidade, procedência,
licença e atributos da ficha — nunca conteúdo protegido de sistema comercial.

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/roleplay/systems` | Lista os sistemas de regras registrados | Não |

Resposta (um item por sistema registrado):

```json
[{
  "id": "d20-generico",
  "label": "D20 Éroleplay",
  "family": "D20",
  "author": "Éroleplay",
  "license": "conteúdo original do Éroleplay",
  "officialUrl": null,
  "builtIn": true,
  "attributes": [
    { "key": "forca", "label": "Força", "type": "NUMBER", "required": true, "options": [] },
    { "key": "origem", "label": "Origem", "type": "CHOICE", "required": true,
      "options": ["Acadêmico", "Artista", "Combatente", "Devoto", "Engenhoso", "Forasteiro",
                  "Nômade", "Operário", "Popular", "Sócio"] }
  ]
}]
```

`type` aceita `NUMBER`, `TEXT`, `LONGTEXT`, `BOOLEAN` e `CHOICE`.

## Éroleplay — campanhas

Prefixo do controller: `/roleplay/campaigns` (o gateway remove `/api`). Todas as escritas exigem
sessão autenticada e respondem sob `Idempotency-Key`. A listagem pública devolve apenas
campanhas `PUBLIC` e `ACTIVE`.

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/roleplay/campaigns` | Lista campanhas públicas ativas | Não |
| GET | `/api/roleplay/campaigns/mine` | Lista campanhas em que sou membro | Sim |
| GET | `/api/roleplay/campaigns/{slugOuId}` | Detalhe da campanha | Opcional |
| POST | `/api/roleplay/campaigns` | Cria campanha (criador vira mestre) | Sim |
| PATCH | `/api/roleplay/campaigns/{id}` | Edita a campanha | Mestre |
| POST | `/api/roleplay/campaigns/{id}/join` | Entra na campanha (ou pede entrada) | Sim |
| POST | `/api/roleplay/campaigns/{id}/invite` | Convida alguém para a campanha | Mestre |
| DELETE | `/api/roleplay/campaigns/{id}/members/me` | Sai da campanha | Membro |
| GET | `/api/roleplay/campaigns/{id}/members` | Lista membros | Membro |
| DELETE | `/api/roleplay/campaigns/{id}/members/{membroId}` | Remove um membro | Mestre |
| POST | `/api/roleplay/campaigns/{id}/members/{membroId}/transfer-master` | Transfere a mestria | Mestre |
| GET | `/api/roleplay/campaigns/{id}/requests` | Lista pedidos de entrada pendentes | Mestre |
| POST | `/api/roleplay/campaigns/{id}/requests/{pedidoId}/approve` | Aprova o pedido | Mestre |
| POST | `/api/roleplay/campaigns/{id}/requests/{pedidoId}/reject` | Recusa o pedido | Mestre |
| POST | `/api/roleplay/campaigns/{id}/archive` | Arquiva a campanha | Mestre |
| GET | `/api/roleplay/campaigns/{id}/tables` | Lista as mesas | Membro |
| POST | `/api/roleplay/campaigns/{id}/tables` | Cria uma mesa (`name`, `open`) | Mestre |
| GET | `/api/roleplay/campaigns/{id}/tables/{tableId}/seats` | Lista os lugares da mesa | Membro |
| POST | `/api/roleplay/campaigns/{id}/tables/{mesaId}/sit` | Ocupa um lugar na mesa | Membro |
| DELETE | `/api/roleplay/campaigns/{id}/tables/{mesaId}/sit` | Libera o lugar na mesa | Membro |

### POST /api/roleplay/campaigns

```json
{
  "name": "O Segredo de Várzea",
  "description": "Investigação em uma vila do interior.",
  "setting": "Várzea",
  "gameSystem": "sistema-proprio-d20",
  "systemFamily": "D20",
  "visibility": "INVITE_ONLY"
}
```

`gameSystem` é o identificador do sistema registrado (veja a fase 2 do roadmap). `systemFamily` aceita
`D20`, `D100` e `CUSTOM`; `visibility` aceita `PRIVATE`, `INVITE_ONLY` e `PUBLIC`. Resposta `201`:

```json
{
  "id": "camp_…",
  "slug": "o-segredo-de-varzea",
  "name": "O Segredo de Várzea",
  "description": "Investigação em uma vila do interior.",
  "setting": "Várzea",
  "gameSystem": "sistema-proprio-d20",
  "systemFamily": "D20",
  "visibility": "INVITE_ONLY",
  "status": "ACTIVE",
  "members": 1,
  "viewerRole": "GAME_MASTER",
  "joined": true,
  "createdAt": "2026-11-13T00:00:00Z"
}
```

`viewerRole` e `joined` descrevem a relação de quem pediu com a campanha; para quem não é membro,
`viewerRole` vem nulo.

### POST /api/roleplay/campaigns/{id}/invite

```json
{ "userId": "user_…" }
```

A convite cria o vínculo de membro na hora; a campanha precisa estar em `ACTIVE`.

### POST /api/roleplay/campaigns/{id}/tables

```json
{ "name": "Mesa do Pátio", "open": true }
```

`open` omitido vale `true`. `open=false` cria uma mesa fechada a novos lugares.

### Códigos de erro próprios

| Código | HTTP | Quando |
|---|---|---|
| `CAMPAIGN_NOT_FOUND` | 404 | Campanha inexistente |
| `CAMPAIGN_INVITE_ONLY` | 403 | Campanha privada, sem convite |
| `REQUEST_ALREADY_PENDING` | 409 | Já existe pedido de entrada pendente |
| `REQUEST_NOT_FOUND` | 404 | Pedido inexistente ou não é da campanha |
| `ALREADY_MEMBER` | 409 | Já sou membro da campanha |
| `ALREADY_MASTER` | 409 | A campanha já tem um mestre |
| `CANNOT_REMOVE_MASTER` | 409 | Não se remove o mestre; transfira a mestria |
| `CAMPAIGN_NOT_ACCEPTING_MEMBERS` | 409 | Campanha arquivada ou encerrada |
| `NOT_CAMPAIGN_MEMBER` | 403 | Quem pediu não é membro da campanha |
| `FORBIDDEN` | 403 | Ação exige papel/capacidade que o solicitante não tem |
| `INVALID_NAME` | 400 | Nome da campanha ausente |
| `INVALID_VISIBILITY` | 400 | Visibilidade não reconhecida |
| `SLUG_UNAVAILABLE` | 409 | Não foi possível gerar um slug livre |
| `NOT_MEMBER` | 404 | Membro não encontrado na campanha |
| `TABLE_NOT_OPEN` | 403 | A mesa não está aberta a novos lugares |
| `NOT_SEATED` | 404 | Quem pediu não ocupa lugar na mesa |
| `ALREADY_SEATED` | 409 | Já ocupa lugar nesta mesa |
| `REQUEST_ALREADY_DECIDED` | 409 | Pedido já decidido |
| `MASTERSHIP_TRANSFER_REQUIRED` | 409 | O mestre tentou sair sem transferir a mestria |
| `CAMPAIGN_ARCHIVED` | 409 | Campanha arquivada ou encerrada |
| `INVALID_GAME_SYSTEM` | 400 | Sistema de RPG não reconhecido |
| `GAME_SYSTEM_REQUIRED` | 400 | Criação/edição sem sistema de regras |
| `GAME_SYSTEM_NOT_SUPPORTED` | 400 | Sistema sem adaptador registrado |
| `GAME_SYSTEM_FAMILY_MISMATCH` | 400 | A família informada não bate com a do sistema registrado |
| `TABLE_NOT_FOUND` | 404 | Mesa inexistente ou de outra campanha |

## Éroleplay — fichas de personagem

Prefixo do controller: `/roleplay/campaigns/{id}/sheets` (o gateway remove `/api`). **Nenhuma rota
desta seção é pública**: ficha é dado pessoal de jogo, então toda leitura exige sessão e
participação na campanha. As escritas respondem sob `Idempotency-Key`.

O modelo é definido pelo mestre a partir dos atributos que o sistema da campanha declara — o mestre
não inventa campo, apenas ajusta **até onde cada campo pode abrir**. Cada campo tem uma
visibilidade **máxima** (`PRIVATE` < `GAME_MASTER` < `CAMPAIGN`) e cada valor preenchido carrega a
visibilidade efetiva, que nunca pode ser mais aberta que o máximo do modelo.

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/roleplay/campaigns/{id}/sheets/template` | Modelo da ficha | Membro |
| POST | `/api/roleplay/campaigns/{id}/sheets/template` | Define o modelo (uma vez) | Mestre |
| PUT | `/api/roleplay/campaigns/{id}/sheets/template` | Ajusta a abertura máxima dos campos | Mestre |
| DELETE | `/api/roleplay/campaigns/{id}/sheets/template` | Remove o modelo (só sem ficha preenchida) | Mestre |
| GET | `/api/roleplay/campaigns/{id}/sheets/me` | A própria ficha, inteira | Membro |
| PUT | `/api/roleplay/campaigns/{id}/sheets/me` | Preenche a própria ficha | Membro |
| GET | `/api/roleplay/campaigns/{id}/sheets/board` | Quadro do grupo (só o aberto à campanha) | Membro |
| GET | `/api/roleplay/campaigns/{id}/sheets` | Todas as fichas, no recorte do mestre | Mestre |
| GET | `/api/roleplay/campaigns/{id}/sheets/{userId}` | A ficha de alguém, no recorte permitido | Mestre (ou o dono) |

### GET /api/roleplay/campaigns/{id}/sheets/template

```json
{
  "id": "roleplay_sheet_template_…",
  "campaignId": "camp_…",
  "gameSystem": "d20-generico",
  "fields": [
    { "key": "forca", "label": "Força", "type": "NUMBER", "required": true,
      "options": [], "maxVisibility": "GAME_MASTER" },
    { "key": "historia", "label": "História", "type": "LONGTEXT", "required": false,
      "options": [], "maxVisibility": "CAMPAIGN" }
  ],
  "updatedAt": "2026-11-14T00:00:00Z"
}
```

O campo nasce com o máximo em `GAME_MASTER` — fechar é o padrão, abrir é decisão explícita do mestre.

### POST /api/roleplay/campaigns/{id}/sheets/template

```json
{ "fields": [ { "key": "forca", "maxVisibility": "CAMPAIGN" } ] }
```

`fields` é **opcional**: omitido (ou `[]`), todos os campos ficam com o máximo padrão. Chave que o
sistema não declara responde `SHEET_FIELD_UNKNOWN`. Resposta `201` com o modelo criado.

### PUT /api/roleplay/campaigns/{id}/sheets/template

```json
{ "fields": [ { "key": "historia", "maxVisibility": "PRIVATE" } ] }
```

Só os campos enviados mudam. **Fechar um campo fecha o que já está preenchido**: as fichas da
campanha encolhem junto, senão a regra nova valeria só a partir da próxima gravação.

### PUT /api/roleplay/campaigns/{id}/sheets/me

```json
{
  "name": "Iara, a Cartógrafa",
  "entries": [
    { "key": "forca", "value": "12", "visibility": "CAMPAIGN" },
    { "key": "destreza", "value": "14", "visibility": "GAME_MASTER" },
    { "key": "historia", "value": "Cresceu entre mapas.", "visibility": "PRIVATE" }
  ]
}
```

O corpo substitui a ficha por inteiro. `visibility` omitida vale `PRIVATE` — abrir é ato explícito
de quem preenche. A resposta é a leitura do **dono**, pelo mesmo caminho da leitura, então a
gravação nunca devolve campo que a leitura esconderia.

Normalização e validação são do servidor: número aceita `12`, `12,5` (vira `12.5`) e `-3`, limitado
a `±999999` com 4 casas; escolha é comparada sem diferenciar maiúsculas de minúsculas e devolvida na
grafia da opção; texto não aceita caractere de controle; campo opcional em branco vira ausência.

### Resposta de ficha

```json
{
  "id": "roleplay_sheet_…",
  "campaignId": "camp_…",
  "userId": "user_…",
  "name": "Iara, a Cartógrafa",
  "entries": [
    { "key": "forca", "value": "12", "visibility": "CAMPAIGN" }
  ],
  "derived": [
    { "key": "mod_forca", "label": "Modificador de Força", "value": "1" }
  ],
  "createdAt": "2026-11-14T00:00:00Z",
  "updatedAt": "2026-11-14T00:00:00Z"
}
```

`entries` sai **já filtrado** pelo público de quem pediu. `derived` são os valores que o sistema
calcula e **nunca são persistidos** — são recalculados na leitura e aparecem só quando o público
também enxerga o campo de origem.

### Quem vê o quê

| Leitor | `entries` que recebe |
|---|---|
| Dono (`/me`) | Todas, inclusive as `PRIVATE` |
| Mestre (`/` e `/{userId}`) | `GAME_MASTER` e `CAMPAIGN` de todos; `PRIVATE` de ninguém, nem a própria |
| Outro participante (`/board`) | Só os campos `CAMPAIGN` de todos |
| Jogador pedindo a ficha de outro | `403 FORBIDDEN` — a ficha alheia não é recurso dele |
| Jogador pedindo a lista (`/sheets`) | `403 FORBIDDEN` |

O quadro sempre inclui o `name` do personagem, mesmo quando a ficha está inteiramente fechada: o
grupo precisa saber que aquele personagem existe, e sumir da lista revelaria que há segredo.

### Códigos de erro próprios

| Código | HTTP | Quando |
|---|---|---|
| `SHEET_TEMPLATE_MISSING` | 409 | A campanha ainda não tem modelo de ficha |
| `SHEET_TEMPLATE_EXISTS` | 409 | Já existe modelo; use `PUT` para ajustar |
| `SHEET_TEMPLATE_IN_USE` | 409 | Há ficha preenchida; o modelo não pode ser removido |
| `SHEET_SYSTEM_LOCKED` | 409 | Tentativa de trocar o sistema com ficha preenchida |
| `SHEET_NOT_FOUND` | 404 | Ficha inexistente na campanha |
| `SHEET_NAME_REQUIRED` | 400 | Nome do personagem ausente ou acima de 120 caracteres |
| `SHEET_FIELD_REQUIRED` | 400 | Campo obrigatório veio vazio |
| `SHEET_FIELD_UNKNOWN` | 400 | Chave que o modelo não tem |
| `SHEET_FIELD_DUPLICATED` | 400 | Mesma chave enviada duas vezes |
| `SHEET_FIELDS_REQUIRED` | 400 | Ajuste de modelo sem nenhum campo |
| `SHEET_VISIBILITY_TOO_OPEN` | 400 | Visibilidade acima do máximo do modelo |
| `SHEET_VALUE_TOO_LONG` | 400 | Valor acima do limite do campo |
| `SHEET_VALUE_INVALID` | 400 | Valor incompatível com o tipo do campo |
| `INVALID_SHEET_VISIBILITY` | 400 | Visibilidade não reconhecida ou ausente |
| `GAME_SYSTEM_WITHOUT_ATTRIBUTES` | 400 | Sistema registrado não declara atributos de ficha |
| `GAME_SYSTEM_ATTRIBUTE_DUPLICATED` | 409 | Sistema declara a mesma chave de atributo duas vezes |

## Éroleplay — motor de dados

Prefixo do controller: `/roleplay/campaigns/{id}/rolls` (o gateway remove `/api`). **Nenhuma rota
desta seção é pública**: rolagem é registro do que aconteceu na mesa, e o histórico é visível só
para quem participa. As escritas respondem sob `Idempotency-Key` — repetir a mesma chave devolve o
mesmo registro, não uma nova rolagem.

O resultado **sempre sai do servidor**: o corpo descreve o que rolar (`2d6+3`), e o sorteio usa a
fonte de aleatoriedade do servidor. Não existe campo de resultado no corpo — o que chegasse pronto
seria a mesa escolhendo a própria sorte. O registro é imutável: não há rota que reescreva ou remova
uma rolagem, e cada dado individual fica guardado para a mesa conferir a soma.

### Rotas

| Método | Caminho | Quem | O que faz |
|---|---|---|---|
| `POST` | `/roleplay/campaigns/{id}/rolls` | participante | Rola e registra; corpo `{ "expression", "label"?, "visibility"? }` |
| `GET` | `/roleplay/campaigns/{id}/rolls` | participante | Histórico recente (teto de 100), já recortado por quem pede |
| `GET` | `/roleplay/campaigns/{id}/rolls/{rollId}` | participante | Uma rolagem, no recorte de quem pede |
| `POST` | `/roleplay/campaigns/{id}/rolls/requests` | mestre | Pede rolagem a um jogador; corpo `{ "playerId", "expression", "label"?, "visibility"?, "reason"? }` |
| `GET` | `/roleplay/campaigns/{id}/rolls/requests` | participante | O mestre vê os pedidos que fez; o jogador os que recebeu |
| `GET` | `/roleplay/campaigns/{id}/rolls/requests/{requestId}` | mestre ou jogador do pedido | Um pedido |
| `POST` | `/roleplay/campaigns/{id}/rolls/requests/{requestId}/answer` | jogador do pedido | Responde com uma rolagem; corpo `{ "label"?, "visibility"? }` |

### Visibilidade

A rolagem usa a MESMA régua da ficha (`PRIVATE` / `GAME_MASTER` / `CAMPAIGN`). O valor informado é
o teto, não o pedido: a visibilidade efetiva nunca passa do teto — na rolagem livre o teto é
`CAMPAIGN`, e na resposta a um pedido é o `visibility` que o mestre gravou no pedido. O pedido
respondido aponta para a rolagem que o respondeu (`answeredRollId`), e a rolagem aponta para o
pedido (`requestId`).

Quando o mestre pede uma rolagem, o jogador recebe a notificação `ROLEPLAY_ROLL_REQUESTED` (com
link para a campanha). O corpo da notificação NUNCA leva a expressão nem o motivo — a caixa de
notificações é lida fora da campanha, e o teste do mestre fica dentro dela.

Recortes de leitura, aplicados na resposta:

| Quem pede | Vê |
|---|---|
| Mestre | Todo o histórico da campanha |
| Jogador | As próprias rolagens (inclusive as `PRIVATE`) e as `CAMPAIGN` dos outros |
| Jogador lendo rolagem `PRIVATE`/`GAME_MASTER` de outro | `403 FORBIDDEN` |
| Fora da campanha | `403 FORBIDDEN` em tudo |
| Sem sessão | `401` em tudo |

### Expressão

A gramática é do servidor: grupos `NdF` somados a constantes com sinal (`2d6+3`, `1d8+2d6-1`,
`d20`). Normaliza maiúsculas e espaços; a expressão gravada é a normalizada. Teto por grupo: 100
dados; faces de 1 a 1000; constante até ±999.999; até 8 grupos e 8 constantes; 60 caracteres.
Expressão só de constante (`5`) não é rolagem: sem dado, não há o que sortear nem o que conferir.

### Códigos de erro próprios

| Código | HTTP | Quando |
|---|---|---|
| `DICE_EXPRESSION_REQUIRED` | 400 | Expressão ausente ou vazia |
| `DICE_EXPRESSION_TOO_LONG` | 400 | Expressão acima de 60 caracteres |
| `DICE_EXPRESSION_INVALID` | 400 | Fora da gramática, operador solto, constante sozinha |
| `DICE_GROUPS_TOO_MANY` | 400 | Mais de 8 grupos de dados |
| `DICE_CONSTANTS_TOO_MANY` | 400 | Mais de 8 constantes |
| `DICE_CONSTANT_TOO_LARGE` | 400 | Constante acima de ±999.999 |
| `DICE_COUNT_INVALID` | 400 | Quantidade de dados fora de 1–100 |
| `DICE_FACES_INVALID` | 400 | Faces fora de 1–1000 |
| `DICE_SOURCE_BROKEN` | 500 | Fonte de aleatoriedade devolveu valor fora do intervalo (não registra) |
| `ROLL_PLAYER_REQUIRED` | 400 | Pedido sem o jogador a quem vai |
| `ROLL_REQUEST_SELF` | 400 | Mestre pedindo rolagem a si mesmo |
| `ROLL_REQUEST_NOT_FOUND` | 404 | Pedido inexistente na campanha |
| `ROLL_REQUEST_ALREADY_ANSWERED` | 409 | O pedido já foi respondido — a segunda resposta é recusada |
| `ROLL_LABEL_MISMATCH` | 400 | Rótulo da resposta contradiz o do pedido |
| `ROLL_NOT_FOUND` | 404 | Rolagem inexistente na campanha |
