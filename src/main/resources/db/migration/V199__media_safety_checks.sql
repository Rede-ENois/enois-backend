-- Ledger da checagem de conteúdo explícito nas mídias enviadas.
--
-- Uma linha por media_id: é o que impede a mesma imagem de ser baixada e mandada ao modelo de novo
-- a cada varredura. O resultado fica gravado para auditoria — quem foi barrado, por qual motivo e
-- com que confiança — e para o recurso do autor conseguir mostrar o que aconteceu.
--
-- Só entra aqui mídia de imagem (o vídeo fica para revisão humana); `model` e `checked_at` guardam
-- com o que a decisão foi tomada, porque trocar o modelo muda o veredito.
create table if not exists media_safety_checks (
  media_id varchar(255) not null,
  owner_id varchar(255) not null,
  verdict varchar(16) not null,
  severity varchar(16) not null,
  confidence double not null,
  reason varchar(300) not null,
  model varchar(80) not null,
  checked_at timestamp not null,
  primary key (media_id),
  key idx_media_safety_verdict_checked (verdict, checked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
