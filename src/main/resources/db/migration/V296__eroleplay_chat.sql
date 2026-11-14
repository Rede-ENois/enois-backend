-- Enóis Éroleplay: o chat da campanha — canais IC/OOC, aparte privado e fixação do mestre.
--
-- A mensagem vive dentro da campanha e herda o recorte de participação; o aparte privado é visível
-- só para remetente, destinatário e mestre, e o recorte é aplicado na leitura, por leitor. O
-- conteúdo não se reescreve: o único estado que muda depois da criação é a fixação (quem fixou e
-- quando), que é metadado de leitura e não uma segunda versão do que foi dito.
--
-- SEM chave estrangeira DE PROPÓSITO (collation do MariaDB, errno 150). Integridade na aplicação.
-- Idempotente: `if not exists` em tudo.

create table if not exists roleplay_campaign_messages (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  -- IC (na voz do personagem) | OOC (coordenação entre as pessoas reais).
  channel varchar(3) not null,
  author_id varchar(255) not null,
  -- Na mensagem privada (aparte), o destinatário; nas públicas, nulo.
  recipient_id varchar(255),
  -- A mensagem aparada, entre 1 e 2000 caracteres (o teto é do domínio).
  content varchar(2000) not null,
  created_at timestamp not null,
  -- Fixação: quem fixou e quando; nulos quando a mensagem não está fixada.
  pinned_by varchar(255),
  pinned_at timestamp,
  primary key (id),
  key idx_roleplay_chat_channel (campaign_id, channel, created_at),
  key idx_roleplay_chat_pinned (campaign_id, pinned_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
