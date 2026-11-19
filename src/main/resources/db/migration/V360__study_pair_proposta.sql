-- Par de estudo: o casamento vira PROPOSTA. O par nasce PENDING (não ACTIVE) e só fica ACTIVE
-- quando as duas pessoas aceitam, cada uma com o quebra-gelo respondido. Migração aditiva: sem
-- FK, sem alteração de coluna existente, idempotente.
--
-- Novas colunas em study_pairs:
--   icebreaker_*: as respostas de cada lado (quebra-gelo guiado da proposta; opcionais exceto
--     "por que agora", que é obrigatória no aceite).
--   accepted_a / accepted_b: o aceite de cada membro (nulo enquanto não aceitou).
--   proposed_at / deadline: quando a proposta nasceu e quando vence (7 dias).
--
-- study_pair_icebreakers guarda o catálogo de perguntas do quebra-gelo guiado: uma linha por
-- pergunta, ordenada por position. O texto vive no banco para o front não inventar as perguntas.
create table if not exists study_pair_icebreakers (
  id varchar(64) not null,
  position int not null,
  question varchar(200) not null,
  required boolean not null default false,
  kind varchar(16) not null,
  options varchar(500) null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

insert ignore into study_pair_icebreakers (id, position, question, required, kind, options) values
  ('ice_why_now', 1, 'Por que esse objetivo agora?', true, 'TEXT', null),
  ('ice_time_week', 2, 'Quanto tempo por semana você pretende dedicar?', false, 'CHOICE',
    'Até 1 hora|1 a 3 horas|3 a 5 horas|Mais de 5 horas'),
  ('ice_how_track', 3, 'Como prefere que a gente se acompanhe?', false, 'MULTI',
    'Só pelo check-in daqui|Também por mensagem|Chamada de vez em quando'),
  ('ice_anything_else', 4, 'Algo mais que queira contar?', false, 'TEXT', null);

alter table study_pairs add column if not exists icebreaker_a text null;
alter table study_pairs add column if not exists icebreaker_b text null;
alter table study_pairs add column if not exists accepted_a timestamp null;
alter table study_pairs add column if not exists accepted_b timestamp null;
alter table study_pairs add column if not exists proposed_at timestamp null;
alter table study_pairs add column if not exists deadline timestamp null;
