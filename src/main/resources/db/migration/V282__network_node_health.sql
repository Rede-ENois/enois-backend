-- Énóis Distributed Network (fase 3): saúde medida do nó, para balanceamento.
--
-- O critério do plano é "nó com latência/erro perde prioridade; remoção temporária". Até aqui o
-- scheduler só sabia de folga (vagas livres) e CPU saturada: um nó que devolvia erro em toda
-- tarefa continuava recebendo trabalho até o `max-attempts` de cada tarefa se esgotar, e um nó com
-- latência alta (medida pelo tempo entre a designação e o resultado) era escolhido igual a um nó
-- rápido. Nenhuma das duas coisas é estado administrativo — são MEDIÇÕES, e por isso vivem aqui,
-- não em `status`: o admin não precisa "suspender" um nó que está falhando, e o Control Plane não
-- pode confundir um nó suspenso por decisão humana com um nó removido por defeito.
--
-- O que cada coluna guarda e por quê:
-- - `observed_latency_ms`: média móvel do tempo entre designação e conclusão (peso 1/4 para a
--   amostra nova). Média simples guardaria memória longa demais — um nó que foi lento uma vez
--   demoraria a voltar a parecer rápido. A conta é feita no próprio UPDATE, com o valor anterior na
--   linha, para dois resultados simultâneos não se sobrescreverem.
-- - `consecutive_failures`: streak de erro. Um erro não condena; o que caracteriza nó doente é erro
--   SEGUIDO. Um sucesso zera o streak, senão o nó acumularia histórico para sempre e acabaria
--   removido por erros já corrigidos. Não há contador de sucessos: dele só se usa "zerou o de
--   falha", e guardar o outro lado seria estado sem leitor.
-- - `quarantined_until` / `quarantine_reason`: a janela de remoção temporária. Nula = em
--   balanceamento; preenchida e no futuro = fora da fila até aquela hora; vencida = volta sozinho.
--   É o que separa "removido" de "suspenso": ninguém precisa religar nada.
--
-- Nenhum nó externo escreve aqui; tudo é derivado pelo Control Plane a partir dos resultados que o
-- próprio nó devolveu. Revogar continua sendo em `status`: quarentena é operacional e expira,
-- revogação é administrativa e é terminal.
--
-- `add column` um por instrução, e não na forma composta do MySQL: o H2 da suíte não aceita a
-- composta, e a migração tem de rodar igual nos dois. `if not exists` em todas, porque a migração é
-- aplicada duas vezes na validação (MariaDB 11.4 e H2), como todas as deste módulo.

alter table network_nodes add column if not exists observed_latency_ms int null;

alter table network_nodes add column if not exists consecutive_failures int not null default 0;

alter table network_nodes add column if not exists quarantined_until timestamp null;

alter table network_nodes add column if not exists quarantine_reason varchar(40) null;

-- A varredura de recuperação lista nós ativos com janela vencida. Sem o índice, ela varreria a
-- tabela inteira a cada minuto.
create index if not exists idx_network_nodes_quarantine on network_nodes (status, quarantined_until);
