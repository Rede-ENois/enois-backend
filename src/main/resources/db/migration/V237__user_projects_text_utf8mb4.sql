-- A tabela `user_projects` de producao veio de um schema legado sem utf8mb4. O V117 corrigiu so a
-- coluna `html`; `description` (V185) e as demais colunas de texto continuaram no charset padrao
-- da tabela. Resultado: importar do GitHub um repositorio cuja descricao tem simbolos Unicode
-- (ex.: atalhos de teclado do macOS) falhava com o 409 generico "operacao conflita com o estado
-- atual dos dados". Declarar o charset em cada coluna de texto livre tira a dependencia do default.
--
-- Nenhuma dessas colunas participa de indice, FK ou comparacao com outra tabela; a conversao para
-- utf8mb4 so alarga o conjunto de caracteres aceitos. `id` e `user_id` ficam de fora de proposito
-- (por isso nao e `convert to character set`): chave com collation trocada e o drift que ja
-- derrubou o boot.
--
-- Nulidade em comentario executavel `/*! ... */`: MariaDB/MySQL executam o conteudo, o H2 dos
-- testes ignora. O H2 recusa `null`/`not null` depois de `collate` num `modify`, e omitir a
-- nulidade no MariaDB tornaria `title` e `stack` anulaveis.

alter table user_projects modify column title varchar(120)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table user_projects modify column description varchar(500)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table user_projects modify column stack text
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table user_projects modify column repo_url varchar(500)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table user_projects modify column status varchar(40)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
alter table user_projects modify column diary longtext
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
