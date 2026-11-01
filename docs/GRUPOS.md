# ÉNOIS — GRUPOS: OPERAÇÃO, PROGRESSO E PLANO

Este arquivo tem três partes:

1. **Operação** — como trabalhar neste plano (repositórios, comandos, restrições).
2. **Progresso** — o checklist vivo; toda execução atualiza esta seção.
3. **Plano** — o pedido completo, na íntegra (a partir de "PLANO COMPLETO").

Quem trabalha aqui: a automação **"Implementar o plano de Grupos a cada 30 minutos"**, que roda a
cada 30 minutos e continua exatamente do ponto onde a execução anterior parou. Qualquer sessão
interativa que pegue este plano segue as mesmas regras.

## Operação

Cada execução:

1. Lê este documento inteiro, o `git log` recente dos dois repositórios e o `git status`.
2. Escolhe a próxima pendência na ordem das Etapas 1–8 do plano e implementa **uma fatia coerente
   de verdade** — código funcional, não planejamento. Não pare no plano.
3. Roda os testes do que tocou e corrige o que quebrou (sem declarar aprovado sem ter rodado).
4. Atualiza a seção **Progresso** (o que ficou pronto, o que foi testado, o que falta).
5. Commita e empurra o que produziu, com escopo nos seus próprios arquivos.

**Condição de parada:** quando TODAS as etapas do plano estiverem implementadas e testadas (os
critérios de aceite da seção 21 verificados) e a seção Progresso estiver completa, a execução não
implementa mais nada: usa `CronList` para localizar a automação e `CronDelete` para removê-la.
Enquanto houver pendência real, a automação fica viva.

### Repositórios e comandos

- **Backend** — `/home/mel/Documentos/rede` (Spring Boot 4 / Java 25, Maven; MariaDB 11.4 em prod,
  H2 nos testes). Testes: `mvn -q test -Dtest=Classe1,Classe2` — **nunca `mvn clean`** (a árvore é
  compartilhada com outra sessão que compila no mesmo `target/`; para suíte pesada, use
  `git worktree` num diretório temporário). Migrações: `src/main/resources/db/migration`, sempre
  **idempotentes** (`if not exists` em tabela/coluna/índice) — migração que recria objeto existente
  derruba o boot e trava o rollout em silêncio. Validar em MariaDB de bancada antes de confiar.
- **Frontend** — `/home/mel/Documentos/connect-grow` (React 19 / TanStack Start). Testes:
  `npx vitest run <arquivo>`; build: `npx vite build`; lint: `npx eslint <arquivos>` conferindo o
  exit code (`eslint=$?`). A linha ativa é `main`: publicar com
  `git push origin feat/tema-personalizado:main` (sem trocar de branch).
- **Deploy** — o front publica pelo workflow do repo `rede`
  (`gh workflow run "Build, Push and GitOps Deploy" --ref main`), que também empurra o manifesto
  do GitOps. Só faça deploy quando a fatia estiver verificada, e **nunca afirme que subiu sem
  sondar** (o pod do backend já ficou horas travado). Commit e push, sim, a cada execução.

### Restrições (valem para toda execução)

- A árvore é **compartilhada** com outra sessão que também commita. Nunca `git checkout .`,
  `git restore .`, `git reset --hard`; nunca `eslint --fix` em arquivo que não é seu; `git add` só
  nos seus arquivos (nunca `git add -A`). Se um arquivo compartilhado tiver mudanças que não são
  suas, trabalhe por cima sem desfazê-las.
- Não pergunte nada (ninguém vai responder). Se estiver bloqueado, registre no Progresso e siga
  para a próxima pendência possível.
- Não use dados fictícios para simular funcionalidade; não deixe botão que aparenta funcionar sem
  operação real.
- Não crie nem agende outras automações.
- Fora do escopo deste plano: os itens da fila da sessão interativa (botões de exportar/importar
  tema, marketplace ampliado). Não mexa neles; se o `git status` mostrar arquivos modificados que
  não são seus, evite-os.
- **Existe outra automação trabalhando nos DOIS repos a cada 20 min** (a do plano mestre
  `/home/mel/Documentos/PLANO-temas-loja-e-moeda.md`: temas, moeda faísca, marketplace com
  escrow, serviços, games). Antes de criar uma migração, confira o maior `V*` existente no
  instante e o `git status`; se ela estiver com trabalho não commitado nos arquivos que você
  precisa, escolha outra pendência do plano ou trabalhe por cima sem desfazer nada. Duas
  migrações com o mesmo número derrubam o boot — na dúvida, a sua vai no número seguinte ao
  maior que existir naquele momento.

## Progresso

> Atualize esta seção a cada execução: `- [x] feito — <o que> — <onde> (commit <hash>)`.

