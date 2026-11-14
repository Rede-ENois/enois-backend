# ADR-006 — Estado de retentativa na linha da tarefa, não no evento

**Status:** aceita (Fase 0), implementação na Fase 2
**Data:** 2026-11-11

## Contexto

O scheduler é um handler do outbox ([ADR-004](ADR-004-scheduler-como-handler-do-outbox.md)). Isso
resolve a entrega do gatilho, mas não a vida da tarefa.

O `OutboxWorker` grava o recibo do handler (`processed_event_handlers`, V252) na **mesma transação**
dos efeitos do handler e só então chama o handler. Quando o nó falha no meio da tarefa, o evento já
está concluído: não há retentativa de outbox que vá reenviar aquele evento. Se o estado de
retentativa morasse no evento, a tarefa ficaria presa em "concluída" com o nó morto.

## Decisão

A linha da tarefa (`network_tasks`, V292) é a **autoridade** sobre tentativa, prazo, nó designado,
claim e erro. O evento do outbox é só o gatilho que cria a linha.

- `attempt` sobe a cada designação; resultado de tentativa antiga é descartado pelo `attempt`.
- `claim_expires_at` devolve à fila a tarefa de um nó que caiu no meio.
- `deadline` vencido não executa: a tarefa vira `DEAD` com `TASK_EXPIRED`.
- `next_attempt_at` é o backoff da tarefa, independente do backoff do outbox.
- `max-attempts` e prazo são da tarefa (`rede.network.task.*`), não do worker: o lote de 50 e o
  `max-attempts` 20 do outbox não servem para todo tipo de trabalho.

## Consequências

**Positivas**
- Falha do nó é tratada onde ela acontece, com o estado certo.
- O painel mostra a tarefa real (tentativa, nó, erro), não um evento já concluído.
- Redistribuição sem efeito duplicado vem de `id` estável + `attempt` crescente.

**Negativas**
- Dois lugares com "pendente": outbox (evento não processado) e `network_tasks` (tarefa não
  concluída). Cada um tem significado próprio e o painel separa os dois.
- Uma tarefa que morre antes de a linha existir depende do retry do outbox — que é o comportamento
  correto, porque aí não há tarefa, só um evento que falhou.

## Alternativas descartadas

- **Estado de retentativa no evento do outbox:** exigiria o handler falhar para o worker reenviar,
  mas o recibo já commitou — o evento nunca voltaria.
- **Fila de tarefas sem outbox:** perde a garantia de entrega do gatilho e o registro de que a
  tarefa foi criada.

## Relacionado

- [ADR-004 — Scheduler como handler do outbox](ADR-004-scheduler-como-handler-do-outbox.md)
- [ADR-005 — Nenhum nó externo escreve no banco principal](ADR-005-sem-escrita-de-no-no-banco.md)
- [05 — Sincronização](../rede-distribuida/05-sincronizacao.md)