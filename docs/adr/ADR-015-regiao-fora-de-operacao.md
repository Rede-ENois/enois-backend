# ADR-015 — Região fora de operação: declaração humana, e filtro em quatro portas

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

O critério da fase 6 é "região afetada sai; nós de outras regiões atendem o que podem"
(`07-plano-incremental.md`). A primeira metade não existia. A ADR-012 fixou a região como
**afinidade de ordenação**: `regionPenalty` faz o nó da região do pedido vir antes, mas nada podia
tirar uma região de operação — nem o admin, nem o Control Plane.

O defeito concreto, numa queda regional: os nós da região continuam `ACTIVE` (não foram suspensos,
a região é que saiu), então:

1. `findCandidates` os devolve e `choose()` **designa** tarefa para eles;
2. `availableCapacity` **conta a vaga** deles, e o modo lê `vagas=6` com a região inteira caída;
3. o heartbeat responde `acceptTasks=true`, então o nó segue pedindo trabalho;
4. a drenagem existente não os vê: `findOnNonOperatingNodes` pergunta por `n.status <> 'ACTIVE'`,
   e o status deles é `ACTIVE`. A tarefa fica presa até o claim vencer (120 s no padrão), volta
   por `reclaimAbandoned`, e `choose()` a designa **de novo ao mesmo nó** — em loop, até morrer de
   tentativas.

Quatro perguntas de desenho, cada uma com a alternativa rejeitada:

1. **A região sai por medição ou por declaração humana?** Por **declaração humana, com motivo
   obrigatório**, pelo mesmo raciocínio do modo operacional (ADR-009). Alternativa rejeitada:
   derivar de métrica (latência, taxa de erro, heartbeat parado). Nenhum número distingue "região
   em desastre" de "região com a rede lenta", e uma região inteira cair por limiar automático
   criaria o incidente que ela deveria mitigar. O que se declara é uma decisão de incidente — e ela
   precisa de registro: quem declarou, por quê, desde quando.

2. **A volta também é humana?** Sim, e sem prazo de relógio. Alternativa rejeitada: expirar a
   evacuação por `declared_at + janela`, como a quarentena de saúde. A de saúde tem janela porque
   falha se resolve sozinha; uma região caída não — readmiti-la por relógio seria a automação
   desfazendo decisão de incidente e devolvendo tráfego a uma região que ninguém verificou. O
   caminho de volta é `DELETE /admin/network/regions/{region}`, auditado
   (`NETWORK_REGION_BACK_IN_SERVICE`).

3. **Afirmação ou preferência?** **Filtro** no despacho, ao contrário da afinidade da ADR-012, e a
   diferença é o que "evacuar" significa: afinidade é preferência **entre nós que podem executar**;
   evacuação é a afirmação de que **este nó não pode executar agora**. Designá-lo é exatamente o
   loop que a evacuação existe para romper. Por isso a região evacuada não é penalidade
   (`regionPenalty` maior): é `.filter(...)` em `choose()`, antes de qualquer critério de mérito.

4. **O que fecha?** As **quatro portas** que o desenho do scheduler abriu, porque fechar três
   deixaria a quarta aberta:
   - **despacho** (`choose`) — a tarefa nova não vai para a região;
   - **capacidade** (`availableCapacity`) — a vaga não é contada, e o modo vê a rede menor (o painel
     idem, `freeSlots` e `outOfServiceRegions`);
   - **heartbeat** (`acceptTasks=false`) — o nó para de pedir, que é como o runtime já drena (não
     pedir, sem 403 que encerraria o canal de reporte). `nextFor` devolve lista vazia como segunda
     porta, para o runtime que insiste ou que tem o heartbeat em cache;
   - **drenagem regional** (`drainEvacuatedRegions`) — o que já estava na mão dos nós volta à fila
     na hora, sem esperar o claim TTL.

## Decisão

Uma tabela `network_region_states` (V304) guarda a **decisão**: `region` (chave normalizada),
`declared_by`, `declared_reason`, `declared_at`. `POST /admin/network/regions` declara (região e
motivo obrigatórios, auditoria `NETWORK_REGION_OUT_OF_SERVICE`), `DELETE /admin/network/regions/{region}`
devolve (`NETWORK_REGION_BACK_IN_SERVICE`, 404 se não estava fora), `GET` lista o painel.