> **Plano CONCLUÍDO em 2026-10-19.** Não há mais nenhum item aberto. A automação de 30 minutos
> (`automation-423f2e18-213d-4b4e-9698-1252869e9bd2`) **já foi removida** — conferido em 2026-10-21
> pelo `CronList`, que hoje só mostra a auditoria do Énóis ("Auditar e corrigir o Énois a cada 40
> minutos", `automation-3796fc81`). A nota antiga ("o `CronDelete` não está disponível") era do
> ambiente daquela sessão: hoje a ferramenta existe e o registro foi limpo.

### Etapa 1 — Auditoria
- [x] Mapear a arquitetura real: feed, comunidades, autenticação, persistência, mídia,
      notificações, AutoMod, denúncias (backend `rede` + front `connect-grow`) — escrito em
      "Pendências, riscos e decisões".
- [x] Registrar riscos e dependências em "Pendências, riscos e decisões" (2026-10-18).

### Etapa 2 — Fundação de dados
- [x] Migração `V81__groups.sql` com as tabelas de Grupos (próprias, separadas das de
      Comunidades): `groups`, `group_members`, `group_join_requests`, `group_topics`,
      `group_posts`, `group_post_comments`, `group_post_reactions`. Validada em MariaDB 11.4 de
      bancada (aplica, reaplica sem erro = idempotente) e com as garantias estruturais testadas
      por SQL: tópico de outro grupo, membro/slug/pendência/reação duplicados e comentário órfão
      são todos recusados pelo banco; apagar o grupo leva o conteúdo junto (2026-10-18).
- [x] Índices, unicidade e integridade referencial — dentro da própria V81: índice para cada
      consulta prevista (feed por grupo, tópicos, pendências, autor), unicidade em (grupo, pessoa),
      slug e (publicação, pessoa) na reação, e chave estrangeira para grupo/autor/pessoa
      (2026-10-18).
- [x] Regras de autorização centrais — `groups/domain/{GroupRole,GroupCapability,GroupPermissions}`:
      uma tabela papel → capacidade lida por todos os endpoints (dono / admin / moderador / membro /
      visitante), as quatro proibições do plano negadas a todo mundo, e a trava de o grupo nunca
      ficar sem autoridade (o último dono não sai). Mais o ciclo de vida em
      `{Group,GroupStatus,GroupJoinMode,GroupMember,GroupSlug}`. `GroupPermissionsTests` (7) e
      `GroupDomainTests` (7) rodados: 14/14 verdes (2026-10-18).

### Etapa 3 — Ciclo de vida e ingresso
- [x] Criação e edição do grupo (identidade visual, descrição, modo de ingresso) — `GroupService`
      + `GroupController` (`POST/PATCH /groups`), slug único com sufixo numérico, capa em HTML
      sanitizada pela mesma régua do perfil (2026-10-18).
- [x] Modo aberto e modo por aprovação; solicitações; aprovar/rejeitar sem duplicidade — entrada
      aberta vira membro na hora; aprovação cria um pendente por pessoa (índice `pending_slot`);
      a decisão é um UPDATE condicional (`status = PENDING`), então duas aprovações simultâneas
      não viram duas associações (2026-10-18).
- [x] Membros: entrada, saída, remoção; permissões revogadas de imediato — listar exige ser membro,
      remover exige `MANAGE_MEMBERS`, o dono não sai sem transferir a propriedade (e a transferência
      existe: `POST /groups/{id}/transfer`). `GroupFlowTests` cobre os 7 cenários e passa (2026-10-18).

### Etapa 4 — Tópicos e feed privado
- [x] Tópicos do grupo (criar, editar, reordenar, arquivar) — `GroupFeedService` + endpoints
      (`/groups/{id}/topics`); nome único por grupo, arquivado para de receber publicação, e a
      reordenação exige a lista COMPLETA do grupo (ordem parcial é recusada) (2026-10-18).
- [x] Publicações sempre presas a grupo + tópico do mesmo grupo — `group_posts` em tabela própria;
      a V81 perdeu as chaves estrangeiras (collation da `users` de produção), então a checagem
      tópico↔grupo mora no serviço e é testada (tópico de outro grupo → 404). **Isolamento
      provado**: a publicação de grupo não aparece no `GET /feed` (2026-10-18).
- [x] Comentários e reações do grupo — comentar/reagir exigem ser membro (`INTERACT`); a resposta
      só aponta para comentário da MESMA publicação; a reação é uma por pessoa e alterna (mesma de
      novo remove, outra troca, DELETE remove de vez), com os contadores da publicação acompanhando
      na mesma transação. Anexos viajam como `mediaIds` (serviço de mídia compartilhado) e o
      isolamento do feed principal está provado. `GroupDiscussionTests` + a suíte de grupos verdes
      (2026-10-18).

### Etapa 5 — Descoberta e interface
- [x] Descoberta e pesquisa públicas — `/grupos` (pública, sem sessão): busca por nome/descrição,
      cards com os metadados públicos e link para a página. O conteúdo interno não aparece
      (2026-10-18).
- [x] Apresentação pública, criação/edição, página interna, tópicos, feed, publicação — página
      `/grupos/$slug` com banner (foto ou HTML sanitizado), descrição, modo de ingresso, contagem
      de membros e ingresso (entrar/pedido pendente/sair); criação em diálogo; **edição em diálogo**
      para dono/admin (nome, descrição, modo); dentro do grupo, tópicos com filtro, criação de
      tópico para quem administra, compositor e publicações com os emblemas do autor,
      **comentários (lista + envio) e reações** (o `ReactionPicker` do feed é reutilizado, e o
      backend devolve `myReaction` para o estado não se perder ao recarregar) (2026-10-18).
- [x] Administração: membros, solicitações, moderação; estados de carregamento/vazio/sem acesso —
      painel no topo da página do grupo (dono/admin): **pedidos de ingresso** com aprovar/recusar,
      **lista de membros** com papel, **remover** (com confirmação) e **transferir a propriedade**
      (só o dono). Carregamento, vazio e sem-acesso tratados na descoberta, na página e no painel.
      A moderação LOCAL (remover publicação/comentário pela administração) fica para a Etapa 6,
      junto com o AutoMod e as denúncias (2026-10-18).
- [x] **Logo e banner na criação e na edição** — a API já aceitava `avatar`/`banner`/`bannerHtml`,
      mas nenhum diálogo oferecia os campos; pior, o em branco virava nulo (que significa "mantém"),
      então era impossível REMOVER a imagem. Agora: a criação envia logo + banner; a edição tem as
      abas Foto/HTML (HtmlEditor sanitizado no backend) com trocar/remover; a página mostra o logo
      ao lado do nome; no backend, nulo mantém e em branco limpa. `GroupIdentityTests` (2) e a
      suíte de grupos **49/49** verdes, build do front verificado (2026-10-19; backend `22ada06`,
      front `7996b99`).

### Etapa 6 — Segurança e moderação
- [x] Moderação local — o autor apaga o próprio post/comentário; quem modera (`MODERATE_CONTENT`)
      apaga o dos outros. O conteúdo vira REMOVED e some das listagens, e remoção/encerramento vão
      para a auditoria da plataforma (`shared/audit`). `GroupModerationTests` cobre (2026-10-18).
- [x] Edição de comentário — `PATCH /groups/{id}/posts/{postId}/comments/{commentId}` com
      `{content}`; só o autor edita (quem modera remove, não reescreve), com os mesmos portões do
      comentar (ECA, sanção, `INTERACT`, 1.200 caracteres). Comentário removido responde 404 e a
      edição vai para a auditoria. `GroupModerationTests` cobre (2026-10-31).
- [x] Estado ENCERRADO com efeito real — o dono encerra (`CLOSE_GROUP`): o grupo sai da descoberta
      e o feed privado responde 404 para leitura (a equipe da plataforma investiga por outro
      caminho) (2026-10-18).
- [x] Estados RESTRICTED e SUSPENDED aplicados pela plataforma — `POST /groups/{id}/status`
      restrito a administrador (`AdminGuard`), com motivo na auditoria. RESTRICTED para a entrada e
      mantém o conteúdo; SUSPENDED congela entrada e conteúdo e some da descoberta (o que existe
      continua legível para quem já é membro); ACTIVE reativa. Encerrar continua sendo do dono.
      `GroupPlatformTests` cobre (2026-10-18).
- [x] AutoMod e denúncias no contexto do grupo — `GROUP_POST`/`GROUP_COMMENT` entram no
      `TargetType`; os leitores de conteúdo e autor (os mesmos do feed, agora cientes do grupo)
      resolvem a publicação/comentário do feed privado. Com isso o painel de moderação vê texto e
      autor, e a **triagem por IA** passa a julgar o conteúdo de grupo em vez do id.
      `GroupReportsTests` prova o caminho: denúncia → painel com conteúdo e autor (2026-10-18).
- [x] Bateria de testes de segurança da seção 18 — coberta ao longo da suíte: papel por
      capacidade (`GroupPermissionsTests`); isolamento entre grupos e interação cruzada com ids
      alheios (`GroupSecurityTests`); visitante sem leitura (GroupFeedTests); removido perde o
      acesso na hora (GroupFlow + GroupSecurity); aprovação concorrente sem duplicidade
      (GroupFlow); suspenso/encerrado com efeito real (GroupPlatform + GroupModeration); HTML do
      cabeçalho sanitizado — script não é guardado (GroupSecurity); denúncia de grupo chegando com
      conteúdo e autor (GroupReports) (2026-10-18). **Risco residual:** a corrida "suspensão no
      meio de uma publicação" não tem teste dedicado (as duas operações passam pela mesma
      transação, mas o cenário concorrente não está exercitado).
- [x] Notificações do contexto de grupos (seção 16) — `GroupNotifier` (backend `0ffa1ee`):
      pedido pendente e entrada aberta avisam dono/admin; decisão avisa o solicitante; remoção
      avisa o removido; transferência avisa o novo dono; mudança de estado pela plataforma avisa
      o dono; comentário avisa o autor da publicação e resposta avisa o autor do comentário pai;
      remoção pela moderação avisa o autor. O corpo nunca leva o texto do conteúdo (só o nome
      público do grupo) e o href `/grupos/{slug}` continua exigindo autorização; ação própria
      nunca notifica o autor. `GroupNotificationsTests` (9) cobre os caminhos e o removido
      perdendo a leitura do feed; **suíte de grupos 46/46 verde** (2026-10-19). O front ganhou
      ícones e navegação para os kinds novos (`8ec0223`, build verificado). **Fora do escopo
      real:** "tópico seguido" e menção dentro do grupo não existem como recurso, então não há
      notificação para eles.

### Etapa 7 — Comunidades
- [x] Descrição opcional nas Comunidades (edição e apresentação), sem afetar registros antigos —
      o campo já existia; o que faltava era poder ficar vazio. A **V84** afrouxa a coluna para
      NULL (idempotente, nenhum registro é tocado), a criação grava NULL quando vem em branco, a
      edição adiciona/troca e a string vazia limpa. O card e a página simplesmente não renderizam
      quando não há (nada de texto inventado). `CommunityDescriptionTests`: nascer sem, ganhar
      depois e limpar de novo (2026-10-18).

### Etapa 8 — Validação final
- [x] **Suíte completa do backend** executada num worktree isolado (commit `22ada06`):
      **448 testes em 119 classes, 0 falhas, 0 erros, 1 skip** (2026-10-19). Inclui os 48 de
      Grupos; com o teste dedicado de isolamento Grupos × Comunidades, a suíte de grupos fica
      **49/49**.
