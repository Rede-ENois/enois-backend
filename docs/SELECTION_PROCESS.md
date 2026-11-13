# Processo seletivo (ATS) — pipeline de recrutamento por vaga

Estado: implementado em 2026-11-12 e na `qa` (PR #677 mergeado); declaração de recrutador
acrescentada em 2026-11-13.

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
| `POST /jobs/{jobId}/process/applications/{applicationId}/move` | dono | `{"stageId","kind","note"}` → movimento |
| `GET /jobs/{jobId}/process/applications/{applicationId}/movements` | dono | histórico em ordem |
| `GET /jobs/{jobId}/process/applications/{applicationId}/notes` | dono | notas internas |
| `POST /jobs/{jobId}/process/applications/{applicationId}/notes` | dono | cria nota (`{"body": ...}`) |
| `GET /jobs/recruitment` | sessão | Central: minhas vagas com o funil de cada uma |

"Todo o fluxo de etapas" é configurável: criar, renomear, apagar (respeitando candidatos na
etapa), reordenar. Renomear preserva o id, então o histórico dos candidatos continua válido.

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

- Toda rota de processo exige sessão e **o autor da vaga**; terceiro recebe 403 `NOT_OWNER`.
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

## Fora deste escopo (próximos passos)

Questionários, avaliações por critério, slot de entrevista escolhido pelo candidato, banco de
talentos, papéis de equipe (Administrador/Recrutador/Entrevistador/Observador) e a UI no
design system. A fundação acima é a que esses itens vão usar — não há segunda base a criar.

A UI do "Sou recrutador/a" no front (`@rede-enois/design-system`) ainda não foi entregue: o
backend já aceita, guarda e aplica a declaração, mas a tela que a liga vem no incremento do
frontend.
