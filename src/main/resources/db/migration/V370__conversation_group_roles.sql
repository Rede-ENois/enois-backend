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
-- grupo, e a conversa e a fonte, porque e ela que nasce com o criador.
--
-- CHARSET: a tabela e do V6 (criada antes das tabelas com collation unica), e o default do banco
-- de producao nao e o mesmo das tabelas novas. Por isso a coluna precisa declarar charset e
-- collation na propria definicao. No MySQL isso e um `add column` comum; no MariaDB o `add
-- column if not exists` e aceito, mas nao com a clausula de charset na mesma linha (error 1064,
-- "near 'character set ...'"). O caminho que os dois aceitam e trocar a coluna declarando o
-- tipo com charset/collation: `modify column` reescreve a definicao, e o `modify` sem a
-- clausula (tambem recusado no MariaDB) nunca precisa acontecer porque as duas linhas levam a
-- mesma definicao.
--
-- ADITIVA E IDEMPOTENTE: rodar de novo adiciona a coluna que ja existe com `if not exists`, e o
-- `modify` seguinte so repete a mesma definicao, sem mexer em valor nenhum. Quem ja participava
-- de um grupo entra como MEMBER, que e como sempre foi, e o dono segue sendo o criador.
--
-- O papel e do codigo (enum do JPA), nao do banco: a restricao de valores fica no servico, e o
-- padrao da coluna garante o MEMBER para as linhas antigas.

alter table conversation_participants
  add column if not exists role
    varchar(16) not null default 'MEMBER';

alter table conversation_participants
  modify column role
    varchar(16) not null default 'MEMBER' character set utf8mb4 collate utf8mb4_uca1400_ai_ci;