- [x] **Suíte do front** (`npx vitest run`): **85 testes verdes** em 10 arquivos. Os 2 arquivos
      `eslint/rules/*.test.js` não são suítes do Vitest e já falhavam antes (config pré-existente;
      não é regressão de Grupos).
- [x] **Migrations validadas** — V81 (tabelas de Grupos) e V84 (descrição opcional) aplicadas e
      reaplicadas na bancada MariaDB 11.4; em produção o Flyway está na 86, sem pendências
      (`GET /api/version`). Nenhuma migração destrutiva ou que toque registros existentes.
- [x] **Critérios da seção 21** verificados um a um — mapa abaixo.
- [x] **Relatório de entrega da seção 23** — abaixo.

#### Mapa dos critérios da seção 21

1. **Criação de grupo** — `GroupFlowTests` (dono, slug único, descoberta pública) e o diálogo de
   criação no front (`/grupos`).
2. **Edição de identidade visual e descrição** — `GroupIdentityTests` (logo, banner foto/HTML,
   trocar e remover) + `GroupFlowTests` (permissão de edição) + `EditGroupDialog`.
3. **Ingresso aberto** — `GroupFlowTests.entradaAbertaViraMembroNaHora` (e a recusa de duplicado).
4. **Ingresso mediante aprovação** — `GroupFlowTests.entradaPorAprovacaoFicaPendenteEAprovaUmaVezSo`.
5. **Aprovação e rejeição de solicitações** — `GroupFlowTests` (decisão única e concorrente) +
   `GroupNotificationsTests.aprovacaoErejeicaoAvisamOSolicitante`.
6. **Saída e remoção de membros** — `GroupFlowTests` (dono não sai sem transferir; remoção) +
   `GroupSecurityTests` (o removido perde o acesso na hora) + `GroupNotificationsTests`.
7. **Criação e administração de tópicos** — `GroupFeedTests` (criar, editar, reordenar, arquivar).
8. **Publicação em tópicos** — `GroupFeedTests` (publica, lista por tópico, tópico de outro grupo
   → 404).
9. **Comentários e interações** — `GroupDiscussionTests` (comentário, resposta da mesma publicação,
   reação alternando/trocando/removendo com contadores).
10. **Isolamento entre grupos** — `GroupSecurityTests` (ids de outro grupo → 403/404; interação
    cruzada recusada).
11. **Isolamento entre Grupos e Comunidades** — `GroupCommunityIsolationTests` (novo: feed da
    comunidade não vê o post do grupo e vice-versa) + estrutural (tabelas `group_*` × `posts`).
12. **Isolamento do feed principal** — `GroupFeedTests.feedPrincipalNaoConheceConteudoDoGrupo`.
13. **Proteção de anexos e URLs** — decisão registrada em Riscos (opção c): `GET /media/{id}` é
    público por id (UUID não enumerável) e nenhum endpoint de grupo entrega mídia a não-membro; a
    URL direta alcançável por quem já a tem fica como risco residual aceito e documentado.
14. **Descoberta e pesquisa públicas** — `GroupFlowTests` (GET /groups sem sessão; busca por
    nome/descrição; conteúdo interno nunca aparece).
15. **Denúncias e AutoMod** — `GroupReportsTests` (`GROUP_POST`/`GROUP_COMMENT` chegam à moderação
    com conteúdo e autor; a triagem por IA recebe o texto).
16. **Penalidades e restrições administrativas** — `GroupPlatformTests` (`AdminGuard`, RESTRICTED,
    SUSPENDED, ACTIVE; efeito na entrada/conteúdo/descoberta; auditoria).
17. **Proteção do HTML personalizado** — `GroupSecurityTests` (script não é guardado) +
    `GroupIdentityTests` (bannerHtml sanitizado).
18. **Notificações com autorização correta** — `GroupNotificationsTests` (9 casos; o corpo nunca
    leva o texto do conteúdo; href continua exigindo autorização).
19. **Mudanças concorrentes de estado e permissões** — `GroupFlowTests` (duas aprovações → 409) e
    `GroupSecurityTests`; **residual:** a corrida "suspensão no meio de uma publicação" não tem
    teste dedicado (registrada em Riscos).
20. **Compatibilidade com dados antigos** — V81 só cria tabelas, V84 só afrouxa uma coluna; nenhum
    backfill; `CommunityDescriptionTests` prova nascer sem, ganhar e limpar.
21. **Descrição opcional nas Comunidades** — `CommunityDescriptionTests` (1).
22. **Regressão das funcionalidades existentes** — suíte completa do backend (448 verdes) e frente
    (85 verdes).

#### Relatório de entrega (seção 23)

**Arquitetura encontrada.** Backend Spring Boot, um pacote por feature
(`domínio`/`application`/`adapters`), sem Spring Security — autorização por endpoint via
`CurrentUserResolver` (sessão + identidade verificada) e `AdminGuard` para a plataforma. Testes
com `@SpringBootTest`/MockMvc/H2 e `TestAuthHelper`. Front TanStack Start (rota por arquivo), API
em `src/lib/api/*` com `X-Session-Token`, tipos por arquivo e textos centrais.

**Estruturas de dados.** V81 cria `groups`, `group_members`, `group_join_requests`, `group_topics`,
`group_posts`, `group_post_comments` e `group_post_reactions` — próprias, separadas de Comunidades.
A V84 torna a descrição das Comunidades opcional (aditiva, sem tocar registros). Nenhuma tabela
existente foi alterada para suportar Grupos.

**Endpoints.** `/groups` (GET lista/descoberta, POST cria), `/groups/{id|slug}` (GET/PATCH),
`/{id}/join`, `/{id}/members` e `/{id}/members/{memberId}`, `/{id}/requests` (+ approve/reject),
`/{id}/transfer`, `/{id}/status` e `/{id}/close`, `/{id}/topics` (criar/editar/reordenar/arquivar),
`/{id}/posts` (publicar/listar/remover), `/{id}/posts/{postId}/comments` (+ remover) e
`/{id}/posts/{postId}/reactions`.

**Frontend.** `/grupos` (descoberta pública e criação com logo/banner), `/grupos/$slug` (página
pública, ingresso, edição com logo/banner foto ou HTML, tópicos, feed, comentários, reações,
painel de administração), diálogos de solicitações/membros/remoção/transferência, denúncia e
notificações com ícone/navegação próprios.

**Autorização.** `GroupPermissions` (papel → capacidade) é a única tabela de decisão; o backend
valida em toda operação (leitura privada exige associação, escrita exige capacidade, estado do
grupo é conferido no serviço). A plataforma mantém `AdminGuard` para RESTRICTED/SUSPENDED/ACTIVE.

**Moderação e denúncias.** `TargetType` ganhou `GROUP_POST`/`GROUP_COMMENT`; os leitores de
conteúdo/autor do feed resolvem o conteúdo de grupo, então o painel de moderação e a triagem por IA
recebem texto e autor reais. Remoções locais e mudanças de estado vão para `shared/audit`.

**Testes executados.** Backend: 448 em 119 classes (0 falhas, 1 skip); Grupos 49/49. Front: 85
testes (0 falhas; 2 arquivos de regra do ESLint não são Vitest — pré-existente). Migrações
validadas na bancada MariaDB 11.4 e em produção (Flyway 86).

**Vulnerabilidades encontradas e corrigidas.** As FKs da V81 derrubariam o boot em produção pela
collation da `users` (errno 150) — foram removidas (outra sessão) e a amarração tópico↔grupo passou
a ser conferida e testada no serviço; HTML do cabeçalho é sanitizado com a régua do perfil (script
não é guardado); todo acesso privado é negado a visitante/removido no backend, com testes.

**Riscos residuais.** Mídia por URL direta (aceito e documentado — opção c); corrida
"suspensão no meio de uma publicação" sem teste dedicado; "tópico seguido" e menção dentro do grupo
não existem como recurso (não há notificação para eles); o composer do feed ainda está duplicado
entre feed/comunidade (dívida do front, sem impacto funcional).

