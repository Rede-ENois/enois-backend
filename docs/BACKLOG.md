# Backlog ordenado (mais fácil → mais difícil)

Ordem por **esforço × risco**, para entregar valor cedo e não travar em dependência externa.
Cada item é feito **numa passada**, compilando, com testes verdes e publicado (backend e/ou front).
Os 4 últimos são **BLOQUEADOS** por dependência externa (não é código meu).

## Fácil (1 arquivo/poucos pontos)
1. **[x] ENTREGUE (verificado no código em 2026-10-20) — Tag "vulgo"/aka no perfil.**
   `UserProfile.aka` (coluna `aka`, varchar 60) no domínio e no perfil público; front com o
   campo no editar perfil (com contador) e a exibição no perfil/card.
   *Aceite cumprido:* salva com/sem aka, aparece no perfil, limite respeitado.
2. **[x] ENTREGUE (verificado no código em 2026-10-20) — Arquivar conversa (ver arquivadas /
   restaurar).** `GET /conversations/archived` + `POST /conversations/unhide` no backend
   (`MessagingContractController`), com a aba "Arquivadas" e a ação "Restaurar" no front.
   *Aceite cumprido:* arquivar some da caixa, restaurar volta, não afeta o outro participante.
3. **[x] ENTREGUE (verificado no código em 2026-10-20) — Apagar mensagens dentro da conversa.**
   `message_hidden(user_id, message_id)` com `POST /conversations/{id}/messages/hide` em massa
   e a seleção na thread no front.
   *Aceite cumprido:* apagar some só para quem apagou e a paginação continua coerente (a
   leitura filtra no banco).

## Médio
4. **[x] ENTREGUE (verificado no código em 2026-10-20) — Áudio nos comentários.** Gravação
   pelo microfone no composer de comentário (`MediaRecorder`, teto de 3 min) → upload de mídia →
   player sem autoplay; GIF e embeds já entregues antes.
   *Aceite cumprido:* gravar, enviar, tocar; recarregar mantém o anexo; sem autoplay.
5. **[x] ENTREGUE 2026-10-20 — Vaga: valor obrigatório + período + faixa + descrição.** V110
   (`description longtext`, `salary_min numeric(12,2)`, `salary_max`, `salary_period`); vagas
   antigas continuam exibindo o texto livre em `salary`. Backend: descrição e mínimo > 0
   obrigatórios em criar E editar (`DESCRIPTION_REQUIRED`, `SALARY_REQUIRED`), faixa invertida
   recusada (`INVALID_SALARY_RANGE`), período HOUR|DAY|MONTH|YEAR (padrão MONTH;
   `INVALID_SALARY_PERIOD`), rótulo pronto no `JobView.salaryLabel` ("R$ 1.500 / mês" ou
   "de R$ 1.500 até R$ 2.000 / mês"). Front: formulário de criação e edição com mínimo/teto/
   período e descrição obrigatória; listagem e página mostram `salaryLabel` primeiro.
   Testes: `JobSalaryTests` (7) + ajuste dos testes que criavam vaga sem valor; V110
   validada na bancada MariaDB (aplicada 2x + insert com as colunas novas).

## Médio-grande
6. **[x] ENTREGUE (verificado no código em 2026-10-20) — Feedback por candidato.**
   `JobApplicationStatus {APPLIED, IN_REVIEW, ADVANCED, NOT_SELECTED}` com `feedback` e
   `reviewed_at` no domínio, `PATCH /jobs/{jobId}/applications/{id}` e o diálogo de retorno no
   front (o motivo é obrigatório ao recusar).
   *Aceite cumprido:* avançar/recusar com motivo; o candidato é notificado (DM e e-mail).
7. **[x] ENTREGUE 2026-10-20 — Penalidade por excluir vaga sem dar retorno.** V114
   (`job_abandon_penalties`, uma linha por vaga — a PK garante que a mesma exclusão não conta
   duas vezes). O `delete` da vaga conta as candidaturas ainda em APPLIED; se houver, registra
   a penalidade e devolve o aviso na própria resposta (`{unanswered, penalized}`), que o painel
   "Minhas vagas" mostra como toast; o diálogo de exclusão já alerta a regra antes de confirmar.
   No `ReputationService`, a penalidade entra no componente de integridade: 25, depois 75,
   depois 150 (teto próprio de 150, somado à penalidade de moderação com teto global de 600).
   Quem respondeu todas (qualquer estado fora de APPLIED) não é penalizado. Testes:
   `JobAbandonPenaltyTests` (3: sem resposta penaliza e avisa; respondido não penaliza; não
   conta duas vezes) + `ReputationServiceTests` 6/6 (penalidade desconta 25). V114 validada na
   bancada MariaDB (create 1x + reexecução + PK rejeitando duplicata).
8. **[x] ENTREGUE 2026-10-20 (verificação de código; entregue nas rodadas anteriores) —
   Currículo em PDF.** Confirmado no código de ponta a ponta: o diálogo do perfil
   (`profile-resume-dialog.tsx`) sobe o PDF pelo fluxo de mídia com purpose `RESUME`,
   validando tipo (`application/pdf`) e tamanho no cliente, com baixar e remover; o campo
   vive no perfil (V64, `resume_url`/`resume_name`); a candidatura congela o currículo no
   `candidate_snapshot` (não é coluna: trocar o PDF depois não reescreve candidatura antiga)
   e o empregador recebe o link no resumo e no `CandidateView`. O backend aceita o purpose
   com teto de 50MB (`MediaService`). Aceite cumprido: PDF válido anexa; tipo errado recusa
   no cliente.

## Médio-grande (pedido 2026-10-17, Game Dev community)
9. **[x] ENTREGUE 2026-10-20 — Imagem no meio do texto.** O conteúdo aceita o marcador
   `{{media:<id>}}` e o render troca cada um pela mídia real no ponto exato (imagem com zoom,
   vídeo e áudio com player sem autoplay). Backend: `shared/text/InlineMedia` valida que TODO
   marcador referencia anexo da MESMA publicação (400 `INVALID_MEDIA` para marcador órfão —
   mídia alheia disfarçada de anexo não passa), aplicado em publicar, editar e comentar no
   `FeedController` (a edição valida contra as mídias que o post JÁ tem, pois ela não mexe em
   anexos). Front: `RichText` recebe `mediaIds` e divide o texto nos marcadores; a grade do fim
   só mostra o que NÃO foi inserido inline (sem repetição); no composer, cada anexo ganha o
   botão "Inserir no texto" — o marcador nasce `pendente-N` e vira o id real no upload.
   `InlineMediaTests` 4/4 (marcador próprio aceito e preservado; alheio recusado; comentário
   idem; sem marcador não valida nada). Sem marcador = anexo no fim, como sempre.

## Grande
10. **[x] ENTREGUE 2026-10-21 — Importador de vagas plugável.** O plano previa a porta
    `JobImportSource` + tabela `imported_jobs` com `unique (source, external_id)`; no código a
    porta tem exatamente esse nome e a tabela chama `external_jobs` (mesma chave de unicidade,
    V40) — a mesma que abriga também a vaga que a comunidade cola de outro site, para a listagem,
    o filtro por plataforma, a busca e a dedupe não terem dois códigos paralelos.
    *O que está feito:* porta `JobImportSource` (`jobs/application/port/out/`) com duas fontes
    oficiais — `JobRadarJobSource` e `OpenJobApiSource` (`jobs/adapters/out/imports/`), **sem
    scraping** (nenhuma delas toca a API privada da SPA); `ExternalJobImportService` deduplica por
    (plataforma, id externo) **e** globalmente pela URL canônica, não sobrescreve a vaga que a
    comunidade cadastrou, importa em lotes de 200 (transação curta por bloco) e isola a falha de
    uma fonte; `ExternalJobRefreshScheduler` faz a carga no boot (em segundo plano) e o refresh
    diário; a listagem é `GET /jobs?source=EXTERNAL` e o front tem a aba "EXTERNAL" com filtro por
    plataforma (`routes/vagas.tsx:149`).
    *Aceite cumprido:* importar e deduplicar — `ExternalJobImportServiceTests`
    (`importsIntoItsOwnTableAndDoesNotDuplicateOnRerun`) e `CommunitySubmissionImportGuardTests`
    (a vaga da comunidade não é reescrita pela importação); bloqueio sem os requisitos — a fonte
    fora do ar não derruba as demais (`available()` por fonte, `catch` só na chamada externa) e
    quem cadastra vaga na comunidade passa pelo mesmo gate das vagas internas (reputação > 150 +
    `COMMUNITY_RULES_NOT_ACCEPTED`).
    *Nota de escopo:* a "revisão antes de publicar" do plano se aplica a quem **publica** vaga
    (`JobPublishingPolicy`: reputação > 150 e identidade verificada quando a exigência está
    ligada). A vaga importada é listagem de terceiro (o botão vai para o link de origem), não
    publicação de membro — por isso ela não passa por aquele gate.
11. **[x] ENTREGUE 2026-10-21 (o ALIAS) — API `/api/v1` versionada.** A API tinha um endereço só,
    que muda junto com o produto: quem constrói um app em cima do Énóis não tinha como saber se a
    resposta de hoje é a de amanhã. Agora existe o caminho fixado: `/api/v1/...` responde **o mesmo
    que** `/api/...` (mesmo status, mesmo corpo, mesmo envelope de erro `{code, message, timestamp}`)
    e `GET /api/v1` responde o **índice da versão** — a lista de endpoints montada a partir do
    roteador de verdade (`RequestMappingHandlerMapping`), que não pode divergir das rotas. O
    envelope de erro é o mesmo (`{code, message, timestamp}`).
    *Como foi feito:* um filtro (`apiv1/V1AliasFilter.java`) reescreve o caminho e entrega o pedido
    ao mesmo handler do v0 — sem controller duplicado e sem cópia da regra de negócio, então a
    igualdade das respostas é por construção, não por promessa. O filtro roda em `@Order(0)`,
    **antes** dos limitadores de taxa: se rodasse depois, o limitador de força bruta do login não
    reconheceria o caminho `/v1/auth/login` e a proteção viraria uma porta dos fundos (há teste
    fechando isso: `V1LoginRateLimitTests`).
    *Testes:* `V1ApiAliasTests` (9) — leitura pública, leitura autenticada, escrita com sessão,
    escrita sem sessão (continua recusada), erro, índice, caminho inexistente e "v1x" (que não é
    versão) — e `V1LoginRateLimitTests` (1).
    *No app:* a página pública de desenvolvedores (`/developers`) mostra a base versionada, o
    exemplo de chamada e o que a v1 significa (front `cf6d580d`).
    *O que NÃO está feito (e é decisão de produto):* o v1 é o **alias** do v0, não uma cópia
    congelada — quando o v0 muda, o v1 muda junto. Congelar exige DTO próprio por endpoint e uma
    política de depreciação do v0. A outra metade do item (**OpenAPI gerado e comparado no CI**)
    continua com o raposo: a esteira só constrói a imagem.
    *(no ROADMAP são dois itens: 1 = a API v1, 2 = o OpenAPI no CI.)*
