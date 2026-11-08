-- Outbox com retentativa, claim entre instancias e recibo por handler.
--
-- O worker marcava o evento como processado ANTES de rodar os handlers e engolia as excecoes: uma
-- falha de handler perdia o efeito para sempre, e duas instancias podiam executar o mesmo evento.
-- Agora o evento so sai de PENDING quando todos os handlers concluem, a falha agenda a proxima
-- tentativa com backoff e o evento e reservado por instancia antes de rodar.
--
-- Tudo aditivo: colunas novas com default e uma tabela nova. `processed_events` continua existindo,
-- intocada: a linha dela era gravada antes da execucao e por isso nao diz nada sobre o que rodou.
-- As linhas antigas ficam preservadas e a tabela deixa de ser usada pelo worker.

alter table outbox_events
  add column if not exists attempts int not null default 0;

alter table outbox_events
  add column if not exists last_error varchar(500) null;

-- Reserva de quem esta executando: um claim velho (instancia que caiu no meio) expira sozinho e o
-- evento volta a ser elegivel, sem intervencao manual.
alter table outbox_events
  add column if not exists claimed_at timestamp null;

-- Recibo por handler: o que permite retomar um evento que falhou no meio sem repetir os efeitos
-- dos handlers que ja concluiram. A chave e o par (evento, handler).
create table if not exists processed_event_handlers (
  event_id varchar(255) not null,
  handler varchar(255) not null,
  processed_at timestamp not null,
  primary key (event_id, handler)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
