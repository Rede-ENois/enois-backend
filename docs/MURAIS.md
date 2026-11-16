# Murais

Coleções **colaborativas** de publicações do feed, dentro do **Explorar**. O mural é só o quadro:
o dono cria, edita e remove; qualquer pessoa cola uma publicação que **JÁ existe no feed**. Nada
aqui cria conteúdo novo — a publicação colada continua sendo ela mesma, com comentário, reação e
moderação no feed; o mural guarda apenas o elo.

## Regras do espaço

- **O quadro é do dono.** Criar, editar (título/descrição) e remover o mural são gestos do dono
  (`403 NOT_AUTHORIZED` para os outros). Remover o mural **não apaga** as publicações coladas.
- **Colar aponta, não copia.** O post precisa existir e ser visível para quem cola — a MESMA
  visibilidade do feed (`404 POST_NOT_FOUND` para apagado, privado ou bloqueado). O elo é
  determinístico (`mural:post`): recolocar a mesma publicação no mesmo mural recusa com
  `409 JA_NO_MURAL`, sem duplicar linha.
- **Quem colou ou o dono tira.** Remover a contribuição apaga só o elo (`403 NOT_AUTHORIZED`
  para os outros). Publicação apagada do feed simplesmente sai da página do mural — o elo fica,
  a vitrine não.
- **Notificação**: colar publicação alheia avisa o autor do post (`MURAL_CONTRIBUTED`, categoria
  SOCIAL) com link para o mural.
- **Moderação**: mural é conteúdo público e pode ser denunciado — alvo `MURAL` em `TargetType`;
  a denúncia confirmada derruba **só o quadro**, nunca os posts colados.
- **Gates de sempre**: leitura com sessão; escrita com `Idempotency-Key`, idade, sanção e rate
  limit (`CREATE_POST` para criar mural, `CREATE_COMMENT` para colar).
- **Título de até 120** (`400 MURAL_TITLE_REQUIRED` / `400 MURAL_TITLE_TOO_LONG`), descrição
  opcional de até 500 (`400 CONTENT_TOO_LONG`).

## Endpoints (prefixo `/api` na borda do gateway)

| Método | Rota | O que faz |
| --- | --- | --- |
| `GET /murais` | murais ativos, mais novos primeiro (`cursor`/`limit` ≤ 48, padrão 24) |
| `POST /murais` | `{ title, description? }` → 201 |
| `GET /murais/{muralId}` | quadro único com dono e contagem de contribuições |
| `PATCH /murais/{muralId}` | `{ title, description? }` — só o dono |
| `DELETE /murais/{muralId}` | só o dono; posts colados ficam no feed |
| `GET /murais/{muralId}/contribuicoes` | página crônica (`cursor`/`limit`), posts enriquecidos |
| `POST /murais/{muralId}/contribuicoes` | `{ postId }` → 201 (id ou link; a tela extrai o id) |
| `DELETE /murais/{muralId}/contribuicoes/{postId}` | quem colou ou o dono tira o elo |

Erros: `400 MURAL_TITLE_REQUIRED`, `400 MURAL_TITLE_TOO_LONG`, `400 CONTENT_TOO_LONG`,
`400 IDEMPOTENCY_KEY_REQUIRED`, `403 NOT_AUTHORIZED`, `404 MURAL_NOT_FOUND`,
`404 POST_NOT_FOUND`, `404 CONTRIBUICAO_NOT_FOUND`, `409 JA_NO_MURAL`.

## Persistência

`V329__murais.sql` cria `murais` e `mural_contribuicoes` (sem FK por limitação do ambiente
MariaDB; idempotente e aditivo). A leitura das contribuições consome o port-in `GetPostsUseCase`
do feed — um só lugar decide o que é visível.
