-- Texto livre do feed em utf8mb4.
--
-- Em 2026-11-25 publicar artigo falhava com o 409 generico "operacao conflita com o estado atual dos
-- dados". O log de producao mostrava a causa real:
--
--   data_integrity_violation: Data truncation: Incorrect string value: '\xE2\xB8\xBB...'
--   for column `rede_prd`.`articles`.`body` at row 1
--
-- \xE2\xB8\xBB e U+2E3B (o travessao triplo "⸻"), tres bytes que so nao existem em latin1: a coluna
-- `articles.body` ficou num charset legado porque a tabela nasceu (V46) sem charset declarado e
-- herdou o default que o banco tinha na epoca. O texto do artigo, escrito em editor, sai com
-- travessao, aspas curvas e emoji — e o INSERT quebra. O mesmo ja tinha acontecido com
-- `user_projects` (V117, V237) e com `ban_content_archive`, que segue falhando a cada tentativa de
-- arquivar post de pessoa banida (a linha aparece no log do agendador a cada 30 minutos).
--
-- A correcao e a mesma da V237: declarar o charset em cada coluna de TEXTO LIVRE, em vez de depender
-- do default que existia quando a tabela foi criada. Nenhuma coluna daqui participa de chave, indice,
-- FK ou comparacao entre tabelas — por isso e `modify column` coluna a coluna e nao
-- `convert to character set`, que trocaria tambem a collation de `id`/`user_id` e e o drift que ja
-- derrubou o boot (errno 150 em FK entre collations diferentes).
--
-- Nulidade em comentario executavel `/*! ... */`: MariaDB/MySQL executam o conteudo, o H2 dos testes
-- ignora. O H2 recusa `null`/`not null` depois de `collate` num `modify`, e omitir a nulidade no
-- MariaDB tornaria as colunas anulaveis.

-- post comum e pagina: o texto do post e o HTML da pagina (V57 ja mostrou o mesmo sintoma aqui)
alter table posts modify column content varchar(5000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table posts modify column page_html longtext
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- corpo do artigo: a coluna do erro de 2026-11-25
alter table articles modify column body varchar(10000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;

-- comentario e notificacao: mesma origem (texto escrito por pessoa, nomes e trechos de post)
alter table comments modify column content varchar(1024)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table notifications modify column title varchar(500)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table notifications modify column body varchar(2000)
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- envelope da publicacao: o payload do outbox e o JSON devolvido ao cliente (idempotencia) carregam
-- o texto do post, entao tambem nao podem estar num charset menor que ele
alter table outbox_events modify column payload text
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
alter table idempotency_keys modify column response_body text
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;

-- arquivo de conteudo removido (ban): o sintoma ja esta no log, em producao e na qa
alter table ban_content_archive modify column content_snapshot longtext
  character set utf8mb4 collate utf8mb4_unicode_ci /*! not null */;
