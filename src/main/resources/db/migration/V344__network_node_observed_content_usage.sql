-- Énóis Storage, brief §7: a "capacidade utilizada pelo Énóis", medida no próprio nó.
--
-- O §7 pede, por nó, o que ele usa; e pede que o armazenamento "não consuma espaço além do limite
-- configurado". Até aqui o Control Plane sabia o uso AGREGADO do catálogo (`sum(bytes)` das cópias
-- OK) e o teto como CONTRATO (`declared_disk_mb`), mas nunca o uso MEDIDO em cada disco. Sem ele
-- não havia como responder à pergunta mais óbvia do painel — "quanto do que este nó prometeu ele
-- está usando?" — nem perceber um nó cujo estado de conteúdo cresce para além do que ele cedeu
-- (cache de borda, réplica órfã, corpo que o catálogo já não conhece).
--
-- A medida é a do ESTADO DE CONTEÚDO (edge/, replica/ e objects/), a mesma que o runtime compara
-- com o teto assinado ao decidir o que evictar. Não é o `diskFreeMb`: o livre cai por qualquer
-- processo da máquina; este é o espaço que o Énóis ocupa.
--
-- Nulo é "o nó não reportou" (runtime anterior a este formato), diferente de zero, que é um nó sem
-- conteúdo — e as duas coisas pedem leituras diferentes do painel. A regra é a mesma de todas as
-- observações deste módulo.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes add column if not exists observed_content_used_mb bigint null;
