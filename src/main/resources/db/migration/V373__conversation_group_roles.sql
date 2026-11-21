-- Papel de administrador no grupo de conversa (issue #915).
--
-- O QUE FALTAVA: o grupo de mensagens (conversa kind=GROUP) nao tinha NENHUM papel. A tabela
-- `conversation_participants` guardava QUEM participava e nada mais: nao existia dono, nao existia
-- administrador, e nao existia quem pudesse adicionar ou remover gente do grupo depois de criado.
-- Com o painel de admin, o grupo precisa de um papel que valha para alem da lista.
--
-- O QUE ESTA COLUNA GUARDA: a PROMOCAO a administrador (`ADMIN`). O DONO nao mora aqui: e quem
-- CRIOU a conversa (`conversations.created_by`), que ja era guardado desde o V6. Guardar o dono
-- em dois lugares faria a linha do participante e a conversa discordarem sobre quem manda no
-- grupo — e a conversa e a fonte, porque e ela que nasce com o criador.
--
-- CHARSET: a tabela e do V6 (criada antes das tabelas com collation unica), e o default do banco
-- de producao nao e o mesmo das tabelas novas. A coluna herda a collation utf8mb4 da tabela, que
-- e a que o codigo espera. A clausula explicita nao entra: o flyway le `/*! ... */` como
-- comentario e manda a clausula crua ao MariaDB, que recusa ela depois de `add column`, e o
-- MySQL, sem ela, avisa "cannot store utf8mb4 characters" mesmo criando a coluna (aviso que ja
-- aparece em outras migracoes deste banco). O papel so guarda ascii (ADMIN/MEMBER), entao a
-- collation da tabela basta.
--
-- ADITIVA E IDEMPOTENTE: coluna nova com padrao, nenhuma linha antiga mudada. Quem ja participava
-- de um grupo entra como MEMBER — que e como sempre foi — e o dono segue sendo o criador.

alter table conversation_participants
  add column if not exists role
    varchar(16) not null default 'MEMBER';