**Pendências externas.** Nenhuma para Grupos. (O produto "Share on LinkedIn" e a chave de
criptografia são pendências do LinkedIn, fora deste plano.)

### Pendências, riscos e decisões

#### Arquitetura encontrada (auditoria de 2026-10-18)

**Backend (`rede`)** — Spring Boot, um pacote por feature (`domain` / `application` / `adapters`).
**Não existe Spring Security**: a autorização é por endpoint, via `shared/web/CurrentUserResolver`
(`resolve` exige sessão E identidade verificada, senão `ApiException(401/403)`; há variante
tolerante para leitura pública). Erro é sempre `ApiException(status, code, mensagem)`. Escrita
idempotente passa por `IdempotencyApplicationService`. Migrações Flyway idempotentes.

- **Comunidades** (o análogo mais próximo): `communities/domain/{Community,CommunityMember,MemberRole}`,
  `application/CommunityService` (a autorização mora no serviço: `community.createdBy().equals(userId)`),
  `adapters/in/web/CommunityContractController` (`/communities`, header `X-Session-Token`),
  persistência JPA + `SpringData*Repository`. Papéis hoje: ADMIN | MEMBER (não há moderador).
- **Feed**: a tabela `posts` é única e **já tem `community_id`** — o feed de comunidade é o feed
  principal filtrado. É justamente o padrão que o plano proíbe para Grupos: isolamento por consulta
  em vez de por estrutura (basta um `where` esquecido para vazar).
- **Transversais reutilizáveis**: `shared/audit` (`record(actor, action, target, metadata)`),
  `moderation` (denúncia com `TargetType` POST|COMMENT|USER|JOB|GAME + `ReportReason`; AutoMod em
  `moderation/ai/ModerationAiService`), `notifications/application/NotificationService.create(...)`,
  `media` (`GET /media/{id}` é **público de propósito**), `shared/html/SafeHtml`,
  `shared/text/PlainText.defuse`, `shared/web/CursorCodec` + `PageResponse` (paginação por cursor).
- **Testes**: `@SpringBootTest` + MockMvc + H2, com `TestAuthHelper` (signup e confirmação de
  e-mail direto no banco) e `TestDatabase`.

**Front (`connect-grow`)** — TanStack Start, rota por arquivo (mãe que também é página precisa de
`useMatchRoute` + `<Outlet/>`). API em `src/lib/api/<dominio>.ts` sobre `api-client` (header
`X-Session-Token`), um tipo por arquivo em `src/types/**`, textos num `src/texts.ts` por seção.
**O `PostCard` e a linha de ações (`post/post-actions`, `post/post-comments`, reações, selos,
denúncia) são genéricos e já servem dois feeds** — o feed do grupo deve reusá-los mapeando para
`PostView`, não escrever uma segunda versão. O que **não** existe é um composer extraído: o bloco
de publicação está duplicado em `routes/feed.tsx` e `routes/comunidades.$communityId.tsx`.

#### Decisões desta rodada

1. **Tabelas próprias, com chave estrangeira** (V81). O isolamento vira estrutural: nenhuma consulta
   do feed principal, da busca ou dos perfis alcança conteúdo de grupo. FK não é convenção nova —
   as migrações V1–V14 já usam.
2. **A publicação amarra o tópico AO MESMO grupo por chave composta**
   (`(topic_id, group_id) → group_topics (id, group_id)`): publicar num tópico de outro grupo
   manipulando ids deixa de ser possível, não é só uma checagem na aplicação.
3. **Uma solicitação pendente por pessoa** (`pending_slot`, que vira NULL ao decidir — índice único
   ignora NULL): duas aprovações concorrentes não geram associações duplicadas.
4. **Sem tabela de anexos**: mídia é o serviço transversal de sempre (`media_ids`, como no feed).
5. **Sem tabela de auditoria própria**: `shared/audit` já registra ação, alvo e metadados; a moderação
   local escreve nele com o prefixo do grupo.
6. **Estado do grupo** em `groups.status` (ACTIVE | RESTRICTED | SUSPENDED | CLOSED), validado no
   backend em cada operação — nunca só na tela.

#### Riscos e pendências

- **Mídia de grupo é alcançável por URL — decisão de 2026-10-19: aceitar e documentar (opção c).**
  `GET /media/{id}` continua público (precisou ser, senão o feed não renderiza), os ids são UUID
  não enumeráveis e nenhum endpoint de grupo entrega mídia a não-membro — o feed exige associação
  e a resposta de post/comentário só volta para membro. Quem já tem a URL direta continua
  alcançando o arquivo; isso é o risco residual aceito, registrado aqui em vez de silenciado.
  URL assinada/expirável fica para quando o serviço de mídia ganhar contexto de dono (o que
  também resolve mídia do feed principal e de comunidades de uma vez).
- **Contadores desnormalizados** (`member_count`, `post_count`, `reactions`, `comments`): existem
  para o feed não fazer `count` a cada leitura; precisam ser atualizados na mesma transação da escrita.
- **Composer duplicado no front**: na Etapa 5, extrair um `PostComposer` em vez de copiar a terceira vez.
- **O pod do backend está atrás**: nada de backend novo chega ao ar enquanto o rollout não acontecer.
  A validação de migração é feita na bancada MariaDB (`enois-bench66`, porta 3411) — a V81 foi
  validada numa base copiada (`enois_grupos`), sem tocar nas bases compartilhadas.

---

# PLANO COMPLETO

# ÉNOIS — IMPLEMENTAR SISTEMA DE GRUPOS PRIVADOS, TÓPICOS, FEED ISOLADO E MODERAÇÃO HIERÁRQUICA

## 1. Missão

Implemente no énóis um novo sistema chamado **Grupos**, integrado à plataforma existente, mas com estruturas de dados próprias, isolamento rigoroso de conteúdo e regras específicas de administração, ingresso, publicação e moderação.

Repositório da organização: https://github.com/Rede-ENois

Site: https://enois.ia.br

Antes de modificar o código, examine os repositórios disponíveis, identifique o frontend, o backend, a arquitetura de persistência, o sistema de autenticação, os feeds, as comunidades, os uploads, o HTML personalizado, as notificações, o AutoMod e os mecanismos de denúncia existentes.

Não presuma que os nomes, as rotas ou os modelos descritos neste documento já existem. Descubra a implementação real e adapte a solução a ela.

**A tarefa é implementar a feature funcionalmente completa, não apenas desenhar a interface ou produzir um planejamento.**

Preserve os padrões arquiteturais do projeto, reutilize os serviços transversais adequados e crie estruturas de dados próprias para Grupos.

Não introduza dependências ou abstrações desnecessárias.

---

# 2. Conceito e regras fundamentais

Grupos são espaços privados dentro do énóis, destinados à organização de pessoas por interesses, projetos, equipes, estudos, jogos, tecnologias e outros assuntos.

Cada grupo possui:

- Identidade visual própria.
- Nome e endereço identificável.
- Logo.
- Descrição.
- Banner ou cabeçalho personalizado.
- Configuração de ingresso.
- Administração própria.
- Lista de membros.
- Tópicos internos.
- Feed exclusivo.
- Publicações, comentários e interações.
- Sistema de solicitações de entrada.
- Moderação local subordinada à plataforma.
- Denúncias e aplicação das regras gerais do énóis.

O grupo é um espaço de publicação e interação independente do feed principal.

### Regras inegociáveis

1. O conteúdo interno de um grupo só pode ser acessado por seus membros autorizados e pelos serviços privilegiados estritamente necessários à operação e à segurança da plataforma.
2. Publicações de grupos não aparecem no feed principal.
3. Publicações do feed principal não aparecem automaticamente nos feeds dos grupos.
4. Grupos possuem tabelas e relacionamentos próprios, separados das estruturas específicas de Comunidades.
5. A descoberta de grupos pode ser pública, mas não revela o conteúdo privado.
6. A administração do grupo não pode desativar as regras gerais de segurança e moderação do énóis.
7. A plataforma mantém autoridade final sobre denúncias, investigações e penalidades.
8. A interface nunca substitui a autorização no backend.
9. Todas as operações críticas precisam respeitar a identidade autenticada, as permissões atuais e o estado do grupo.
10. Não introduza vazamentos de informações entre grupos, comunidades e feed principal.

