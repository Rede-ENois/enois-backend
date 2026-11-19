-- Vlog: o post do blog aceita mídia (foto/vídeo) anexada — associação media ↔ artigo (issue
-- "Postar vídeos no blog"). Os ids viajam como a casa já faz (messages, games): lista separada
-- por vírgula, teto de 1000 caracteres. O upload em si segue pelo fluxo de sempre
-- (POST /media/uploads); aqui só a associação com o artigo. Aditivo e idempotente.
alter table blog_posts add column if not exists media_ids varchar(1000) null;
