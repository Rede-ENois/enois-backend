-- Advertência administrativa dada diretamente por um administrador a um perfil.
--
-- Antes não existia advertência registrada: "avisar" era só a decisão de uma denúncia (o autor
-- recebia uma notificação e nada ficava guardado como histórico dele). Quem é advertido precisa
-- conseguir ver o histórico, e a próxima decisão (recurso, escalada, banimento) precisa saber o que
-- já foi dito — por isso a linha fica presa à CONTA, não à denúncia.
--
-- Aditiva e idempotente: não toca tabela existente. Constraint de contas e de administradores é
-- checada no serviço, não por foreign key: o banco desta rede evita FK para `users` (o collation do
-- MariaDB de produção difere do das tabelas de moderação, e uma FK aqui derrubaria a migração).
create table if not exists user_warnings (
  id varchar(40) not null,
  user_id varchar(64) not null,
  admin_id varchar(64) not null,
  reason varchar(2000) not null,
  created_at timestamp not null,
  revoked_at timestamp null,
  revoked_by varchar(64) null,
  primary key (id),
  key idx_user_warnings_user (user_id, created_at),
  key idx_user_warnings_created (created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;