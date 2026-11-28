-- Énóis Distributed Network: o que cada nó declarou conseguir EXECUTAR das tarefas que a config
-- assinada autoriza (brief §8.2, bloco "Runtime": "Health checks").
--
-- POR QUE EXISTE: a config assinada autoriza tipos por PERFIL (um nó `NODE_MEDIA` pode receber
-- `MEDIA_TRANSCODE`), e permissão não é capacidade: a máquina pode não ter o `ffmpeg` que o
-- executor precisa. Sem esta declaração o Control Plane designava, o nó tentava, faltava o binário
-- e a tarefa gastava uma tentativa — uma por ciclo, até acabarem, com o nó aparecendo saudável.
--
-- SEMÂNTICA: JSON array com os tipos autorizados que a máquina consegue executar agora, na forma
-- `["MEDIA_TRANSCODE","READ_QUERY"]`. Nulo é "não declarou" — runtime anterior a esta versão, nó
-- que ainda não subiu depois da atualização —, e o despacho continua como era: a capacidade é
-- DECLARADA pelo nó, não adivinhada pelo Control Plane. Array vazio não deveria acontecer (o nó
-- sem capacidade nenhuma não pede tarefa); quando acontece, é respeitado: nenhum tipo cabe nele.
--
-- Aditiva: coluna nula, sem default; os nós já gravados ficam "não declarou" até o próximo release.
-- A versão é a 390 porque a maior existente é a 389 (swap e pressão de memória).
alter table network_nodes
  add column if not exists executor_task_types text null after profiles;