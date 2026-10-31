# Banco de Dados

Migrações Flyway em `src/main/resources/db/migration/`.

## V1 - Schema Inicial

### `users`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | UUID do usuário |
| username | VARCHAR(255) UNIQUE | Nome de usuário único |
| name | VARCHAR(255) | Nome completo |
| headline | VARCHAR(255) | Cargo / headline |
| email | VARCHAR(255) UNIQUE | Email |
| company | VARCHAR(255) | Empresa atual |
| location | VARCHAR(255) | Localização |
| avatar | VARCHAR(512) | URL do avatar |
| banner | VARCHAR(512) | URL do banner |
| bio | TEXT | Biografia |
| availability | VARCHAR(50) | Disponibilidade |
| portfolio | VARCHAR(512) | URL do portfólio |
| open_to_work | BOOLEAN | Aberto a oportunidades |
| is_mentor | BOOLEAN | É mentor |
| reputation | INT | Pontuação de reputação |
| created_at | TIMESTAMP | Data de criação |
| updated_at | TIMESTAMP | Data de atualização |

### `user_skills`

| Coluna | Tipo |
|---|---|
| user_id | VARCHAR(36) FK → users.id |
| skill | VARCHAR(100) |

### `user_goals`

| Coluna | Tipo |
|---|---|
| user_id | VARCHAR(36) FK → users.id |
| goal | VARCHAR(255) |

### `user_external_links`

| Coluna | Tipo |
|---|---|
| user_id | VARCHAR(36) FK → users.id |
| external_link | VARCHAR(512) |

### `user_credentials`

| Coluna | Tipo | Descrição |
|---|---|---|
| user_id | VARCHAR(36) PK FK → users.id | |
| email | VARCHAR(255) UNIQUE | Email de login |
| password_hash | VARCHAR(512) | Hash PBKDF2 da senha |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### `auth_sessions`

| Coluna | Tipo | Descrição |
|---|---|---|
| token | VARCHAR(255) PK | Token de sessão |
| refresh_token | VARCHAR(255) UNIQUE | Token de refresh |
| user_id | VARCHAR(36) FK → users.id | |
| created_at | TIMESTAMP | |
| expires_at | TIMESTAMP | |
| revoked_at | TIMESTAMP (nullable) | Se preenchido, sessão revogada |

### `posts`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | ID do post (post_<uuid>) |
| author_id | VARCHAR(36) FK → users.id | |
| community_id | VARCHAR(36) (nullable) FK → communities.id | Post em comunidade |
| type | VARCHAR(20) | INSIGHT, ARTICLE, QUESTION, PROJECT, OPPORTUNITY, POLL, ... (ver `PostType`) |
| content | TEXT | Conteúdo |
| visibility | VARCHAR(20) | PUBLIC, CONNECTIONS, COMMUNITY |
| reactions | INT DEFAULT 0 | Contador de reações |
| comments | INT DEFAULT 0 | Contador de comentários |
| saves | INT DEFAULT 0 | Contador de salvos |
| shares | INT DEFAULT 0 | Contador de compartilhamentos |
| status | VARCHAR(20) | ACTIVE, DELETED |
| deleted_at | TIMESTAMP (nullable) | Data de deleção (soft delete) |
| created_at | TIMESTAMP | |

### `poll_options` (enquete)

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(64) PK | `poll_option_<uuid>` |
| post_id | VARCHAR(64) | Post `POLL` dono da alternativa |
| position | INT | Ordem (0..5) — é o que o voto guarda |
| text | VARCHAR(120) | Texto da alternativa |

Unique `(post_id, position)`. Em `posts`, o `content` do post `POLL` é o título da enquete (400).

### `poll_votes` (enquete)

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(64) PK | `poll_vote_<uuid>` |
| post_id | VARCHAR(64) | Post `POLL` |
| user_id | VARCHAR(64) | Quem votou |
| option_position | INT | Alternativa escolhida |
| created_at | TIMESTAMP | |

Unique `(post_id, user_id)`: **um voto por pessoa, imutável** — não existe UPDATE do voto; o
segundo INSERT é recusado pelo banco.

### `post_tags`

| Coluna | Tipo |
|---|---|
| post_id | VARCHAR(36) FK → posts.id |
| tag | VARCHAR(50) |

### `post_media`

| Coluna | Tipo |
|---|---|
| post_id | VARCHAR(36) FK → posts.id |
| media_id | VARCHAR(36) FK → media_assets.id |

