-- Énóis Storage, brief §7 (gerenciamento de capacidade): o TAMANHO do disco, junto do livre.
--
-- Até aqui o heartbeat mandava só o disco livre (`observed_disk_free_mb`, V321) e o contrato
-- (`declared_disk_mb`, V305). Com dois números não se responde o que o §7 exige: "espaço reservado
-- pelo participante" precisa do total físico, porque reserva é total menos o que o Énóis pode usar
-- ("2 TB de disco, 500 GB reservados pessoalmente, 1,5 TB oferecidos"). Somar só livres dava a
-- disponibilidade agregada do painel, mas não permitia mostrar ao participante quanto ele guardou
-- para si — nem detectar o caso em que ele cedeu mais do que o disco tem.
--
-- O total é um fato conhecido e não muda a menos que se troque o disco, mas continua sendo medido em
-- cada heartbeat: um nó que permaneceu no ar com o volume remontado troca de total, e guardar só o
-- primeiro valor medido mostraria um disco que não existe mais.
--
-- Nulo é "o nó não reportou" (runtime anterior a este formato), diferente de zero, que seria um
-- disco de tamanho zero. Mesma regra das demais observações: o nulo não entra em soma nenhuma.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes add column if not exists observed_disk_total_mb bigint null;
