-- Énóis Distributed Network (fase 5): a região de origem da tarefa.
--
-- O plano promete "região declarada e considerada no escalonamento", e até aqui a região existia só
-- no nó: era gravada no enroll (`network_nodes.region`), exibida no painel e assinada na config, mas
-- nenhuma linha do scheduler a consultava. O motivo é que a pergunta "em que região esta tarefa
-- nasceu?" não tinha onde ser respondida — a tarefa não carregava origem nenhuma.
--
-- Esta coluna responde. Ela é **preferência, não filtro**:
--   - null significa "sem preferência" e é o valor de toda tarefa que não declara região, inclusive
--     as já existentes depois desta migração. Nesse caso o escalonamento é exatamente o de antes
--     (degradação → origem → folga → jitter), porque todos os nós empatam no termo novo;
--   - região declarada faz o nó daquela região vir antes **dentro do mesmo grupo de saúde e de
--     origem**, nunca por cima de um filtro (vivo, perfil, vaga, saturação, memória).
--
-- Filtrar por região esvaziaria a fila sempre que a região do pedido não tivesse nó vivo: a tarefa
-- esperaria a região voltar em vez de rodar no vizinho, e a rede cooperativa é justamente o que
-- existe para não ter esse ponto único.
--
-- Aditiva e idempotente, como todas as deste módulo: `if not exists` em cada instrução, uma por
-- comando (a forma composta do MySQL não roda no H2).

alter table network_tasks add column if not exists region varchar(64) null;

-- A coluna não entra em índice sozinha: a escolha de nó é feita em memória sobre os candidatos vivos
-- (no máximo MAX_LIST por rodada), e um índice por região não seria usado por nenhuma consulta.