### `idempotency_keys`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | BIGINT AUTO_INCREMENT PK | |
| user_id | VARCHAR(36) | |
| method | VARCHAR(10) | HTTP method |
| route | VARCHAR(255) | Rota |
| idempotency_key | VARCHAR(255) | Chave de idempotência |
| request_hash | VARCHAR(64) | SHA-256 do corpo |
| status | VARCHAR(20) | IN_PROGRESS, COMPLETED, CONFLICT |
| response_status | INT | Status HTTP da resposta |
| response_body | TEXT | Corpo da resposta |
| created_at | TIMESTAMP | |
| expires_at | TIMESTAMP | |
| completed_at | TIMESTAMP (nullable) | |

### `outbox_events`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| event_type | VARCHAR(100) | Tipo do evento |
| aggregate_id | VARCHAR(36) | ID do agregado |
| payload | TEXT | JSON do payload |
| status | VARCHAR(20) | PENDING, PROCESSED, FAILED |
| created_at | TIMESTAMP | |
| next_attempt_at | TIMESTAMP | Próxima tentativa |

### `processed_events`

| Coluna | Tipo |
|---|---|
| event_id | VARCHAR(36) PK |
| processed_at | TIMESTAMP |

### `audit_logs`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| actor_id | VARCHAR(36) |
| action | VARCHAR(100) |
| target_id | VARCHAR(255) (nullable) |
| metadata | TEXT (nullable) |
| created_at | TIMESTAMP |

---

## V2 - Feed Interactions

### `comments`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| post_id | VARCHAR(36) FK → posts.id | |
| author_id | VARCHAR(36) FK → users.id | |
| parent_comment_id | VARCHAR(36) (nullable) | Resposta a outro comentário |
| content | TEXT | Conteúdo |
| created_at | TIMESTAMP | |
| status | VARCHAR(20) | ACTIVE, DELETED |

### `reactions`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| post_id | VARCHAR(36) FK → posts.id |
| user_id | VARCHAR(36) FK → users.id |
| reaction_type | VARCHAR(20) |
| created_at | TIMESTAMP |

UNIQUE: (post_id, user_id)

### `saved_posts`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| post_id | VARCHAR(36) FK → posts.id |
| user_id | VARCHAR(36) FK → users.id |
| created_at | TIMESTAMP |

UNIQUE: (post_id, user_id)

### `post_shares`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| post_id | VARCHAR(36) FK → posts.id |
| user_id | VARCHAR(36) FK → users.id |
| created_at | TIMESTAMP |

UNIQUE: (post_id, user_id)

---

## V3 - Media Assets

### `media_assets`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| owner_id | VARCHAR(36) FK → users.id | |
| file_name | VARCHAR(255) | |
| mime_type | VARCHAR(100) | |
| size_bytes | BIGINT | |
| purpose | VARCHAR(50) | AVATAR, POST_IMAGE, COVER, etc |
| object_key | VARCHAR(512) | Chave no ImageKit |
| cdn_url | VARCHAR(1024) | URL do CDN |
| status | VARCHAR(20) | PENDING, ACTIVE, EXPIRED, DELETED |
| delete_status | VARCHAR(20) | NONE, REQUESTED, DELETED |
| created_at | TIMESTAMP | |
| expires_at | TIMESTAMP (nullable) | |
| deleted_at | TIMESTAMP (nullable) | |

### `media_deletion_jobs`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| media_id | VARCHAR(36) UNIQUE FK → media_assets.id | |
| post_id | VARCHAR(36) (nullable) | |
| owner_id | VARCHAR(36) FK → users.id | |
| status | VARCHAR(20) | PENDING, PROCESSING, COMPLETED, FAILED |
| attempts | INT DEFAULT 0 | |
| created_at | TIMESTAMP | |
| next_attempt_at | TIMESTAMP | |
| processed_at | TIMESTAMP (nullable) | |
| error_message | TEXT (nullable) | |

---

## V4 - User Connections

### `user_connections`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | BIGINT AUTO_INCREMENT PK | |
| requester_user_id | VARCHAR(36) FK → users.id | Quem solicitou |
| receiver_user_id | VARCHAR(36) FK → users.id | Quem recebeu |
| status | VARCHAR(20) | PENDING, ACCEPTED, BLOCKED |
| created_at | TIMESTAMP | |
| accepted_at | TIMESTAMP (nullable) | |
| removed_at | TIMESTAMP (nullable) | |

UNIQUE: (requester_user_id, receiver_user_id)

---

## V5 - Communities, Jobs, Events, Mentoring

