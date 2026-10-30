-- Ledger da checagem de DESTINO de link publicado.
--
-- Uma linha por URL normalizada: o mesmo vídeo postado por dez pessoas é visitado uma vez, e a
-- segunda publicação já nasce com o veredito conhecido. Guarda também com que modelo e quando a
-- decisão foi tomada — trocar o modelo muda o veredito, e a trilha precisa mostrar isso.
--
-- Só entra destino http(s) público; link que não responde nunca é gravado como violação.
create table if not exists link_safety_checks (
  url_hash char(64) not null,
  url varchar(1000) not null,
  owner_id varchar(255) not null,
  verdict varchar(16) not null,
  severity varchar(16) not null,
  confidence double not null,
  reason varchar(300) not null,
  model varchar(80) not null,
  checked_at timestamp not null,
  primary key (url_hash),
  key idx_link_safety_verdict_checked (verdict, checked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
