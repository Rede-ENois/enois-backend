# API Reference

Base URL: `http://localhost:8080`

Autenticação via header: `Authorization: Bearer <session_token>`

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
  "visibility": "PUBLIC",
  "tags": ["java", "spring"],
  "mediaIds": ["media_xxx"]
}
```

Tipos: `INSIGHT`, `ARTICLE`, `QUESTION`, `PROJECT`, `OPPORTUNITY`, `PAGE`, `NEWS`, `TUTORIAL`,
`REVIEW`, `DIVERTIDO`, `MOMENT`, `BLOG`, `BUSINESS`, `POLL`

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
      "media": [...]
    }
  ],
  "nextCursor": "base64encodedtimestamp",
  "lastSeenAt": "2025-01-01T00:00:00Z"
}
```

`lastSeenAt` (ISO-8601 ou `null`): o marcador "Você está em dia" da pessoa **como estava antes desta
visita**. Só vem na primeira página (sem `cursor`); páginas com `cursor` mandam `null` e o front guarda o
valor recebido na primeira. Só vem nas abas em ordem cronológica (`ALL`, `UNSEEN`, filtros por tipo e as
legadas); `FOR_YOU` (tópicos preferidos primeiro), `TRENDING` (por reações) e `INTERACTED` mandam sempre
`null`, porque nelas "o primeiro post mais antigo" não marca a fronteira do que é novo. `null` na primeira página significa que a pessoa nunca marcou nada (não
desenhe o divisor).

Contrato do front: desenhe o divisor "Você está em dia" **antes do primeiro post com `createdAt` menor
ou igual a `lastSeenAt`**, olhando os posts que a resposta trouxe (quarentena, silenciados e bloqueios já
foram filtrados no servidor). Se nenhum post da lista carregada for tão antigo, o divisor ainda não aparece
(ele surge quando a página com posts antigos chegar). Quando a pessoa rolar além do post mais recente,
chame `POST /api/feed/seen` com o `createdAt` desse post (uma vez por visita basta).

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
só para contas criadas depois da V210 que ainda não concluíram nem pularam.

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
| POST / DELETE | `/api/empresas/{companyId}/follow` | Follow / unfollow (idempotent) | Yes |
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

## Par de estudo

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| POST | `/api/study-pairs/requests` | Procurar par para um objetivo (`{goal, topic?}`) | Sim |
| DELETE | `/api/study-pairs/requests/current` | Cancelar o pedido aberto | Sim |
| GET | `/api/study-pairs/me` | Pedido aberto, pares ativos e check-ins das últimas 4 semanas | Sim |
| POST | `/api/study-pairs/{pairId}/checkins` | Check-in da semana (`{note}`, até 500); repetir na semana edita | Sim (membro) |
| POST | `/api/study-pairs/{pairId}/end` | Encerrar o par | Sim (membro) |
| GET | `/api/study-pairs/goals/popular` | Objetivos com pedidos abertos (sem dado pessoal) | Sim |

Contrato completo, regras e códigos de erro em [`STUDY_PAIRS.md`](STUDY_PAIRS.md).

---

## Mensagens

| Método | Rota | Descrição | Autenticação |
|---|---|---|---|
| GET | `/api/conversations` | Listar conversas (`?context=SOCIAL\|PROFESSIONAL`, see SOCIAL_PROFESSIONAL_CONTEXTS.md) | Sim |
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
| GET | `/api/notifications` | Listar notificações (`?context=SOCIAL\|PROFESSIONAL`) | Sim |
| POST | `/api/notifications/{notificationId}/read` | Marcar como lida | Sim |
| POST | `/api/notifications/read-all` | Marcar todas como lidas | Sim |
| GET | `/api/me/notification-preferences` | Preferências de push e do resumo semanal | Sim |
| PUT | `/api/me/notification-preferences` | Atualização parcial (campo ausente/nulo mantém o valor) | Sim |
| GET | `/api/notifications/digest/unsubscribe?token=` | Descadastro do resumo semanal (link do e-mail, HTML) | Não (token) |
| POST | `/api/notifications/digest/unsubscribe?token=` | Descadastro em um clique (RFC 8058, cliente de e-mail): 200/400 sem corpo | Não (token) |
| POST | `/api/blogs/descadastrar?token=` | Mesmo descadastro em um clique para a newsletter do blog | Não (token) |

