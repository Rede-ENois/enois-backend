-- Papo Reto: os assuntos em alta do Explorar, medidos pelo que a comunidade conversa.
--
-- O ranking não é enquete e não pergunta nada: o sistema extrai as expressões que nomeiam a
-- conversa do conteúdo PÚBLICO já publicado (posts públicos e comentários do feed, desabafos,
-- confissões do Confessionário) com n-gramas determinísticos e clustering local — sem LLM,
-- sem serviço externo. A pontuação soma pesos por fonte (posts/comentários), pessoas distintas
-- e crescimento recente, com decaimento temporal; expressão que só uma mão de contas publicou
-- não vira alta (chão de pessoas distintas trava manipulação).
--
-- Três tabelas:
--
-- papo_falas é a CONTABILIDADE por bucket da hora: cada expressão extraída entra numa linha
-- com o id do autor (SÓ para a contagem distinta da janela — nunca sai daqui), a comunidade
-- de origem (para o escopo do ranking por comunidade) e a publicação que gerou a fala (a
-- âncora da deduplicação). O texto completo nunca é gravado.
--
-- papo_assuntos é o RESULTADO por escopo e subject (o escopo vazio é o ranking global; cada
-- comunidade tem o seu), reescrito pelo worker a cada corte de 5 minutos a partir das falas
-- da janela — sem releitura de publicações.
--
-- papo_intervalos é a TIMELINE por escopo, subject e bucket (série histórica), reconciliada
-- a cada corte: o corte manda a lista completa do escopo e ela substitui a gravada — o mesmo
-- bucket volta sempre ao mesmo número, e conteúdo removido sai da série.
--
-- O worker roda a cada 5 minutos. O corte é incremental por fronteira de leitura (lida_em).

create table if not exists papo_falas (
  id varchar(64) not null,
  -- Hora cheia da publicação, em UTC (o bucket): "2026-11-17T18:00:00Z".
  hora timestamp not null,
  -- A expressão dobrada (sem acento, minúscula; unigrama ou bigrama) que nomeia a conversa.
  expressao varchar(120) not null,
  -- Fonte da fala: POST, COMMENT, DESABAFO ou CONFISSAO.
  fonte varchar(20) not null,
  -- Autor interno, para a contagem distinta da janela; nunca sai daqui.
  autor_id varchar(64) not null,
  -- Comunidade de origem (nula quando a fala vem do feed geral, desabafos ou confessionário):
  -- define o escopo do ranking por comunidade.
  community_id varchar(64),
  -- A publicação que gerou a fala: lida de novo, ela não fala duas vezes.
  publicacao_id varchar(64) not null,
  -- O momento da leitura que produziu esta fala: a fronteira do corte incremental.
  lida_em timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index if not exists idx_papo_falas_hora_expressao on papo_falas (hora, expressao);
create index if not exists idx_papo_falas_expressao on papo_falas (expressao);
create index if not exists idx_papo_falas_lida_em on papo_falas (lida_em);
create index if not exists idx_papo_falas_comunidade on papo_falas (community_id);
create index if not exists idx_papo_falas_publicacao on papo_falas (publicacao_id);

create table if not exists papo_assuntos (
  -- O escopo do ranking (vazio é o global; senão, o id da comunidade) e o subject.
  escopo varchar(64) not null default '',
  -- O subject (expressão normalizada) compõe a chave: o worker reescreve a linha a cada corte.
  subject varchar(120) not null,
  estado text not null,
  updated_at timestamp not null,
  primary key (escopo, subject)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists papo_intervalos (
  -- Identidade do bucket: escopo + subject + hora ("@inteligencia artificial@2026-11-17T18:00:00Z").
  id varchar(200) not null,
  escopo varchar(64) not null default '',
  subject varchar(120) not null,
  hora timestamp not null,
  falas bigint not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index if not exists idx_papo_intervalos_escopo_subject_hora on papo_intervalos (escopo, subject, hora);
