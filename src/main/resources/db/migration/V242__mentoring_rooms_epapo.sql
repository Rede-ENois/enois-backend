-- Salas de mentoria no Epapo: o nome da coluna e os valores do modo deixam de citar o Jitsi.
--
-- A sala padrão sempre foi uma conferência que o próprio servidor de reunião cria; quem mudou de
-- dono (serviço gerenciado -> Epapo hospedado) foi a conferência, não o formato. O nome da sala
-- fixa continua sendo só letras, números e traço, e passa a se chamar `room_slug`.
--
-- Aditiva: renomeia a coluna (nenhum dado se perde) e atualiza os valores gravados. Linhas novas
-- já nascem com os valores novos; o código também lê os antigos, então a ordem entre esta migração
-- e o deploy do binário não importa.
--
-- Sem FK: `mentors` e `mentor_sessions` não são alvo de chave estrangeira aqui, o que evita a
-- briga de collation entre esta tabela e `users`. O rename não toca no tipo nem na collation da
-- coluna: reescrever a coluna aqui só reescreveria a tabela inteira (lenta e arriscada no deploy).
alter table mentors
  rename column jitsi_room to room_slug;

update mentors
  set room_mode = 'EPAPO_SESSION'
  where room_mode = 'JITSI_SESSION';

update mentors
  set room_mode = 'EPAPO_PERMANENT'
  where room_mode = 'JITSI_PERMANENT';

update mentor_sessions
  set room_mode = 'EPAPO_SESSION'
  where room_mode = 'JITSI_SESSION';

update mentor_sessions
  set room_mode = 'EPAPO_PERMANENT'
  where room_mode = 'JITSI_PERMANENT';
