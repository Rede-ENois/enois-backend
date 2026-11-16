-- Índices que faltavam para o módulo de mensagens.
--
-- O envio de mensagem e a listagem da conversa saiam caros demais por varredura de tabela:
--
--   * conversations(updated_at): a caixa de entrada ordena por `updated_at DESC` e filtra por
--     participante. Sem índice próprio, a coluna leading era varrida a cada abertura do inbox.
--   * conversation_participants(user_id, hidden_at, conversation_id): a listagem do inbox filtra
--     `cp.userId = :userId AND cp.hiddenAt IS NULL` dentro de um subselect; o índice atual
--     (user_id) não ajuda o filtro de "apagado para mim".
--   * messages(conversation_id, sender_id, created_at): a checagem de duplicata (duplo clique /
--     retry) filtra por conversa + remetente + instante. Só havia índice por (conversation_id,
--     created_at), então a busca varria todas as mensagens da conversa.
--   * messages(conversation_id, created_at, id): a paginação da conversa ordena por
--     `created_at DESC, id DESC` — o desempate por id precisa do índice, senão o banco ordena a
--     página inteira em memória.
--
-- Aditivo e idempotente: pode rodar sobre uma base que já tenha os índices. Nenhum dado muda e
-- nenhuma regra de negócio é tocada — só o custo de leitura.
create index if not exists idx_conversations_updated_at on conversations (updated_at);

create index if not exists idx_conversation_participants_user_hidden
  on conversation_participants (user_id, hidden_at, conversation_id);

create index if not exists idx_messages_conversation_sender_created
  on messages (conversation_id, sender_id, created_at);

create index if not exists idx_messages_conversation_created_id
  on messages (conversation_id, created_at, id);