12. **[x] ENTREGUE 2026-10-21 — Portal de docs no app + matriz de escopos.** A página pública
    `/developers` (`src/routes/developers.tsx` + `src/constants/developers-api.ts`) traz a
    matriz da API com o escopo de cada endpoint (`public` / `auth` / `admin`, renderizado na
    própria página) e os casos de uso em curl.
    *Aceite cumprido (verificado em 2026-10-21):* **os 132 pares (método, caminho) documentados
    batem com os mappings do backend** (checagem automática por script contra os `@*Mapping`) —
    e antes da rodada de hoje 8 deles NÃO existiam (o módulo de serviços é `/servicos/...`, mas a
    doc prometia `/services/servicos/...` e `/services/contratos/...`: quem copiasse pegava 404).
    *Exemplos sintéticos:* a página é pública e os exemplos estavam com dado REAL capturado da
    produção (nome completo da dona, nome completo de outra pessoa, username de um usuário e o id
    de um post que continua no ar). Tudo virou a persona fictícia que o arquivo já usava
    (`ada`/`ada-silva`), o id do post virou sintético e o placeholder de alvo do painel de
    moderação deixou de citar um usuário real. O cabeçalho do arquivo, que se gabava de "exemplos
    REAIS capturados da produção", foi corrigido. `developers-api.test.ts` trava a regra (só
    persona fictícia e id de exemplo): o teste **falha** se o dado real voltar.
    *Escopo que fica para o item 13:* escopos OAuth de aplicações de terceiros (aqui o escopo é o
    nível de acesso da API de cada rota).
    Front: `925b048a` (branch `fix/lint-higiene-front`).
    - **[x] ENTREGUE 2026-10-26 — A matriz prometia rota aberta que a API recusa, e faltavam sete
      rotas (o Labirinto inteiro).** O item acima prometia os pares (método, caminho) batendo com
      o backend — e batem. Mas o **escopo** de cada rota (a coluna `public`/`auth`/`admin`, que é
      o que quem integra acredita) nunca foi conferido: a auditoria chamou as 41 leituras
      documentadas **sem sessão** e achou duas prometidas como `public` que respondem **401** —
      `GET /daily-games/blocks/today` e `GET /daily-games/words/today` (a própria descrição da
      primeira dizia "com sessão", e o rótulo dizia o contrário). E a seção de jogos diários
      documentava 6 das 13 rotas da API: faltava o Labirinto inteiro (estado, início, movimento,
      ranking) e o ranking/início/guesses de Palavras.
      *O que foi feito:* os dois rótulos viraram `auth` e as sete rotas entraram na matriz. E a
      conferência de escopo virou **código**: `npm run check:api-matrix` agora sonda as leituras
      documentadas sem credencial e compara com o escopo prometido (rota `public` que responde
      401/403 é promessa quebrada; rota `auth`/`admin` que responde 200 é rota escondida).
      *A prova de que a trava pega:* com os dois rótulos errados de volta, o script accuse
      exatamente as duas e sai com erro.
      *Testes:* `src/lib/developers/api-matrix-check.test.ts` (17 no total, +4 do escopo).
      *Verificado:* `vitest` 467/467, `eslint` limpo, `tsc --noEmit` sem erro, `vite build` ok, e o
      script fechando em "a página documenta só o que existe, com o escopo certo".
      Front: #264 (`fix/matriz-escopos-daily-games`, sobre a `qa`).
13. **Registro de aplicações de terceiros (OAuth + PKCE, escopos, revogação)** — programa de auth de
    terceiros. Exige decisão de produto/segurança antes. *Aceite:* app só recebe escopo autorizado; revogável.
14. **CI de segurança + testes adversariais** — secret-scanning, SCA, SAST com política de bloqueio;
    testes negativos (anônimo x privado, id de terceiro, mass assignment, token expirado, payload
    gigante, automação social por caminho alternativo). *Aceite:* gates verdes sem desligar checagem.
    *Estado em 2026-10-21:* a **esteira não roda nada disso** — `.github/workflows/build-and-push.yml`
    só constrói e publica a imagem (nem teste Java, nem lint do front, nem secret-scanning). Dos
    testes negativos, já existem: anônimo x privado (`GroupSecurityTests`), token expirado/revogado
    (`OAuthHardeningTests`, `EmailVerificationFlowTests`), payload gigante (`MediaUploadAuthTests` com
    o teto de 500MB, `InlineMediaTests`), XSS/HTML não confiável (`SafeHtmlAttackTests`,
    terceiro no corpo) e "automação social por caminho alternativo". O passo de CI fica com o raposo
    (Actions é dele).
    *2026-10-21 — os DOIS cenários que faltavam foram implementados e passam:*
    - **mass assignment** — `MassAssignmentTests` (4): o corpo manda `authorId`, `authorUsername`,
      `userId`, `createdBy`, `ownerId` e até contadores, e a autoria, a data e os contadores
      continuam sendo os do servidor e da sessão; a quarta prova é "acesso a terceiro por id":
      mídia de outra conta na publicação alheia é recusada com 403 `MEDIA_OWNER_MISMATCH`. O app
      **não tem** o buraco — o teste é a trava para ele voltar.
    - **automação social por caminho alternativo** — `AutomatedWritePathsTests` (2): varre as
      **253 rotas de escrita** do backend (extraídas do próprio código fonte, não de uma lista
      escrita à mão) e exige 401 em todas sem sessão, menos os 14 fluxos públicos de entrada; e
      compara a allowlist do `WriteRouteInterceptor` com o catálogo esperado — se alguém abrir mais
      um fluxo público (webhook, caminho de bot), o teste quebra. Provado nos dois sentidos: o
      teste **falha** quando `/feed/posts` entra na allowlist.
    *O que continua aberto neste item:* a esteira em si (secret-scanning, SCA, SAST) — Actions é do
    raposo.
    *(no ROADMAP são dois itens: 4 = a CI de segurança, 5 = os testes adversariais.)*

## BLOQUEADO por dependência externa (não é código meu)
15. **Portal `developers.enois.ia.br`** — exige DNS, TLS, hospedagem, roteamento e pipeline.
16. **gov.br** — pedido pelo representante legal + credenciais (código pronto, desligado).
17. **LinkedIn** — app aprovado (`w_member_social`); sem isso, sem publicação legítima.
18. **Sanitizar usuários/posts de teste em produção** — sem acesso ao banco e sem endpoint admin.

- **[x] ENTREGUE 2026-10-21 — O teste de upload do LinkedIn era uma corrida: a suíte falhava
  sozinha, sem nada mudado.** O teste sobe um Tomcat de verdade e manda um ZIP de 12 MB para
  `/me/import/linkedin` esperando o 413 do limite de multipart (8 MB no perfil de teste). O
  servidor recusa o upload **no meio da stream** — é assim que o multipart funciona — e o cliente
  ainda tinha megabytes para escrever: quando a máquina estava carregada, o servidor fechava a
  conexão antes de o cliente terminar de enviar, e o teste morria com "pipe quebrado" em vez de
  ver o 413. O defeito é do teste, não do limite.
  *Corrigido em duas partes.* (1) O ZIP do teste passou a ter tamanho **exato** (entrada
  armazenada, sem compressão: o deflate do texto aleatório diminuía o arquivo, e o teste media o
  limite errado) e a passar do limite de 8 MB por pouco. (2) O envio do arquivo que estoura o
  limite passou a usar um **socket cru**: o corpo vai numa thread e a resposta é lida na outra,
  porque o servidor recusa o upload **no meio da stream** e fecha a conexão — com um cliente HTTP
  comum, o cliente ainda estava escrevendo e tomava "pipe quebrado" antes de conseguir ler o 413.
  Agora o teste vê sempre o 413 com `FILE_TOO_LARGE` (que é o que importa: erro claro, não 500).
  Nada foi desligado nem afrouxado: o teste continua subindo um Tomcat de verdade.
  *Comprovado:* a suíte completa no `main` publicado, **sem nenhuma mudança minha**, falha do
  mesmo jeito (718 testes, 1 erro, "pipe quebrado") — a corrida é do main, não do trabalho da API
  v1. *Achado pela auditoria da API `/api/v1`* (rodada de 2026-10-21), que roda a suíte inteira.
- **[x] ENTREGUE 2026-10-21 — A migração V140 (pré-triagem de moderação) não rodava no
  MariaDB: o pod da produção não subia.** A migração criava a tabela
  `moderation_signal_weights` com uma coluna chamada **`signal`**, que é **palavra reservada no
  MariaDB**: o `CREATE TABLE` devolvia 1064 e o mesmo aconteceria em qualquer `SELECT`/`INSERT` com
  esse nome. O H2 dos testes aceita, então **a suíte passava e o banco real não** — o defeito só
  apareceu quando o deploy da imagem com a V140 chegou ao cluster (o pod antigo continuava
  servindo, o novo não subia).
  *Corrigido:* a coluna virou **`signal_key`** (a migração nunca tinha sido aplicada em nenhum
  MariaDB — ela quebrava —, então renomear não quebra checksum de ninguém) e as queries do
  `JdbcModerationLearningAdapter` apontam para o nome novo. *Verificado na bancada MariaDB 11.4:*
  a cadeia inteira de migrações (139 tabelas) aplica numa base nova **e** a V140 sozinha aplica
  2x sem erro; os testes de moderação passam (16/16).
  *Fica em aberto:* a esteira do backend **não roda teste nem migra nada** — por isso um SQL que
  quebra só no MariaDB passa pelo H2 e só aparece em produção. Falta um job de CI com um
  container MariaDB aplicando as migrações (o do front, o `ci.yml`, já roda lint, typecheck e
  vitest).
