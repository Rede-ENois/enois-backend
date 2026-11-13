-- Énóis Distributed Network (fase 5): a tarefa que nasce para um nó específico.
--
-- O despacho é por mérito: a tarefa vai para o nó com mais folga, mais saudável, da região do
-- pedido. Isso está certo para trabalho fungível — transcode, consulta de leitura, job assíncrono —
-- e está errado para trabalho cujo <b>estado vive no nó</b>. O cache de borda é o caso: o cursor do
-- feed (`$NODE_HOME/edge/cursor`) e os corpos guardados são daquele nó. Uma tarefa global de
-- aquecimento não "distribui" nada — ela designa a UM nó sorteado, que avança só o seu próprio
-- cursor, e os demais nós de borda nunca são aquecidos. Pior: o mesmo pedido feito de novo é outra
-- tarefa (outra chave de idempotência), então cada repetição aquece um nó diferente por sorteio,
-- ou o mesmo, indefinidamente.
--
-- Esta coluna diz a qual nó a tarefa pertence. É um <b>filtro</b>, ao contrário de `region` (V285),
-- e a diferença não é estilo: região é preferência porque qualquer nó saudável entrega aquele
-- trabalho; alvo é vínculo porque só um nó sabe continuar aquele trabalho. Por isso o alvo entra
-- em `choose()` antes de qualquer critério de mérito — e um alvo que não está entre os candidatos
-- (<b>offline</b>, suspenso, sem perfil, sem vaga) deixa a tarefa na fila em vez de mandá-la para
-- outro nó: executar no nó errado não é executar pior, é não executar.
--
-- A coluna é anulável e as tarefas existentes ficam nulas, com o despacho exatamente o de antes:
-- alvo nulo = qualquer nó elegível. Aditiva e idempotente, como as demais deste módulo.
--
-- O índice acompanha `findTargeted` (a leitura que rearma a aquisição periódica): tarefas com alvo
-- numa faixa de estado. Sem ele, a varredura leria a fila inteira para achar as poucas com alvo.

alter table network_tasks add column if not exists target_node_id varchar(40) null;

create index if not exists idx_network_tasks_target on network_tasks (target_node_id, status);