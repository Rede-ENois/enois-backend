-- Énóis Distributed Network (fase 3): transcode de mídia executado no nó (NODE_MEDIA).
--
-- O critério de aceite (docs/rede-distribuida/07-plano-incremental.md) é "transcode executa no nó;
-- resultado verificado por hash e tamanho". A tarefa em si já vive em `network_tasks` (V277); esta
-- tabela guarda o que a verificação precisa e o que o painel mostra:
--
-- - `source_sha256`/`source_bytes` — a origem que o Control Plane leu e fixou no momento do pedido.
--   Sem isso, "o resultado é o transcode desta origem" não é verificável: o objeto poderia ter sido
--   trocado entre o pedido e a entrega.
-- - `result_sha256`/`result_bytes` — o que o Control Plane MEDIU no objeto que o nó gravou. Não é o
--   que o nó declarou: o nó declara, o Control Plane mede, e só grava aqui o que conferiu.
-- - `status` — PENDING enquanto o nó não entregou; VERIFIED quando hash e tamanho conferem;
--   REJECTED quando não conferem (o motivo fica em `last_error`, e a tarefa volta para a fila).
--
-- Uma linha por mídia: o transcode é idempotente por origem (a chave de idempotência da tarefa é
-- `media:<id>:<sha256 da origem>`), então pedir de novo a mesma origem devolve o mesmo trabalho.
--
-- Regra que não se negocia (docs/rede-distribuida/05 §2): só objeto de mídia público entra aqui.
-- Não há coluna de classe de dado porque a tabela só recebe `PUBLIC_MEDIA`; o tipo é a allowlist.

create table if not exists network_media_jobs (
  media_id varchar(64) not null,
  source_key varchar(512) not null,
  mime_type varchar(64) not null,
  source_bytes bigint not null,
  source_sha256 char(64) not null,
  result_key varchar(512) not null,
  result_bytes bigint null,
  result_sha256 char(64) null,
  status varchar(16) not null default 'PENDING',
  task_id varchar(40) null,
  last_error varchar(500) null,
  created_at datetime(3) not null,
  verified_at datetime(3) null,
  primary key (media_id),
  key idx_network_media_jobs_status (status, created_at),
  key idx_network_media_jobs_task (task_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