A região de nó e a declaração são comparadas **normalizadas** (`NetworkRegions.normalize`: trim e
lower-case), no domínio e não no serviço — os adapters e a aplicação comparam região, e duas grafias
("Sudeste" no nó, "sudeste" na declaração) seriam a mesma região para o balanceador e duas para o
evacuador.

No scheduler, `drainEvacuatedRegions` roda **antes** de `dispatchPending`, na mesma posição da
drenagem por status. Tarefa com **alvo** num nó evacuado volta para a fila mas **não** é designada a
outro: alvo é vínculo (V302), não preferência — o estado daquele trabalho vive naquele nó, e
executar no nó errado não é executar pior, é não executar.

## Consequências

- `network_region_states` é tabela nova, aditiva; nenhum dado antigo é tocado. Repetir a declaração é
  `on duplicate key update region = region` — `declared_at` é quando a região **entrou** na
  contingência, e redeclarar não reinicia o incidente (só a auditoria registra a segunda
  declaração).
- Região **ausente** nunca é evacuada: nó sem região declarada continua elegível. Evacuar região
  vazia seria tirar a rede inteira por uma grafia que ninguém comparou.
- `NetworkTaskService`, `NetworkModeService`, `NetworkDashboardService`, `NetworkNodeController`
  ganham `NetworkRegionService` no construtor (aditivo interno; nenhum teste os constrói à mão).
- A tarefa drenada sai como `PENDING` com `attempt` inalterado e a próxima designação incrementa —
  o resultado do nó evacuado, se chegar, cai no descarte por tentativa que já existe. Drenar cedo
  não abre porta para efeito duplo.
- `EdgeCacheService.request` recusa com 409 `NODE_REGION_OUT_OF_SERVICE` e `sweep` pula o nó: o
  pedido de cache é tarefa **com alvo**, e enfileirá-la para um nó que não vai executá-la a deixaria
  na fila até o prazo.
- `/admin/network/tasks/overview` ganha `awaitingEvacuation` (aditivo, como `awaitingDrain`) e
  `/admin/network/nodes/dashboard` ganha `outOfServiceRegions`: entre a declaração e o ciclo
  seguinte, o admin vê que havia trabalho na mão da região.
- Nenhum nó externo escreve na tabela. A declaração é operação administrativa, sob a trava central
  `/admin` e o `AdminGuard`, como todas as outras.

## Provas

- Q0 do servidor: neutralizando as quatro portas (filtro de `choose`, drenagem regional, `acceptTasks`
  e as exclusões de capacidade no modo e no painel), `NetworkRegionTests` falha em **6 de 13** —
  `freeSlots` 4 em vez de 0, `acceptTasks` `true`, `awaitingEvacuation` 1, e o despacho reenviando à
  região declarada caída; passa nos 13 com as portas fechadas.
- Sem outra região: `semOutraRegiaoATarefaFicaNaFilaEmVezDeVoltarParaARegiaoEvacuada` fixa que a
  tarefa fica `PENDING` em vez de voltar ao nó evacuado — o loop que existia antes.
- Normalização: `evacuacaoIgnoraCaixaEEspacosDaRegiao` prova `"  SUDESTE  "` evacuando o nó de
  `"Sudeste"`.
- Alvo: `tarefaComAlvoNaRegiaoEvacuadaNaoRodaEmOutroNo` prova que a tarefa com alvo não é
  redistribuída para outra região.
- Volta: `limparADeclaracaoDevolveARegiaoAOperacao` prova `acceptTasks=true` e despacho novo para o
  nó, e `limparRegiaoQueNaoEstavaForaResponde404` fixa o 404 confesso.
- Motivo: `declaracaoSemMotivoERecusada` e `declaracaoEVoltaSaoAuditadas` (motivo com aspas não
  quebra o JSON da auditoria).
- Migração: V304 aplicada **duas vezes** em MariaDB 11.4 real, com `network_nodes`/`network_tasks`
  povoados antes — dados preservados.
- Suíte completa: 2673 testes, 0 falhas, 3 skipped.