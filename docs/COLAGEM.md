# Colagem

O espaço fotográfico da rede, dentro do **Explorar**. É um feed só dele — quase um grupo de
fotografias: nada do que é publicado aqui entra nos feeds gerais (`/feed`), por construção (tabelas
e consultas próprias, fora do módulo de feed).

## Regras do espaço

- **Uma fotografia por publicação.** A mídia precisa existir, pertencer a quem publica e ser
  imagem (`image/*`) — vídeo recusa com `400 NOT_IMAGE`. Sem mídia, `400 MEDIA_REQUIRED`; mídia
  alheia, `403 MEDIA_OWNER_MISMATCH`; mídia removida, `404 MEDIA_NOT_FOUND`.
- **Legenda opcional de até 500 caracteres** (`400 CONTENT_TOO_LONG` acima disso).
- **Comentários** aceitam texto (até 800), uma fotografia, ou os dois. Vazio recusa com
  `400 COMMENT_EMPTY`. **Resposta a comentário fotográfico não pode carregar outra fotografia**
  (`400 PHOTO_REPLY_NOT_ALLOWED`) — sem recursão de imagens; a resposta em texto passa normal.
- **Reação única por pessoa por foto**: reagir de novo troca o tipo; remover é DELETE. Os tipos são
  os mesmos do feed (`feed.domain.ReactionType`).
- **Leitura exige sessão** (é espaço de convivência, como o feed geral); toda escrita exige
  `Idempotency-Key` e passa pelos gates de sempre (idade, sanção, rate limit).
- **Bloqueios valem aqui**: foto de quem foi bloqueado some do feed de quem bloqueou.
- **Notificação**: contribuição (texto ou foto) na foto de outra pessoa avisa o autor
  (`COLLAGEM_COMMENTED`, categoria SOCIAL).

## Endpoints (prefixo `/api` na borda do gateway)

| Método | Rota | O que faz |
| --- | --- | --- |
| `GET /colagem` | feed próprio (grade), cursor por tempo (`cursor`, `limit` ≤ 48, padrão 24) |
| `POST /colagem/posts` | publica `{ mediaId, description?, mediaSpoiler? }` → 201 |
| `GET /colagem/posts/{postId}` | foto única com autor, reações (`reactions`, `myReaction`) e `comments` |
| `DELETE /colagem/posts/{postId}` | o autor remove a própria foto (e os comentários vão junto) |
| `GET /colagem/posts/{postId}/comments` | contribuições em ordem crônica, cursor ascendente (`after`) |
| `POST /colagem/posts/{postId}/comments` | `{ parentCommentId?, content?, mediaId?, mediaSpoiler? }` → 201 |
| `DELETE /colagem/posts/{postId}/comments/{commentId}` | o autor do comentário remove o próprio |
| `POST /colagem/posts/{postId}/reactions` | `{ reaction: "LIKE" }` → devolve o resumo por tipo |
| `DELETE /colagem/posts/{postId}/reactions` | remove a reação de quem pediu |

`mediaSpoiler: true` marca a foto como sensível (o cliente mostra aviso antes de revelar; ver
`docs/MIDIA_SENSIVEL.md` quando a proteção de mídia sensível estiver no ar).

## Moderação

Os alvos `COLLAGEM_POST` e `COLLAGEM_COMMENT` entram no fluxo padrão de denúncia (`TargetType`):
a revisão confirmada remove pelo `ReportedContentRemover`, o autor sai de
`ReportedContentAuthorAdapter` e a triagem lê a legenda/comentário pelo `ReportedContentAdapter`.

**Takedown de mídia**: quando a barreira de mídia sensível (`MediaSafetyService`) confirma uma
imagem, ela chama `ColagemModerationPort.removeByMedia(mediaId)`, que derruba todas as fotos que
usam a mídia (com os comentários) e os comentários fotográficos que a carregam. Além disso, foto
cuja mídia deixou de estar visível **some do feed** na leitura (sem imagem não há Colagem).

## Persistência (`V328__colagem.sql`)

- `colagem_posts` — foto, legenda, `media_spoiler`, status (`ACTIVE`/`REMOVED`), `removed_at`
- `colagem_comments` — texto e/ou `media_id`, `parent_comment_id` (um nível), status
- `colagem_reactions` — uma linha por (foto, pessoa); trocar reação é UPDATE estrutural
- Índices: feed por `(status, created_at)`, takedown por `media_id`, contagem por post
