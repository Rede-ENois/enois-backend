# Processo seletivo (ATS) — pipeline de recrutamento por vaga

Estado: implementado em 2026-11-12 e na `qa` (PR #677 mergeado); declaração de recrutador
acrescentada em 2026-11-13; tabela paginada acrescentada em 2026-11-13 (V291).

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

## Fora deste escopo (próximos passos)

Questionários, avaliações por critério, slot de entrevista escolhido pelo candidato, banco de
talentos e a UI no design system. A fundação acima é a que esses itens vão usar — não há
segunda base a criar.


A UI do "Sou recrutador/a" no front (`@rede-enois/design-system`) ainda não foi entregue: o
backend já aceita, guarda e aplica a declaração, mas a tela que a liga vem no incremento do
frontend.