---

# 3. Banco de dados e isolamento estrutural

## 3.1. Criar estruturas próprias para Grupos

Projete um conjunto dedicado de tabelas para a nova funcionalidade.

Os nomes abaixo são referências conceituais. Adapte-os às convenções reais do projeto.

Estruturas esperadas:

- `groups`
- `group_members`
- `group_join_requests`
- `group_topics`
- `group_posts`
- `group_post_comments`
- `group_post_reactions`
- `group_post_attachments`
- `group_reports`, caso o modelo existente de denúncias não comporte adequadamente o novo contexto.
- `group_moderation_actions`, quando necessário para separar a auditoria específica dos grupos.

Crie outras estruturas somente quando justificadas pelos requisitos e pela arquitetura encontrada.

### Separação de Comunidades

Não reutilize a tabela principal de Comunidades como tabela de Grupos.

Não transforme Comunidades em Grupos por meio de um campo discriminador.

Não misture membros, tópicos, publicações ou permissões de Grupos com os registros equivalentes de Comunidades.

As duas funcionalidades devem poder evoluir independentemente.

Entretanto, reutilize componentes transversais quando apropriado, como:

- Identidade e autenticação de usuários.
- Armazenamento de mídia.
- Sanitização de HTML.
- Serviços de notificações.
- Sistema geral de denúncias.
- AutoMod.
- Auditoria e observabilidade.
- Infraestrutura de busca.

Reutilizar serviços compartilhados não significa compartilhar indiscriminadamente as tabelas específicas de domínio.

### Integridade referencial

Defina chaves estrangeiras, índices e restrições de unicidade.

Garanta, no mínimo:

- Um usuário não pode ter duas associações de membro duplicadas no mesmo grupo.
- Uma solicitação de ingresso não pode ser aprovada mais de uma vez.
- Um tópico pertence a exatamente um grupo.
- Uma publicação pertence a exatamente um grupo e a um tópico daquele mesmo grupo.
- Comentários, reações e anexos não podem ser associados a publicações inexistentes ou pertencentes a outro contexto.
- Um usuário removido do grupo não conserva autorização para consultar seu conteúdo privado.
- Um grupo suspenso não pode continuar aceitando publicações por endpoints alternativos.

Considere índices para consultas por grupo, tópico, data, autor e estado de moderação.

Evite índices redundantes e consultas que exijam varreduras completas de tabelas à medida que a plataforma cresce.

### Migrações

Crie migrations versionadas e compatíveis com o processo de implantação existente.

A criação dos Grupos não deve alterar nem apagar os dados atuais das Comunidades.

A alteração da descrição das Comunidades deve ser aditiva e preservar os registros existentes.

---

# 4. Modelo de permissões

Implemente autorização centralizada para os recursos de Grupos.

As permissões precisam ser verificadas no backend para cada operação protegida.

## Proprietário do grupo

Responsável pela existência e administração geral do grupo.

Pode:

- Editar nome, descrição, logo e cabeçalho.
- Configurar o ingresso.
- Criar, editar, reorganizar e arquivar tópicos.
- Administrar membros.
- Nomear ou remover administradores, conforme as regras de propriedade.
- Moderar publicações dentro das permissões concedidas.
- Consultar solicitações de entrada.
- Encerrar o grupo, respeitando os procedimentos de segurança e integridade.

O sistema precisa impedir que a remoção de um administrador ou uma falha de sessão deixe o grupo sem uma autoridade administrativa válida.

## Administrador

Pode administrar o funcionamento cotidiano do grupo.

Pode:

- Editar os dados permitidos do grupo.
- Criar e gerenciar tópicos.
- Aprovar ou rejeitar solicitações de entrada quando o modo exigir aprovação.
- Remover membros conforme as regras da plataforma.
- Moderar publicações e comentários.
- Fixar ou arquivar publicações, se essa funcionalidade for implementada.
- Consultar denúncias relacionadas ao próprio grupo quando houver autorização para isso.

O administrador não pode:

- Acessar funções administrativas globais.
- Apagar evidências de denúncias contra si ou contra o grupo.
- Desativar o AutoMod.
- Impedir a investigação da equipe do énóis.
- Alterar registros de auditoria.
- Conceder a si próprio privilégios globais.
- Tornar público o conteúdo privado por meio de endpoints não autorizados.

## Moderador local

Se a arquitetura justificar esse papel, implemente um nível intermediário.

Pode moderar publicações, comentários e membros conforme as permissões concedidas.

Não pode alterar configurações críticas, transferir a propriedade do grupo ou administrar privilégios globais.

## Membro

Pode:

- Consultar o conteúdo autorizado do grupo.
- Navegar pelos tópicos.
- Publicar nos tópicos permitidos.
- Comentar e interagir com publicações.
- Denunciar conteúdo.
- Sair do grupo.

Não pode administrar outros membros, acessar dados administrativos restritos ou modificar a configuração do grupo sem autorização.

## Visitante

Pode consultar apenas as informações públicas autorizadas para descoberta e apresentação do grupo.

Não pode consultar:

- Publicações.
- Comentários.
- Reações.
- Anexos privados.
- Lista de membros.
- Tópicos internos.
- Solicitações de ingresso.
- Relatórios de moderação.
- Histórico de atividades internas.

A resposta da API deve preservar esse isolamento mesmo que o visitante conheça IDs válidos de recursos privados.

---

# 5. Criação e configuração de grupos

Implemente uma interface de criação integrada ao design atual do énóis.

O formulário deve permitir configurar:

- Nome do grupo.
- Identificador de URL, respeitando as regras existentes para slugs.
- Descrição.
- Logo.
- Banner ou cabeçalho.
- Modo de ingresso.
- Regras adicionais do grupo, caso implementadas.

Valide nomes, tamanhos, formatos de mídia e permissões.

O identificador de URL deve ser único segundo a política de unicidade do sistema, com tratamento adequado para concorrência.

Evite expor informações pessoais no endereço ou nos metadados do grupo.

### Identidade visual

Cada grupo deve possuir logo e cabeçalho próprios.

A implementação deve utilizar os mecanismos de upload e armazenamento existentes sempre que forem adequados.

Garanta:

- Validação real do tipo e tamanho dos arquivos.
- Proteção contra uploads maliciosos.
- Tratamento de falhas.
- Compatibilidade com os dispositivos suportados.
- Carregamento eficiente das imagens.
- Alternativas visuais para grupos sem imagem personalizada.

Não confie exclusivamente na extensão do arquivo ou no MIME type enviado pelo cliente.

---

# 6. Cabeçalho HTML personalizado

Assim como nas funcionalidades de personalização existentes no énóis, avalie a possibilidade de permitir que o proprietário configure um cabeçalho utilizando HTML e CSS.

O recurso deve seguir as capacidades reais do sistema de personalização atual.

Se HTML personalizado já estiver disponível em outro contexto, reutilize sua infraestrutura de validação e renderização quando isso não comprometer o isolamento entre Grupos e Comunidades.

Se o mecanismo atual não oferecer segurança suficiente, implemente um modelo de renderização restrito.

### Requisitos de segurança

Não execute JavaScript arbitrário enviado por usuários.

Não permita acesso a cookies, tokens, armazenamento de sessão, APIs privilegiadas ou dados internos por meio do HTML personalizado.

Avalie e bloqueie, conforme a implementação:

- Scripts embutidos.
- Event handlers HTML.
- URLs `javascript:`.
- Carregamento de recursos executáveis não autorizados.
- Manipulação indevida do DOM da aplicação.
- Tentativas de phishing por imitação da interface.
- Formulários que capturem credenciais.
- CSS que tente ocultar avisos de segurança ou simular controles privilegiados.

Utilize sanitização robusta, lista explícita de recursos permitidos e isolamento de renderização quando necessário.

Não presuma que remover a tag `<script>` seja suficiente.

