-- Énóis Éroleplay: a fundação das campanhas de RPG de mesa e RPG textual.
--
-- Tabelas PRÓPRIAS de propósito: a campanha é a fronteira de isolamento do produto. O que é
-- secreto — ficha de jogador, segredo do mestre, anotação privada — pertence a uma campanha e
-- não existe fora dela. O isolamento é estrutural, como nos Grupos: o feed principal, a busca
-- pública e os perfis não têm como alcançar conteúdo de campanha nem por engano de consulta.
--
-- SEM chave estrangeira DE PROPÓSITO (a armadilha de collation do MariaDB, errno 150, derrubava o
-- boot: o users de produção tem collation diferente das tabelas novas). Integridade na aplicação,
-- como no resto do banco; os índices ficam para as junções.
--
-- Idempotente: `if not exists` em tudo, porque uma migração que recria objeto existente derruba o
-- boot e trava o rollout em silêncio.

create table if not exists roleplay_campaigns (
  id varchar(255) not null,
  slug varchar(80) not null,
  name varchar(120) not null,
  description varchar(600),
  -- A ambientação: o mundo onde a campanha acontece (o "setting" que o mestre descreve).
  setting varchar(300),
  -- O identificador do sistema de regras, resolvido pelo Registry para um adapter concreto.
  game_system varchar(80) not null,
  -- A família do motor (D20, D100, CUSTOM): como o motor genérico interpreta o resultado.
  system_family varchar(16) not null default 'D20',
  -- PRIVATE | INVITE_ONLY | PUBLIC (o significado operacional vive no domínio).
  visibility varchar(16) not null default 'PRIVATE',
  -- ACTIVE | ARCHIVED | CLOSED.
  status varchar(16) not null default 'ACTIVE',
  owner_id varchar(255) not null,
  member_count int not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_campaigns_slug (slug),
  key idx_roleplay_campaigns_status_created (status, visibility, created_at),
  key idx_roleplay_campaigns_owner (owner_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists roleplay_campaign_members (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  user_id varchar(255) not null,
  -- GAME_MASTER | PLAYER
  role varchar(16) not null default 'PLAYER',
  joined_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_members_campaign_user (campaign_id, user_id),
  key idx_roleplay_members_user (user_id, joined_at),
  key idx_roleplay_members_campaign (campaign_id, role)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Pedido de entrada. `pending_slot` vale o user_id enquanto PENDING e vira NULL quando o pedido
-- é decidido: o índice único (campaign_id, pending_slot) então garante UMA solicitação pendente por
-- pessoa e campanha — sem ele, duas requisições simultâneas criariam dois pedidos e a aprovação
-- de um deixaria o outro vivo. Índice único ignora NULL, então o histórico pode ter várias linhas
-- decididas.
create table if not exists roleplay_join_requests (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  user_id varchar(255) not null,
  message varchar(500),
  -- PENDING | APPROVED | REJECTED | CANCELLED
  status varchar(16) not null default 'PENDING',
  pending_slot varchar(255),
  created_at timestamp not null,
  decided_by varchar(255),
  decided_at timestamp,
  primary key (id),
  unique key uk_roleplay_join_pending (campaign_id, pending_slot),
  key idx_roleplay_join_campaign_status (campaign_id, status, created_at),
  key idx_roleplay_join_user (user_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A mesa: o grupo que se reúne em torno da campanha.
create table if not exists roleplay_tables (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  name varchar(120) not null,
  owner_id varchar(255) not null,
  -- A sala de voz do Épapo vinculada; nulo quando a mesa ainda não tem voz.
  voice_room_id varchar(120),
  open boolean not null default true,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  key idx_roleplay_tables_campaign (campaign_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Quem está sentado na mesa. O par (mesa, pessoa) é único: sentar duas vezes não é um estado
-- possível.
create table if not exists roleplay_table_seats (
  id varchar(255) not null,
  table_id varchar(255) not null,
  user_id varchar(255) not null,
  seated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_seats_table_user (table_id, user_id),
  key idx_roleplay_seats_user (user_id, seated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
