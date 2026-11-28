-- Énóis Distributed Network: retentativas das tarefas por nó (brief §8.2, "retentativas").
--
-- POR QUE EXISTE: a V386 guarda a distribuição da DURAÇÃO das tarefas concluídas. A pergunta
-- "retentativas" é outra e não tinha onde ser respondida: `network_tasks.attempt` conta as tentativas
-- da tarefa em curso (e, antes desta fase, nem subia na devolução à fila), então não sobrava dado de
-- quantas tentativas cada nó precisa até concluir. A coluna entra na tabela que já vive por nó e por
-- hora truncada, com o mesmo UPSERT de contador — uma linha por faixa usada na hora, não por tarefa.
--
-- POR QUE SEPARADA DA DURAÇÃO: o tempo até uma falha não é tempo de execução. Somar a tentativa à
-- coluna `samples` contaminaria a cauda (p95/p99 de execução) com trabalho que não terminou; a
-- distribuição de tentativas é lida da coluna própria.
--
-- Aditiva: coluna nova sem default not null, nada é alterado nas linhas existentes (as anteriores
-- ficam com 0 tentativas). A versão é a 387 porque a 386 é a da V386 (`network_task_latency_buckets`).

alter table network_task_latency_buckets
  add column if not exists retries int not null default 0;
