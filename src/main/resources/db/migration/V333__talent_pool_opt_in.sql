-- Banco de talentos: a candidatura que a pessoa libera para o recrutamento ver sem vaga aberta.
--
-- A unicidade do par (candidato, organizacao) e o que impede a lista de duplicar quando a mesma
-- pessoa libera a candidatura duas vezes ou quando a empresa e a pagina da vaga sao apontadas por
-- fluxos diferentes. `withdrawing_at` marca a saida sem apagar a linha na hora: o recrutamento
-- perde o acesso imediatamente (toda leitura filtra por ele) e a linha some de vez na expurga —
-- apagar aqui apagaria junto o fato que a auditoria precisa preservar.
--
-- Nenhum retrato do perfil e copiado para ca: o que o recrutamento le e o perfil de hoje, pelas
-- mesmas colunas que ele ja le em qualquer outra tela. Sem consentimento nao ha linha, e sem
-- linha nao ha resultado de busca.
--
-- Aditiva e idempotente; sem FK para users de proposito (armadilha de collation do MariaDB).
create table if not exists talent_pool_opt_ins (
  id varchar(255) not null,
  candidate_id varchar(255) not null,
  company_id varchar(255) not null,
  consent_version varchar(32) not null,
  consented_at timestamp not null,
  updated_at timestamp not null,
  withdrawing_at timestamp null,
  note varchar(500) null,
  primary key (id),
  constraint uk_talent_pool_opt_in unique (candidate_id, company_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Leitura do candidato ("onde estou liberado") e expurgo por candidato.
create index idx_talent_pool_candidate on talent_pool_opt_ins (candidate_id, consented_at);
-- Leitura da empresa: só as linhas ativas entram na busca e na contagem.
create index idx_talent_pool_company on talent_pool_opt_ins (company_id, withdrawing_at, consented_at);
