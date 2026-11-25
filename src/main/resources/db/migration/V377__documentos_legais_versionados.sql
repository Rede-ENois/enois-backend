-- Documentos legais versionados (Diretrizes da Comunidade e Termos de Uso) + histórico de aceite.
--
-- Cada versão é uma linha imutável: o texto publicado nunca é editado. Corrigir ou publicar texto
-- novo é SEMPRE uma versão nova. O que o usuário aceitou fica ligado ao id da versão — não a um
-- "documento" genérico —, então dá para provar depois qual texto estava vigente no instante do
-- aceite, mesmo que o documento tenha evoluído.
--
-- "Vigente" é a versão com vigente_desde preenchido e em vigor (aprovado=true). A versão seguinte
-- pode ser cadastrada com aprovado=false: ela existe (para revisão e para o histórico) mas NÃO
-- conta como o texto atual enquanto não for aprovada e entrar em vigor.

create table if not exists documentos_legais (
  id varchar(64) not null,
  tipo varchar(32) not null,
  versao int not null,
  titulo varchar(160) not null,
  resumo varchar(1000) null,
  conteudo mediumtext not null,
  -- Hash SHA-256 do conteúdo: o aceite aponta para o texto exato, mesmo que alguém tente editar
  -- o registro no banco depois. A aplicação nunca atualiza o conteúdo de uma versão existente.
  conteudo_hash varchar(64) not null,
  exige_aceite boolean not null default true,
  aprovado boolean not null default false,
  vigente_desde timestamp null,
  criado_em timestamp not null,
  criado_por varchar(64) null,
  aprovado_em timestamp null,
  aprovado_por varchar(64) null,
  primary key (id),
  unique key uk_documentos_legais_tipo_versao (tipo, versao),
  key idx_documentos_legais_vigente (tipo, vigente_desde)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

create table if not exists documentos_legais_aceites (
  id varchar(64) not null,
  documento_id varchar(64) not null,
  user_id varchar(64) not null,
  documento_tipo varchar(32) not null,
  documento_versao int not null,
  conteudo_hash varchar(64) not null,
  origem varchar(32) not null,
  aceito_em timestamp not null,
  ip_hash varchar(64) null,
  user_agent varchar(255) null,
  -- coluna só para o índice não depender de valor NULL (mesma regra das outras tabelas)
  update_scope tinyint not null default 0,
  primary key (id),
  unique key uk_documentos_legais_aceites_user_doc (user_id, documento_id),
  key idx_documentos_legais_aceites_user (user_id, documento_tipo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- Sem seed: nenhuma versão entra em vigor por esta migração. O texto das Diretrizes/Termos entra
-- por um passo de publicação explícito (ops escopado), depois da revisão jurídica.
