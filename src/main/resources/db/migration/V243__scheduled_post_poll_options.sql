-- Enquete agendada: as alternativas são o conteúdo do post e precisam viajar com ele até a hora
-- marcada. Sem elas, a publicação é recusada por falta de alternativas. Guardadas como CSV, a
-- mesma convenção das outras listas do agendado (tags, mídias, menções).
alter table scheduled_posts add column if not exists poll_options text;
