-- Lembretes de compromisso (projeto Énóis Calendar, fase 1/6, decisão D4).
--
-- Um lembrete é a promessa de avisar alguém X minutos antes de um compromisso. Ele não agenda
-- nada no processo: é uma linha com `due_at`, e um worker de 30 s pega o que venceu. Agendar um
-- `Runnable` por compromisso perderia o lembrete a cada reinício do container, que no calendário
-- é rotina (a assinatura externa atualiza a agenda e o container recicla).
--
-- `attempts`/`last_error`/`sent_at` são o mesmo contrato dos posts agendados: uma falha não some,
-- fica visível, e depois do teto o lembrete para de ser tentado em vez de girar para sempre.
--
-- Não tem chave estrangeira, como as outras tabelas da agenda: cancelar o compromisso não deve
-- apagar a linha do lembrete pela metade (o compromisso cancelado continua na tabela, com data, e
-- o lembrete dele é encerrado por `sent_at` com motivo, não por cascade).
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda.
create table if not exists calendar_reminders (
  id varchar(255) not null,
  entry_id varchar(255) not null,
  user_id varchar(255) not null,
  minutes_before int not null,
  due_at timestamp not null,
  attempts int not null default 0,
  sent_at timestamp null,
  last_error varchar(500) null,
  created_at timestamp not null,
  primary key (id),
  constraint ck_calendar_reminders_minutes check (minutes_before in (5, 15, 30, 60, 1440)),
  constraint ck_calendar_reminders_attempts check (attempts >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A varredura do worker: vencidos e ainda não enviados. `sent_at is null` fica fora do índice de
-- propósito (coluna nula no meio quebraria o range), então a consulta é por `due_at` e descarta o
-- já enviado na leitura — o volume de vencidos por ciclo é pequeno.
create index idx_calendar_reminders_due on calendar_reminders (due_at);

-- Dois lembretes idênticos para a mesma pessoa e o mesmo compromisso seriam a duplicata que a D4
-- proíbe; o banco recusa em vez de a aplicação lembrar de checar.
create unique index uq_calendar_reminders_entry_user_minutes
  on calendar_reminders (entry_id, user_id, minutes_before);

-- Os lembretes de um compromisso (cancelar, reagendar, encerrar junto com a entrada).
create index idx_calendar_reminders_entry on calendar_reminders (entry_id);