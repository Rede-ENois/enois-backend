# Processo seletivo (ATS) — pipeline de recrutamento por vaga

Estado: implementado em 2026-11-12 e na `qa` (PR #677 mergeado); declaração de recrutador
acrescentada em 2026-11-13; tabela paginada acrescentada em 2026-11-13 (V291); questionário
condicional e rascunho acrescentados em 2026-11-15 (V330).

Complementa `docs/JOB_AGGREGATOR.md` (vagas internas e externas) e
`docs/SOCIAL_PROFESSIONAL_CONTEXTS.md` (identidade profissional). Não cria um segundo sistema
de vagas nem de candidaturas: estende `jobs` / `job_applications` com o que faltava para
conduzir o processo seletivo dentro do Énóis.

## O que existia e o que mudou

| Peça | Antes | Agora |
|---|---|---|
| Estado da candidatura | um estado só (`APPLIED` → `IN_REVIEW` → `ADVANCED` / `NOT_SELECTED`) | o estado clássico continua; o pipeline é a fonte da etapa |
| Etapas do processo | não existiam | `selection_stages`, por vaga, com nome e ordem |
| Histórico | só o estado atual | `stage_movements`: quem moveu, de onde para onde, quando, por quê |
| Notas da equipe | não existiam | `stage_notes`, internas — nunca devolvidas ao candidato |
| Visão do recrutador | lista de candidaturas | board por etapa + Central de recrutamento com o funil |

A vaga, a candidatura, a entrevista (`docs` de vagas), as mensagens e as notificações são as
mesmas de antes. Nada de segunda vaga, segunda candidatura ou segundo mensageiro.

## Modelo

- **`selection_stages`** — `(id, job_id, name, position, created_at)`. Cada vaga tem o próprio
  fluxo; não existe conjunto global. Nome é único dentro da vaga. Máximo de 15 etapas.
- **`stage_movements`** — `(id, application_id, job_id, from_stage_id, to_stage_id, kind,
  actor_id, note, moved_at)`, com `kind ∈ {ADVANCE, RETURN, REJECT}` e FK para a candidatura.
  A **etapa atual é o destino do último movimento**; candidatura sem movimento está na
  entrada do fluxo (primeira etapa). Uma fonte de verdade, não um campo solto.
- **`stage_notes`** — `(id, application_id, author_id, body, created_at)`, FK para a
  candidatura. Existe para a equipe; não é exposta ao candidato por nenhuma rota.

Reprovar (`REJECT`) exige motivo não vazio. O motivo vai para o movimento e para o campo
`feedback` da candidatura (que o candidato já vê), e o estado vira `NOT_SELECTED`. Candidatura
reprovada não se move mais.

## Endpoints (todos sob `/jobs`)

| Rota | Quem | O que faz |
|---|---|---|
| `GET /jobs/{jobId}/process/stages` | dono da vaga | fluxo de etapas, na ordem |
| `POST /jobs/{jobId}/process/stages` | dono | cria etapa (`{"name": ...}`) |
| `PATCH /jobs/{jobId}/process/stages/{stageId}` | dono | renomeia (identidade preservada) |
| `DELETE /jobs/{jobId}/process/stages/{stageId}` | dono | apaga etapa vazia; etapa ocupada → 409 `STAGE_IN_USE` |
| `POST /jobs/{jobId}/process/stages/order` | dono | reordena (`{"stageIds": [...]}`, todas as etapas) |
| `GET /jobs/{jobId}/process` | dono | board: etapas, colunas por etapa, reprovadas, resumo |
| `GET /jobs/{jobId}/process/applications` | dono | **página** de candidaturas: cursor, filtro e busca |
| `POST /jobs/{jobId}/process/applications/{applicationId}/move` | dono | `{"stageId","kind","note"}` → movimento |
| `GET /jobs/{jobId}/process/applications/{applicationId}/movements` | dono | histórico em ordem |
| `GET /jobs/{jobId}/process/applications/{applicationId}/notes` | dono | notas internas |
| `POST /jobs/{jobId}/process/applications/{applicationId}/notes` | dono | cria nota (`{"body": ...}`) |
| `GET /jobs/{jobId}/process/applications/{applicationId}/audit` | equipe (VIEW_CANDIDATES) | timeline de auditoria da candidatura |
| `GET /jobs/{jobId}/process/candidates` | equipe (VIEW_CANDIDATES) | fila de triagem com score explicável, ordenada |
| `POST /jobs/{jobId}/process/applications/batch-move` | equipe (TRIAGE) | move/reprova várias de uma vez, com desfecho por item |
| `GET /jobs/recruitment` | sessão | Central: minhas vagas com o funil de cada uma |

"Todo o fluxo de etapas" é configurável: criar, renomear, apagar (respeitando candidatos na
etapa), reordenar. Renomear preserva o id, então o histórico dos candidatos continua válido.

### Tabela paginada

`GET /jobs/{jobId}/process/applications` devolve **uma página** de candidaturas, do mais recente
para o mais antigo.

| Parâmetro | O que faz |
|---|---|
| `cursor` | o `nextCursor` da página anterior; a página começa depois dele |
| `limit` | linhas por página (1 a 100; padrão 30) |
| `status` | `APPLIED`, `IN_REVIEW`, `ADVANCED` ou `NOT_SELECTED` |
| `search` | casa o texto do retrato do candidato e da mensagem |

A resposta é `{ "items": [...], "nextCursor": "..." }`; o cursor só vem quando a página veio
cheia, e a última página não devolve cursor — é o que faz o cliente parar.

Por que isso importa: antes, o board trazia a vaga inteira e cortava no teto (1000), o que
tornava impossível ver a candidatura de número 1001 e recarregava tudo a cada rolagem. Agora a
página sai do banco por **cursor keyset** — `(applied_at, id)`, do mais novo para o mais velho —
com o filtro e a busca aplicados **na consulta**. O `id` entra como desempate porque a coluna
guarda segundos: duas candidaturas podem cair no mesmo segundo, e paginar só pelo instante
estrito pularia ou repetiria linha empatada. Índice composed em `V291`
(`job_applications (job_id, applied_at, id)`).

A etapa de cada linha vem de uma consulta só, para as candidaturas **da página** — pedir o
histórico da vaga inteira desfaria a paginação.

`status` desconhecido é **400 `INVALID_APPLICATION_STATUS`** e cursor inválido é **400
`INVALID_CURSOR`**: entrada errada é recusa explícita, não lista vazia que parece "não tem
candidato".

### Declaração de recrutador

O perfil profissional ganhou `isRecruiter` (`users.is_recruiter`, V290) — o "Sou recrutador/a"
da edição de perfil, aceito por `PATCH /me/profile` e devolvido no perfil próprio.

Ler a Central (`GET /jobs/recruitment`) exige **uma das duas coisas**: a declaração ligada ou
pelo menos uma vaga publicada por quem está na sessão. Quem publica vaga é recrutador por
definição, então o fluxo que já existia continua funcionando sem nenhuma tela nova; quem não
recruta e não tem vaga recebe 403 `RECRUITER_REQUIRED` em vez de uma lista vazia que fingiria
um recurso que não é dela.

Desligar a declaração não apaga vaga, candidatura, etapa nem histórico: só fecha a Central para
quem não tem vaga. Reativar devolve o acesso ao que já estava lá.

## Autorização (no backend)

- Toda rota de processo exige sessão e quem comanda a vaga: **o autor** sempre e, quando a vaga
  nasceu numa página, a **equipe de recrutamento** dela conforme o papel
  (ver [COMPANY_PAGES.md](COMPANY_PAGES.md)). Quem não comanda recebe 403 `NOT_OWNER` — a mesma
  recusa de antes, então nenhuma resposta nova vaza.
- A candidatura é sempre resolvida **dentro da vaga da URL**: id de candidatura de outra vaga
  responde 404 `APPLICATION_NOT_FOUND`, sem vazar entre processos.
- A Central lista apenas as vagas cuja autoria é de quem está na sessão — não há parâmetro de
  "recrutador" vindo do cliente.
- Notas internas: só o dono da vaga; o próprio candidato recebe 403 e o texto não aparece em
  nenhuma resposta acessível a ele.

## Desempenho

- Board: movimentos da vaga em uma consulta (`findByJobIdOrderByMovedAtAsc`) + candidaturas da
  vaga; sem consulta por candidatura.
- Central: três consultas em lote (vagas, reprovadas por vaga, último movimento por
  candidatura, com `max(id) group by application_id`) — sem N+1 por vaga nem por candidato.
- Tabela paginada: uma consulta de ids por página (ordenada e cortada no banco) e uma para
  carregar só as linhas da página; o último movimento vem em uma consulta para as candidaturas
  pedidas. O `answers` da candidatura é `@ElementCollection` EAGER — por isso a página é
  montada em duas etapas, senão o Hibernate paginaria em memória.

## Provas

`SelectionProcessTests` (8 testes, integração com H2 e MockMvc):

- fluxo criado, nome repetido (409), reordenação completa e incompleta (422), candidatura sem
  movimento na entrada do board;
- avanço e retorno gravados em ordem, board seguindo o último movimento, `ALREADY_IN_STAGE`
  (409), etapa de outra vaga inalcançável (404);
- reprovação sem motivo (400), reprovação exigindo motivo, estado `NOT_SELECTED` e retorno
  visível em `/jobs/applications/mine`, candidatura reprovada não se move (409);
- notas internas: criação, nota vazia (422), terceiro e candidato recebem 403, texto ausente
  da resposta do candidato;
- autorização: 401 sem sessão, 403 para terceiro, 404 para candidatura de outra vaga e vaga
  inexistente;
- exclusão de etapa ocupada (409) e de etapa vazia com posições recontadas; renomear preserva
  o vínculo dos candidatos;
- espécie de movimento desconhecida é 400, não avanço silencioso;
- Central: funil por vaga, isolamento entre recrutadores, 401 sem sessão;
- declaração de recrutador: conta sem vaga e sem a declaração recebe 403 `RECRUITER_REQUIRED`,
  ligar a declaração abre a Central vazia e desligar volta a fechar; quem tem vaga continua
  entrando sem a declaração (o fluxo antigo não quebra).

`ApplicationBoardPageTests` (5 testes, integração com H2 e MockMvc):

- as 12 candidaturas de uma vaga aparecem **uma vez cada**, percorrendo o cursor até o fim (o
  teste falha sem o corte por cursor — a prova é que a implementação antiga traria as mesmas
  linhas em todas as páginas);
- a última página não devolve `nextCursor`, então o cliente para;
- filtro por estado devolve só quem está nele; a busca acha o candidato pelo nome do retrato e
  um termo que não casa ninguém devolve lista vazia, não a lista inteira;
- `status` desconhecido é 400 `INVALID_APPLICATION_STATUS` e cursor inválido é 400
  `INVALID_CURSOR`;
- terceiro recebe 403 `NOT_OWNER` e sem sessão 401.

`ApplicationEvaluationTests` (2 testes, integração com H2 e MockMvc):

- a avaliação sobe com critérios, veredito e resumo e volta na leitura; **reavaliar pelo mesmo autor
  grava por cima** (uma linha, não duas) e a leitura não devolve duplicata;
- critérios vazios, veredito fora do conjunto e resumo acima do teto são recusa explícita
  (`EVALUATION_*`), não gravação silenciosa.

`ApplicationAuditTimelineTests` (2 testes, integração com H2 e MockMvc):

- nota, avaliação e movimento real (avanço para a segunda etapa) geram três eventos na ordem
  inversa do tempo, com ator, alvo `job_application:{id}` e metadado dizendo o que mudou;
- candidato e terceiro recebem 403 e candidatura de outra vaga recebe 404 `APPLICATION_NOT_FOUND` —
  a timeline é interna e não vaza para quem não conduz a seleção.

## Quem conduz a seleção

Duas axes de papel convivem e não se confundem:

- **Papel na página** (`CompanyRole`: OWNER/ADMIN/EDITOR/RECRUITER) — vale para agir *em nome
  dela*: publicar vaga exige RECRUITER ou superior. Não conduz processo por si só.
- **Equipe de recrutamento** (`recruitment_members`, V328) — vale para conduzir a seleção da
  organização: ORG_ADMIN, RECRUITMENT_MANAGER, RECRUTER… (ver abaixo). O **dono da página vale
  ORG_ADMIN sem registro**; terceiro sem registro não ganha nada.

| Papel | O que pode |
|---|---|
| `ORG_ADMIN` | tudo: configurar etapas, triar, ver candidatos, entrevistas, gerir a equipe |
| `RECRUITMENT_MANAGER` | configurar etapas, triar, ver candidatos, entrevistas |
| `RECRUITER` | triar, ver candidatos, entrevistas |
| `INTERVIEWER` | ver candidatos e entrevistas (sem mover) |
| `OBSERVER` | só ver candidatos |

Rotas da equipe: `GET/POST /empresas/{companyId}/recrutamento/equipe`,
`PATCH/DELETE .../equipe/{userId}`, `GET /empresas/{companyId}/recrutamento/eu` (papel próprio;
`null` para forasteiro). Gerir a equipe exige ORG_ADMIN (`RECRUITMENT_FORBIDDEN`).

Publicar vaga continua exigindo papel de página (RECRUITER+): **publicar ≠ conduzir**, e a
equipe de recrutamento não concede papel na página.

## Modelos de processo seletivo

Biblioteca **pessoal** de fluxos: quem recruta guarda os modelos que usa e aplica por cópia
numa vaga — editar o modelo depois não muda vaga já configurada, e o modelo não é da página
(é de quem o criou; aplicar numa vaga continua exigindo CONFIGURE_PROCESS dela).

| Rota | O que faz |
|---|---|
| `GET /jobs/recruitment/templates` | os modelos de quem está na sessão |
| `POST /jobs/recruitment/templates` | cria modelo (`{"name", "stages":[...]}`) |
| `DELETE /jobs/recruitment/templates/{templateId}` | apaga (só o dono) |
| `POST /jobs/{jobId}/process/templates/apply` | aplica o modelo na vaga (semeia as etapas na ordem) |

Recusas explícitas: nome repetido na biblioteca 409 `TEMPLATE_NAME_TAKEN`, modelo sem etapa 422
`TEMPLATE_STAGES_REQUIRED`, etapa repetida 422 `TEMPLATE_STAGE_DUPLICATED`, vaga com fluxo já
configurado 409 `PIPELINE_ALREADY_CONFIGURED` (quem quer trocar apaga as etapas vazias — a regra
de etapa ocupada protege quem está no pipeline).

## Ciclo de vida da vaga

- **Prazo de candidaturas** (`applicationDeadline`, V329): opcional na criação e ajustável por
  `PUT /jobs/{jobId}/deadline` (corpo nulo/vazio remove). Vaga aberta com prazo vencido recusa a
  candidatura com **409 `APPLICATION_DEADLINE_PASSED`** — estado distinto de vaga encerrada
  (`JOB_CLOSED`), para o candidato saber o que aconteceu. A checagem é no servidor, no mesmo
  lock do apply.
- **Reabrir**: `POST /jobs/{jobId}/reopen` devolve a vaga encerrada a OPEN preservando
  candidaturas e fluxo; reabrir vaga aberta é 409 `JOB_NOT_CLOSED` (erro de estado, não
  sucesso silencioso). Quem comanda a vaga reabre: o autor sempre e, na vaga de página, quem
  tem CONFIGURE_PROCESS (a equipe de recrutamento pode encerrar e reabrir; a mensagem de
  recusa segue a de antes, `NOT_OWNER`).
- **Pessoa transita**: recrutar é estado, não identidade — desligar "Sou recrutador/a" fecha a
  Central para quem não tem vaga, mas não apaga modelo, vaga nem histórico; religar devolve
  tudo. Quem recruta continua podendo se candidatar a vagas de outras pessoas normalmente.

## Triagem com score explicável

O score de uma candidatura vai de 0 a 100 e **todo ponto vem de um motivo escrito**
(`CandidateScore`, em `jobs/domain`). Ele não é uma caixa-preta: a resposta traz a lista de
componentes, cada um com código, texto e pontos.

| Componente | Peso | Quando existe |
|---|---|---|
| `SKILLS` | 75 | a vaga lista requisitos **e** o retrato do candidato tem habilidades |
| `IDENTITY` | 25 | sempre (é um sim/não do retrato) |

Como no `JobCompatibility`, o peso só considera os sinais que existem, e o total é normalizado
sobre o peso dos sinais presentes. Regras que fazem o número ser honesto:

- **É o retrato congelado que pontua**, não o perfil de hoje: o recrutador decide com o que a
  pessoa apresentou quando se candidatou.
- **Vaga que não diz o que pede não gera score**: sem requisito no anúncio, `available = false`
  e `total = 0` com o motivo "identidade verificada"/"não verificada". A tela diz "sem dados para
  comparar" — nunca "0% compatível", porque não houve comparação.
- **O motivo cita o que casou**: "2 de 3 requisitos da vaga: Java, Spring".
- **O score ordena, não reprova.** Recusa continua sendo ato humano, com retorno escrito.
- **Não entram idade, gênero, local de nascimento, foto nem qualquer atributo protegido**: a
  comparação é sobre requisito declarado, verificação e nada mais. Reputação e faixa salarial
  ficam de fora de propósito — a primeira é histórico da conta, não competência; a segunda o
  perfil não declara.

Endpoints:

| Rota | Quem | O que faz |
|---|---|---|
| `GET /jobs/{jobId}/process/candidates?limit=` | VIEW_CANDIDATES | candidaturas com score, da mais aderente para a menos; sem score vai ao fim |
| `POST /jobs/{jobId}/process/applications/batch-move` | TRIAGE | `{"applicationIds":[...],"stageId","kind","note"}` |

O card do board (`GET /jobs/{jobId}/process`) já vem com `score` — não há segunda chamada para
mostrar a nota na coluna.

### Ações em lote

Um recrutador triagem 30 candidaturas, não uma. O lote aceita até 200 ids (acima disso é 422
`BATCH_TOO_LARGE` — seleção errada não se adivinha), descarta ids repetidos e devolve **o
desfecho de cada item**, não um "ok" global:

```json
[{"applicationId":"12","movement":{...},"errorCode":null,"errorMessage":null},
 {"applicationId":"13","movement":null,"errorCode":"ALREADY_IN_STAGE","errorMessage":"..."}]
```

Recusas antes de gravar qualquer coisa (nada é movido, erro explícito): lista vazia 400
`NO_APPLICATIONS_SELECTED`, candidatura de outra vaga 404 `APPLICATION_NOT_FOUND`, candidatura
já reprovada 409 `APPLICATION_REJECTED`, reprovação sem motivo 400 `FEEDBACK_REQUIRED`, quem
pede sem TRIAGE 403 `NOT_OWNER`.

Cada item roda em **transação própria**: o que entrou ficou gravado mesmo que o próximo seja
recusado no meio do lote. O caminho de cada item é o mesmo do movimento individual — nenhuma
regra de etapa é reimplementada no lote.

## Questionário condicional e rascunho (V330)

O formulário de candidatura (os campos que o autor define na vaga) ganhou lógica condicional e
o candidato ganhou autosave — sem segunda vaga nem segunda candidatura: são as mesmas
`job_application_fields`, com duas colunas novas e uma tabela nova de buffer.

### Campo condicional

Na criação/edição da vaga, um campo pode declarar de quem depende:

```json
{"label":"Carteira de motorista","type":"TEXT","required":true,
 "visibleIfFieldId":"field_abc","visibleIfValue":"Sim"}
```

Regras, todas do domínio (`JobApplicationField.conditional`):

- A origem é referida por **id** ou pelo **rótulo exato** de um campo anterior — na criação da
  vaga os ids ainda não existem, então o rótulo é o jeito natural de apontar; o que fica gravado
  (e o que a vaga devolve) é sempre o **id resolvido** da origem, então renomear o campo de
  origem depois não quebra a condição.
- Origem sem texto ou valor vazio é 422 `CONDITION_REQUIRES_SOURCE` / `CONDITION_REQUIRES_VALUE`.
- Origem que não existe na vaga é 422 `CONDITION_SOURCE_NOT_FOUND` — a origem precisa **preceder**
  na lista de campos, o que torna ciclo impossível por construção (depende de quem ainda não
  existe não passa na resolução).
- Casagem do valor é normalizada: caixa e acento ignorados (`"SIM"` revela igual a `"Sim"`).
- Campo invisível **não é exigido** no envio, mesmo sendo obrigatório — exigir o invisível é o
  que faz o candidato travar num formulário que não mostra o que falta.
- A vaga devolve `visibleIfFieldId`/`visibleIfValue` junto do campo; quem monta o formulário
  (front) é quem esconde o campo não revelado.

### Autosave do rascunho

| Rota | Quem | O que faz |
|---|---|---|
| `PUT /jobs/{jobId}/applications/draft` | candidato | grava o rascunho (`{"answers": {"fieldId": "valor"}}`), um por candidato×vaga, gravando por cima |
| `GET /jobs/{jobId}/applications/draft` | candidato | o próprio rascunho; sem rascunho devolve 200 vazio, não 404 |

O rascunho é um buffer de digitação (`job_application_drafts`, JSON numa linha só): não valida
obrigatórios, não entra no board, não notifica ninguém, não conta como candidatura. Teto de 25
respostas (`DRAFT_TOO_MANY_ANSWERS`) e 100.000 caracteres. **Enviou a candidatura, o rascunho
morre** — o apply apaga a linha. Vaga inexistente é 404 `JOB_NOT_FOUND`, igual ao apply.

## Avaliações internas (V332)

Parecer estruturado por avaliador, ao lado da nota livre: critérios nomeados com nota de 0 a 10
(até 10 critérios), veredito fechado (`STRONG_YES`, `YES`, `MAYBE`, `NO`) e resumo de até 2000
caracteres. Uma avaliação por autor por candidatura (`job_application_evaluations`, única por
`application_id` + `author_id`): reavaliar grava por cima — o que interessa é a posição atual de
cada avaliador; o histórico da decisão vive nos movimentos do pipeline.

| Rota | Quem | O que faz |
|---|---|---|
| `PUT /jobs/{jobId}/process/applications/{applicationId}/evaluations` | TRIAGE | cria ou refaz a avaliação do próprio chamador |
| `GET /jobs/{jobId}/process/applications/{applicationId}/evaluations` | VIEW_CANDIDATES | as avaliações da candidatura, mais recente primeiro |

Códigos de recusa explícitos: `EVALUATION_VERDICT_INVALID`, `EVALUATION_CRITERIA_REQUIRED`,
`EVALUATION_TOO_MANY_CRITERIA`, `EVALUATION_CRITERIA_LABEL_REQUIRED`,
`EVALUATION_CRITERIA_LABEL_TOO_LONG`, `EVALUATION_SCORE_OUT_OF_RANGE`,
`EVALUATION_SUMMARY_TOO_LONG`. Como as notas, a avaliação jamais aparece em rota do candidato.

## Timeline de auditoria

Toda decisão que muda o estado de uma candidatura deixa rastro na trilha de auditoria que o
projeto já mantém (`shared.audit.AuditTrail`, tabela `audit_logs` e o índice `(target_id,
created_at)` da V315) — **não há tabela nova para isso**. O alvo é sempre
`job_application:{applicationId}`, o ator é quem agiu e o metadado diz o que mudou:

| Ação | Quando | Metadado |
|---|---|---|
| `RECRUITMENT_APPLICATION_MOVED` | movimento, avanço, retorno ou reprovação | `kind` (`ADVANCE`, `RETURN` ou `REJECT`) e a etapa (`toStageId`, ou `fromStageId` na reprovação) |
| `RECRUITMENT_APPLICATION_NOTED` | nota interna criada | `noteId` |
| `RECRUITMENT_APPLICATION_EVALUATED` | avaliação interna gravada ou refeita | `verdict` |

| Rota | Quem | O que faz |
|---|---|---|
| `GET /jobs/{jobId}/process/applications/{applicationId}/audit` | VIEW_CANDIDATES | a timeline da candidatura, mais recente primeiro (até 200 eventos) |

A rota exige sessão e a capacidade de ver candidatos da vaga, e confere que a candidatura pertence
àquela vaga: candidato e estranho recebem 403, candidatura de outra vaga recebe 404
`APPLICATION_NOT_FOUND`. Como o resto do módulo, a timeline é **interna** — não existe rota de
auditoria para o candidato.

## Entrevistas na agenda central (sem segundo calendário)

A entrevista da candidatura **é** um compromisso da agenda que o projeto já tem: a origem é
`JOB_INTERVIEW` + id da entrevista, gravada nas duas agendas (candidato e quem marcou) pela porta de
origem do calendário. Reagendar move as duas entradas em vez de criar outras; cancelar publica o
cancelamento no mesmo UID. Não existe segundo calendário nem segundo lembrete.

| Rota | Quem | O que faz |
|---|---|---|
| `POST /jobs/{jobId}/applications/{applicationId}/interview` | MANAGE_INTERVIEWS | marca (ou reagenda a que já vale) |
| `PATCH /jobs/{jobId}/applications/{applicationId}/interview/{interviewId}` | quem marcou | reagenda |
| `DELETE /jobs/{jobId}/applications/{applicationId}/interview/{interviewId}` | quem marcou | cancela |
| `GET /jobs/{jobId}/applications/{applicationId}/interview` | empregador (VIEW_CANDIDATES) ou candidato | as entrevistas da candidatura |
| `GET /jobs/{jobId}/interviews/free-slots` | MANAGE_INTERVIEWS | horários livres na janela pedida |

### Horários livres e conflito

`GET /jobs/{jobId}/interviews/free-slots?from=&to=&durationMinutes=` devolve os horários livres
tirados da **disponibilidade semanal que quem está marcando declarou na agenda** (`/calendar/
availability`), em passos de meia hora, descartando o que já ocupa a agenda profissional dessa
pessoa. Nunca lê a agenda do candidato: a agenda dele é privada, e quem responde se o horário serve
é ele, pelo convite.

Sem janela declarada, a resposta traz `reasonCode` `NO_AVAILABILITY`; com as janelas todas ocupadas,
`NO_FREE_SLOT` — lista vazia **com motivo**, nunca uma lista inventada de horários.

Marcar em cima de outro compromisso **não é bloqueado**: o mesmo princípio do calendário (sobrepor é
uma decisão da pessoa, e um 409 impediria o caso legítimo). A resposta de marcar/reagendar traz
`conflicts` — o que já ocupa aquele horário na agenda de quem marcou (id, título e intervalo), sem
contar a própria entrevista. Nas listas, `conflicts` vem `null` ("não avaliado"), distinto de lista
vazia ("nada conflita").

## Fora deste escopo (próximos passos)

Slot de entrevista escolhido pelo candidato, banco de talentos e a UI no design system. A
fundação acima é a que esses itens vão usar — não há segunda base a criar.


A UI do "Sou recrutador/a" no front (`@rede-enois/design-system`) ainda não foi entregue: o
backend já aceita, guarda e aplica a declaração, mas a tela que a liga vem no incremento do
frontend.