Preferências (GET e resposta do PUT):

```json
{ "weeklyDigest": true, "pushSocial": true, "pushMessages": true, "pushGroups": true,
  "pushJobs": true, "pushSystem": true, "updatedAt": "2026-10-30T12:00:00Z" }
```

`updatedAt` é `null` enquanto a pessoa nunca mudou nada (padrão: tudo ligado). Segurança, moderação e
sanções sempre viram push. Regras e categorias em [`DIGEST.md`](DIGEST.md).

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
| POST | `/api/admin/reports/{reportId}/approve` | Aprovar report (deletar alvo) | Sim |
| POST | `/api/admin/reports/{reportId}/dismiss` | Rejeitar report | Sim |

Razões: `AI_GENERATED`, `FAKE_CONTENT`, `SCAM`, `DISCRIMINATION`, `PREJUDICE`, `HARASSMENT`, `SPAM`, `VIOLENCE`, `SEXUAL_CONTENT`, `COPYRIGHT`, `OTHER`

Target types: `POST`, `COMMENT`, `USER`, `JOB`, `GAME`, `GROUP_POST`, `GROUP_COMMENT`, `MESSAGE`

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
| GET | `/api/loja?order=oldest\|newest` | Native catalog (default `oldest`) | No; with a session, adds `owned` and `equipped` |
| GET | `/api/loja/colecoes` | Seasonal collections, sale windows and ready-made looks | No |
| POST | `/api/loja/{itemId}/comprar` | Buy one item | Yes |
| POST | `/api/loja/looks/{lookId}/comprar` | Buy the missing pieces of a look and wear it | Yes |
| POST | `/api/loja/{itemId}/equipar` / `.../desequipar` | Equip / unequip an owned item (`none` unequips the frame) | Yes |
| PUT | `/api/me/status` | Status bubble `{ text, icon }` | Yes |
| PUT | `/api/cosmetics/profile/name-style` | Name theme (free preset or owned `NAME_STYLE` id), font, color | Yes |
| GET / POST | `/api/eventos/saci-halloween` / `.../escolha` | Saci x Halloween duel | Read: no; choice: yes |
| GET / POST | `/api/eventos/caca-saci` / `.../achados/{spot}` | Saci hunt | Read: no; find: yes |

The catalog lives in `src/main/resources/cosmetics/catalog.json` and is validated at startup
(unique ids and release orders, known collections, parseable windows, look items that exist and
are for sale); a broken file stops the boot. Each item has `id`, `kind` (`FRAME`, `BADGE`,
`NAME_STYLE`, `NAMEPLATE`, `BANNER`, `CARD_THEME`, `PROFILE_EFFECT`), `collection`, `name`,
`description`, `priceMf`, `cssRing`, `releaseOrder`, `available` and `purchasable`.

- `releaseOrder` is the positive, unique order of entry in the catalog. The 26 original frames
  keep 1 to 26, have `collection: null` and are always on sale; the seasonal items follow.
- Seasons are month-day windows in America/Sao_Paulo, inclusive, and may wrap the year. Out of
  season an item is listed with `available: false` and buying it returns `409 OUT_OF_SEASON`;
  owned items can always be equipped. Event rewards are never for sale (`403 NOT_FOR_SALE`).
- Owning = a PURCHASE ledger entry with ref `cosmetico:{userId}:{itemId}` or a row in
  `cosmetic_grants` (free event rewards). The listing loads ownership in two queries, never per item.
- A look costs 75% of its missing pieces, rounded to whole faíscas; each piece is still charged
  under its own ref, so buying the same look twice never charges twice. It equips every piece,
  replaces the badge list with the look badges and sets the look status.
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
