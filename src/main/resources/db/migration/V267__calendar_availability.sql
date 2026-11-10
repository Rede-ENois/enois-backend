-- Disponibilidade semanal da agenda (projeto Énóis Calendar, fase 6, decisão D24).
--
-- Uma janela diz "toda terça, das 9h às 12h, no fuso de São Paulo". Ela é regra da pessoa, não de
-- um compromisso, e é distinta do `mentor_availability` do perfil de mentor: lá o horário é o de um
-- slot de mentoria; aqui é quando a agenda admite compromisso.
--
-- Os horários são `time` de parede no fuso declarado, não instantes: a regra é semanal e precisa
-- continuar valendo depois da mudança de horário de verão da cidade. Uma janela não atravessa a
-- meia-noite; quem precisa das 22h às 2h declara uma janela em cada dia.
--
-- Não tem chave estrangeira, como as outras tabelas da agenda: a disponibilidade é da pessoa e não
-- some pela metade quando um compromisso é cancelado.
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda.
create table if not exists calendar_availability (
  id varchar(255) not null,
  user_id varchar(255) not null,
  day_of_week int not null,
  starts_at time not null,
  ends_at time not null,
  time_zone_id varchar(64) not null,
  created_at timestamp not null,
  primary key (id),
  constraint ck_calendar_availability_day check (day_of_week between 1 and 7),
  constraint ck_calendar_availability_interval check (starts_at < ends_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A leitura é sempre da semana inteira de uma pessoa, na ordem em que a tela mostra.
create index idx_calendar_availability_user on calendar_availability (user_id, day_of_week, starts_at);

-- Duas janelas começando no mesmo minuto do mesmo dia seriam a mesma janela; o banco recusa em vez
-- de a aplicação lembrar de checar. Sobreposições com começos diferentes são recusadas no serviço.
create unique index uq_calendar_availability_user_day_start
  on calendar_availability (user_id, day_of_week, starts_at);