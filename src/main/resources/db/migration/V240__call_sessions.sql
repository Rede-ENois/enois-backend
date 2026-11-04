-- Chamadas de áudio e vídeo (1:1 e grupo "Tamo junto").
--
-- A sala NÃO é nomeada com o @ de ninguém: `room_id` é um identificador aleatório gerado no
-- servidor e só quem tem direito de entrar recebe um token assinado para ela. O nome amigável
-- fica em `display_name`, usado apenas para mostrar na tela.
--
-- `conversation_id` aponta para uma conversa (DM 1:1 ou conversa de grupo) e `group_id` para um
-- grupo de comunidade quando a chamada nasce no grupo. Um dos dois sempre existe; a autorização
-- é sempre recalculada no servidor a partir do vínculo, nunca a partir da URL.
--
-- Aditiva e idempotente: nada aqui toca tabelas existentes, então o deploy não depende de
-- migração de dados e um rollback do binário deixa as tabelas vazias sem efeito no app.
create table if not exists call_sessions (
  id varchar(255) not null,
  kind varchar(16) not null,
  modality varchar(16) not null,
  conversation_id varchar(255) null,
  group_id varchar(255) null,
  initiator_id varchar(255) not null,
  room_id varchar(64) not null,
  display_name varchar(120) not null,
  state varchar(16) not null,
  created_at timestamp not null,
  answered_at timestamp null,
  ended_at timestamp null,
  ended_by varchar(255) null,
  end_reason varchar(32) null,
  expires_at timestamp null,
  last_activity_at timestamp not null,
  primary key (id),
  unique key uk_call_sessions_room (room_id),
  key idx_call_sessions_conversation (conversation_id),
  key idx_call_sessions_group (group_id),
  key idx_call_sessions_state_expires (state, expires_at),
  key idx_call_sessions_state_activity (state, last_activity_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Quem foi convidado e quem já entrou. Para o 1:1 o chamado entra em RINGING na criação; para o
-- grupo "Tamo junto" a linha nasce em INVITED e só vira JOINED quando a pessoa entra de fato,
-- então a contagem de participantes reflete a sessão, não a lista de membros.
create table if not exists call_participants (
  id varchar(255) not null,
  call_id varchar(255) not null,
  user_id varchar(255) not null,
  state varchar(16) not null,
  invited_at timestamp not null,
  joined_at timestamp null,
  left_at timestamp null,
  last_seen_at timestamp null,
  primary key (id),
  unique key uk_call_participants_call_user (call_id, user_id),
  key idx_call_participants_call (call_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