## Higiene do backend (entregue 2026-10-21)
- **[x] ENTREGUE 2026-10-25 — O IP que o app resolvia era o do proxy (na QA) ou o da borda do
  Cloudflare (na prod): todo mundo parecia a mesma pessoa.** A variável `TRUSTED_PROXY_IPS` não
  estava no ambiente — a lista de proxies confiáveis nasceu vazia e o IP do proxy não é loopback
  nem RFC1918, então o app **rejeitava** o `X-Forwarded-For` e respondia com o
  `getRemoteAddr()`: o IP do proxy, idêntico para todas as requisições. Todo limite por IP
  (login, cadastro, leitura) e o bloqueio da moderação passavam a valer para a plataforma inteira
  como se fosse uma pessoa só — o mesmo defeito que derrubou o app antes, agora pela metade
  resolvida (a lista configurável existia, mas ninguém a configurou).
  *A evidência (logs dos containers, agregados):* na **QA**, `TRUSTED_PROXY_IPS` **ausente** e
  **1 único IP distinto** em todas as requisições; na **prod**, a variável existia (o IP do
  Traefik) mas os IPs resolvidos eram faixas **172.6x/172.7x — do próprio Cloudflare**, que fica
  na frente: 91 de 131 requisições vindas de 3 endereços de borda. O IP real da pessoa estava no
  cabeçalho que o Cloudflare escreve (`CF-Connecting-IP`) e o app não lia.
  *A segunda metade do defeito (código):* o `ClientIpResolver` passou a ler o **primeiro** item do
  `X-Forwarded-For` — e o primeiro item é o único que o **cliente** controla: cada pessoa podia
  escolher com que IP os limites a viam. O próprio teste que existia para provar que o cliente não
  escolhe o próprio IP tinha sido invertido para afirmar o contrário.
  *O conserto:*
  - o app resolve o IP nesta ordem: (1) `CF-Connecting-IP`, quando existe — é o IP que o
    Cloudflare viu e ele o sobrescreve a cada requisição, então o cliente não o forja (e só vale
    vindo de um proxy confiável); (2) a cadeia do `X-Forwarded-For`, lida **da direita para a
    esquerda**, pulando os proxies confiáveis — assim o item forjado nunca é alcançado e a
    leitura serve às duas convenções (proxy que substitui o cabeçalho e proxy que o acrescenta);
    (3) o `getRemoteAddr()`;
  - `TRUSTED_PROXY_IPS` entrou no deploy da **QA** (que não tinha a variável) e no da **prod** —
    onde ela existia só digitada à mão no Portainer, fora do arquivo: um deploy da stack apagaria
    e o defeito voltaria sozinho.
  *A prova (o teste que falhou antes do conserto):* com o código como estava, `ClientIpSpoofingTests`
  dava **5 falhas em 9** — e a mais grave mostrava que quatro requisições seguidas, cada uma com
  um `X-Forwarded-For` diferente, passavam **sem** bater no teto global (200 em vez de 429): o
  limite por IP tinha virado decorativo.
  *Testes:* `ClientIpSpoofingTests` (9) — o item forjado não vale; as duas convenções de proxy
  servem; proxy confiável no meio da cadeia é pulado; cabeçalho inchado não faz o forjado valer;
  o cabeçalho do Cloudflare tem precedência e só vale de proxy confiável; e girar o header do
  cliente não escapa do teto global (429).
  *Verificado:* `ClientIpSpoofingTests` 9/9; contra o código anterior os mesmos testes falham
  5/9 (a prova de que o defeito era real, não impressão); suíte do backend verde.
- **[x] ENTREGUE 2026-10-25 — O plano do perfil corporativo ainda dizia "pendente" no que já estava
  entregue.** Cruzamento dos planos contra o código (a regra: conferir no código antes de dizer que
  falta — a lista do repo já mentiu várias vezes) achou dois marcadores desatualizados:
  - `corporate-profile-validation.md` dizia "commit pendente no próximo ciclo" para os documentos do
    plano — eles entraram no mesmo dia (`32123181`) e o plano inteiro foi publicado depois;
  - `corporate-profile-progress.md` tinha "[ ] Confirmar com a dona as decisões abertas
    (username com ponto, rotas)" — **essas duas foram resolvidas na Fase 10** (o próprio
    `decisions.md` marca a pergunta 1 como resolvida).
  *Conferido no código:* a rota pública `/profissional/{username}` existe no front
  (`profissional_.$username.tsx`), e o backend atende `GET /corporate/{username}` e
  `GET /corporate/{profileUsername}/posts` (o feed corporativo). Os itens marcados como entregues
  estão de pé.
  *Fica só uma pergunta de produto, sem bloqueio:* a visibilidade do feed corporativo (público
  para todos ou só para seguidores) — o plano já tem o padrão (público) e a `decisions.md` diz que
  não trava nada.
  Back: `docs/backlog-plano-atualizado` (branch própria, sobre a `qa`).
- **[x] ENTREGUE 2026-10-22 — A recorrência do banimento JÁ estava coberta (o plano ainda
  dizia que faltava).** Conferido no código: o banimento revoga **todas** as sessões da conta
  (`revokeAllByUser` — access e refresh na mesma linha de sessão), o **login** é recusado com 403
  `ACCOUNT_BANNED`, o **desafio de 2FA** também, e o refresh de token revogado responde 401. A
  prova está em `BulkBanAndSuspiciousTests` (a sessão antiga deixa de valer e o login novo é
  bloqueado) e `SanctionFlowTests` (ban do recurso negado, com sessões revogadas e login travado).
  *O que de fato falta (e fica anotado):* no banimento, o vínculo de **integração** (o token do
  LinkedIn e os vínculos OAuth) continua valendo — o `BanCleanupService` apaga o conteúdo da
  conta, não as credenciais de terceiros. O plano de segurança registra isso como a parte que
  sobra da lacuna.
- **[x] ENTREGUE 2026-10-22 — O histórico de risco ficou legível para a moderação.** O motor
  grava cada avaliação (nível, sinais, versão da regra) para a decisão poder ser reconstruída, mas
  o histórico só existia na tabela: ninguém consultava. Agora o admin tem
  `GET /admin/users/{username}/risco` (o histórico da conta, do mais novo para o mais antigo, com
  nível, sinais e versão da regra) — é o que responde "por que esta pessoa foi sinalizada?". O
  mesmo formato do `/age` e do `/ips` do painel: sessão + `AdminGuard`.
  *Testes:* `RiskHistoryEndpointTests` (2) — o admin vê o histórico com nível e sinais, e quem não
  é admin não enxerga. *(A tela do painel para exibir isso ainda não foi feita — o endpoint é o
  passo que a destrava.)*
