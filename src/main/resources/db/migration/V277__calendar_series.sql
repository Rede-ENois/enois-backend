-- Séries de compromissos (projeto Énóis Calendar, decisão D42).
--
-- Uma série é um grupo de linhas de `calendar_entries` com o mesmo `series_id`, cada uma com o
-- horário próprio já convertido para instante no fuso da série. A leitura da agenda não muda:
-- consulta por janela encontra as ocorrências que caem nela, sem expandir regra em tempo de leitura.
--
-- O compromisso que fundou a série também pertence a ela: o `series_id` dele fica preenchido com a
-- cadência e a âncora, e as irmãs nascem com origem vazia (só a fundadora tem o fato; as irmãs são
-- compromissos do próprio calendário, editáveis individualmente pela mesma rota de sempre).
--
-- O `uq_calendar_series_start` é a idempotência da geração (D3): rodar a extensão duas vezes, ou em
-- duas instâncias ao mesmo tempo, não cria a mesma ocorrência duas vezes — o banco recusa.
--
-- Aditiva: colunas novas, nenhuma linha existente muda de significado (o nulo é o compromisso único,
-- que é tudo que existe hoje).
--
-- `tail` marca a ocorrência mais à frente da série. A extensão parte dela, e só ela: procurar a
-- última por ordenação a cada ciclo daria a resposta errada quando duas ocorrências fossem criadas ao
-- mesmo tempo, e a marca é uma coluna da linha que já está sendo escrita.
alter table calendar_entries add column if not exists series_id varchar(255) null;
alter table calendar_entries add column if not exists recurrence varchar(16) null;
alter table calendar_entries add column if not exists tail boolean not null default false;
alter table calendar_entries add constraint ck_calendar_entries_series check (
  (series_id is null and recurrence is null and tail = false)
  or (series_id is not null and recurrence in ('WEEKLY', 'BIWEEKLY', 'MONTHLY'))
);

-- A idempotência da geração por série (D3): o banco recusa a ocorrência repetida.
create unique index uq_calendar_series_start on calendar_entries (series_id, starts_at);

-- O worker de extensão procura a série pela irmã mais recente; a âncora não serve, porque a mais
-- recente pode ter sido reagendada ou cancelada individualmente.
create index idx_calendar_series_id on calendar_entries (series_id, starts_at);
