# Par de estudo

Duas pessoas com o mesmo objetivo ("aprender Rust", "primeiro emprego em dados") e um check-in
curto por semana. O motivo para voltar é outra pessoa contando com você, não um contador: não há
sequência, ranking nem métrica pública. O módulo é `studypairs` (migração V216).

## Regras

- **Objetivo**: texto livre de até 120 caracteres, com assunto opcional da taxonomia
  (`ContentTaxonomy.TOPICS`). O casamento usa a chave normalizada (`StudyGoal.key`: minúsculo,
  sem acento, sem pontuação), então "Aprender Rust" e "aprender rúst" se encontram. O assunto é
  informativo e não entra no casamento.
- **Fila**: um pedido aberto por pessoa. Ao pedir, o backend procura pedidos abertos com a mesma
  chave, do mais antigo para o mais novo (FIFO), e casa com o primeiro elegível. Sem candidato, o
  pedido fica `OPEN`. A reivindicação do pedido da fila é atômica
  (`update ... where status = 'OPEN'`): dois pedidos simultâneos não levam a mesma pessoa.
- **Proposta (V360)**: o casamento não abre o par — nasce `PENDING` com prazo de 7 dias. Cada lado
  responde o quebra-gelo guiado (catálogo em `study_pair_icebreakers`; "por que esse objetivo
  agora?" é obrigatória) e aceita (`POST /study-pairs/{id}/accept`); os dois aceites viram o par
  `ACTIVE` e a semana do check-in passa a contar do aceite mútuo. Recusa (`POST
  /study-pairs/{id}/decline`) ou prazo vencido encerra a proposta: quem não recusou volta para a
  fila na posição original (o `createdAt` do pedido não muda) e a mesma dupla não volta a casar
  enquanto o vínculo vive (a chave única `active_pair_key` cobre `ACTIVE` **e** `PENDING`). A
  proposta pendente conta no teto de 2 pares. Cron horário (17 minutos depois da hora,
  `rede.study-pairs.expiry-cron`, `-` desliga) vence propostas passadas do prazo.
- **Concorrência (V228)**: dois cliques no mesmo botão (ou duas réplicas) passavam pelas checagens
  antes de qualquer escrita e criavam pedido/par duplicado. Agora `POST /study-pairs/requests`
  trava a linha da pessoa (`select ... for update`) antes de contar pares e pedidos: a segunda
  requisição espera e vê o que a primeira criou. Como rede de segurança, o banco recusa o que a
  aplicação deixaria passar — índice único em `open_user_id` (uma solicitação `OPEN` por pessoa;
  `NULL` quando casa ou cancela) e em `active_pair_key` (`menor:maior`, só enquanto `ACTIVE`). A
  colisão de índice vira o mesmo `409` que a checagem da aplicação devolveria. A migração saneia
  antes de criar os índices: pedidos `OPEN` repetidos ficam os mais antigos; pares `ACTIVE`
  repetidos da mesma dupla são encerrados.
- **Busca de pedidos** (`GET /study-pairs/search?q=`): o termo passa pela mesma dobra do objetivo
  (`StudyGoal.key`), então "Química Orgânica" acha "quimica-organica"; o assunto entra na busca com
  `_` tratado como `-`. Resultado anônimo (objetivo e assunto, sem quem pediu).
- **Elegível**: não é a própria pessoa, sem bloqueio nem silenciamento em nenhum sentido, e-mail confirmado, conta
  não banida, adulta, com menos de 2 pares ativos e que ainda não forma par ativo com quem pede.
- **Limites**: até 2 pares ativos por pessoa (`STUDY_PAIR_LIMIT`).
- **Idade (ECA Digital)**: só adultos com data de nascimento declarada. Escolhemos a opção mais
  segura entre "menor só com menor" e "nenhum menor": o par é um canal privado entre
  desconhecidos, e no resto da plataforma menor só é alcançado por conexão aceita (mensagens) e
  não entra nas sugestões de pessoas do onboarding. Menor recebe `403 STUDY_PAIRS_ADULTS_ONLY`;
  sem data declarada, `403 BIRTH_DATE_REQUIRED`. O filtro de candidatos confere de novo, para o
  caso de a data ser corrigida depois do pedido.
- **Moderação**: conta restrita não pede par nem faz check-in (`ACCOUNT_RESTRICTED`).
- **Check-in**: um por pessoa por semana ISO (`2026-W44`, fuso America/Sao_Paulo), nota de até 500
  caracteres (pode ser vazia). Repetir na mesma semana edita a nota. Só o primeiro check-in da
  semana notifica o par. Só os dois membros veem as notas (`403` para os demais). Dois envios
  simultâneos (toque duplo, duas abas) continuam sendo **uma** linha por semana: a PK
  `(pair_id, user_id, week_key)` decide quem insere e a outra requisição vira edição da nota, sem
  segundo aviso ao par.
- **Módulo desativável**: chave `STUDY_PAIRS` em `disabledModules` de `/me/preferences`
  ("O que não quero ver"). Com o módulo desligado: pedir par responde `409 STUDY_PAIRS_DISABLED`;
  um pedido aberto antes fica pausado (não é casado, segue `OPEN` e volta a valer ao religar; quem
  quiser sair da fila cancela); a pessoa não recebe `STUDY_PAIR_REMINDER`. Pares ativos seguem
  (o outro membro não é afetado). O resumo semanal por e-mail não lista nada deste módulo. O
  mapa kind para módulo do sino fica no front: os quatro `STUDY_PAIR_*` pertencem a `STUDY_PAIRS`.
- **Encerrar**: qualquer membro encerra; o outro recebe um aviso gentil.
- **Bloqueio ou silenciamento depois do par** (qualquer sentido): encerra o par em silêncio (sem
  notificação), sem check-in nem lembrete. Silenciar é tratado como bloquear aqui porque o par é
  um canal pessoal: não faz sentido seguir fazendo check-in com quem você silenciou.
- **Lembrete**: toda quinta às 18h (Brasília), um aviso para cada membro de par ativo que ainda não
  fez o check-in da semana. Cron em `rede.study-pairs.reminder-cron`
  (`STUDY_PAIRS_REMINDER_CRON`, `-` desliga). Idempotente no banco: antes de enviar, cada aviso é
  reivindicado com uma linha em `study_pair_reminders` (PK `pair_id, user_id, week_key`). Com
  várias réplicas o cron roda em todas, mas só a inserção que vence envia.

## Notificações

Todas apontam para `/par-de-estudo`.

| kind | Para quem | Quando |
|---|---|---|
| `STUDY_PAIR_PROPOSED` | Os dois | Proposta formada (ator: a outra pessoa) |
| `STUDY_PAIR_ACTIVE` | Os dois | Os dois aceitaram; o par está aberto |
| `STUDY_PAIR_CHECKIN` | O par | Primeiro check-in da semana do outro |
| `STUDY_PAIR_DECLINED` | Quem não recusou | Recusa ou prazo vencido; o pedido voltou para a fila |
| `STUDY_PAIR_ENDED` | O outro | Um membro encerrou |
| `STUDY_PAIR_REMINDER` | Quem falta | Lembrete semanal (ator `sistema`) |

Categoria de push: quando as preferências por categoria (PR #333) entrarem, os quatro kinds vão
para `SOCIAL` em `NotificationCategory`.

## API

Todas exigem sessão. Escritas exigem `Idempotency-Key`.

### `POST /study-pairs/requests`

Corpo `{ "goal": string, "topic": string | null }`. Resposta `201`:

```json
{ "requestId": "spr_...", "status": "OPEN" | "MATCHED", "pairId": "sp_..." | null }
```

`MATCHED` devolve `pairId` de uma **proposta** `PENDING` (não de um par aberto).

Erros: `400 STUDY_GOAL_REQUIRED`, `400 STUDY_GOAL_TOO_LONG`, `400 INVALID_TOPIC`,
`403 STUDY_PAIRS_ADULTS_ONLY`, `403 BIRTH_DATE_REQUIRED`, `403 EMAIL_NOT_VERIFIED`,
`403 ACCOUNT_RESTRICTED`, `409 STUDY_PAIRS_DISABLED`, `409 STUDY_REQUEST_ALREADY_OPEN`, `409 STUDY_PAIR_LIMIT`.

### `DELETE /study-pairs/requests/current`

Resposta `200 { "id": "spr_...", "status": "CANCELLED" }`. Sem pedido aberto:
`404 STUDY_REQUEST_NOT_FOUND`.

### `GET /study-pairs/me`

```json
{
  "openRequest": { "id": "spr_...", "goal": "Aprender Rust", "goalKey": "aprender-rust",
                   "topic": "TECNOLOGIA" | null, "createdAt": "ISO-8601" } | null,
  "proposals": [{
    "id": "sp_...", "goal": "Aprender Rust", "goalKey": "aprender-rust", "topic": null,
    "partner": { "id": "...", "username": "...", "name": "...", "avatar": "..." | null },
    "myAccepted": false, "partnerAccepted": true,
    "partnerIcebreaker": { "ice_why_now": "..." } | null,
    "deadline": "ISO-8601"
  }],
  "pairs": [{
    "id": "sp_...", "goal": "Aprender Rust", "goalKey": "aprender-rust", "topic": null,
    "startedAt": "ISO-8601", "weekKey": "2026-W44",
    "partner": { "id": "...", "username": "...", "name": "...", "avatar": "..." | null },
    "myCheckinThisWeek": false, "partnerCheckinThisWeek": true,
    "checkins": [{ "userId": "...", "weekKey": "2026-W44", "note": "...",
                   "createdAt": "ISO-8601", "updatedAt": "ISO-8601" }]
  }]
}
```

`checkins` cobre a semana atual e as 3 anteriores, da mais recente para a mais antiga. O
`partnerIcebreaker` só sai depois do meu aceite (revelação simétrica); antes disso é `null`.

### `GET /study-pairs/icebreakers`

`{ "items": [{ "id": "ice_why_now", "position": 1, "question": "Por que esse objetivo agora?",
"required": true, "kind": "TEXT", "options": [] }] }` — catálogo do quebra-gelo guiado.

### `POST /study-pairs/{pairId}/accept`

Corpo `{ "answers": { "ice_why_now": "...", ... } }` — resposta `200 { "pairActive": boolean }`
(`true` quando o aceite foi o segundo e o par abriu). Erros: `409 STUDY_PAIR_NOT_PENDING`,
`409 STUDY_PAIR_EXPIRED`, `409 STUDY_PAIR_ALREADY_ACCEPTED`, `400 STUDY_ICEBREAKER_REQUIRED`,
`400 STUDY_ICEBREAKER_TOO_LONG`.

### `POST /study-pairs/{pairId}/decline`

Resposta `200 { "id": "sp_...", "status": "DECLINED" }`. Quem não recusou volta para a fila.
Erros: `409 STUDY_PAIR_NOT_PENDING`.

### `POST /study-pairs/{pairId}/checkins`

Corpo `{ "note": string }`. Resposta `200` com o check-in no formato de `checkins[]`. Erros:
`404 STUDY_PAIR_NOT_FOUND`, `403 FORBIDDEN` (não é membro), `409 STUDY_PAIR_ENDED`,
`400 STUDY_NOTE_TOO_LONG`.

### `POST /study-pairs/{pairId}/end`

Resposta `200 { "id": "sp_...", "status": "ENDED" }`. Erros: `404`, `403 FORBIDDEN`,
`409 STUDY_PAIR_ENDED`.

### `GET /study-pairs/goals/popular`

Até 10 objetivos com pedidos abertos, sem id nem nome de ninguém:

```json
{ "items": [{ "goalKey": "aprender-rust", "goal": "Aprender Rust", "topic": null, "openRequests": 1 }] }
```

### `GET /study-pairs/search?q=&limit=`

Busca por texto nos pedidos abertos (objetivo e tópico), sem revelar quem pediu: cada item traz só
`goal`, `topic` e `createdAt`. Passa pelo mesmo filtro de elegibilidade do pareamento (bloqueio/mudo
nas duas direções, menores, banidos, módulo desligado, teto de pares). Termo com menos de 2 letras
devolve lista vazia; acima de 80 letras: `400 STUDY_SEARCH_TOO_LONG`.

## Dados

- `study_pair_requests(id, user_id, goal_key, goal_text, topic, status OPEN|MATCHED|CANCELLED, created_at)`
- `study_pairs(id, user_a, user_b, goal_key, goal_text, topic, status PENDING|ACTIVE|ENDED, started_at, ended_at, icebreaker_a, icebreaker_b, accepted_a, accepted_b, proposed_at, deadline)`
- `study_pair_icebreakers(id, position, question, required, kind TEXT|CHOICE|MULTI, options)`: catálogo do quebra-gelo
- `study_checkins(pair_id, user_id, week_key, note, created_at, updated_at)`, PK `(pair_id, user_id, week_key)`
- `study_pair_reminders(pair_id, user_id, week_key, created_at)`, PK `(pair_id, user_id, week_key)`: um lembrete por semana