- **[x] ENTREGUE 2026-10-22 — O motor de risco passou a ENTREGAR a ocorrência à moderação (não
  só registrar).** O passo 1 do motor (nível + registro) existia, mas ninguém ficava sabendo: a
  avaliação ia para a tabela e morria ali. Agora, nível **MEDIUM ou acima**, o motor entrega a
  ocorrência à moderação com o nível, os sinais e a versão da regra na descrição — e a denúncia
  automática **não depende de alguém ter denunciado**, que é o que o plano mestre pede ("o próprio
  Énóis deve identificar e encaminhar incidentes proativamente"). Intervalo de 1 h por conta, para
  um robô não virar uma tentativa de inserção por post.
  *E o guarda de cadência deixou de denunciar sozinho:* ele **detecta**, o motor **entrega**. Antes
  a denúncia do guarda saía primeiro e a do motor chegava depois como duplicata (a moderação
  recusa repetida com 409) — a moderação ficava com a descrição pobre, sem nível e sem o conjunto
  de sinais. O comportamento antigo (cadência detectada → moderação avisada) continua de pé, agora
  com o nível junto.
  *Testes:* `RiskEngineTests` (6) — nível grave vira ocorrência com nível e sinais, cadência
  sozinha (MEDIUM) também vira ocorrência com o nível, e o modo de observação continua sem barrar
  ninguém.
- **[x] ENTREGUE 2026-10-22 — A varredura de rotas não custava nada ao atacante.** Um scanner
  martelando todas as rotas produzia 404 atrás de 404 (e 401/403/400 ao sondar acesso e payload) e
  o único freio era o limitador global, que conta REQUISIÇÕES: dava para errar dezenas de vezes
  antes de bater no teto, e cada erro não deixava consequência nenhuma.
  *Corrigido:* `shared/security/AbuseGuardFilter` conta **respostas de erro** por IP numa janela
  curta (padrão: 20 erros em 3 min) e, ao estourar, nega tudo daquele IP com **429 +
  `Retry-After`** por 15 minutos — dobrando a cada reincidência (até 6 h). Quem usa o app produz
  200 e, no máximo, um 409 de estado, que não conta; `health`/`actuator` fica fora, porque
  derrubar a sonda derruba o pod.
  *Testes:* `shared/security/AbuseGuardFilterTests` (5) — a varredura acaba em negação com prazo,
  o bloqueio expira sozinho, a reincidência dura mais, uso normal (200/409) não conta e a sonda do
  cluster nunca entra. O perfil de teste deixa a defesa ligada com teto alto (1000), para uma
  classe que exercita 401/404 de propósito não derrubar as seguintes.
  *Achado pelo relato da dona* ("nosso amigo probe está atacando em todas as rotas").
- **[x] ENTREGUE 2026-10-26 — A vitrine do marketplace buscava a galeria de temas para todo
  item, e levava 404 em cada um.** O card da listagem pedia o pacote JSON do tema
  (`GET /temas/galeria/{id}`) para **qualquer** item à venda — mas o `itemId` de uma listagem só
  aponta para a galeria quando o item é um **TEMA** (`ItemKind.THEME`: "Tema da galeria (aponta
  para theme_gallery)"). Para banner, template, página, post, blog e serviço o id é o do próprio item
  do marketplace (`mitem_…`), e a chamada respondia **404** — uma por item da vitrine, com o caminho
  errado no console (e a prévia caía no fundo genérico, então a tela parecia funcionar).
  *Achado pela varredura de responsividade* (Chrome headless, 390px e 1280px, com a QA ao vivo):
  a tela não transbordava, mas o console mostrava 8 respostas 404.
  *O conserto:* a consulta da galeria virou um hook (`src/lib/marketplace/use-listing-theme.ts`) que
  só roda quando a listagem é de tema; os outros tipos seguem com a prévia genérica.
  *Prova:* com o `enabled` de volta, o teste acusa (o spy é chamado para item que não é tema); com o
  conserto, a vitrine do marketplace na QA ao vivo ficou **sem nenhuma resposta com falha e sem
  erro de console**.
  *Testes:* `src/lib/marketplace/use-listing-theme.test.tsx` (2).
  Front: #268 (`fix/marketplace-so-busca-tema-no-tema`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-22 — O serviço não dizia quem prestava, não tinha conversa com quem
  prestava, e os serviços de teste que sobraram na produção continuavam no catálogo.**
  Três coisas da mesma feature (o marketplace de serviços, Fase 5), pedidas pela dona:
  1. **Serviços de teste no catálogo:** as ofertas de teste que rodaram contra a API de produção
     ficaram lá depois que as contas de teste sumiram — oferta sem nome, sem foto e sem chat com
     ninguém. A V155 limpa as ofertas (e os pedidos e propostas) cujo dono **não existe mais**; a
     conta que a pessoa apaga pelo app é anonimizada e continua no catálogo, com o nome
     anonimizado — essa não é órfã e a migração não mexe nela. *Validada na bancada MariaDB: a
     cadeia inteira de migrações aplica numa base nova e a V155 aplica 2x sem erro; num teste com
     uma oferta de conta real e uma de conta inexistente, a primeira fica e a segunda sai.*
  2. **O serviço não mostrava quem presta:** o card tinha só o título. Agora a oferta carrega o
     nome e a foto de quem prestador (uma consulta por prestador, não por oferta) e o preço
     respeita a moeda do serviço (reais ou faíscas — o card mostrava sempre faíscas).
  3. **O chat de serviço:** `POST /servicos/ofertas/{id}/chat` abre (ou reaproveita) uma conversa
     do tipo **SERVICE**, com **assunto** (o tipo do serviço e o título da oferta) e a primeira
     mensagem sendo o pedido da contratação. O prestador recebe numa aba **“Serviços”** da caixa
     de entrada (as conversas de serviço não se misturam com as de vaga nem com as de pessoa), e
     no compositor quem presta pode anexar o link de um serviço seu.
     *Testes:* `ServicesFlowTests` (+2) — o catálogo mostra quem presta e a moeda, o chat abre com o
     assunto e a primeira mensagem, clicar de novo não abre outra conversa, e o prestador não
     abre chat consigo mesmo.
- **[x] ENTREGUE 2026-10-22 — O motor central de risco (plano mestre de segurança humana,
  item 7): os sinais viram nível, e a decisão fica registrada.** Até aqui cada guarda olhava um
  sinal sozinho e agia sozinho — o de cadência denunciava, o de duplicidade recusava, o de conta
  nova segurava. O plano pede o contrário: **combinar** sinais independentes, transformar a soma em
  um **nível** e aplicar a resposta correspondente, com o motivo e a versão da regra registrados
  para a decisão poder ser reconstruída.
  *Entregue agora (passo 1, em modo de observação):* `shared/risk/RiskEngine` (o motor), com
  `RiskLevel` (LOW/MEDIUM/HIGH/CRITICAL), `RiskSignal` (cadência de robô, conteúdo repetido, conta
  nova, sanção ativa), `RiskDecision` (ALLOW/MONITOR/ESCALATE/RESTRICT) e `RiskAssessment` (o
  registro). O motor pergunta aos guardas (memória, sem consulta), junta o contexto (conta nova e
  sanção ativa), soma os pesos (`rede.risk.weights.*`) e compara com os limiares
  (`rede.risk.thresholds.*`); a avaliação vai para a tabela nova `risk_assessments` (V154) com o
  nível, os sinais, a versão da regra e a resposta. Ele **não barra ninguém** por enquanto
  (`rede.risk.enforce=false`): o plano pede medir antes de endurecer, e a contenção
  (RESTRICT) está no código com o seu teste, esperando a linha de base.
  *Onde roda:* na publicação e no comentário do feed — e só quando há sinal (o motor não custa uma
  consulta a cada post).
  *Testes:* `shared/risk/RiskEngineTests` (4) — a cadência de robô registra a avaliação com o
  sinal, a escrita normal não suja o histórico, o conteúdo repetido entra como sinal, e o modo de
  observação não restringe ninguém.
  *O que fica para o próximo passo:* a escalada pelo motor (hoje a denúncia automática continua
  saindo dos guardas, para não duplicar denúncia e inflar o score da triagem) e a leitura do
  histórico na tela da moderação.
- **[x] ENTREGUE 2026-10-21 — Os portões de escrita não cobriam todos os caminhos: uma conta
  restrita ainda publicava em grupo, comentava em grupo e mandava mensagem privada.** A sanção de
  moderação (a RESTRICTION — decisão humana de "não publica nem comenta", com recurso) era
  conferida em **dois** lugares: o post e o comentário do feed. A mesma conta restrita continuava
  alcançando pessoas pelos outros caminhos, e o canal privado é justamente onde a moderação não
  enxerga. Pior: as escritas de grupo nem passavam pelo **portão de idade (ECA Digital)**, então uma
  conta de menor sem autorização do responsável publicava e comentava em grupo normalmente — o
  feed barra, o grupo não.
  *Corrigido:* o portão virou um componente compartilhado (`shared/sanctions/SanctionGate`, no
  mesmo formato do `AgeGate`), com **código e mensagem únicos** (`ACCOUNT_RESTRICTED`), e passou a
  ser chamado em toda escrita que produz conteúdo ou fala com outra pessoa: post e comentário do
  feed, post e comentário de grupo e mensagem privada. O blog já estava coberto (o post do blog
  nasce como post do feed). As escritas de grupo ganharam também o portão de idade.
  *Testes:* `shared/sanctions/WriteGateCoverageTests` (5) — conta restrita não publica em grupo,
  não comenta em grupo, não manda mensagem, menor sem autorização não publica em grupo, e a
  conta sem nada contra ela continua escrevendo. A suíte inteira continua verde.
  *No app:* o chat não mostrava **nada** quando o envio falhava (a pessoa clicava em enviar e o
  app ficava quieto) e a publicação de grupo mostrava um erro genérico que escondia o motivo.
  Agora os dois mostram a mensagem do servidor — que é a que diz o motivo (conta restrita, ECA,
  tópico arquivado) e onde recorrer.
  *Fica em aberto (decisão de produto):* as escritas de **anúncio** (vaga, freelance, serviço,
  projeto, evento, comunidade, microprojeto) seguem sem o portão da sanção — hoje elas têm
  portões próprios (reputação, identidade verificada, revisão). Se a restrição deve alcançar também
  esse tipo de publicação, é decisão da dona: pode ser que uma empresa restrita por um comentário
  não deva perder o cadastro de vaga.
- **[x] ENTREGUE 2026-10-21 — A suíte voltou a ficar verde, e 2 das 9 falhas eram defeitos do
  app (não dos testes).** O cadastro em duas etapas (V134, 2026-10-20) deixou 5 classes de teste
  desatualizadas e, no caminho, **abriu dois buracos no código de produção**:
  1. **Login de cadastro pendente respondia "senha inválida".** Quem se cadastrava e clicava em
     "Entrar" antes de confirmar recebia 401 `INVALID_CREDENTIALS` — a senha estava certa, a conta
     só ainda não existia. Agora o backend responde **403 `EMAIL_NOT_VERIFIED`**, mandando
     confirmar o e-mail (`AuthService.login`).
  2. **O reenvio do link de confirmação não tinha teto.** O fluxo de conta já criada conta os
     envios e recusa o 4º dentro da janela; o cadastro de duas etapas reenviava **sem limite** —
     dava para spammar a caixa de quem se cadastrou. Agora vale o mesmo teto (3 envios por hora,
     contando o e-mail do cadastro): **V138** (`pending_signups.resend_count` +
     `last_sent_at`), validado na bancada MariaDB (aplicada 2x, sem erro).
  *Os testes desatualizados foram atualizados (não desligados):* `birthDate` no corpo do
  cadastro (obrigatório desde a regra do ECA), confirmação do e-mail antes do login, o estado do
  menor passou a `MINOR_CONSENT_PENDING` (supervisão parental, V137), a criação de mensagem
  responde 201 e o link usado uma segunda vez responde 404 `INVALID_TOKEN` (no fluxo de duas
  etapas o pendente some quando o link é usado).
  *Verificado:* as 5 classes que estavam vermelhas (`AgePolicyTests`, `AuthProfileFlowTests`,
  `EmailVerificationFlowTests`, `SignupChallengeTests`, `ThemeGalleryTests`) passam — 14/14.
  *Fica em aberto (para a próxima rodada):* o front consome o link de confirmação **no
  carregamento** da página, então um scanner de e-mail (Safe Links e afins) que busca o link
  queima o token e a pessoa vê "link inválido" ao clicar de verdade — o backend não tem como
  saber que ele já foi usado, porque o pendente é apagado na confirmação.

- **[x] ENTREGUE 2026-10-21 — O quadro de HTML nunca encolhia (buraco de 400px na tela).**
  A bio em HTML do perfil — e os outros quadros com altura automática — ficavam com a altura
  inicial de 520px mesmo com conteúdo de 116px: o card "Sobre" virava quase vazio. A causa: a
  conta pegava o `scrollHeight` do `<html>` do quadro, mas o `<html>` tem a altura da **viewport**
  e o `scrollHeight` dele nunca é menor que ela — então a média do quadro era sempre a viewport.
  Agora o `<html>` só entra na conta quando o conteúdo **estoura** a viewport, e a conta virou uma
  função pura (`src/lib/html-frame-height.ts`) com 4 casos de teste usando os números medidos no
  navegador. *Prova:* Chrome headless com conta local e bio em HTML — o quadro foi de 520px para
  116px, e o card "Sobre" passou a abraçar o texto. Front: `fffff551`.
- **[x] ENTREGUE 2026-10-21 — A página de desenvolvedores era ilegível no celular.** O bloco de
  código usava `overflow-x-auto`: o comando de `curl` saía cortado e a única saída era um scroll
  lateral sem nenhuma pista. Agora a linha **quebra** (`whitespace-pre-wrap` + `break-words`) e o
  exemplo inteiro cabe na tela. E o **botão de copiar só aparecia no `hover`** (`opacity-0` →
  `group-hover:opacity-100`): no toque não há hover, então o botão ficava invisível — agora ele
  fica sempre visível no celular (`sm:opacity-0` mantém o hover no desktop), com o bloco abrindo
  espaço (`pr-11`) para o texto não ficar embaixo do botão.
  *Verificado* (Chrome headless a 390px): `scrollWidth` 390 = `clientWidth` 390 (sem transbordo),
  bloco com 324px e a linha longa quebrada dentro da caixa, botão de copiar com `opacity: 1`.
  Front: `e692a78f`.
- **[x] ENTREGUE 2026-10-21 — O link de confirmação não é mais consumido no carregamento da
  página.** A tela `/auth/verify` confirmava o e-mail no `useEffect`, ou seja, no carregamento:
  quem busca o link sem ser a pessoa (Safe Links do Outlook, verificador de links do Gmail e de
  vários gateways corporativos) executa a página, **queima o token e cria a conta sem ninguém
  pedir** — e a pessoa que clicava de verdade recebia "link inválido", sem entender que a conta
  já existia. Agora a página mostra "Confirme seu e-mail" com um botão, e **só o clique consome o
  token**; o clique duplo (com sessão na tela) mostra "link já usado" em vez de "link inválido".
  *Prova* (Chrome headless, sessão real contra backend local, com o pendente lido do H2): scanner
  (busca sem JS) deixa o pendente no banco; **com o código antigo, abrir a página no navegador
  consumia o token** (pendente = 0 linhas e a conta nascia sozinha); **com o código novo, abrir a
  página não consome** e o clique confirma, guarda a sessão e leva ao feed. Front: `924233af`.
- **[x] ENTREGUE 2026-10-21 — O "quem sou eu" vinha do cache sem inscrição: a rede, os
  comentários e os grupos tinham o mesmo defeito da caixa de entrada.** A regra era
  `getCurrentUserId()` ler o **cache** da query `["me"]` sem se inscrever nela: só quem assina a
  query redesenha quando ela chega, e a inscrição fica na árvore do cabeçalho — as páginas
  continuavam com o id do primeiro desenho.
  *Medido com o `/me` atrasado de propósito* (Chrome headless, sessão real contra backend
  local): em `/rede`, o **pedido de conexão que a própria pessoa enviou caía na seção
  "RECEBIDAS", com os botões "Rejeitar" e "Aceitar"** — dava para aceitar o próprio pedido. Em
  `/grupos`, o próprio post só mostrava "excluir" depois de outra consulta redesenhar; nos
  comentários, o próprio comentário era tratado como de terceiro até a próxima redesenhadura.
  *Corrigido:* as três telas (e o item de comentário) passam a se inscrever na query do `/me`.
  Não sobrou nenhuma leitura avulsa do cache no front. Front: `33f2b1c6`.
- **[x] ENTREGUE 2026-10-21 — A caixa de entrada mostrava a própria pessoa no lugar do
  parceiro.** A tela de mensagens resolve o "outro participante" com `getCurrentUserId()`, que lia
  o **cache** da query `["me"]` sem se inscrever nela — e a página usa o `AppContainer` comum (não
  o `AuthenticatedAppContainer`, que é quem assina a query), então o cache ficava vazio: o id vinha
  `null` e o "outro" caía no **primeiro** participante da conversa, que era a própria pessoa. Na
  tela: cada conversa da caixa aparecia com o nome e a foto de quem estava olhando, e a própria
  mensagem saía alinhada como se fosse do outro.
  *Corrigido:* `getCurrentUserId()` cai no `current_user` do `localStorage` (o app grava no login)
  e exige sessão para isso; a página e os dois componentes passam a se inscrever na query do
  `/me` para redesenhar quando a resposta chega. `current-user.test.ts` trava o fallback (4 casos).
  *Prova:* Chrome headless a 390px, com sessão real contra backend local e uma conversa com
  mensagem — a lista passou de "AA | Ana Auditoria" para "BA | Bruno Audicao" e a própria
  mensagem saiu à direita (`justify-end`). Front: `d92bfdcc`.
- **[x] ENTREGUE 2026-10-21 — Novo tipo de feed "Ainda não vi" + a lista de tipos recolhida.**
  Pedido da dona (2026-10-21): uma opção que reúna **os posts em que a pessoa ainda não reagiu
  nem comentou** (o oposto de "Discussões", que são os posts em que ela comentou) e as opções de
  feed colapsadas, com a legenda "Tipo de feed" e um botão que abre a lista.
  *Back:* `FeedTab.UNSEEN` + o caso em `GetFeedService` — tira da janela de candidatos os posts
  comentados pela pessoa e os posts em que ela reagiu (as portas `CommentRepository` e
  `PostInteractionRepository` que já existiam), e o próprio post dela fica de fora. Teste novo
  `UnseenFeedTabTests` (2): o post sem interação entra, o reagido e o comentado saem, e o post
  próprio não aparece. *Front:* a aba nova em `HOME_TABS` + `feed-tab.ts`, os textos
  ("Ainda não vi", "Tipo de feed") e a lista de pílulas trocada por um seletor (o botão mostra o
  tipo escolhido e abre a lista) — as 12 opções ocupavam três linhas na tela. *Prova no navegador
  (Chrome headless a 390px, sessão real contra backend local):* o seletor mostra "Para você",
  a lista abre com a opção nova e, escolhida, a URL vai para `/feed?aba=UNSEEN` e o feed traz os
  posts que a pessoa ainda não tocou (3 → 2 depois de uma reação ✓).
  Front: `fix/lint-higiene-front`; back: `e6073d2`+ (esta entrega).

- **[x] ENTREGUE 2026-10-21 — A identidade nova chegou a todas as surfaces do produto.** A landing
  passou a se apresentar como **rede humana** (e não mais como rede profissional), mas a frase
  antiga continuava em mais seis lugares, e eram os que a pessoa vê fora do site: o **e-mail de
  confirmação de e-mail** (o primeiro e-mail de todo mundo), o **e-mail de recuperação de
  senha**, o **e-mail do consentimento do responsável**, o **cartão de compartilhamento** de
  publicação (a meta que aparece no WhatsApp e no Twitter), a **descrição dos Termos de Uso** e o
  **motivo do limite de idade** nos Termos ("por se tratar de uma rede profissional" — a regra
  de 18+ continua, o motivo não depende mais da identidade antiga). Verificado: nenhum texto de
  tela ou e-mail do produto diz mais "rede profissional" (restaram só usos técnicos: perfil
  corporativo, objetivos profissionais no perfil, termos de limitação de responsabilidade).
  Front: `cb9b38aa` + esta rodada; back: templates dos e-mails.
## Higiene do front (entregue 2026-10-21)
- **[x] ENTREGUE 2026-10-25 — A CI do front não rodava teste, lint nem typecheck nos PRs que vão
  para a QA.** O `ci.yml` disparava **só para `main`** — e a linha do dia a dia é a `qa`. Ou seja: os
  PRs da equipe entravam **sem rodar teste nenhum**; o único check que disparavam era o build da
  imagem, que faz `vite build` e **não** typecheca (o `"build"` do `package.json` é só `vite
  build`). O script `typecheck` existia e **nenhum workflow o chamava**.
  O comentário no arquivo explicava o motivo: *"Lint e typecheck entram aqui quando a main passar
  neles (hoje: ~300 erros de lint... e ~60 erros de TypeScript)"*. **Essa dívida foi paga** — o lint
  e o typecheck passam limpos nas duas linhas agora — e o passo nunca entrou.
  *O que foi feito:* o `ci.yml` passa a disparar para a `qa` também (pull_request e push) e ganha os
  passos **Lint** e **Typecheck** (os testes unitários já estavam lá); a dívida de lint foi quitada
  (15 erros de formatação em 8 arquivos na `qa`, 14 em 7 na `main`, mais um tipo que vivia fora de
  `src/types/**` — a regra `local/type-boundaries`); e o texto da página de desenvolvedores citava o
  código de erro errado ("sem origem cadastrada" responde **403 `ORIGIN_NOT_ALLOWED`**, não
  `ORIGIN_REQUIRED`).
  *Por que importa:* sem o typecheck, um campo que a API devolve **nulo** passa e quebra no
  navegador — e o `tsconfig` do front é `strict: true`, ou seja, o compilador pega essa classe
  inteira de uma vez.
  *Verificado:* `qa` — `vitest` 401/401, `eslint` sem erro, `tsc --noEmit` sem erro, `vite build`
  ok. `main` — `vitest` 383/383 e o resto igual.
  Front: #245 (`fix/ci-validar-prs-qa`, sobre a `qa`) e #246 (`fix/chave-origin-blocked`, sobre a
  `main`).
