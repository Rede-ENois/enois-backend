# Isolated Content Platform — Fase 1 (Discovery)

> Diagnóstico do sistema de conteúdo HTML/CSS do Énóis, feito sobre o código real em
> 2026-11-09. Nenhum caminho, classe ou endpoint aqui é suposto: cada linha foi lida no
> repositório. Este documento é a base das Fases 2–9 do plano de reestruturação.

## 1. Arquitetura atual (como funciona hoje)

Dois lados, um contrato simples:

1. **Entrada e limpeza (backend).** Todo HTML é limpo no *save* por `SafeHtml`
   (`shared/html/SafeHtml.java`), com allowlist de tags/atributos via jsoup, limpeza de CSS
   (sem `@import`, `expression()`, `url()` fora de `https:`/`data:`), SVG inline permitido e
   `iframe` restrito a provedores conhecidos. O resultado é gravado no banco — **o cru não é
   persistido**.
2. **Renderização (frontend).** O HTML limpo vai para um `iframe` com `srcDoc`, sandbox e uma CSP
   própria (`design-system/src/patterns/html-frame/sandboxed-html-frame.tsx`). O conteúdo nasce
   dentro do iframe, nunca no DOM do app.

**Atributos do iframe hoje:**

| Item | Valor atual | Observação |
|---|---|---|
| Carregamento | `srcDoc={buildDocument(...)}` | documento montado em memória |
| `sandbox` (padrão) | `allow-same-origin allow-popups allow-popups-to-escape-sandbox` | **sem** `allow-scripts` |
| `sandbox` (SoundCloud) | `allow-scripts allow-popups allow-popups-to-escape-sandbox` | origem opaca |
| CSP interna | `default-src 'none'; img-src https: data:; media-src https: data:; font-src data:; style-src 'unsafe-inline'; frame-src https://w.soundcloud.com; object-src 'none'; connect-src 'none'; worker-src 'none'; form-action 'none'` | definida em `FRAME_CSP` |
| CSP do app | `frame-src https:` (aceita o iframe), `frame-ancestors 'none'` | `frontend/src/lib/csp.ts` |
| Altura | medida do pai via `contentDocument` (exige `allow-same-origin`) | `contentHeight()` + `ResizeObserver` |
| `allow` | `autoplay; fullscreen` (ou só `fullscreen` no modo lite) | `referrerPolicy=no-referrer`, `loading=lazy` |

## 2. Inventário de superfícies (todas leem/escrevem HTML)

| Recurso | Escrita (backend) | Leitura/render (frontend) | Outros leitores |
|---|---|---|---|
| Post de página (`PostType.PAGE`) | `PublishPostService:314`, `EditPostService:126` → `SafeHtml.requirePageHtml` (teto 200.000) | `post-card.tsx:287`, expandir post | feed, busca, crawler (HTML servido pela API) |
| Bio do perfil | `ProfileService:301` → `requireProfileHtml` (20.000) | `perfil.$username.tsx:875`, `profile-bio-dialog.tsx:49` (preview) | perfil público |
| Capa/banner do perfil | `ProfileService:303` | `banner-html-frame.tsx` | perfil público |
| Capa de comunidade | `CommunityService:89` | `comunidades.$communityId.tsx:390` | listagem de comunidades |
| Banner de grupo | `GroupService:88` | — (usado no DS) | — |
| Tema do blog | `BlogService:144` → `requireBlogHtml` (20.000) | `blog.$username.tsx:105` | página do blog |
| Post do blog | `BlogService:279` → `requireBlogHtml` | `blog.$username_.$postId.tsx:209` | página do post |
| Card de projeto | `ProjectsService:131` → `sanitizeProjectHtml` (200.000) | perfil (`projects-manager`) | perfil público |
| Aviso administrativo | `NoticeService:66` → `requirePageHtml` | `admin-notice.tsx:52,108` | app inteiro |
| Bio importada do GitHub | `MarkdownBioRenderer:50` → `requireProfileHtml` | mesma da bio | — |
| Jogos (descrição) | — | `game-detail.tsx` | catálogo |
| Preview de editor | — | `html-editor.tsx:22` | só editor |

**Total de pontos de render:** 16 usos de `SandboxedHtmlFrame` em 10 arquivos.
**Total de pontos de escrita:** 13 chamadas a `SafeHtml.*` em 9 serviços.

## 3. Conflitos com o plano (documentados, não corrigidos ainda)

### C1 — `srcDoc` + `allow-same-origin` é o modelo que o plano proíbe

