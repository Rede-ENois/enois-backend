-- Limite de série de compromissos (projeto Énóis Calendar, continuação da V277).
--
-- A série importada de `.ics` pode trazer `UNTIL` na regra. Hoje o limite só vale na materialização
-- do import: sem coluna, a marca de ponta fica aberta e o worker de extensão volta a crescer a série
-- para além do que o arquivo dizia — o limite precisava ser lembrado para sempre, e não tinha onde.
--
-- Aditiva: coluna nova, nula por padrão (série sem limite, que é tudo que existe hoje). Nenhuma
-- linha existente muda de significado; a coluna só é escrita junto com `series_id` preenchido.
alter table calendar_entries add column if not exists series_until datetime(3) null;
