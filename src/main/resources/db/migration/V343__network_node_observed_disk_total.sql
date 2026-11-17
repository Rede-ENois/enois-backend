-- Énóis Storage, brief §7 (gerenciamento de capacidade): o TAMANHO do disco, junto do livre.
--
-- Reaplica a V341__network_node_observed_disk_total.sql, que não chegou a rodar: por causa da
-- colisão da V341 com a lista de rascunhos, o Flyway gravou a versão 341 com o checksum do
-- rascunhos e pulou a do storage, deixando o boot do QA sem a coluna `observed_disk_total_mb`.
-- A migração original é aditiva e idempotente, então reaplicar com número novo é seguro.
--
-- Nulo é "o nó não reportou" (runtime anterior a este formato), diferente de zero, que seria um
-- disco de tamanho zero. Mesma regra das demais observações: o nulo não entra em soma nenhuma.

alter table network_nodes add column if not exists observed_disk_total_mb bigint null;