O plano pede origem dedicada (`usercontent.enois.ia.br`) e `sandbox="allow-scripts"` mínimo,
sem `allow-same-origin`. Hoje usamos `srcdoc` (herda a origem do app) + `allow-same-origin`
para medir a altura do documento por `contentDocument`.

**Por que existe:** sem `allow-scripts`, o iframe não consegue avisar o pai da própria altura; a
alternativa foi medir de fora, o que exige a mesma origem. Com origem dedicada, a medição passa a
ser por `postMessage` (que o plano já prevê) e `allow-same-origin` sai.

**Decisão autorizada (2026-11-09):** o domínio isolado será **`enois.ia.br/usercontent`** (mesma
origem do site, caminho dedicado) em vez do subdomínio `usercontent.enois.ia.br`.

**Consequência técnica que precisa de aprovação explícita:** `enois.ia.br/usercontent` **não é
origem separada** — origem é esquema + host + porta, e o caminho não conta. Servir por caminho
mantém a **mesma origem** do app, o que anula a principal garantia do plano ("não compartilhar
contexto/cookies/armazenamento"). Mitigações possíveis, em ordem de efetividade:

1. **Subdomínio** (`usercontent.enois.ia.br`) — única forma de origem separada de verdade. Exige
   DNS + certificado (o certificado pode ser wildcard `*.enois.ia.br`, se já existir).
2. Caminho + sandbox **sem** `allow-same-origin` + CSP `sandbox` — reduz muito o risco (sem
   acesso a cookie/storage/DOM do pai), mas o navegador ainda considera a origem igual: um bug do
   navegador no sandbox afeta o app.
3. Caminho + `allow-same-origin`: **não recomendado** — é o modelo atual sem ganho real.

Este documento registra o conflito; a escolha entre 1 e 2 é decisão de produto/infra.

### C2 — `frame-src https:` aceita qualquer origem

A CSP do app permite qualquer iframe `https:`. Com renderizador central, dá para restringir a
`frame-src 'self' https://usercontent...`.

### C3 — A sanitização é a única barreira real hoje

Como o sandbox permite a mesma origem, quem escapasse da allowlist teria acesso ao DOM do app.
Por isso os fixes recentes no `SafeHtml` (atributo `id`, CSS) importam: até a camada de
isolamento existir, a limpeza continua sendo a fronteira principal — o plano prevê mantê-la como
defesa em profundidade, não como única.

### C4 — Conteúdo antigo no banco

Tudo que foi gravado passou por versões anteriores do `SafeHtml`. Mudar o renderizador não pode
exigir reprocessar o banco (o plano proíbe conversão destrutiva): o novo caminho deve renderizar
o HTML já limpo como está, e a limpeza nova vale para gravações novas.

## 4. Onde o KofScript entra

Autorizado: referência da linguagem em **`koflang.github.io`**. A arquitetura prevê
`HTML_ONLY`, `HTML_CSS` e `HTML_CSS_KOF` como tipos do renderizador; o terceiro fica preparado
(contrato e ponto de extensão) sem implementar interpretador nesta fase.

## 5. O que a Fase 2 precisa decidir (para aprovação)

1. **Origem:** subdomínio (recomendado) ou caminho com sandbox sem mesma-origem (ver C1).
2. **Protocolo:** mensagens `CONTENT_READY`, `RESIZE_REQUEST`, `CONTENT_ERROR` — e o canal
   (`MessageChannel` quando a origem for opaca).
3. **Altura:** passa a vir do `postMessage` (o pai não lê mais o documento).
4. **Cache:** conteúdo imutável por hash? (o HTML é limpo no save, então o hash do texto gravado
   já é estável).
5. **Limites:** instâncias ativas por página, timeout de renderização, tamanho de mensagem.

## 6. Passos de infraestrutura que só o time pode executar

- **DNS:** registro para o domínio escolhido.
- **Certificado:** wildcard ou específico do subdomínio.
- **Proxy reverso:** rota do domínio/path para o serviço de conteúdo, com headers de segurança
  (`nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` restritiva, CORS fechado).
- **Serviço de entrega:** endpoint público que serve o HTML limpo por id, **sem** credenciais da
  aplicação e sem acesso a endpoints privados.

## 7. Estado e próximos passos

Fase 1 concluída: superfícies mapeadas, conflitos documentados, infra identificada. As Fases 2–9
dependem da decisão de origem (item 5.1) e da provisão de infra (seção 6).