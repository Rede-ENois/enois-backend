-- Contexto de uma denúncia de CHAMADA: quem estava na sala no momento e quem foi denunciado.
--
-- A linha de `reports` diz "alguém denunciou a chamada X". Isto diz O QUÊ essa denúncia mostra:
-- quem a pessoa acusou (e por quê), quem estava dentro naquele instante e o print do momento
-- (um asset de `media_assets`, como qualquer imagem). A lista de participantes é lida do
-- servidor na hora do pedido — nunca é o cliente que informa quem estava na chamada.
--
-- Aditiva e idempotente: nada aqui toca tabelas existentes.
create table if not exists call_reports (
  report_id varchar(255) not null,
  call_id varchar(255) not null,
  reported_user_id varchar(255) null,
  media_id varchar(255) null,
  participants text not null,
  reported_at timestamp not null,
  primary key (report_id),
  key idx_call_reports_call (call_id),
  key idx_call_reports_reported_user (reported_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