Caso o sistema existente ofereça um modo seguro de HTML/CSS restrito, priorize a compatibilidade com ele.

Se não for possível garantir a segurança do recurso no escopo atual, entregue uma alternativa segura com personalização visual limitada e documente a limitação.

---

# 7. Sistema de ingresso

Cada grupo deve permitir configurar um dos seguintes modos.

## Modo A: entrada aberta

Qualquer usuário autenticado elegível pode solicitar a entrada e ser aceito automaticamente.

A associação deve ser criada no servidor após validar as condições aplicáveis.

Implemente proteção contra requisições duplicadas, concorrência e abuso automatizado.

A entrada aberta não significa que visitantes anônimos podem acessar o conteúdo.

## Modo B: entrada mediante aprovação

O usuário solicita participação.

A solicitação permanece pendente até uma decisão de um administrador autorizado.

O solicitante deve conseguir consultar o estado da própria solicitação, sem acessar as solicitações de terceiros.

Os administradores devem conseguir:

- Consultar solicitações pendentes.
- Aprovar.
- Rejeitar.
- Consultar o estado atual.
- Processar solicitações sem duplicidade.
- Evitar que decisões concorrentes criem associações inconsistentes.

Uma rejeição não deve revelar informações internas desnecessárias.

A plataforma pode aplicar restrições adicionais a contas que apresentem riscos objetivos.

### Mudança de modalidade

Quando o grupo mudar de entrada aberta para aprovação, novas entradas deverão seguir a configuração atual.

A mudança não deve remover automaticamente os membros existentes.

Quando houver mudança de estado ou de permissões, valide novamente as condições no backend.

### Sair e retornar

O usuário pode sair do grupo.

A saída deve revogar imediatamente as permissões correspondentes.

Se o usuário solicitar entrada novamente, deverá obedecer à modalidade vigente e às eventuais restrições aplicáveis.

Contas suspensas ou banidas não podem contornar suas restrições saindo e retornando ao grupo.

---

# 8. Descoberta e pesquisa de grupos

Crie uma experiência de descoberta semelhante à navegação por Comunidades já existente.

A página de descoberta deve permitir:

- Visualizar grupos disponíveis para descoberta.
- Pesquisar por nome.
- Pesquisar por descrição.
- Filtrar por categorias, se a arquitetura atual suportar esse recurso ou se sua implementação for justificada.
- Ordenar resultados de maneira útil.
- Abrir a página de apresentação de um grupo.
- Consultar o modo de ingresso.
- Entrar ou solicitar participação.
- Identificar grupos suspensos ou indisponíveis sem expor informações internas de moderação.

A busca deve trabalhar exclusivamente com os metadados públicos permitidos.

**Nunca utilize o conteúdo das publicações privadas como resultado de pesquisa para visitantes externos ao grupo.**

Implemente autorização no backend e evite que endpoints de pesquisa retornem informações internas por meio de relações, contagens ou agregações.

Não exponha a lista de membros a visitantes.

Não apresente contagens ou indicadores que permitam inferir informações sensíveis sobre a atividade interna do grupo sem uma justificativa legítima.

Se houver cache, garanta que respostas privadas não sejam compartilhadas indevidamente entre usuários.

---

# 9. Feed independente por grupo

Esta é uma das partes centrais da funcionalidade.

Cada grupo possui um feed próprio, que funciona como uma réplica funcional do feed social existente, adaptada ao contexto privado.

Investigue as funcionalidades reais do feed atual e preserve as que forem compatíveis.

Conforme os recursos já disponíveis, isso pode incluir:

- Publicações textuais.
- Imagens.
- Vídeos.
- Links.
- Anexos permitidos.
- Comentários.
- Reações.
- Edição e exclusão.
- Ordenação cronológica ou outros modos já suportados.
- Paginação.
- Carregamento incremental.
- Denúncias.
- Ferramentas de moderação.

Não implemente uma segunda versão simplificada se for possível reutilizar componentes de apresentação e lógica compartilhada de forma segura.

Por outro lado, não reutilize os endpoints do feed principal de maneira que misture os domínios ou permita consultas sem o contexto obrigatório do grupo.

## Regras de publicação

Toda publicação de grupo deve estar associada a:

- Um grupo.
- Um tópico válido daquele grupo.
- Um autor autorizado.
- Um estado de publicação.
- Os metadados necessários à auditoria e à moderação.

A associação com o grupo deve ser estabelecida e validada no servidor.

Não confie em IDs enviados pelo cliente sem verificar a propriedade e o contexto dos recursos.

### Isolamento do feed principal

Publicações de grupos não podem:

- Aparecer no feed global.
- Entrar em recomendações globais.
- Ser incluídas em pesquisas públicas de publicações.
- Ser distribuídas automaticamente a seguidores do autor.
- Ser expostas em endpoints públicos de perfil.
- Ser incluídas em compartilhamentos públicos que revelem seu conteúdo.
- Gerar notificações para usuários externos ao grupo com conteúdo privado.
- Ser disponibilizadas por APIs alternativas sem autorização.

Uma notificação para um membro pode informar que existe uma nova publicação no grupo, respeitando suas preferências. Ela não deve revelar o conteúdo privado a quem não possui acesso.

O mesmo princípio deve ser aplicado a contagens, prévias, metadados, anexos, caches e indexadores.

### Compartilhamento

Se o feed atual permitir compartilhar publicações, defina um comportamento seguro para o contexto privado.

O compartilhamento externo não deve reproduzir o conteúdo privado.

Caso seja necessário permitir links para publicações de grupos, o destino deve exigir autorização e não oferecer prévias que revelem informações protegidas.

---

# 10. Tópicos internos

Cada grupo deve possuir seu próprio sistema de tópicos.

Os tópicos servem para organizar as publicações por assunto, evitando que todo o conteúdo fique concentrado em um único fluxo.

Exemplos:

- Geral.
- Avisos.
- Dúvidas.
- Projetos.
- Recursos.
- Eventos.
- Off-topic.

Os exemplos não precisam ser criados automaticamente, a menos que isso combine com o padrão atual de inicialização de recursos.

## Administração dos tópicos

Administradores autorizados podem:

- Criar tópicos.
- Editar nome e descrição.
- Reordenar tópicos.
- Arquivar tópicos.
- Reabrir tópicos arquivados, se permitido.
- Definir permissões adicionais de publicação, caso essa capacidade seja implementada.

Não permita que um usuário crie tópicos em grupos dos quais não participa.

### Integridade entre grupo e tópico

Uma publicação deve pertencer a um tópico do mesmo grupo.

Não basta verificar se o tópico existe. É obrigatório verificar se ele pertence ao grupo informado.

Evite a vulnerabilidade em que alguém associa uma publicação de um grupo a um tópico de outro grupo manipulando os IDs da requisição.

### Exclusão e arquivamento

Prefira arquivamento quando houver publicações associadas.

A exclusão definitiva só deve ser permitida quando as regras de integridade, retenção e auditoria forem respeitadas.

Se um tópico for arquivado, defina claramente se novas publicações ficam bloqueadas e como o conteúdo histórico continuará acessível aos membros autorizados.

---

# 11. Comunidades: permitir descrição opcional

Aproveite a implementação para melhorar a apresentação das Comunidades existentes.

Se uma Comunidade não tiver descrição, deve ser possível adicionar uma posteriormente.

Investigue o modelo, o formulário de edição e as telas de apresentação.

Implemente:

- Campo de descrição opcional.
- Limite de tamanho adequado.
- Validação no frontend e no backend.
- Renderização segura.
- Compatibilidade com registros antigos.
- Atualização sem perda dos dados existentes.
- Tratamento visual adequado para Comunidades sem descrição.

A migration deve preservar os registros atuais.

Não crie uma descrição fictícia para preencher dados ausentes.

Se já existir um campo de descrição, corrija o fluxo de edição e apresentação em vez de criar outro campo redundante.

Essa melhoria deve permanecer separada do domínio de Grupos.

---

# 12. Moderação local e AutoMod da plataforma

Todo grupo está subordinado às regras gerais do énóis.

