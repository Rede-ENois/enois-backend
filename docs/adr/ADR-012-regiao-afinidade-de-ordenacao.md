# ADR-012 — Região: afinidade de ordenação, nunca filtro

**Status:** aceita (Fase 5)
**Data:** 2026-11-12

## Contexto

O plano da fase 5 promete "distribuição geográfica: região declarada e considerada no
escalonamento". A evidência de código, até esta rodada: a região era **gravada** (enroll grava
`network_nodes.region`), **exibida** (painel) e **assinada** (config do nó), mas nenhuma linha do
scheduler a consultava — e a tarefa nem carregava origem, então a pergunta "região de quem?" não
tinha resposta.

Duas perguntas de desenho, cada uma com alternativa rejeitada:

1. **A região é do nó ou da tarefa?** Do nó sozinho não basta: saber que o nó está em `sa-east-1`
   não diz nada sobre para onde o trabalho *quer* ir. A região precisa entrar na tarefa
   (`network_tasks.region`, V285), declarada por quem enfileira — igual ao `dataClass`, é uma
   propriedade do pedido, não do executor.

2. **Filtro ou ordenação?** **Filtro rejeitado.** Filtrar por região esvaziaria a fila sempre que a
   região do pedido não tivesse nó vivo e com vaga: a tarefa esperaria a região voltar em vez de
   rodar no vizinho — exatamente o ponto único regional que a rede cooperativa existe para não ter.
   Também criaria um segundo caminho de "tarefa presa" que a drenagem não conhece.

E uma pergunta de posição: onde na ordem existente (degradação → origem → folga → jitter,
ADR-010)?

## Decisão

A ordem passa a ser **degradação → origem (`NODE_LOCAL`) → região → folga → jitter**. Região
significa 0 para o nó da região do pedido, 1 para os demais (comparação sem diferenciar
maiúscula; nó sem região declarada empatA em 0 — não declarar região não é culpa que o nó pague).

Quatro propriedades:

1. **Ordenação, nunca filtro.** Tarefa de `sa-east-1` roda em `us-east-1` quando não há próximo
   elegível. Sem região no pedido, todos empatam e o escalonamento é o de antes — cada tarefa
   existente hoje se comporta exatamente como antes desta rodada.

2. **Depois da origem.** A casa continua absorvendo o pico antes de chegar ao participante
   (ADR-010): o `NODE_LOCAL` com vaga recebe mesmo sendo de outra região. A região decide entre os
   participantes.

3. **Antes da folga.** Afinidade aplicada depois da folga só decidiria no empate exato de vagas —
   e é no caso normal (todos com vaga parecida) que ela precisa existir. Mesmo raciocínio da
   posição da origem em ADR-010.

4. **`region` vazia na tarefa é `null` no banco.** Guardar `""` criaria um valor que não casa com
   nó nenhum e que ninguém distingue de `null` — dois jeitos de dizer a mesma ausência viram bug
   de comparação.

## Consequências

- Correção de contrato incidental, na mesma rodada: o enroll **documentava** `location` aninhado
  desde a fase 1 e o DTO só lia região solta no topo — um instalador seguindo a doc gravava o nó
  sem região em silêncio. O DTO agora aceita as duas formas (a solta vence em conflito); a suíte
  prova a forma documentada (`enrollComLocationDaDocGravaARegiao`).
- O painel (`GET /admin/network/tasks`) expõe `region` por tarefa: sem isso, "por que esta tarefa
  foi para um nó distante?" era indizível.
- Sem índice novo: a escolha é em memória sobre os `MAX_LIST` candidatos vivos da rodada; nenhuma
  consulta filtra por `network_tasks.region`.
- Latência geográfica de verdade (medir distância, priorizar por proximidade) continua fora — o
  termo é binário por igualdade. Entrar com distância real exigiria dados de posição que a rede
  não coleta, e LGPD desaconselha coletar mais do que a região declarada.

## Provas

- Q0: `tarefaDaRegiaoRecebeONoDaRegiaoSemVirarFiltro` falha sem o termo de região (a folga antiga
  escolhia o nó distante determinística) e passa com ele; cenário com o nó da região **cheio**
  prova que a tarefa da mesma região vai para o nó distante em vez de esperar na fila.
- Preservação: `tarefaSemRegiaoSegueOEscalonamentoDeAntes` (mesma rodada, espalha pela folga) e
  `noDaRegiaoDegradadoPerdeParaODistanteSaudavel` (saúde vence região).
- Contrato: `enrollComLocationDaDocGravaARegiao`.
- Migração V285 aplicada duas vezes em MariaDB 11.4.13 real com linha antiga preservada.
