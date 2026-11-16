-- A declaracao de charset foi acrescentada a V117 depois de sua primeira versao.
-- Bancos que ja a tinham aplicado mantiveram o charset legado; a V237 nao inclui html.
-- Esta migracao converte o conteudo existente sem mudar ids, referencias ou nulidade.
-- Nulidade em comentario executavel para compatibilidade com o H2 em modo MySQL.
alter table user_projects modify column html longtext
  character set utf8mb4 collate utf8mb4_unicode_ci /*! null */;