### `communities`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| name | VARCHAR(255) | Nome |
| description | TEXT | Descrição |
| topic | VARCHAR(100) | Tópico/tema |
| cover | VARCHAR(512) | URL da capa |
| avatar | VARCHAR(512) | URL do avatar |
| created_by | VARCHAR(36) FK → users.id | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### `community_members`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| community_id | VARCHAR(36) FK → communities.id |
| user_id | VARCHAR(36) FK → users.id |
| role | VARCHAR(20) (ADMIN, MODERATOR, MEMBER) |
| joined_at | TIMESTAMP |

### `jobs`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| title | VARCHAR(255) | |
| company | VARCHAR(255) | |
| logo | VARCHAR(512) | |
| location | VARCHAR(255) | |
| type | VARCHAR(50) | CLT, PJ, REMOTO, etc |
| level | VARCHAR(50) | JUNIOR, PLENO, SENIOR |
| salary | VARCHAR(100) | |
| posted_by | VARCHAR(36) FK → users.id | |
| posted_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### `job_skills`

| Coluna | Tipo |
|---|---|
| job_id | VARCHAR(36) FK → jobs.id |
| skill | VARCHAR(100) |

### `job_applications`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| job_id | VARCHAR(36) FK → jobs.id |
| user_id | VARCHAR(36) FK → users.id |
| status | VARCHAR(20) (PENDING, REVIEWED, REJECTED, ACCEPTED) |
| applied_at | TIMESTAMP |

### `job_saves`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| job_id | VARCHAR(36) FK → jobs.id |
| user_id | VARCHAR(36) FK → users.id |
| saved_at | TIMESTAMP |

### `events`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| title | VARCHAR(255) | |
| date | DATE | |
| time | VARCHAR(50) | |
| format | VARCHAR(50) | ONLINE, PRESENTIAL, HYBRID |
| host | VARCHAR(255) | |
| cover | VARCHAR(512) | |
| created_by | VARCHAR(36) FK → users.id | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |
| recurrence | VARCHAR(16) | NONE (padrão), WEEKLY, BIWEEKLY, MONTHLY (V215) |
| series_id | VARCHAR(255) NULL | Id da primeira ocorrência da série; único com `date` (V215) |

### `event_attendees`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| event_id | VARCHAR(36) FK → events.id |
| user_id | VARCHAR(36) FK → users.id |
| responded_at | TIMESTAMP |

### `mentors`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| user_id | VARCHAR(36) UNIQUE FK → users.id |
| expertise | TEXT |
| rating | DOUBLE DEFAULT 0 |
| sessions_count | INT DEFAULT 0 |
| price | DECIMAL(10,2) |
| created_at | TIMESTAMP |
| updated_at | TIMESTAMP |

### `mentor_slots`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| mentor_id | VARCHAR(36) FK → mentors.id |
| starts_at | TIMESTAMP |
| ends_at | TIMESTAMP |
| available | BOOLEAN DEFAULT TRUE |

### `mentor_sessions`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| mentor_id | VARCHAR(36) FK → mentors.id |
| mentee_id | VARCHAR(36) FK → users.id |
| slot_id | VARCHAR(36) FK → mentor_slots.id |
| message | TEXT |
| status | VARCHAR(20) (SCHEDULED, COMPLETED, CANCELLED) |
| created_at | TIMESTAMP |

---

## V6 - Messaging

### `conversations`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| kind | VARCHAR(20) | DIRECT (2 pessoas) ou GROUP |
| title | VARCHAR(255) (nullable) | |
| created_by | VARCHAR(36) FK → users.id | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### `conversation_participants`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| conversation_id | VARCHAR(36) FK → conversations.id |
| user_id | VARCHAR(36) FK → users.id |
| last_read_at | TIMESTAMP |
| joined_at | TIMESTAMP |

### `messages`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| conversation_id | VARCHAR(36) FK → conversations.id |
| sender_id | VARCHAR(36) FK → users.id |
| text | TEXT |
| created_at | TIMESTAMP |

---

## V7 - Notifications

### `notifications`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| user_id | VARCHAR(36) FK → users.id | Destinatário |
| kind | VARCHAR(50) | Tipo (POST_COMMENT, POST_REACT, etc) |
| actor_id | VARCHAR(36) FK → users.id | Quem gerou a notificação |
| title | VARCHAR(255) | |
| body | TEXT | |
| href | VARCHAR(512) | Link |
| cta_label | VARCHAR(100) (nullable) | Texto do CTA |
| read | BOOLEAN DEFAULT FALSE | |
| created_at | TIMESTAMP | |

---

## V8 - Profile Extensions

