# Portfólio profissional

Vitrine de trabalho que cada pessoa monta no próprio perfil: uma **página com aparência própria**
(HTML do autor, no mesmo formato de página do resto do enois) e uma lista ordenada de **peças**
(projeto, texto, mídia, PDF, link). Esta fase entrega o modelo, a API, as regras e a mídia/PDF das
peças; o editor e o render público da página no front são fases seguintes.

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
| `GET /portfolio` | lista pública das vitrines no ar (`limit` 1–50, `cursor`; sem HTML nem peças) |
| `GET /portfolio` | lista pública das vitrines no ar (`limit`, `cursor`; sem HTML nem peças) |
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
| Lista de vitrines | 1–50 por página (padrão 20) |
| Peças por portfólio | 30 |
| Título da peça | 160 |
| Texto da peça | 4.000 |

## Banco (V385)

- `portfolio_pages`: `user_id` (pk), `draft_html`, `published_version`, `published_at`, `updated_at`.
- `portfolio_publications`: `id`, `user_id`, `version`, `html`, `blocks` (JSON), `published_at`;
  único por `(user_id, version)`.
- `portfolio_blocks`: `id`, `user_id`, `position`, `block_type`, `title`, `text`, `project_id`,
  `media_id`, `link_url`, `published_version` (null = nunca publicado), `created_at`, `updated_at`.

## Mídia e PDF da peça (V390)

A imagem da peça é a própria mídia. O PDF é o conjunto das páginas renderizadas no servidor:
o classificador não lê PDF, então cada folha é desenhada (PDFBox), conferida pelo verificador das
imagens ANTES de ir ao bucket e só depois gravada como `media_assets` com a chave
`paginas/<lote>/pagina-<n>.png`.

- `media_render_jobs` (uma linha por PDF): o pedido é o registro do trabalho — `PENDING` é render a
  fazer ou que parou com página gravada, `RENDERED` concluído, `FAILED` recusado (não é refeito).
- O check de segurança é da PÁGINA, no mesmo ledger `media_safety_checks`. Folha barrada contamina o
  documento: a peça sai do portfólio, a publicação é recusada e o PDF e as páginas recebem pedido de
  remoção.
- Enquanto alguma página não tem veredito `SAFE`, a mídia não é liberada (bloco de mídia,
  publicação e leitura pública do arquivo); o dono continua vendo o próprio arquivo.
- A varredura de segurança roda em duas fases: recolhe o render parado e, em seguida, entrega a
  análise às páginas — render antes de analisar, sempre.

## Lista pública das vitrines (`GET /portfolio`)

A seção de vitrines do Explorar lista quem **está no ar** — aberta a quem visita, como a leitura por
username. Só entra página publicada (versão 1 em diante): rascunho nunca aparece, e vitrine de conta
banida ou removida sai da lista. A resposta traz o que a lista precisa (nome, foto, @, versão, data e
o título da página — o primeiro `h1`, ou o primeiro `h2`) e **não** o HTML nem as peças.

Paginação por cursor `(published_at, user_id)` — não por posição: um offset pularia ou repetiria
linhas quando alguém publicasse entre duas páginas. `limit` vai de 1 a 50 (padrão 20); `nextCursor` é
`null` quando a lista terminou. Cursor fora do formato: `400 INVALID_CURSOR`.

## Fases seguintes (não implementadas aqui)

1. Render público da página no front (`patterns/html-frame`, quadro isolado) e editor
   (`patterns/editor`), como views do design system — **entregue** (Rede-ENois/enois-frontend#1209).
2. Seção no Explorar e card do feed — **entregue** (Rede-ENois/enois-frontend#1209), com a lista
   pública de `GET /portfolio` acima.
3. Exportação em PDF — **decidida em 2026-11-26: feita no navegador de quem abre a vitrine**
   (imprimir/salvar como PDF), sem render no servidor. O HTML do autor já é exibido em quadro
   isolado sem scripts; gerar o arquivo no cliente não acrescenta processo novo no backend nem
   coloca conteúdo de terceiro em renderizador no servidor. A imagem do backend continua sem
   navegador, e não precisa dele.
