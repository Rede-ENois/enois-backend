-- Texto das vagas externas em utf8mb4.
--
-- Na primeira carga da qa em 2026-11-25 o agendador de vagas falhava inteiro:
--
--   [vagas-externas-boot] Error: 1366-22007: Incorrect string value: '\xE2\x80\xAF% A...'
--   for column `enois_qa`.`external_jobs`.`description` at row 1
--
-- \xE2\x80\xAF e U+202F (o espaco fino que os anuncios usam em "12 000 € pro Jahr"); e so um dos
-- tres bytes que o charset legado da tabela recusa. `external_jobs` nasceu (V40) sem charset
-- declarado e herdou o default que o banco tinha na epoca, e toda coluna de texto acrescentada
-- depois (V44, V65, V102, V112, V269) herdou o mesmo. O importador grava o anuncio como veio da
-- fonte — titulo, empresa, cidade, faixa salarial, descricao — e qualquer anuncio com acento fora
-- do latin1, moeda ou emoji derrubava o lote da transacao, nao so a vaga.
--
-- A correcao e a mesma da V378: declarar o charset em cada coluna de texto, em vez de depender do
-- default do banco no dia em que a tabela foi criada. Sao todas `varchar`, entao `modify column`
-- coluna a coluna nao troca a collation de `id`, que e pk e alvo do uk de (platform, external_id)
-- — `convert to character set` trocaria, e e esse drift que derruba o boot (errno 150 em FK entre
-- collations diferentes).
--
-- `external_job_applications` (V271) ja nasceu em utf8mb4 e nao entra aqui.
--
-- Nulidade em comentario executavel `/*! ... */`: MariaDB/MySQL executam o conteudo, o H2 dos
-- testes ignora (ele recusa `null`/`not null` depois de `collate` num `modify`).

-- identificacao do anuncio: o titulo e a coluna que primeiramente carrega o texto da fonte
alter table external_jobs modify column platform varchar(64)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table external_jobs modify column external_id varchar(255)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table external_jobs modify column title varchar(500)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table external_jobs modify column company varchar(255)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column location varchar(255)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column url varchar(1000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table external_jobs modify column canonical_url varchar(1000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column external_status varchar(32)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null default 'FOUND' */;

-- salario como a fonte publica: a moeda e o periodo vem junto do numero
alter table external_jobs modify column salary varchar(255)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column currency varchar(8)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column salary_min varchar(50)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column salary_max varchar(50)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column salary_period varchar(20)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- candidatura e vaga enviada pela comunidade
alter table external_jobs modify column apply_email varchar(320)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column submitted_by varchar(255)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- descricao: a coluna do erro de 2026-11-25
alter table external_jobs modify column description varchar(5000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- campos normalizados (V269) e a URL de atribuicao exigida pelas fontes
alter table external_jobs modify column employment_type varchar(40)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column level varchar(20)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column work_model varchar(20)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column city varchar(120)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column state varchar(60)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column country varchar(60)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column salary_currency varchar(8)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column salary_period_code varchar(10)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table external_jobs modify column attribution_url varchar(1000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
