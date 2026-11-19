# Énóis Games — plano de execução

Plano vivo do módulo Games (a plataforma de jogos indie do Énóis). Cada fase registra o que foi
feito, o que ficou pendente e por quê. **A regra da casa vale aqui em dobro: nada é marcado como
pronto sem estar implementado, testado e verificável.**

Última atualização: 2026-10-18.

---

## 1. Estado atual (auditoria)

O que **já existe** e é reutilizado (não foi recriado):

| Área | O que tem | Onde |
| --- | --- | --- |
| Jogos (agregador) | `games` (link + título/descrição/capa/tags), moderação `SUBMITTED → PUBLISHED/REJECTED`, `/games` (catálogo por limite), `/games/{username}`, `/games/{username}/{slug}`, `/me/games`, envio e reenvio | `games/` (V70) |
| Player | quadro isolado na página do jogo: `sandbox`, `allow="fullscreen; gamepad; autoplay"`, clique-para-jogar, "abrir na origem" | `routes/games.$username.$gameSlug.tsx` |
| Identidade | perfil do Énóis (nome, vulgo, avatar, capa, bio/HTML, projetos, emblemas), sessão por token, `CurrentUserResolver` | `profile/`, `identity/` |
| Social | posts do feed, comentários, reações, selos, blogs com inscrição (follow de mão única), conexões, notificações | `feed/`, `blog/`, `network/`, `notifications/` |
| Moderação | denúncia com alvo (`POST/COMMENT/USER/JOB`), fila de recursos, penalidades, painel `/moderacao` | `moderation/` |
| Mídia | upload com provedor do servidor (S3/ImageKit), leitura pública `GET /media/{id}`, `mediaIds` como convenção nos posts | `media/` |
| Moeda | **Faísca**: `coin_accounts` (saldo + `version` para concorrência otimista) e `coin_entries` (razão com `Kind`), carteira no app | `coin/` (V69) |
| Idempotência | `IdempotencyApplicationService` (chave + hash) usado em toda escrita | `idempotency/` |

O que **não existe** (as lacunas que este plano fecha):

1. Perfil público do desenvolvedor (hoje `/games/{username}` é só a lista de jogos do usuário).
2. Catálogo com busca, gêneros, filtros, ordenação confiável e paginação.
3. Página do jogo com o que um jogador precisa para decidir (galeria, estágio, idiomas, controles,
   requisitos, avisos, versão/histórico, avaliações).
4. Biblioteca do jogador e favoritos.
5. Histórico de atualizações do jogo (devlog).
6. Validação de URL de publicação no backend (hoje o front confia no que vem).
7. Hospedagem gerenciada (B7 Cloud) — **sem contrato técnico**: não há endpoint, credencial nem SDK.
8. Compra com Faísca, licença e repasse ao criador — **decisão comercial em aberto** (seção 7).
9. Métricas confiáveis (sessões, jogadores únicos) e instrumentação do player.

---

## 2. Decisões de arquitetura

1. **Um módulo, não um app.** Games é um módulo do backend (`games/`) e uma área de navegação do
   front, com a mesma stack (hexagonal, records, `ApiException`, migração Flyway, idempotência nas
   escritas). Nada de microsserviço, nada de segunda identidade de usuário: o desenvolvedor **é** o
   usuário do Énóis.
2. **O Énóis não executa código de terceiros.** O jogo roda na origem do criador, num `iframe`
   isolado. O backend **não** faz build, não roda container e não recebe executável no MVP.
3. **Faísca desde a modelagem, compra desligada.** O domínio nasce com preço, licença e lançamentos
   no razão, mas a compra só liga quando as regras de comissão, reembolso e repasse estiverem
   fechadas (seção 7) — é a recomendação estratégica do próprio pedido.
4. **B7 Cloud como infraestrutura independente.** O Énóis guarda o pedido, o estado e o vínculo da
   URL; quem provisiona e isola é a parceira. Enquanto não houver contrato, o estado existe e a
   solicitação fica registrada como **pendente de provisionamento** — nunca simulada como sucesso.
5. **Ordenação por popularidade resistente a manipulação**: contadores vêm de eventos do servidor
   (`game_events`), não de "views" da página. Sem métrica inventada (seção 6).

---

## 3. Modelo de dados

Migração `V75__games_platform.sql` (aditiva — nada do que existe é renomeado ou removido):

- `games` ganha: `short_description`, `genres`, `languages`, `controls`, `requirements`,
  `content_warnings`, `stage` (IN_DEVELOPMENT/DEMO/EARLY_ACCESS/RELEASED/PAUSED), `distribution`
  (FREE/PAID), `price_coins`, `aspect` (16:9/4:3/1:1/9:16), `version`, `website_url`, `support_url`,
  `media_ids`, `plays` (sessões iniciadas, contador do servidor), `published_at`, `suspended_at`.
- `developer_profiles` — a página pública do criador, presa à conta: `user_id` (único),
  `display_name`, `headline`, `bio`, `links`, `avatar`, `cover`, `updated_at`.
