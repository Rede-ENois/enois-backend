-- Agregador de vagas, fase 1: o que as fontes externas trazem de ESTRUTURADO passa a ser guardado
-- como dado, e não como texto solto na descrição.
--
-- Regra do módulo: nada é inventado. Coluna nula significa "a fonte não informou" — nunca um valor
-- deduzido. Por isso tudo é anulável, inclusive a faixa salarial.
--
-- As colunas `salary_min`/`salary_max` (varchar) continuam existindo: são o texto que a pessoa
-- digitou ao cadastrar uma vaga da comunidade. As novas `salary_min_amount`/`salary_max_amount` são
-- numéricas e servem para filtrar e ordenar; a importação preenche as duas quando consegue ler a
-- faixa, e a comunidade continua só com o texto.
alter table external_jobs add column if not exists published_at timestamp null;
alter table external_jobs add column if not exists employment_type varchar(40) null;
alter table external_jobs add column if not exists level varchar(20) null;
alter table external_jobs add column if not exists work_model varchar(20) null;
alter table external_jobs add column if not exists city varchar(120) null;
alter table external_jobs add column if not exists state varchar(60) null;
alter table external_jobs add column if not exists country varchar(60) null;
alter table external_jobs add column if not exists salary_min_amount numeric(12,2) null;
alter table external_jobs add column if not exists salary_max_amount numeric(12,2) null;
alter table external_jobs add column if not exists salary_currency varchar(8) null;
alter table external_jobs add column if not exists salary_period_code varchar(10) null;

-- Link obrigatório de atribuição da fonte (Remotive, Jobicy, RemoteOK, Himalayas e Adzuna exigem
-- crédito com link de volta). Separado de `url` porque a URL da vaga pode ser a de candidatura.
alter table external_jobs add column if not exists attribution_url varchar(1000) null;

-- Ciclo de vida: `last_seen_at` é a última vez que a fonte devolveu a vaga; `expires_at` é o prazo
-- que a própria fonte declara (quando declara); `removed_at` é quando saiu da listagem ativa.
alter table external_jobs add column if not exists last_seen_at timestamp null;
alter table external_jobs add column if not exists expires_at timestamp null;
alter table external_jobs add column if not exists removed_at timestamp null;

-- A listagem ativa ordena por publicação e descarta as removidas: um índice só para esse par.
create index if not exists idx_external_jobs_active on external_jobs (removed_at, published_at);

-- Os filtros novos da busca (modelo de trabalho, nível e faixa salarial).
create index if not exists idx_external_jobs_work_model on external_jobs (work_model);
create index if not exists idx_external_jobs_level on external_jobs (level);
create index if not exists idx_external_jobs_salary on external_jobs (salary_min_amount);

-- A expiração varre por plataforma e última verificação.
create index if not exists idx_external_jobs_platform_seen on external_jobs (platform, last_seen_at);
