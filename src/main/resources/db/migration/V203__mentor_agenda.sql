-- Agenda do mentor: regras de disponibilidade semanal (dia da semana 1..7 e horário "HH:mm")
-- das quais o serviço gera horários concretos por 28 dias à frente.
create table if not exists mentor_availabilities (
  id varchar(255) not null,
  mentor_id varchar(255) not null,
  day_of_week int not null,
  time_of_day varchar(5) not null,
  primary key (id),
  key idx_mentor_availabilities_mentor (mentor_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

alter table mentor_availabilities add constraint fk_mentor_availabilities_mentor
  foreign key (mentor_id) references mentors (id);
