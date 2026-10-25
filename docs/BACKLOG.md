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
- **[x] ENTREGUE 2026-10-24 — O card da comunidade mostrava o CÓDIGO do assunto ("TECNOLOGIA") em
  vez da palavra, e três tipos do front diziam que um campo nunca era nulo quando a API anula.**
  A API devolve o assunto como código (`"TECNOLOGIA"`, `"CARREIRA"`) e o app tem o dicionário
  `TOPIC_LABELS`, que o filtro de assuntos e o card do post já usavam — mas o card da comunidade e a
  página de detalhe renderizavam o código cru. Na mesma tela, o chip dizia "Tecnologia" e o card
  dizia "TECNOLOGIA" (confirmado na QA).
  *O que foi feito:* `topicLabel()` (`src/constants/community-topics.ts`) traduz o código na
  palavra, e os três lugares (card da comunidade, prateleira do perfil e página da comunidade) passam
  a usá-lo. Verificado no navegador depois da mudança: o card mostra "Engraçado"/"Tecnologia" e não
  sobra nenhum código cru na tela.
  *E os tipos:* três campos são anuláveis na API e o front afirmava o contrário — `Community.topic`
  (`null` na comunidade sem assunto), `Mentor.nextSlot` (`null` quando o mentor não tem vaga livre)
  e `TimeSlot.startsAt`/`endsAt` (`null` no slot sem horário). Medido na QA: 2 dos 5 mentores da
  lista vinham com `nextSlot: null` e a primeira comunidade com `topic: null`; o backend anula os
  dois explicitamente (`orElse(null)` e `startsAt() != null ? ... : null`). E o `sessionId` da
  resposta de agendar sessão: **a API nunca mandou esse nome** (ela devolve o `id`).
  *Como foi achado:* 30 endpoints da API comparados com os tipos do front, com dado real da QA
  (~300 campos) — estes foram os que mentiam. A lista de assuntos do front e do backend bate (34 nos
  dois lados).
  *Teste:* `src/constants/community-topics.test.ts` (5) — o rótulo traduz o código, comunidade sem
  assunto não mostra nada, assunto desconhecido cai no próprio código, e as telas não podem voltar
  a renderizar o código cru.
  *Verificado:* `vitest` 394/394, `eslint` sem erro novo (15 = os 15 da base), `tsc --noEmit` sem
  erro, `vite build` ok.
  Front: `1be8c186` (branch `fix/mentor-tipo-null`, sobre a `qa`).
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
