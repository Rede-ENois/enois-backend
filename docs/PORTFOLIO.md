# Portfólio profissional

Vitrine de trabalho que cada pessoa monta no próprio perfil: uma **página com aparência própria**
(HTML do autor, no mesmo formato de página do resto do enois) e uma lista ordenada de **peças**
(projeto, texto, mídia, PDF, link). Esta fase entrega o modelo, a API e as regras; o editor, o
render público da página no front e a moderação de PDF são fases seguintes.

## Regras do espaço

- **O público nunca vê o rascunho.** Toda edição grava no rascunho (`portfolio_pages.draft_html`
  e os blocos sem versão). Só `POST /me/portfolio/publish` publica: ele copia o retrato para
  `portfolio_publications` e incrementa `published_version`. A leitura pública serve SEMPRE a
  última publicação, mesmo que o dono tenha editado depois.
- **A trilha é imutável.** Cada publicação guarda o HTML e as peças daquela versão. É o retrato
  que a leitura pública usa e a base de um futuro "desfazer", sem inventar histórico retroativo.
- **HTML pela mesma limpeza de página.** O HTML do portfólio passa por
  `SafeHtml.sanitizePortfolioHtml` (allowlist de tags/CSS, teto de 200.000 caracteres, o mesmo do
  post de página e do card de projeto). Link perigoso é recusado antes mesmo da gravação pelo
  gate global de link do corpo JSON (`400 UNSAFE_LINK`).
- **Peça aponta, não copia.** A peça de projeto guarda `project_id` de `user_projects` (não copia o
  card); reordenar no portfólio não mexe na ordem do perfil. A peça de mídia/PDF guarda `media_id`,
  e o bloco só aceita mídia que é do dono, está `READY` e tem o mime certo (PDF para a peça de PDF).
  Projeto apagado depois da publicação sai do retrato público em vez de aparecer quebrado.
- **Restrição e banimento valem aqui.** Conta restrita não edita nem publica
  (`403 ACCOUNT_RESTRICTED`, mesmo portão do feed, blog e grupos). O portfólio de conta banida não
  aparece no perfil público (`404 PORTFOLIO_NOT_FOUND`, igual a perfil inexistente).
- **Publicar exige conteúdo.** Página vazia e sem peças responde `409 PORTFOLIO_EMPTY`.

## Endpoints (prefixo `/api` na borda do gateway)

| Método | Rota | O que faz |
| --- | --- | --- |
| `GET /portfolio/{username}` | leitura pública: última publicação (versão, HTML, peças) |
| `GET /me/portfolio` | o que o dono edita: rascunho, peças e versão publicada |
| `PUT /me/portfolio/draft` | `{ html }` — grava o rascunho da página (sanitizado) |
| `POST /me/portfolio/blocks` | cria uma peça no rascunho → 201 |
| `PUT /me/portfolio/blocks/{blockId}` | edita a peça (volta a ser rascunho até publicar) |
| `DELETE /me/portfolio/blocks/{blockId}` | apaga a peça |
| `PUT /me/portfolio/order` | `{ order: [ids] }` — reordena; precisa listar todas, uma vez cada |
| `POST /me/portfolio/publish` | publica o rascunho como a próxima versão |

A peça (`block`) é `{ type, title?, text?, projectId?, mediaId?, linkUrl? }`, com `type` em
`PROJECT | TEXT | MEDIA | PDF | LINK`. A vista devolve `published` por peça (se ela já está em
alguma versão publicada).

Erros: `400 PORTFOLIO_BLOCK_TYPE_REQUIRED`, `400 PORTFOLIO_BLOCK_TITLE_TOO_LONG`,
`400 PORTFOLIO_BLOCK_TEXT_TOO_LONG`, `400 PORTFOLIO_BLOCK_CONTENT_REQUIRED`,
`400 PORTFOLIO_BLOCK_PROJECT_INVALID`, `400 PORTFOLIO_BLOCK_MEDIA_INVALID`, `400 PORTFOLIO_LINK_INVALID`,
`400 PORTFOLIO_ORDER_INVALID`, `400 MAX_PORTFOLIO_BLOCKS`, `400 INVALID_PORTFOLIO_HTML`,
`403 ACCOUNT_RESTRICTED`, `404 PORTFOLIO_BLOCK_NOT_FOUND`, `404 PORTFOLIO_NOT_FOUND`,
`409 PORTFOLIO_EMPTY`.

## Limites

| Item | Limite |
| --- | --- |
| HTML da página | 200.000 caracteres (o mesmo do post de página) |
| Peças por portfólio | 30 |
| Título da peça | 160 |
| Texto da peça | 4.000 |

## Banco (V385)

- `portfolio_pages`: `user_id` (pk), `draft_html`, `published_version`, `published_at`, `updated_at`.
- `portfolio_publications`: `id`, `user_id`, `version`, `html`, `blocks` (JSON), `published_at`;
  único por `(user_id, version)`.
- `portfolio_blocks`: `id`, `user_id`, `position`, `block_type`, `title`, `text`, `project_id`,
  `media_id`, `link_url`, `published_version` (null = nunca publicado), `created_at`, `updated_at`.

## Fases seguintes (não implementadas aqui)

1. Envio de mídia/PDF no bloco, com página de PDF renderizada no servidor (PDFBox) e cada página
   passando pelo classificador NSFW pelo mesmo ledger `media_safety_checks` — o portfólio não pode
   ser uma porta lateral para conteúdo que a moderação de imagem barra.
2. Render público da página no front (`patterns/html-frame`, quadro isolado) e editor
   (`patterns/editor`), como views do design system.
3. Seção no Explorar e card no feed.
4. Exportação em PDF (processo isolado, sem rede, com timeout — o HTML é do autor).
