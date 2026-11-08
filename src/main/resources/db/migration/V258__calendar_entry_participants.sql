-- Convites da agenda (projeto Énóis Calendar, Fase 1).
--
-- O compromisso continua sendo uma linha de `calendar_entries`, com um dono só. Esta tabela diz
-- quem mais tem aquele compromisso na agenda e em que pé está o convite: convidado, aceito,
-- recusado ou talvez. O estado é por participante porque cada pessoa responde por si.
--
-- Sem chave estrangeira, como nas outras migrações: a linha do compromisso é do dono e o convite
-- não deve desaparecer se o compromisso for cancelado (o cancelamento fica na entrada, com a data,
-- e o participante precisa ver que o evento foi cancelado, não que o convite sumiu).
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda.
create table if not exists calendar_entry_participants (
  id varchar(255) not null,
  entry_id varchar(255) not null,
  user_id varchar(255) not null,
  invited_by varchar(255) not null,
  state varchar(16) not null,
  invited_at timestamp not null,
  responded_at timestamp null,
  updated_at timestamp not null,
  primary key (id),
  constraint uq_calendar_participants_entry_user unique (entry_id, user_id),
  constraint ck_calendar_participants_state check (state in ('INVITED', 'ACCEPTED', 'DECLINED', 'TENTATIVE'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Lista de participantes de um compromisso (a tela de detalhe do organizador).
create index idx_calendar_participants_entry on calendar_entry_participants (entry_id);

-- Minhas agendas: consulta por pessoa atravessando os compromissos que ela não organiza.
create index idx_calendar_participants_user on calendar_entry_participants (user_id, state);