- **[x] ENTREGUE 2026-10-25 — Um PR em conflito não roda CI nenhuma, e a `main` tinha 1 erro de
  lint que viraria CI vermelho.** Ao abrir o PR #246 (o texto da chave de origem que faltava), o
  GitHub **não disparou nenhum workflow** nele. A causa não era o workflow: sem o *merge ref*
  (`refs/pull/246/merge`), que o GitHub não conseguiu criar enquanto o PR teve conflito, o evento
  `pull_request` nem chega a começar. O sintoma é silencioso — o PR aparece sem check nenhum, o
  que parece "a CI não roda para PR de `main`", mas era o conflito.
  *O que foi feito:* o conflito foi resolvido (a mesma chave `originBlocked` em posições
  diferentes, porque a `main` tinha recebido o merge da `qa` #231), ficando o texto com o código
  de erro certo; e a `main` levou a correção de formatação que faltava (`use-frame-theme.ts`):
  sem ela, o passo **Lint** que o PR #245 adiciona deixa a `main` vermelha assim que ele chegar
  lá — o `eslint .` acusava 1 erro de prettier.
  *Verificado:* `main` mesclada — `vitest` 401/401, `eslint .` sem erro, `tsc --noEmit` sem erro,
  `vite build` ok; e o **PR #246 passou a rodar a CI de verdade** (job `verificar` verde), o que
  prova que a linha da `main` volta a ser validada.
  *Nota de operação (front):* todos os PRs abertos estão `MERGEABLE`; o `BLOCKED` que o GitHub
  mostra em cada um é só o **pedido de 1 aprovação** que a proteção das branches exige (ninguém
  mais está exigindo check de status).
- **[x] ENTREGUE 2026-10-25 — O plano dizia que o OpenAPI estava plugado: não está, e o pipeline
  do front estava quebrado em três pontos.** O ROADMAP afirmava que o springdoc estava no
  `pom.xml` (`springdoc.api-docs.path=/api/openapi.json`, ligado por `SPRINGDOC_ENABLED`) e que só
  faltava o passo da esteira. **Conferido no código: o springdoc saiu no `pom.xml` no lote de
  segurança (`302b7690`)** — incompatível com o Boot 4 e a UI dele expunha a superfície inteira
  da API. Não existe `/openapi.json`: o caminho responde como qualquer rota desconhecida (200 sem
  corpo). O mesmo evento apagou do `OperationalController` o único registro de que o documento
  existia, e o front ficou com um pipeline que não podia funcionar:
  1. o `specs/openapi.json` commitado é um **esqueleto** — zero rotas, mas com cara de documento;
  2. o gerador importava `generate` do `@orval/core`, que **não existe** na versão travada do lock
     (7.21.0 — o `generate` está no pacote `orval`): `npm run generate:api` quebrava com
     "generate is not a function";
  3. mesmo com um documento em mãos, a URL buscada (`/api/openapi.json`) não responderia nada.
  *O que foi feito.* **No back:** o comentário do `OperationalController` passou a dizer a verdade
  (não há OpenAPI, não há `SPRINGDOC_ENABLED` que ligue algo) e o índice `GET /api/v1` — que é o
  contrato legível por máquina, montado a partir do roteador — passou a declarar em texto que o
  documento OpenAPI não existe e onde está o contrato (`/developers` do app e o próprio índice).
  *No front:* o `specs/openapi.json` se declara esqueleto e aponta para o contrato real (com
  `x-enois`); os scripts de fetch e de geração **recusam o esqueleto** com mensagem que aponta o
  caminho (em vez de pilha de erro do orval); o gerador passou a usar a CLI (que funciona na
  versão travada) e `scripts/**/*.ts` entrou no `tsc` (antes o typecheck não via os scripts).
  *E a lacuna que mais importava:* a matriz documentada da página `/developers` (157 pares) era
  conferida **à mão**, em roteiro de auditoria — ou seja, uma vez por rodada, e cada_ENDPOINT novo
  podia deixá-la velha. Agora é código: `npm run check:api-matrix` busca o índice ao vivo e
  compara par a par (path e método, com variável de caminho casada por segmento como o roteador
  faz). *Verificado contra a QA:* 157 pares documentados × 494 rotas do índice — tudo bate.
  *Testes:* `src/lib/developers/api-matrix-check.test.ts` (8) e `src/lib/developers/openapi-spec.test.ts`
  (5) no front; `V1ApiAliasTests` (11, era 9) no back — o índice afirma que não há OpenAPI e o
  backend não serve documento nem em `/openapi.json`.
  *Verificado:* front `vitest` 414/414, `eslint` limpo, `tsc --noEmit` limpo (com os scripts
  dentro), `vite build` ok; back `V1ApiAliasTests` 11/11.
  Front: #247 (`fix/contrato-publico-da-api`, sobre a `qa`). Back: #181 (mesmo branch).
  *O que continua aberto (não é código meu):* gerar um OpenAPI de verdade exige um documento
  gerado e revisado (escrever à mão não é caminho) e um passo na esteira — que é do raposo.
- **[x] ENTREGUE 2026-10-25 — Data e hora sem fuso: o React estava regenerando a página inteira
  na hora que ela abre.** O item estava aberto desde 2026-10-18 (a página do blog dava erro de
  hidratação #418 em produção) e era **maior do que parecia**: 45 pontos do front formatavam data
  direto no componente (`toLocaleDateString`, `toLocaleString`, `Intl.DateTimeFormat`), sem
  `timeZone`, e os **getters locais** (`getFullYear`, `getMonth`, `getDate`, `getTimezoneOffset`)
  lêm no fuso do *runtime*. O pod roda em UTC e o navegador da pessoa roda no fuso dela: o servidor
  escrevia "23 de outubro" e o navegador "24 de outubro", o HTML do servidor não batia com o do
  cliente e o React cuspia *"Hydration failed because the server rendered text didn't match the
  client"* — que faz o React **regenerar a árvore inteira** no cliente (a mesma família do
  "cabeçalho pisca"). Para quem está em São Paulo, a janela em que isso acontece é **todo dia
  entre 00h e 03h (UTC)** — ou seja, boa parte da noite.
  *Como foi provado:* Chrome headless com o dev server em UTC (o fuso do pod) e o navegador em
  `Pacific/Kiritimati` (via `Emulation.setTimezoneOverride`): a página do blog acusou o erro de
  hidratação; com o conserto, **zero** erros de console em quatro telas (blog, post, comunidades,
  vagas).
  *O que foi feito:* um módulo único (`src/lib/date/format.ts`) formata toda data **no fuso do
  app** (`America/Sao_Paulo`) — servidor e navegador dizem a mesma coisa, qualquer que seja o fuso
  de quem está olhando. Data **sem fuso** é lida como dia de calendário (e não como o dia
  anterior, que é o que `new Date("2026-10-18")` fazia). Os 45 pontos passaram a usar o módulo,
  inclusive os getters: o ano do rodapé, o ano mínimo do campo de nascimento, a contagem de meses
  do currículo e o valor do campo `datetime-local` (agendamento) também divergiam.
  *A regra ficou travada:* `src/lib/date/formato-unico.test.ts` falha se alguém formatar data fora
  do módulo — e o teste **falha** quando a regra é quebrada (provado: a regra foi quebrada de
  propósito e o teste acusou). As duas exceções (`night-rest.ts` e `min-age.ts`) são
  intencionais: são do **aparelho** de quem está usando e só rodam no cliente, depois da montagem.
  *Testes:* `src/lib/date/format.test.ts` (11) — o mesmo instante formatado com o runtime em três
  fusos (UTC, São Paulo, Tóquio) dá **a mesma saída**; data sem fuso é o dia de calendário; e
  `formato-unico.test.ts` (2).
  *Verificado:* `vitest` 414/414, `eslint` limpo nos arquivos tocados, `tsc --noEmit` sem erro,
  `vite build` ok, e a reprodução no Chrome headless sem erro de hidratação.
  Front: #248 (`fix/data-sem-fuso`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-24 — O app oferecia login com Google mesmo num ambiente onde a API o
  desliga.** A API tem o flag `rede.oauth.google.enabled`: quando está desligado, `/auth/config`
  avisa (`googleLoginEnabled: false`) e `/oauth/google/start` **recusa** com 403
  `OAUTH_PROVIDER_DISABLED`. O front **ignorava esse campo** — o tipo `AuthConfig` nem o declarava
  e a tela de entrada desenha o botão do Google em código fixo. Na prática, desligar o Google no
  ambiente não tirava o botão da frente da pessoa: ela clicava e só recebia um erro (e a
  mensagem que aparecia era a do Google mesmo quando a falha era do LinkedIn).
  *Como o problema foi achado:* as **382 chamadas de API do front** foram extraídas do código e
  os **tipos de resposta** (em `src/types/**`) comparados com o JSON **real** que a API devolve —
  foi assim que apareceu o `googleLoginEnabled` que a API entrega e o front não tipa. (O mesmo
  cruzamento achou, na rodada anterior, a rota `/admin/carteira/supply` faltando na API.)
  *O que foi feito:* `AuthConfig` declara `googleLoginEnabled`; `availableSocialProviders()`
  (`src/constants/auth-providers.ts`) decide o que a tela oferece — o Google só entra na lista
  quando a API diz que está ligado (a mesma regra do captcha, que também espera a configuração) —
  e o botão deixou de ser escrito em código fixo. A falha da entrada social passou a citar o
  provedor certo, e a recusa da API (`OAUTH_PROVIDER_DISABLED`) ganha a mensagem dela, que é mais
  útil que o texto genérico.
  *Testes:* `src/constants/auth-providers.test.ts` (7) — o Google some quando a API diz que está
  desligado, aparece quando diz que está ligado, não aparece antes da resposta da API, os demais
  provedores continuam, o tipo declara o campo (trava do contrato) e a tela consulta a config.
  *Verificado:* `vitest` 390/390, `eslint` limpo nos arquivos tocados, `tsc` sem erro NOVO (o
  `originBlocked` de `developer-origins-section.tsx` já existia na base), `vite build` ok, e a tela
  conferida no Chrome headless a 390px e 1280px nos dois estados do flag (sem overflow horizontal).
  Front: `6fc43bdd` (branch `fix/login-google-desligado`, sobre a `qa`).
  *Fica de fora (decisão de produto):* o diálogo "contas vinculadas" continua oferecendo o Google
  mesmo com o flag desligado — a API **não** recusa o vínculo (só o `/start` do login), então ali
  não há contrato quebrado.
- **[x] ENTREGUE 2026-10-21 — A tela de entrada parou de afirmar o que o produto não tem.**
  O painel de marca do login (e do reset de senha, que tinha o mesmo bloco) trazia **números
  fixos no texto** — "120k+ profissionais", "2.8k mentores ativos", "640 comunidades" — numa
  rede com **2.275 contas** (é o que o próprio `/stats/community` responde). E trazia um
  **depoimento inventado**, atribuído a "Marina Rocha, Engenheira Sênior · Nubank": pessoa
  que não existe, com o nome de uma empresa real.
  Agora o painel usa os **números de verdade** (mesma fonte da landing, cache de ~10 min) e a
  frase é a tese do produto. Se a consulta falhar, o painel fica **sem número** em vez de voltar
  a inventar um. O bloco duplicado nas duas rotas virou o componente `AuthHero`.
  *Achado pela auditoria de responsividade* (Chrome headless a 1280px). Front: `c8ac304d`.
- **[x] ENTREGUE 2026-10-21 — As telas de erro do app estavam em inglês.** A página 404 e a
  *error boundary* do root (a que aparece quando qualquer rota quebra) falavam inglês: "Page not
  found", "Try again", "Go home" — num produto que fala português do começo ao fim. As rotas com
  `notFoundComponent` próprio (perfil, blog, vaga) já estavam em português; o root ficou para trás.
  A varredura também achou a categoria de tema "Save points" (as outras cinco são em português).
  Agora: "Página não encontrada" / "Esta página não carregou" / "Tentar de novo" / "Voltar para o
  início" / "Pontos de save". `src/texts.test.ts` trava a regra — varre todos os valores de
  `texts` e falha se algum tiver palavra inequivocamente inglesa (o português não tem "the",
  "your", "try again"); o teste **falha** se o texto em inglês voltar.
  *Achado pela auditoria de responsividade* (Chrome headless a 390px): uma URL inexistente caía
  na 404 em inglês. Front: `0aaca5b8`.
- **[x] ENTREGUE 2026-10-21 — Front com lint limpo e build que nao quebra no `npm install`.**
  O `eslint` do front acusava 53 erros e a CI nao roda lint, entoa divida acumulou em silencio
  (regra `local/type-boundaries`: um tipo por arquivo em `src/types/**`). Os tipos que viviam
  fora de `src/types/**` (matriz da API em `developers-api.ts`, tipos do jogo de palavras, tipos
  de moderacao) foram para a casa canonica; `SessionPayload` e `ThemeGalleryItem` ja tinham
  arquivo proprio e passaram a importar dele. Tambem sumiram dois statements mortos do
  ImageKit em `src/lib/api/media.ts` (chave publica do ImageKit, morta desde o S3).
  *Verificado:* `eslint .` de 53 erros para 0 (4 avisos preexistentes), `tsc --noEmit` sem erro,
  `vitest` 273/273, `vite build` ok. Alem disso, `@tanstack/react-start` estava com `^` e o
  `npm install` resolvia 1.169.x (exige vite >= 8): o build quebrava com "Could not resolve entry
  module index.html" e o proprio `npm install` falhava com ERESOLVE. As tres versoes do
  `@tanstack` foram travadas na que o lock ja resolvia (react-start 1.168.22, react-router
  1.170.13, router-plugin 1.168.16) e o `npm install` puro voltou a resolver.
  Front: `847adfc8` (branch `fix/lint-higiene-front`).
- **[x] ENTREGUE 2026-10-21 — O manifesto do PWA tinha dois donos, e o vivo era o congelado.**
  `/manifest.webmanifest` (o arquivo que o navegador lê para montar o pedido de instalação do
  app) era servido por **duas** fontes: o arquivo estatico `public/manifest.webmanifest` e a
  rota `src/routes/manifest[.]webmanifest.ts`. O estatico ganha (o servidor responde `public/`
  antes de chegar na rota), entao a rota era **codigo morto** — foi la que o texto novo da
  identidade foi escrito na passagem anterior e nunca chegava no navegador. Em producao o
  manifesto ainda dizia "Rede profissional para conexões autenticas: vagas, mentoria,
  comunidades e eventos", com `categories` "business"/"productivity" e os icones da outra
  familia. Agora a rota foi apagada, o arquivo estatico e o dono unico, a frase vem de
  `texts.pwa.manifestDescription` e as categorias sao "social"/"lifestyle"/"games".
  O novo `src/pwa-manifest.test.ts` (6 verificacoes) amarra o JSON: a descricao tem de ser a
  mesma do `texts.ts`, nenhum icone apontado pode faltar no disco, o tamanho declarado tem de
  bater com o nome do arquivo e a cor de fundo do splash tem de ser o token `--background` do
  app (o `--background` e `#faf9ff`; a rota morta usava `#f9f8ff`, que e o `--surface-1`).
  *Verificado:* `Page.getAppManifest` no Chrome headless devolve **0 erros** e a descricao nova;
  `curl` de producao confirma que o estatico e o que responde.
- **[x] ENTREGUE 2026-10-21 — As superficies de entrada ainda falavam em rede profissional.**
  A varredura das telas que a pessoa ve na entrada (login, reset de senha, convite de instalar
  o app, rodape, manifesto) achou a frase antiga em tres lugares: a descricao de SEO do login
  ("comece sua jornada profissional na Einois"), o copyright do rodape ("Rede profissional
  gratuita e humana") e o manifesto do PWA. E achou **6 deslizes de artigo da marca**: a marca e
  "**o** Einois" (135 usos no front contra 6 no feminino), mas o reset de senha dizia "sua conta
  na Einois" e o convite de instalar dizia "Instale o app da Einois". Agora: "Acesse sua conta ou
  entre para o Einois, uma rede humana feita por gente", "© {ano} Einois · Rede humana gratuita",
  "Defina uma nova senha para sua conta no Einois" e "Instale o app do Einois".
  O `src/texts.test.ts` ganhou duas travas: "nao chama o Einois de rede profissional" (a frase
  inteira, porque "perfil profissional" continua existindo — e e a feature do alter ego
  corporativo) e "usa o artigo masculino da marca".
  *Verificado:* `vitest` 342/342, `tsc --noEmit` sem erro, `eslint` limpo, `vite build` ok, e o
  HTML do SSR do login ja entrega a descricao nova.
- **[x] ENTREGUE 2026-10-26 — A varredura de responsividade ficou completa: nenhuma rota do app
  ficou sem medir.** O que faltava eram as **7 telas protegidas** (moderação, denúncias,
  penalidades, meus-serviços, perfil profissional, painel de projetos, bloqueados): elas exigem
  sessão de administrador, e a sessão da QA estava morta. Quebrou-se o impasse com o backend local
  na bancada MariaDB (`enois-bench66`) e a allowlist de admin por variável
  (`REDE_ADMIN_BOOTSTRAP_ENABLED` + `REDE_ADMIN_EMAILS`) — sem tocar no banco de nenhum ambiente.
  *O que foi medido (Chrome headless, 390px e 1280px, sessão injetada):* as 7 telas protegidas e
  mais as que exigem login (feed, mensagens, salvos, carteira, configurações, segurança, minhas
  vagas) — **overflow horizontal 0 em todas**. Também foram medidas as telas novas do dia (grupos,
  loja, marketplace, jogos diários, trilha, notificações): limpas nas duas larguras.
  *Como a sessão foi obtida:* cadastro local → confirmação de e-mail gravando o hash do token na
  bancada (o e-mail não sai sem SMTP) → `POST /auth/email/verify` devolve a sessão; na bancada
  foram semeados uma denúncia e uma sanção para as tabelas de moderação terem conteúdo.
  *Fora do escopo (externo):* Turnstile em produção, CORS do bucket S3 e a aprovação do app do
  LinkedIn.
- **[x] ENTREGUE 2026-10-27 — O painel de contribuintes (aba nova do `/moderador`) levava a
  página a rolar de lado no celular: um e-mail longo estourava 40px.** A varredura acima mediu
  as telas do dia; a aba "Contribuintes" do painel de moderadores entrou depois e mostra o
  contato de quem se candidatou — login do GitHub e e-mail. São texto do usuário e não têm
  espaço em que quebrar: como item de flex, o `min-width: auto` deles é o **min-content** do
  próprio e-mail, ou seja, o e-mail inteiro.
  *Medido (Chrome headless, 390px, DOM real do painel com o CSS compilado da QA):* o link de
  e-mail ficava com 401px numa tela de 390px — **40px de transbordo**. A 1280px nada estourava.
  *O conserto:* `min-w-0` + `break-all` nos dois links (o `break-words` sozinho não resolve,
  porque não muda o cálculo do min-content) e `shrink-0` nos ícones, para o ícone não ser
  espremido quando o texto quebra em duas linhas. Depois: **0px de transbordo** a 390px, e a
  1280px continua igual.
  *Teste:* `src/lib/layout-contato.test.ts` (3) — trava a regra nos dois links e nos ícones;
  **sem o conserto os 3 falham**. `vitest` da linha `qa` sem regressão (as 10 falhas de arquivo
  e os 2 testes quebrados são o pacote privado `@rede-enois/design-system`, que não resolve
  neste host — o CI da PR resolve com o token do registry).
  Front: PR #338 (branch `fix/contato-transborda-celular`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-27 — Um login (ou nome) longo sem espaços levava a página a rolar de
  lado no celular: 34px de transbordo em 9 telas.** O `username` é o slug do nome — troca cada
  espaço por hífen, mas quem se cadastra com o nome colado ("MariaAparecidaConceicao…") fica
  com um login sem hífen nenhum, e aí não existe ponto de quebra. Onde o login é mostrado num
  bloco, a palavra estoura a caixa; onde é item de flex, o `min-width: auto` dele é o
  min-content do login inteiro e o item não encolhe: o mesmo estouro, com a forma diferente.
  *Medido (Chrome headless, 390px, DOM real e CSS compilado da QA):* a lista de freelas
  (`/freelances`) e a fila de notas do moderador estouravam **34px** com um login de 72
  caracteres sem hífen — e o formulário de cadastro aceita nome de até 80, ou seja, é dado real.
  A 1280px nada estourava.
  *O conserto:* `break-words` nos 9 lugares onde o app mostra o login de uma pessoa (freelas,
  fila de notas, fila de jogos, fila de projetos, triagem de post, perfil profissional, perfil de
  quem faz jogos, lista de contas suspeitas e o nome do candidato no painel de contribuintes) e
  `min-w-0` nos dois que são item de flex. Depois: **0px de transbordo** nas duas telas medidas.
  *Teste:* `src/lib/layout-username.test.ts` (9) — trava a regra em cada uma das 9 telas; **sem
  o conserto os 9 falham**. `vitest` da linha `qa` sem regressão e `tsc` sem erro novo (472 =
  472, todos do pacote privado `@rede-enois/design-system`).
  Front: PR #339 (branch `fix/login-longo-quebra-celular`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-27 — Título ou descrição do usuário escrita sem espaços estourava a
  tela em até 586px — e a correção é de raiz, no CSS do app.** O item acima consertou o *login*;
  o mesmo problema aparece com **qualquer texto** que a pessoa escreva colado: título de jogo,
  nome de projeto, assunto de ticket, título de PR, descrição. Sem ponto de quebra, a palavra
  estoura a caixa e a **página inteira passa a rolar de lado no celular**.
  *Medido (Chrome headless, 390px e 1280px, DOM real e CSS compilado da QA):* a fila de jogos
  (+440px), a de projetos (+520px a 390px e +6px a 1280px), a de pull requests (+438px), a de
  contribuições do GitHub (+438px) e a de tickets de suporte (+438px) do painel de moderador.
  *O conserto:* `overflow-wrap: break-word` nos elementos de texto do `src/styles.css`, na
  camada `base` — assim as utilities `truncate`, `line-clamp` e `break-all` continuam valendo
  quando o app quer cortar o texto de propósito. É melhor do que repetir `break-words` em cada
  componente: o texto do usuário aparece em dezenas de lugares e qualquer um deles pode
  receber a palavra colada.
  *Prova de que a regra não mexe no visual:* com **dado curto** (com espaços), a geometria de
  **nenhum** elemento de texto muda em **44 telas** medidas (as 22 rotas principais a 390px e a
  1280px) — a regra só age quando a palavra realmente não cabe na linha. Com o dado adversarial
  (palavra colada), os 6 transbordos vão a **0px**.
  *Teste:* `src/lib/layout-texto-longo.test.ts` (4) — trava a regra no CSS; **sem o conserto os
  3 casos de conteúdo falham**. `vitest` da linha `qa` sem regressão e `tsc` sem erro novo
  (472 = 472, todos do pacote privado `@rede-enois/design-system`).
  Front: PR #348 (branch `fix/texto-longo-nao-estoura`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-28 — O dock de mensagens (novo) deixava a mini-janela de conversa
  FORA da tela, com o botão de fechar inalcançável.** O dock é ancorado à direita e cresce para
  a **esquerda**: cada mini-janela tem 22rem (352px), o painel tem 20rem (320px), o espaço entre
  eles é 8px e a margem da direita é 16px. O estado aceita até 4 janelas abertas
  (`MAX_OPEN_CHATS`), mas a largura da tela quase nunca comporta isso — e a janela mais à
  esquerda **saía da tela**, levando o botão de fechar junto.
  *Medido (Chrome headless, DOM real e CSS compilado da QA, medindo a borda esquerda de cada
  janela):* a 1024px com 2 janelas a primeira começava em **-32px** e com 3 em **-392px**; a
  1280px com 4 janelas, em **-496px**.
  *O conserto:* o dock desenha só as janelas que cabem — a largura da janela do navegador é
  medida em tempo de execução (`useLarguraDaJanela`) e o teto vem de
  `src/lib/chat-dock/chat-dock-layout.ts` (1024px → 1 janela, 1280 → 2, 1440 → 3, 1920 → 4).
  As conversas **continuam abertas no estado**: elas não são fechadas, apenas não são
  desenhadas fora da tela.
  *Testes:* `src/lib/chat-dock/chat-dock-layout.test.ts` (9) e `chat-dock-window.test.ts` (3) —
  o segundo **falha 3/3 sem o conserto**; mais `src/stores/chat-dock.store.test.ts` (5) para o
  caso que a revisão achou (abrir uma conversa que já estava aberta e ficou fora do desenho:
  agora ela **volta para a frente** ao ser clicada, senão ficaria marcada como aberta sem janela
  e sem botão de fechar). `vitest` da linha `qa` sem regressão e `tsc` sem erro novo
  (472 = 472, todos do pacote privado `@rede-enois/design-system`).
  *Verificado no ar:* com o front #349 mergeado na `qa`, a 1024px com 2 conversas abertas só a
  que cabe é desenhada (borda esquerda em 328px, dentro da tela — antes era -32px) e a 1280px
  cabem duas; e clicar numa conversa que ficou fora do desenho a **traz para a frente** (o
  estado passa a `[conv_1, conv_2]` e a janela dela passa a ser desenhada).
  Front: PR #349 (branch `fix/dock-chat-nao-sai-da-tela`, sobre a `qa`).
- **[x] ENTREGUE 2026-10-28 — Ativar o E2E numa conta sem senha trocava a senha ANTES de
  guardar a chave — e a troca encerra a sessão, então o upload saía com 401.** O banner de
  ativação da criptografia ponta a ponta (`E2eSetupBanner`, no topo do chat) tem dois caminhos:
  conta **com** senha (pede a senha e guarda o keypair) e conta **sem** senha, de login social
  (define a senha e guarda o keypair). No segundo, a ordem estava errada: a senha era trocada
  primeiro e o keypair subia depois — mas trocar a senha **encerra todas as sessões** da conta
  (`AuthService.changePassword` → `revokeAllByUser`), inclusive a que está em uso.
  *Confirmado no log do backend:* `POST /auth/password/change` → 200 e, na sequência,
  `POST /e2e/keypair` → **401**. O banner caía em "Não foi possível definir a senha" e a pessoa
  ficava **sem chave E com a senha já trocada** — na volta ao login o banner aparecia de novo e
  o E2E não podia ser ativado.
  *O conserto (front #350):* o keypair é gerado, cifrado com a senha nova e guardado **antes**
  da troca; a sessão local é limpa depois (a troca a encerrou no servidor) e a pessoa entra de
  novo com a senha nova — o mesmo que acontece em qualquer troca de senha do app. A ordem ficou
  num módulo próprio (`src/lib/e2e/ativar-e2e.ts`) para poder ser testada.
  *Testes:* front `src/lib/e2e/ativar-e2e.test.ts` (4 — a ordem `keypair` → `senha` → `limpa`
  **falha com a ordem antiga**) e back `PasswordChangeEndsSessionTests` (3 — a troca derruba a
  sessão, o upload falha depois dela, a conta social que cria a primeira senha também perde a
  sessão).
  *Entregue:* front #350 e back #287 mergeados na `qa` (a regra do backend — trocar a senha
  encerra as sessões — é intencional e vale para qualquer troca; o que mudou foi a ordem no
  front).
- **[x] ENTREGUE 2026-10-27 — Auditoria de contrato da linha `qa` (front x API): nenhuma
  divergência.** O que a lista do repo já tinha contado antes foi reconferido no código de
  hoje: as **427 chamadas do front** (`src/lib/api/**`) contra as **505 rotas** do backend da
  linha `qa` — todo caminho tem par; os **tipos de campo** dos módulos novos (selos de
  contribuição, painel de contribuintes, PRs, notas, LinkedIn, perfil do GitHub) batem registro a
  registro com os `record` do backend; os **33 `href` de notificação** que o backend produz
  estão todos no allowlist de `src/utils/navigation.ts` (fora dele o clique é mudo); as **18
  abas** do `/moderador` têm componente e nenhuma é botão morto; e as rotas do router
  (`src/routes/**`) batem com os links internos.
  *Também foram medidas as telas novas a 390px e 1280px na QA no ar (Chrome headless): as 32
  rotas públicas (landing, Apoiar, Selos, perfil, profissional, blogs, grupos, vagas, empresas,
  serviços, carteira, diretrizes, jogos, marketplace, developers, projetos, etc.) — overflow
  horizontal 0 em todas. A única com transbordo era a aba "Contribuintes" do painel, corrigida
  acima (o painel é protegido: a medição usou a API interceptada no Chrome, com o DOM e o CSS
  reais da QA).
- **[x] ENTREGUE 2026-10-22 — As abas da caixa de entrada cortavam no celular.** Com a quinta aba
  ("Serviços", que veio do chat de serviço), a faixa de abas da caixa de entrada ficava sem
  respiro a 390px e cortava "Arquivadas" na borda a 360px — cortada sem nenhuma pista de que
  havia mais. Agora a faixa quebra a linha no celular (`flex-wrap`) e as cinco abas continuam
  visíveis em 360, 390 e 1280px.
  *Achado pela verificação de responsividade* (Chrome headless com sessão, backend local):
  overflow horizontal 0 e nenhuma aba cortada nas três larguras; o card de serviço também foi
  conferido com dado real (nome do prestador, foto, preço em reais e em faíscas, os dois botões
  lado a lado). Front: `a seguir`.
- **[x] ENTREGUE 2026-10-22 — O preview de rede social das rotas continua genérico: agora cada
  página mostra a si mesma quando alguém compartilha o link.** Só 4 rotas definiam
  `og:title`/`og:description` (landing, post, Apoiar, root). As outras 12 que têm `description`
  (login, reset, sobre, termos, privacidade, diretrizes, feed, blogs, developers, perfil
  profissional, projetos, responsável) eram compartilhadas com a cópia do site inteiro
  ("Énóis · Rede humana" / "Construa relações de verdade, não apenas conexões"), então o preview
  de quem abria o link no WhatsApp ou no X não era da página.
  *Decidido e feito:* a `description` de cada rota vira também o `og:description`, com o título
  próprio da rota no `og:title` (o helper `src/lib/seo.ts` — `ogMetas(titulo, descricao)`). As
  rotas que têm imagem própria (post, blog, perfil) continuam definindo o `og:image` delas; as
  demais herdam a do root.
  *Teste:* `src/lib/seo.test.ts` (2) — o helper devolve o par certo e **as 12 rotas** com título e
  descrição têm o `og:title`/`og:description` da própria página (o teste falha se uma rota
  voltar a herdar a cópia do site).
  *Verificado no HTML do SSR:* `/sobre` entrega `<title>Sobre o énóis · …</title>` com o
  `og:title` e o `og:description` da própria página.

## Regras que valem para tudo
- **Proibido automatizar a criação/publicação de posts** (IA/bots/integrações/endpoints alternativos/
  tarefas agendadas/webhooks). **Permitido** agendar pela funcionalidade oficial. Ver `PUBLISHING_POLICY.md`.
- Publicar vaga exige **identidade verificada** + **credibilidade ≥ 150**, conferido no backend.
- Nada de simulação de integração oficial; onde faltar credencial, documentar e falhar seguro.