- `game_favorites` — a biblioteca/favoritos: `(user_id, game_id)` único, `created_at`.
- `developer_follows` — acompanhar o criador (mão única, como a inscrição de blog):
  `(follower_id, developer_user_id)` único.
- `game_updates` — o devlog: `game_id`, `title`, `body`, `created_at`.
- `game_events` — o que sustenta as métricas: `game_id`, `kind` (PAGE_VIEW, PLAY_START, SHARE),
  `user_id` opcional, `created_at`. **Sem** dados pessoais além do id de quem está logado.

Identificadores `game_…`, `gdev_…`, `gup_…`, `gfav_…`, `gevt_…`; sem FK (a lição de collation das
outras tabelas); `utf8mb4_uca1400_ai_ci`; índices para as consultas do catálogo
(`status, published_at`, `status, plays`, `genres`) — o catálogo nunca faz varredura completa.

---

## 4. Plano de segurança

- **Player**: `sandbox` mínimo (`allow-scripts allow-same-origin allow-pointer-lock`, `allow`
  fullscreen/gamepad/autoplay), `referrerPolicy=no-referrer`, `loading=lazy`. Sem `allow-popups`,
  sem `allow-forms`, sem `allow-top-navigation`. A sessão do Énóis **não** é acessível de dentro do
  quadro (origem diferente + token em `localStorage`, não em cookie).
- **URL de publicação**: validada no backend — `https` obrigatório, host com IP público (bloqueia
  loopback, faixas privadas, link-local, multicast e o endereço de metadados `169.254.169.254`),
  sem credenciais na URL, sem esquema `javascript:`/`data:`, limite de tamanho, e **a validação não
  segue redirecionamento para dentro da rede interna**.
- **CSP/frame-ancestors**: o criador recebe orientação (seção 8) — o Énóis não contorna bloqueio de
  incorporação de terceiro.
- **Hospedagem**: nenhum Dockerfile do usuário é executado na infraestrutura do Énóis. O contrato
  com a B7 Cloud é de provisionamento externo, com isolamento por cliente, quotas, auditoria e
  remoção — nada disso é implementado aqui sem contrato técnico.
- **Dinheiro**: saldo, preço e comissão sempre do servidor; lançamento no razão em transação com
  idempotência; nada de ponto flutuante (Faísca em inteiros, como o `coin` já faz).

---

## 5. Fases

### Fase 0 — Auditoria e arquitetura ✅ (este documento)
Auditoria dos módulos, decisões acima, modelo de dados, riscos e critérios de aceite.

### Fase 1 — Catálogo, identidade do criador e página do jogo ✅
Backend: catálogo com busca textual (título/descrição/tags), filtros (gênero, estágio, distribuição,
tag), ordenação (`recent`, `plays`, `title`), paginação por cursor; perfil do desenvolvedor
(leitura pública + edição pelo dono); favoritos; devlog; edição de jogo pelo dono; eventos de
métrica; validação de URL. Front: catálogo, página do desenvolvedor, página do jogo, painel do
criador.

### Fase 2 — Player ✅ (núcleo)
Proporção configurável por jogo (`aspect`), tela cheia, estados de carregando/falha/indisponível,
"abrir na origem", e a orientação de CSP para o criador. Métrica de "jogo iniciado" disparada pelo
servidor (`POST /games/{id}/play`), não pelo front contar clique.

### Fase 3 — Faísca, licença e biblioteca 🚧 **modelado, compra desligada**
`distribution`/`price_coins` já existem no domínio; falta o fluxo de compra (razão, licença,
comissão, reembolso). **Bloqueado por decisão comercial** — ver seção 7. Enquanto isso a página do
jogo mostra o preço como "em breve" e a biblioteca só guarda favoritos.

### Fase 4 — Hospedagem B7 Cloud ⛔ **dependente de contrato**
Contrato de integração, adaptador, solicitação e acompanhamento, quotas e auditoria. **Não há
contrato técnico, endpoint, credencial nem SDK confirmados** — nada foi inventado. O estado
`HOSTING_REQUESTED` existe no domínio e a tela diz, com todas as letras, que o provisionamento está
pendente de integração.

### Pendências desta rodada (honestas, sem botão que prometa o que não existe)
- **Painel**: cobre a página do criador e a lista dos próprios jogos com o status da moderação. A
  edição da FICHA completa de cada jogo (gêneros, idiomas, controles, requisitos, avisos, capturas,
  versão) existe na API (`PATCH /games/{gameId}`) mas ainda não tem formulário.
- **Capturas de tela**: a coluna `media_ids` e a galeria da página já funcionam; falta o upload no
  painel (o upload de mídia do Énóis já existe e é o que vai ser usado).
- **Coleções editoriais, recomendações e conquistas**: Fase 5.

### Fase 5 — Operação e expansão ⏳
Moderação de jogos no painel (a fila de denúncia já aceita o alvo), coleções editoriais,
recomendações explicáveis, conquistas com validação confiável, times/estúdios com papéis.

---

## 6. Testes e critérios de aceite