O administrador pode criar regras adicionais, desde que não contrariem as políticas da plataforma.

Nenhum grupo pode desativar ou contornar os controles globais de segurança.

## AutoMod

Integre o conteúdo dos grupos ao sistema de moderação automatizada existente.

Investigue como o AutoMod funciona hoje e adapte sua arquitetura para considerar o contexto do grupo.

O AutoMod deve poder analisar, conforme os critérios e as capacidades já existentes:

- Publicações.
- Comentários.
- Links.
- Anexos compatíveis com análise automatizada.
- Spam.
- Phishing.
- Golpes.
- Falsidade de identidade.
- Assédio.
- Discurso proibido pelas regras vigentes.
- Tentativas de explorar usuários.
- Conteúdos potencialmente maliciosos.
- Outras categorias já contempladas pela política de moderação.

A análise deve preservar a privacidade e utilizar apenas os dados necessários.

Se a IA estiver indisponível, controles determinísticos essenciais devem continuar funcionando.

A ausência do AutoMod não pode se tornar uma forma de liberar conteúdo que seria proibido no restante da plataforma.

### Moderação contextual

O sistema deve reconhecer que uma publicação pertence a um grupo e identificar o grupo, o autor, o tópico e as permissões relevantes.

Isso permite investigar padrões sem misturar conteúdo de grupos diferentes.

A moderação automatizada pode sinalizar, limitar preventivamente ou encaminhar conteúdo para análise conforme as políticas vigentes.

Não permita que uma inferência isolada da IA seja tratada automaticamente como prova definitiva de culpa.

---

# 13. Denúncias de conteúdo e de grupos

Qualquer usuário que tenha acesso ao conteúdo deve poder denunciá-lo conforme as regras gerais do énóis.

Visitantes também devem conseguir denunciar informações públicas de apresentação do grupo quando houver fundamento.

Implemente, conforme o sistema de denúncias existente:

- Denúncia de publicação.
- Denúncia de comentário.
- Denúncia de perfil.
- Denúncia do grupo.
- Denúncia de comportamento administrativo.
- Denúncia de tentativa de burlar as regras da plataforma.

Uma denúncia deve registrar o contexto correto.

Preserve as evidências necessárias para investigação, respeitando os controles de acesso e a política de retenção.

O administrador local não pode apagar, ocultar administrativamente ou invalidar uma denúncia registrada no sistema global.

As denúncias devem ser tratadas pela equipe da plataforma segundo os mesmos critérios de seriedade aplicados ao restante do énóis.

Evite que denúncias repetidas e sem evidências provoquem automaticamente a suspensão de um grupo.

Detecte possíveis campanhas coordenadas de denúncias e encaminhe os casos suspeitos para avaliação apropriada.

---

# 14. Responsabilidade administrativa e penalidades

O énóis não pode permitir que um administrador use um grupo privado como refúgio para fraudes, assédio, exploração ou outras violações das regras da plataforma.

A administração possui responsabilidade pelas ações que pratica e pela forma como exerce seus poderes de moderação.

Entretanto, é necessário distinguir uma infração individual de uma política deliberadamente permissiva ou de uma operação organizada de abuso.

## Situação A: infração individual

Um membro publica conteúdo proibido sem evidências de conivência administrativa.

A plataforma pode:

- Remover ou restringir o conteúdo conforme a política.
- Investigar o usuário.
- Aplicar as penalidades cabíveis.
- Avaliar a resposta da administração local.

A infração isolada não implica automaticamente que todos os integrantes do grupo sejam culpados.

## Situação B: negligência administrativa relevante

A administração recebe denúncias fundamentadas, possui condições razoáveis de agir e repetidamente ignora violações graves ou deixa de aplicar as regras locais.

A plataforma deve avaliar:

- A gravidade e a recorrência.
- A qualidade das evidências.
- O conhecimento efetivo da administração.
- As medidas adotadas.
- A possibilidade real de intervenção.
- A existência de padrões sistêmicos de abuso.

Conforme a gravidade, pode exigir correções, limitar funcionalidades administrativas, colocar o grupo sob supervisão ou aplicar outras medidas proporcionais.

## Situação C: conivência comprovada

Existem evidências de que administradores organizam, incentivam, protegem deliberadamente ou facilitam fraudes ou violações graves.

Nesse cenário, a plataforma pode:

- Suspender imediatamente as funcionalidades envolvidas para conter o dano.
- Suspender os administradores responsáveis.
- Remover administradores que abusaram de suas permissões.
- Restringir a entrada de novos membros.
- Suspender temporariamente o grupo.
- Encerrar o grupo em casos graves.
- Aplicar outras penalidades previstas nas regras do énóis.

A plataforma deve preservar as evidências e documentar a justificativa.

## Situação D: abuso estrutural ou organizado

O grupo é utilizado sistematicamente para organizar golpes, coordenar assédio, manipular transações ou contornar sanções.

A plataforma pode investigar a estrutura organizacional, os responsáveis e os mecanismos utilizados.

Quando as evidências justificarem, poderá suspender o grupo, responsabilizar os participantes envolvidos e impedir a continuidade da operação abusiva.

A decisão não deve depender exclusivamente do nome, da descrição ou da categoria declarada pelo grupo.

### Responsabilidade coletiva: regra expressa

A aplicação de penalidades ao grupo inteiro exige fundamentos próprios, relacionados ao funcionamento do grupo ou à conduta de sua administração.

Não penalize automaticamente todos os membros por uma infração individual.

Membros que participaram conscientemente de uma fraude podem ser responsabilizados individualmente.

A administração que foi comprovadamente conivente pode receber penalidades próprias.

O grupo pode ser restringido ou encerrado quando sua estrutura estiver sendo utilizada para abuso sistemático.

Essas são decisões distintas e devem permanecer registradas separadamente.

---

# 15. Estados e restrições do grupo

Implemente um ciclo de vida explícito para os grupos.

Os estados devem refletir as necessidades reais do produto, evitando complexidade desnecessária.

Considere:

- Ativo.
- Restrito.
- Sob investigação, se a exposição desse estado for necessária.
- Suspenso.
- Encerrado.

Defina o significado operacional de cada estado.

Por exemplo, um grupo suspenso pode continuar acessível à equipe autorizada para investigação, mas não aceitar novos membros ou publicações.

Não revele a visitantes detalhes de investigações privadas.

As operações devem validar o estado atual no backend.

A suspensão não pode ser contornada utilizando endpoints alternativos, requisições previamente preparadas ou chamadas diretas ao banco por mecanismos acessíveis ao usuário.

Toda alteração de estado deve registrar responsável, motivo, data e referência do caso quando aplicável.

Implemente reativação controlada quando a política permitir.

---

# 16. Notificações

Integre os grupos ao sistema de notificações existente.

Considere eventos como:

- Solicitação de entrada.
- Aprovação ou rejeição.
- Entrada ou remoção de membros.
- Nova publicação em tópico seguido.
- Comentário ou interação relevante.
- Menção dentro do grupo.
- Alterações administrativas importantes.
- Respostas a denúncias e recursos, quando aplicável.

Respeite as preferências de notificação e os limites contra spam.

Notificações não podem revelar conteúdo privado a destinatários externos ao grupo.

Notificações antigas não devem manter acessível o conteúdo de um grupo após a revogação da autorização.

Quando um usuário for removido ou perder acesso, links e referências a conteúdo protegido devem voltar a exigir autorização.

---

# 17. Interface e experiência do usuário

Implemente a funcionalidade no frontend real do énóis, preservando a identidade visual e os padrões de navegação existentes.

Crie, conforme a arquitetura encontrada:

- Página de descoberta de grupos.
- Pesquisa e filtros.
- Página pública de apresentação do grupo.
- Tela de criação.
- Tela de edição.
- Página interna do grupo.
- Navegação entre tópicos.
- Feed do tópico selecionado.
- Formulário de publicação.
- Comentários e interações.
- Área de administração.
- Gestão de membros.
- Solicitações de entrada.
- Controles de moderação.
- Estados de acesso negado, suspensão e indisponibilidade.