### `user_experiences`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| user_id | VARCHAR(36) FK → users.id |
| title | VARCHAR(255) |
| company | VARCHAR(255) |
| location | VARCHAR(255) |
| description | TEXT |
| currently_here | BOOLEAN DEFAULT FALSE |
| start_date | DATE |
| end_date | DATE (nullable) |
| display_order | INT |
| created_at | TIMESTAMP |
| updated_at | TIMESTAMP |

### `user_education`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| user_id | VARCHAR(36) FK → users.id |
| school | VARCHAR(255) |
| degree | VARCHAR(255) |
| field | VARCHAR(255) |
| description | TEXT |
| currently_here | BOOLEAN DEFAULT FALSE |
| start_date | DATE |
| end_date | DATE (nullable) |
| display_order | INT |
| created_at | TIMESTAMP |
| updated_at | TIMESTAMP |

### `user_certifications`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| user_id | VARCHAR(36) FK → users.id |
| name | VARCHAR(255) |
| issuer | VARCHAR(255) |
| description | TEXT |
| issue_date | DATE |
| expiration_date | DATE (nullable) |
| credential_url | VARCHAR(512) (nullable) |
| created_at | TIMESTAMP |
| updated_at | TIMESTAMP |

---

## V9 - Password Reset & External Identities

### `password_reset_tokens`

| Coluna | Tipo | Descrição |
|---|---|---|
| token | VARCHAR(255) PK | Token de reset |
| user_id | VARCHAR(36) FK → users.id | |
| expires_at | TIMESTAMP | |
| used_at | TIMESTAMP (nullable) | |
| created_at | TIMESTAMP | |

### `user_external_identities`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| user_id | VARCHAR(36) FK → users.id |
| provider | VARCHAR(50) | google, apple |
| external_user_id | VARCHAR(255) |
| email | VARCHAR(255) (nullable) |
| created_at | TIMESTAMP |

UNIQUE: (provider, external_user_id)

---

## V11 - Reports

### `reports`

| Coluna | Tipo | Descrição |
|---|---|---|
| id | VARCHAR(36) PK | |
| reporter_id | VARCHAR(36) FK → users.id | |
| target_type | VARCHAR(20) | POST, COMMENT, USER, COMMUNITY, JOB, EVENT |
| target_id | VARCHAR(36) | |
| reason | VARCHAR(50) | SPAM, INAPPROPRIATE, ABUSIVE, etc |
| description | TEXT | |
| status | VARCHAR(20) | PENDING, APPROVED, DISMISSED |
| created_at | TIMESTAMP | |
| reviewed_by | VARCHAR(36) (nullable) FK → users.id | |
| reviewed_at | TIMESTAMP (nullable) | |
| resolution_note | TEXT (nullable) | |

---

## V14 - User Recommendations

### `user_recommendations`

| Coluna | Tipo |
|---|---|
| id | BIGINT AUTO_INCREMENT PK |
| recommender_id | VARCHAR(36) FK → users.id |
| recommended_id | VARCHAR(36) FK → users.id |
| comment | TEXT |
| created_at | TIMESTAMP |

---

## V15 - Comment & Message Reactions

### `comment_reactions`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| comment_id | VARCHAR(36) FK → comments.id |
| user_id | VARCHAR(36) FK → users.id |
| reaction_type | VARCHAR(20) |
| created_at | TIMESTAMP |

### `message_reactions`

| Coluna | Tipo |
|---|---|
| id | VARCHAR(36) PK |
| message_id | VARCHAR(36) FK → messages.id |
| user_id | VARCHAR(36) FK → users.id |
| reaction_type | VARCHAR(20) |
| created_at | TIMESTAMP |

---

## Migrações

As migrações estão em `src/main/resources/db/migration/` e seguem a numeração:

| Migração | Descrição |
|---|---|
| V1 | Schema inicial (users, posts, credenciais, sessões, outbox, audit) |
| V2 | Feed interactions (comments, reactions, saved_posts, post_shares) |
| V3 | Media assets |
| V4 | User connections |
| V5 | Communities, jobs, events, mentoring |
| V6 | Messaging |
| V7 | Notifications |
| V8 | Profile extensions (experiência, educação, certificações) |
| V9 | Password reset, external identities |
| V10 | Adiciona community_id em posts |
| V11 | Reports |
| V12 | Adiciona avatar em communities |
| V13 | Adiciona reputation em users |
| V14 | User recommendations |
| V15 | Comment & message reactions |
| V16 | Adiciona refresh_token em auth_sessions |
| V217 | Cidade/UF estruturadas (`city`, `state`, `city_key`) em `communities`, `users` e `events`; `users.show_city` (padrão false). `city_key` é a cidade sem acento e minúscula, gravada pela aplicação (`shared/geo/CityState`) |
