# Agenda central (Énóis Calendar)

Estado: implementado e na `qa` (fases 1–12). Documento da agenda no backend; complementa o plano
vivo do frontend, em `enois-frontend/docs/engineering/ENOIS-CALENDAR-ROADMAP.md`.

A agenda é a **fonte temporal única** do app. Todo compromisso é uma linha de `calendar_entries`,
com uma origem opcional (`source_type`/`source_id`) que diz qual módulo o criou. Não existe segunda
agenda, segundo mecanismo de agendamento nem segundo lembrete: mentoria, evento e entrevista
escrevem na mesma tabela pelo serviço de origem.

## O que existe

| Peça | Onde |
|---|---|
| Compromisso (dono, escopo, tipo, título, intervalo, fuso, origem, estado) | `calendar/domain/CalendarEntry.java` |
| Dois escopos independentes | `CalendarScope` — `SOCIAL` e `PROFESSIONAL`; a consolidada é a união na consulta |
| Tipo só para a tela | `CalendarKind` — `PERSONAL`, `EVENT`, `MENTORING`, `INTERVIEW`, `MEETING`, `CALL`, `WORKSHOP` |
| Origem | `CalendarEntrySource` — nula ou (tipo + id) |
| Convites e participantes | `CalendarParticipant`, `CalendarParticipantState` (`INVITED`/`ACCEPTED`/`TENTATIVE`/`DECLINED`) |
| Disponibilidade semanal declarativa | `CalendarAvailabilityWindow` |
| Lembretes | `CalendarReminder` — 5/15/30/60/1440 min |
| Recorrência materializada | `CalendarRecurrence` — `WEEKLY`/`BIWEEKLY`/`MONTHLY` |
| Assinatura `.ics` privada | `CalendarSubscription` |

## Endpoints (o gateway tira o prefixo `/api`)

### Compromissos — `/calendar/entries`

| Rota | O que faz |
|---|---|
| `GET /calendar/entries?from&to&scope` | Agenda de quem lê: o que organiza + o que foi convidada a participar |
| `GET /calendar/entries/conflicts?from&to&scope&entryId` | O que já ocupa o intervalo (leitura, não bloqueio) |
| `GET /calendar/entries/{id}` | Detalhe; o organizador vê os participantes, o convidado não |
| `POST /calendar/entries` | Cria (escopo padrão `SOCIAL`, tipo `PERSONAL`) |
| `PATCH /calendar/entries/{id}` | Edição parcial; campo ausente não muda |
| `DELETE /calendar/entries/{id}` | Cancela (mantém a linha, com `STATUS:CANCELLED` no `.ics`) |
| `POST /calendar/entries/{id}/series` | Inicia a série (`{"recurrence":"WEEKLY"}`) |
| `DELETE /calendar/entries/{id}/series` | Encerra a série |
| `POST /calendar/entries/{id}/participants` | Convida `{"username":"@"}` |
| `GET /calendar/entries/{id}/participants` | Lista (só o organizador) |
| `PATCH /calendar/entries/{id}/participants/me` | Responde `{"state":"ACCEPTED"}` |
| `DELETE /calendar/entries/{id}/participants/{participantId}` | Remove (só o organizador) |
| `GET/PUT/DELETE /calendar/entries/{id}/reminders` | Lembretes de quem lê |
| `GET /calendar/entries/export.ics` | Export do intervalo de quem lê |
| `POST /calendar/entries/import` | Import de `.ics` (multipart) |

### Disponibilidade — `/calendar/availability`

`GET` devolve a semana de quem lê; `PUT` **substitui a semana inteira** `{"windows":[{dayOfWeek,startsAt,endsAt,timezone}]}`.
O conjunto é a regra: o que não veio deixa de existir. Duas janelas sobrepostas no mesmo dia são
recusadas; encostar uma na outra é permitido.

### Convites — `/calendar/invitations`

`GET /calendar/invitations` — convites do próprio usuário.

### Assinatura — `/calendar/subscription` e `/calendar/subscribe`

`GET` mostra o estado (sem token), `POST` cria (devolve o token só na criação), `POST /regenerate`
troca o token (mata o link antigo), `DELETE` revoga. A leitura é pública com token:
`GET /calendar/subscribe/{token}.ics`. O banco guarda o hash, nunca o token em claro.

## Origem: quem manda no compromisso é o módulo dono

Uma entrada **com origem** é somente leitura na rota da agenda. `PATCH` e `DELETE` respondem 409
`CALENDAR_ENTRY_SOURCE_OWNED` — quem muda o fato é o módulo dono, chamando `CalendarSourceService`.
Sem isso, a agenda e o módulo contariam histórias diferentes (a sessão de mentoria seguiria marcada
com outro horário).

