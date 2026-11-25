-- Énóis Distributed Network: a memória do nó ganha as duas medidas (uso e disponível).
--
-- POR QUE EXISTE: o heartbeat do runtime mede `MemAvailable` (memória DISPONÍVEL) e manda em
-- `memoryMb`; o Control Plane guarda em `observed_memory_mb` e o domínio compara com
-- `declared_memory_mb` (o teto CEDIDO) em `NetworkNode.memoryExhausted()`. Duas medidas opostas na
-- mesma coluna: um nó com 8 GB cedidos e 7,9 GB livres era "teto atingido" (fora do balanceamento
-- com a máquina ociosa), e um nó com 100 MB livres aparecia com 100 MB de memória.
--
-- O QUE MUDA: a coluna `observed_memory_mb` passa a guardar o USO medido — o mesmo significado dos
-- demais `observed_*`, que são todos "o que está em uso" — e o disponível ganha coluna própria.
-- A migração é aditiva: a coluna antiga não é renomeada nem apagada, porque `node_samples.memory_mb`
-- já descrevia o uso e renomear criaria duas verdades na tabela vizinha.
--
-- Leitores do formato antigo não quebram: nulo continua "não medido", e o Control Plane deriva o uso
-- de `declared - disponível` quando o runtime só manda o disponível (runtime anterior a este
-- contrato). Nó já registrado fica com o disponível nulo até o próximo heartbeat — "não medido" é a
-- resposta honesta para o que ninguém mediu ainda.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes
  add column if not exists observed_memory_available_mb int null after observed_memory_mb;