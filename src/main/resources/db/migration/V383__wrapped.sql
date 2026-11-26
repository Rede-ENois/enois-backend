-- Retrospectiva anual (Énóis Wrapped).
--
-- Uma linha por (conta, ano) é a retrospectiva daquela pessoa: o resultado final já processado
-- (JSON), o estado da geração, quando começou e terminou, a versão do algoritmo, a credibilidade
-- usada para priorizar a fila (auditoria) e a falha, quando houver. A fila de processamento é a
-- própria tabela filtrada por estado PENDING e ordenada pela posição (a credibilidade no momento
-- em que a fila foi construída).
--
-- Sem FK, como as demais migrações (licão de collation da V33/V46/V52/V58). Todo DDL explícita
-- charset/collation porque o MariaDB de produção difere do H2 dos testes.

create table if not exists wrapped_retrospectivas (
  user_id varchar(64) not null,
  ano int not null,
  estado varchar(16) not null,
  posicao bigint not null default 0,
  prioridade int not null default 0,
  algoritmo_versao int not null,
  dados_insuficientes boolean not null default false,
  resultado longtext,
  falha varchar(2000),
  tentativas int not null default 0,
  claim_expira_em timestamp null,
  iniciado_em timestamp null,
  concluido_em timestamp null,
  criado_em timestamp not null,
  updated_at timestamp not null,
  primary key (user_id, ano),
  constraint ck_wrapped_estado
    check (estado in ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
  constraint ck_wrapped_ano check (ano between 2000 and 2100),
  constraint ck_wrapped_resultado_concluido
    check (estado <> 'DONE' or resultado is not null)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O passo do worker pega a próxima PENDING nesta ordem: estado, então posição.
create index if not exists idx_wrapped_estado_posicao on wrapped_retrospectivas (ano, estado, posicao);
-- A varredura de PROCESSING cuja reserva expirou (instância que caiu no meio da conta).
create index if not exists idx_wrapped_claim on wrapped_retrospectivas (estado, claim_expira_em);
