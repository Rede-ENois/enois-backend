-- Assinatura privada da agenda em `.ics` (projeto Énóis Calendar, fase 9, decisão D7).
--
-- O link `/calendar/subscribe/{token}.ics` responde sem sessão: o token é a única credencial. Por
-- isso o banco guarda só o hash SHA-256 — vazar a tabela não entrega link nenhum — e o token em
-- claro aparece uma única vez, na resposta que o cria ou regenera.
--
-- Uma pessoa tem no máximo uma assinatura ativa. Regenerar não cria linha nova: troca o hash da
-- mesma linha, e o link antigo morre no mesmo instante. Revogar marca `revoked_at`; a linha fica
-- para o histórico e para o índice único não deixar nascer uma segunda ativa por corrida.
--
-- O `scope` fica congelado no token: a assinatura é de uma agenda (social, profissional ou as
-- duas), e trocar o recorte é regenerar o link, não um parâmetro que quem assinou escolhe. Nulo é
-- a agenda consolidada, o mesmo contrato da leitura (`scope` ausente devolve as duas).
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda.
create table if not exists calendar_subscriptions (
  id varchar(255) not null,
  user_id varchar(255) not null,
  scope varchar(32) null,
  token_hash char(64) not null,
  created_at timestamp not null,
  revoked_at timestamp null,
  primary key (id),
  constraint ck_calendar_subscriptions_scope check (scope is null or scope in ('SOCIAL', 'PROFESSIONAL'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A busca da rota sem sessão é pelo hash do token, e ele é único no mundo: dois donos com o mesmo
-- hash significaria o mesmo token servindo duas agendas.
create unique index uq_calendar_subscriptions_token on calendar_subscriptions (token_hash);

-- A gestão lê a assinatura da pessoa; o índice cobre a busca por dono e a checagem de "já existe
-- uma ativa" que a regeneração faz.
create index idx_calendar_subscriptions_user on calendar_subscriptions (user_id, revoked_at);