Automatizados (backend, HTTP com `MockMvc`): catálogo (busca, filtros, ordenação, paginação),
perfil do desenvolvedor (dono edita, outro não), favoritos (idempotentes), devlog, edição de jogo,
**bateria de URLs inválidas/inseguras** (loopback, faixa privada, metadados, `http`, `javascript:`,
credencial na URL), permissões de moderação e "jogo não publicado não aparece no catálogo".

Critérios de aceite da primeira versão funcional (os do pedido). Estado **antes** desta rodada de
implementação — a coluna é atualizada a cada fase entregue:

| # | Critério | Estado |
| --- | --- | --- |
| 1 | Criar perfil de desenvolvedor | ✅ (`PUT /games/profile` + `/games/painel`) |
| 2 | Criar, editar e publicar um jogo | ✅ (edição em `PATCH /games/{id}`; a ficha completa no painel ainda não) |
| 3 | Página pública do jogo | ✅ |
| 4 | Catálogo com jogos persistidos | ✅ |
| 5 | Busca e filtros corretos | ✅ (título/descrição/tag, gênero, estágio, distribuição, ordem, cursor) |
| 6 | Jogo externo roda no player | ✅ (depende do site permitir incorporação) |
| 7 | Tela cheia | ✅ |
| 8 | URL inválida/insegura rejeitada | ✅ (bateria de 12 endereços no teste) |
| 9 | Compartilhar a página do jogo no Énóis | ✅ (botão copia o link; o feed mostra o OG) |
| 10 | Favoritos e acompanhar o criador | ✅ |
| 11 | Permissões de dono e administrador | ✅ |
| 12 | Testes cobrindo os principais fluxos | ✅ (`GamesPlatformTests` 5/5) |
| 13 | Migrações e documentação | ✅ (este documento) |
| 14 | Nada simulado apresentado como concluído | ✅ (Fases 3 e 4 dizem o que falta) |
| 15 | B7 Cloud identificada como pendente | ✅ |
| 16 | Fluxo comercial protegido | 🚧 (compra desligada, não há o que proteger ainda) |
| 17 | Suspender um jogo sem destruir registros financeiros/licenças | 🚧 (não há registro financeiro de jogo ainda) |

---

## 7. Pendências de decisão (comerciais e de infraestrutura)

Estas **não** são tarefas de código — são decisões que o pedido manda não inventar:

1. **Comissão do Énóis** e **repasse ao criador**: percentual, quando, com que identidade e
   tributação. Sem isso, a compra fica desligada (o preço aparece como "em breve").
2. **B7 Cloud**: contrato técnico (endpoints, autenticação, limites, planos, preço de cada plano,
   SLA, política de remoção). Sem isso, a solicitação de hospedagem fica registrada como pendente.
3. **Reembolso**: prazo e regra (o `coin` já tem razão para estornar, falta a política).
4. **Jogos removidos/suspensos**: o que acontece com quem já adquiriu (a regra sugerida: a licença
   continua válida, o acesso depende da disponibilidade — e isso precisa estar escrito na página
   antes da compra existir).

## 8. Orientação para o criador (publicar e incorporar)

- O jogo precisa ser servido em **`https`** e permitir incorporação: `Content-Security-Policy:
  frame-ancestors https://enois.ia.br` (ou a lista de hosts permitidos) e **não** enviar
  `X-Frame-Options: DENY/SAMEORIGIN`.
- Tela cheia: a permissão é concedida pelo quadro do Énóis; o jogo só precisa pedir.
- Controles: teclado, mouse, gamepad e toque — o quadro repassa `gamepad` e a tela cheia.
- O Énóis não contorna bloqueio de incorporação: se o site recusar, o player mostra o motivo e
  oferece "abrir na origem".


## Compartilhar progresso no feed (G-share)

O jogo roda num iframe de OUTRA origem, sem sessão nenhuma da plataforma. Para o jogo
oferecer "compartilhar conquista", ele fala com a página pai por `postMessage`:

```js
// dentro do jogo (iframe)
window.parent.postMessage({
  type: "enois:share",
  texto: "Peguei nível 10 na fazendinha!",
  imagem: "data:image/png;base64,…"   // opcional (screenshot da conquista), ≤ ~2 MB
}, "*");
```

O que a página do Énóis faz:
1. valida a `origin` da mensagem contra o endereço registrado do jogo (outra janela não
   consegue forjar);
2. mostra a PRÉVIA ao jogador (texto + imagem) — o clique de publicar é sempre humano;
3. sobe a imagem pela mídia normal e publica o post na sessão DO JOGADOR, com as tags
   `game` e o slug do jogo.

Limites: texto até 600 caracteres; 12 compartilhamentos por dia por pessoa em cada jogo
(`429 SHARE_LIMIT`). A sessão do jogador nunca atravessa a borda do iframe e o jogo não
consegue publicar sem o clique humano na prévia.

Só conquistas publicadas geram `SHARE`, usado no limite diário e na série de compartilhamentos.
Despesas continuam registradas como `PURCHASE` no ledger de moedas e não geram evento de
compartilhamento.
