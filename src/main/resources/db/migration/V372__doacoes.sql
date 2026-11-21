-- Doações via Pix pessoal (selo de apoiador).
--
-- O txid é o que vai no QR Code de cada pessoa (campo EMV 62-05) e é o que volta no extrato do
-- recebedor; o código por pessoa é resolvido no servidor. O txid sozinho não é prova de nada
-- (qualquer pessoa com o código pode gerar outro pagamento igual), então nada aqui nasce confirmado:
-- a confirmação vem da LEITURA de um comprovante (valor igual ao gerado + identificador fim a fim)
-- ou da linha de entrada do extrato da conta.
--
-- O COMPROVANTE NÃO É GUARDADO. A tabela filha guarda só os identificadores de pix que já
-- confirmaram alguma doação — é a trava contra o mesmo comprovante pagar o selo de duas contas.
--
-- status: ABERTA (código emitido, aguardando) | CONFIRMADA (vira selo) | RECUSADA (revisão).
-- origem: como foi decidida — CONTA (extrato de quem recebe), DOADOR (comprovante), REVISAO.

create table if not exists doacoes (
  id varchar(64) not null,
  user_id varchar(64) not null,
  txid varchar(25) not null,
  amount_cents bigint not null,
  status varchar(16) not null,
  origem varchar(16) null,
  created_at timestamp not null,
  confirmed_at timestamp null,
  decided_by varchar(64) null,
  primary key (id),
  key idx_doacoes_user_status (user_id, status),
  key idx_doacoes_status_created (status, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists doacoes_pix_ids (
  doacao_id varchar(64) not null,
  pix_id varchar(40) not null,
  primary key (doacao_id, pix_id),
  unique key uk_doacoes_pix_ids_pix (pix_id),
  constraint fk_doacoes_pix_ids_doacao foreign key (doacao_id) references doacoes (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