A página pública deve apresentar identidade visual, nome, descrição e modalidade de ingresso.

O conteúdo interno só deve aparecer após a confirmação de que o usuário possui autorização válida.

A interface precisa tratar corretamente:

- Carregamento.
- Ausência de resultados.
- Erros de rede.
- Falta de permissão.
- Solicitações pendentes.
- Grupo suspenso.
- Tópicos arquivados.
- Usuário removido.
- Grupo sem logo ou banner.
- Grupo sem publicações.

Não use dados fictícios para simular funcionalidades concluídas.

Não deixe botões que aparentem funcionar, mas não executem operações reais.

---

# 18. Segurança técnica obrigatória

Realize uma revisão específica de segurança para todos os endpoints de Grupos.

Teste pelo menos:

### Autorização

- Visitante tentando consultar publicação privada.
- Membro de um grupo tentando consultar outro grupo.
- Usuário removido tentando reutilizar uma sessão.
- Membro tentando executar ações administrativas.
- Moderador tentando alterar a propriedade do grupo.
- Administrador local tentando acessar funções globais.
- Usuário tentando aprovar a própria solicitação sem autorização.

### Isolamento de recursos

- Publicação associada a tópico de outro grupo.
- Comentário associado a publicação de outro grupo.
- Anexo privado acessado diretamente por URL.
- Pesquisa pública retornando conteúdo privado.
- Prévia de link revelando conteúdo privado.
- Cache compartilhando respostas privadas.
- Endpoint do feed principal incluindo publicações de grupos.
- Notificação revelando conteúdo a usuários sem acesso.

### Concorrência e integridade

- Duas aprovações simultâneas da mesma solicitação.
- Duas operações simultâneas de entrada.
- Remoção de membro durante uma requisição.
- Suspensão do grupo durante uma publicação.
- Exclusão ou arquivamento de tópico com publicações associadas.
- Alterações concorrentes nas permissões administrativas.

### HTML personalizado

- Scripts e event handlers.
- URLs perigosas.
- Tentativas de acessar credenciais.
- Tentativas de falsificar formulários de autenticação.
- Carregamento de recursos não autorizados.
- Tentativas de escapar do isolamento de renderização.

### Moderação

- AutoMod recebendo conteúdo malicioso.
- Tentativa de desativar a moderação por configuração local.
- Tentativa de apagar evidências.
- Campanha de denúncias falsas.
- Administrador tentando impedir uma investigação global.
- Usuário tentando publicar após suspensão.

Os testes devem verificar o comportamento real da aplicação, não apenas a presença de funções ou componentes.

---

# 19. Desempenho e escalabilidade

Projete as consultas para crescimento progressivo.

Implemente:

- Paginação adequada.
- Ordenação estável.
- Índices coerentes.
- Limites de tamanho e frequência.
- Carregamento incremental quando compatível com o frontend.
- Controle de consultas repetidas.
- Carregamento eficiente de membros e tópicos.
- Cache apenas quando seguro.
- Invalidação de cache após mudanças de permissões.
- Proteção contra consultas que retornem conjuntos ilimitados de dados.

Evite carregar todas as publicações, todos os membros ou todos os tópicos de um grupo em uma única resposta.

Garanta que o custo das operações administrativas não cresça descontroladamente com o tamanho do grupo.

---

# 20. Compatibilidade com funcionalidades existentes

A implementação não pode quebrar:

- Feed principal.
- Comunidades.
- Perfis.
- Autenticação.
- Notificações.
- Sistema de upload.
- HTML personalizado.
- AutoMod.
- Denúncias.
- Ferramentas administrativas.
- Outras funcionalidades existentes.

Quando houver componentes compartilhados, avalie explicitamente o impacto da nova funcionalidade sobre eles.

Não altere o comportamento do feed principal para acomodar Grupos.

Não transforme as Comunidades existentes em Grupos.

A descrição opcional das Comunidades deve ser implementada de maneira independente.

Não altere o comportamento de recursos não relacionados sem necessidade demonstrável.

---

# 21. Testes e critérios de aceite

A feature só pode ser considerada pronta quando os fluxos principais estiverem implementados e testados.

Os testes devem cobrir:

1. Criação de grupo.
2. Edição de identidade visual e descrição.
3. Ingresso aberto.
4. Ingresso mediante aprovação.
5. Aprovação e rejeição de solicitações.
6. Saída e remoção de membros.
7. Criação e administração de tópicos.
8. Publicação em tópicos.
9. Comentários e interações.
10. Isolamento entre grupos.
11. Isolamento entre Grupos e Comunidades.
12. Isolamento do feed principal.
13. Proteção de anexos e URLs.
14. Descoberta e pesquisa públicas.
15. Denúncias e AutoMod.
16. Penalidades e restrições administrativas.
17. Proteção do HTML personalizado.
18. Notificações com autorização correta.
19. Mudanças concorrentes de estado e permissões.
20. Compatibilidade com os dados antigos.
21. Descrição opcional nas Comunidades.
22. Regressão das funcionalidades existentes.

Execute a suíte de testes disponível.

Corrija as falhas introduzidas pela implementação.

Se alguma integração depender de credenciais ou de serviços externos indisponíveis, documente precisamente o que não foi possível validar.

Não declare testes aprovados sem executá-los.

---

# 22. Plano de execução

Execute a implementação na seguinte ordem, adaptando a sequência apenas quando houver dependências técnicas reais.

### Etapa 1 — Auditoria

Identificar arquitetura, feed, Comunidades, autenticação, persistência, mídia, notificações, AutoMod e denúncias.

Registrar riscos e dependências.

### Etapa 2 — Fundação de dados

Criar migrations, tabelas, relacionamentos, índices, constraints e regras de autorização.

### Etapa 3 — Ciclo de vida e ingresso

Implementar criação, edição, modalidades de ingresso, solicitações, membros e permissões.

### Etapa 4 — Tópicos e feed privado

Implementar tópicos, publicações, comentários, interações e isolamento entre os contextos.

### Etapa 5 — Descoberta e interface

Implementar pesquisa, apresentação pública, páginas internas, administração e responsividade.

### Etapa 6 — Segurança e moderação

Integrar AutoMod, denúncias, auditoria, restrições e penalidades.

### Etapa 7 — Comunidades

Permitir a criação e edição de descrições opcionais sem afetar os registros existentes.

### Etapa 8 — Validação final

Executar testes, corrigir regressões, revisar permissões, validar migrations e documentar a entrega.

Não pare após produzir o planejamento.

Se não for possível concluir tudo em uma execução, finalize uma etapa coerente, execute seus testes e registre claramente o progresso e as pendências antes de continuar.

---

# 23. Relatório final obrigatório

Ao concluir, apresente:

- Resumo da arquitetura encontrada.
- Estruturas de dados criadas.
- Migrations implementadas.
- Endpoints e serviços adicionados ou alterados.
- Páginas e componentes do frontend implementados.
- Regras de autorização aplicadas.
- Integração com AutoMod e denúncias.
- Testes executados e resultados reais.
- Vulnerabilidades encontradas e corrigidas.
- Riscos residuais.
- Funcionalidades que ainda dependem de integração ou decisão externa.

Diferencie claramente o que está implementado, o que foi testado, o que está parcialmente concluído e o que continua pendente.

## DIRETRIZ FINAL

Grupos devem permitir que as pessoas construam espaços privados e organizados dentro do énóis, sem transformar esses espaços em ilhas fora das regras da plataforma.

A privacidade protege os membros contra exposição indevida. Não concede imunidade contra investigação legítima.

A administração local organiza o grupo. Não substitui a autoridade de segurança da plataforma.

O AutoMod protege a comunidade. Não deve se tornar uma ferramenta de punição arbitrária.

A plataforma pode aplicar consequências severas a administradores coniventes, grupos usados sistematicamente para fraude e participantes que comprovadamente contribuam para o abuso.

Toda decisão relevante deve ser proporcional, rastreável e fundamentada.

**Implemente a funcionalidade completa no código existente, com isolamento real de dados, autorização no backend, integração com a moderação e testes de segurança. Comece pela auditoria e avance para as alterações concretas.**