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
-- CHARSET: a tabela e do V6, criada antes das tabelas com collation unica, e o default do banco
-- de producao nao e o mesmo das tabelas novas, entao a coluna declara charset e collation na
-- propria definicao. Cada servidor aceita essa clausula por um caminho: o MySQL recusa o `add
-- column` com ela e aceita no `modify column`; o MariaDB so aceita dentro de comentario versionado
-- (`/*! ... */`), que o MySQL ignora. Por isso a migracao tem duas linhas: o `add column` cria a
-- coluna com o padrao, e o `modify column` grava a definicao final. A coluna acabou de nascer com
-- o padrao, entao o `modify` reescreve a mesma definicao, e rodar de novo nao muda nenhum valor.
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
    varchar(16) not null default 'MEMBER' /*! character set utf8mb4 collate utf8mb4_uca1400_ai_ci */;
