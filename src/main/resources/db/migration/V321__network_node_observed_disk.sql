-- Énóis Distributed Network (observabilidade, fase 1 do Cooperative): o disco livre MEDIDO pelo nó.
--
-- Até aqui o nó reportava no heartbeat CPU, memória e tarefas em execução, e o disco era só o
-- `declared_disk_mb` do enroll — o que o participante PROMETEU ceder, não o que existe de fato.
-- A diferença não é cosmética: um nó declara 2 TB e tem 40 GB livres há semanas; o escalonamento
-- continua tratando os 2 TB como disponíveis e o Storage recusa ou falha na hora de gravar.
--
-- Mesma regra que o módulo já aplica em toda parte: o declarado é contrato, o observado é medida.
-- Os dois convivem, e o painel mostra os dois lado a lado justamente para que a divergência
-- apareça (participante que perde disco sem avisar é diagnóstico, não erro de configuração).
--
-- Nulo significa "o nó não reportou" — versão de runtime antiga, ou nó que nunca subiu —, e é
-- diferente de zero, que seria "sem espaço livre". A leitura que decide capacidade tem de tratar
-- os dois casos de forma distinta, e por isso a coluna é anulável.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes add column if not exists observed_disk_free_mb bigint null;