Consumidores de origem hoje:

| Origem | Módulo | Quem entra |
|---|---|---|
| `MENTORING` | `MentorService` | agenda do mentorado |
| `EVENT` | `EventService` | agenda de quem confirma presença |
| `JOB_INTERVIEW` | `JobInterviewService` | agenda do candidato **e** do empregador (entradas independentes, mesma origem) |
| `ICS` | `CalendarIcsService` | import de arquivo (mesmo `UID` não duplica) |

`CalendarSourceService.upsert` é idempotente pela origem (decisão D3): gravar de novo a mesma
origem **reagenda** a entrada existente em vez de criar outra. É o que faz um reagendamento mover o
compromisso em vez de duplicá-lo. Entrada cancelada não reabre: o cancelamento já foi publicado.

## Recorrência (fase 12, V277)

Série é materializada: cada ocorrência é uma linha própria, agrupada por `series_id`. O passo é
calculado em **relógio de parede no fuso da série** (`ZonedDateTime`), não em `Instant`: "toda terça
ás 19h" continua às 19h depois da mudança de horário. A mensal conta os meses a partir da âncora
(dia 31 cai no último dia dos meses curtos e volta). O serviço estende a série até `HORIZON_DAYS`
(90) à frente, com teto de 200 ocorrências por passada.

## Janela de leitura

Toda leitura por intervalo (`lista`, `conflitos`, `export`) valida em `CalendarWindows`: máximo
`MAX_SPAN` = 366 dias. Sem o teto, um `from` de décadas atrás transforma a leitura em varredura da
agenda inteira.

## Lembretes

Criação dirigida pela tela (a pessoa escolhe 5/15/30/60/1440 min antes); envio pelo worker
(`CalendarReminderWorker`, a cada 30 s), que só olha o relógio. São avisados o organizador e quem
**aceitou** o convite — convite pendente ou recusado não recebe. Ator da notificação é `system`.

## Autorização

- **Não existe rota pública de escrita.** Toda rota exige sessão; sem ela, 401.
- Leitura sempre limitada ao dono: a janela filtra por `ownerId` na consulta e o detalhe de outra
  pessoa responde igual a um identificador inexistente (D10, sem vazar existência).
- Convite e resposta são de quem é convidado; lista de participantes é só do organizador.
- A origem **nunca** vem do cliente: a rota HTTP de escrita não aceita `sourceType`/`sourceId`
  (D12). Quem sabe a origem é o módulo dono, que valida a regra própria antes de chamar.
- Toda escrita exige `Idempotency-Key`; a rota entra na chave, então a mesma chave repetida devolve
  a resposta guardada.

## Migrações

| Migração | Tabela |
|---|---|
| V257 | `calendar_entries` |
| V258 | `calendar_entry_participants` |
| V262 | índice único de origem por dono |
| V264 | `calendar_reminders` |
| V267 | `calendar_availability_windows` |
| V272 | `calendar_subscriptions` |
| V277 | colunas de série em `calendar_entries` |

Todas aditivas e idempotentes, com `charset=utf8mb4` e `collation` explícitos.

## Provas

- `CalendarEntryWriteApiTests` — escrita, edição, cancelamento, entrada com origem somente leitura
  (409), alias `/v1`.
- `CalendarEntryReadApiTests`, `CalendarConflictApiTests`, `CalendarDetailAndPendingInvitationsApiTests`
  — janela, conflitos e convites na leitura.
- `CalendarSeriesApiTests`, `CalendarRecurrenceServiceTests`, `CalendarSeriesPersistenceTests`,
  `CalendarRecurrenceTests`, `CalendarIcsRecurrenceTests` — recorrência.
- `CalendarAvailabilityApiTests`, `CalendarAvailabilityServiceTests` — semana substitutiva.
- `CalendarRemindersApiTests`, `CalendarReminderServiceTests`, `CalendarReminderQueryTests` —
  criação, consulta e entrega única do lembrete.
- `CalendarIcsApiTests`, `CalendarIcsServiceTests` — export/import, `UID` estável, `TZID`,
  cancelamento.
- `CalendarSubscriptionApiTests`, `CalendarSubscriptionTests` — assinatura e revogação.
- `CalendarSourceServiceTests` — idempotência por origem.
- `MentorDeletionTests`, `JobInterviewFlowTests`, `JobInterviewTests` — integração de origem com a
  agenda (mentoria e entrevista).
