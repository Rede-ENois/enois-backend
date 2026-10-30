-- Ledger da checagem de conteúdo automatizado (bot/IA publicando como se fosse gente).
--
-- Uma linha por post: é o que impede a varredura de reavaliar o mesmo texto a cada rodada e o que
-- guarda, para auditoria e para o recurso do autor, com que veredito a publicação saiu do ar.
--
-- `review_status` é o estado da revisão humana: `NONE` quando a avaliação não tirou o post do ar
-- (o caso normal, inclusive humano), `PENDING` enquanto o post está em quarentena, e `RELEASED` ou
-- `REMOVED` depois que uma pessoa decidiu. O veredito original nunca é reescrito — se a revisão
-- discordar dele, a linha mostra o que a automação pensou e o que a pessoa decidiu.
create table if not exists content_automation_checks (
  post_id varchar(64) not null,
  author_id varchar(255) not null,
  verdict varchar(16) not null,
  confidence double not null,
  reason varchar(300) not null,
  model varchar(80) not null,
  checked_at timestamp not null,
  review_status varchar(16) not null,
  primary key (post_id),
  key idx_content_automation_pending (review_status, checked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;