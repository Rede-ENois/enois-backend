# Coleções pessoais

Pastas **privadas** em que cada pessoa organiza as publicações que **JÁ salvou** no perfil. Coleção
não é conteúdo público: só quem criou vê e mexe — não há cola de terceiros, notificação nem
moderação de coleção. A publicação guardada continua sendo ela mesma, com comentário, reação e
moderação no feed; a coleção organiza apenas os elos.

## Regras do espaço

- **Privada por construção.** Coleção inexistente **ou de outra pessoa** devolve o MESMO
  `404 COLECAO_NOT_FOUND` em toda rota — a existência de coleção alheia não se confirma.
- **Guardar exige salvo prévio.** A coleção organiza salvos, não substitui o salvar: post que não
  está na lista de salvos do dono recusa com `409 NAO_SALVO`. E precisa estar visível para o dono
  (MESMA visibilidade do feed, `404 POST_NOT_FOUND`). O elo é determinístico (`colecao:post`):
  guardar de novo recusa com `409 JA_NA_COLECAO`.
- **Tirar da pasta nunca apaga nada.** Remover o elo não mexe no post nem no salvar — o post
  continua salvo no perfil. Post apagado do feed simplesmente sai da página da coleção.
- **Remover a coleção apaga de verdade.** Coleção é dado privado: a pasta e os elos saem do banco
  (sem status de remoção, sem rastro público).
- **Sem notificação e sem moderação de coleção.** Não há exposição para terceiros; se um post é
  removido pela moderação, ele desaparece da coleção por deixar de ser visível.
- **Gates de sempre**: leitura com sessão (e só do dono); escrita com `Idempotency-Key`, idade,
  sanção e rate limit (`CREATE_POST` para criar coleção, `CREATE_COMMENT` para guardar).
- **Título de até 120** (`400 COLECAO_TITLE_REQUIRED` / `400 COLECAO_TITLE_TOO_LONG`), descrição
  opcional de até 500 (`400 CONTENT_TOO_LONG`).

## Endpoints (prefixo `/api` na borda do gateway)

| Método | Rota | O que faz |
| --- | --- | --- |
| `GET /colecoes` | as SUAS coleções, mais novas primeiro (`cursor`/`limit` ≤ 48, padrão 24) |
| `POST /colecoes` | `{ title, description? }` → 201 |
| `GET /colecoes/{colecaoId}` | pasta única com contagem de posts |
| `PATCH /colecoes/{colecaoId}` | `{ title, description? }` — a pasta é sua |
| `DELETE /colecoes/{colecaoId}` | apaga a pasta e os elos de verdade |
| `GET /colecoes/{colecaoId}/posts` | página crônica (`cursor`/`limit`), posts enriquecidos |
| `POST /colecoes/{colecaoId}/posts` | `{ postId }` → 201, exige post salvo pelo dono |
| `DELETE /colecoes/{colecaoId}/posts/{postId}` | tira só o elo; o post segue salvo |

Erros: `400 COLECAO_TITLE_REQUIRED`, `400 COLECAO_TITLE_TOO_LONG`, `400 CONTENT_TOO_LONG`,
`400 IDEMPOTENCY_KEY_REQUIRED`, `404 COLECAO_NOT_FOUND`, `404 POST_NOT_FOUND`,
`404 POST_NAO_NA_COLECAO`, `409 NAO_SALVO`, `409 JA_NA_COLECAO`.

## Persistência

`V336__colecoes_salvas.sql` cria `colecoes_salvas` e `colecao_salva_posts` (sem FK por limitação
do ambiente MariaDB; idempotente e aditivo). A listagem dos posts consome o port-in
`GetPostsUseCase` do feed — um só lugar decide o que é visível; a checagem de "salvo" reusa
`PostInteractionRepository.findSavedPostIdsByUserId`, a mesma fonte da listagem `/feed/saved`.
