# ADR-004 — Scheduler como handler do outbox, não sistema paralelo

**Status:** aceita (Fase 0), implementação na Fase 2
**Data:** 2026-11-11

## Contexto

A Fase 2 precisa distribuir tarefas entre nós. O projeto já tem um outbox transacional maduro
(`shared/outbox/OutboxWorker.java`) com:

- claim atômico por instância, com expiração;
- recibo por par (evento, handler) em `processed_event_handlers` (V252), gravado na mesma transação
  dos efeitos;
- backoff exponencial limitado, `max-attempts` 20, lote de 50;
- métricas de backlog, falha e terminal.

Criar uma fila paralela significaria reimplementar tudo isso — e provavelmente pior.

## Decisão

O scheduler distribuído é **um handler do outbox**. Ele recebe o evento, escolhe o nó e despacha. O
recibo do handler é gravado quando o nó devolve o resultado, na mesma transação.

## Consequências

**Positivas**
- Herda de graça: claim entre instâncias, backoff, DLQ, métrica, retenção.
- Um único lugar onde "evento pendente" significa alguma coisa.
- O painel de outbox que já existe mostra o estado das tarefas distribuídas.
- Nenhuma dependência de infra nova (sem Redis, sem broker).

**Negativas**
- O scheduler fica acoplado ao modelo de evento do outbox; tarefa que não é evento precisa ser
  modelada como um.
- O lote de 50 e o `max-attempts` 20 são do outbox e podem não servir para todo tipo de tarefa —
  nesse caso, o handler declara os próprios limites em vez de mudar o worker.

## Alternativas descartadas

- **Fila externa (RabbitMQ, Kafka, SQS):** dependência pesada, sem necessidade comprovada, e
  reimplementa garantias que o outbox já tem.
- **Tabela de tarefas própria:** duplica o outbox e cria dois lugares onde "pendente" significa
  coisas diferentes.
- **Chamada direta do serviço ao nó:** perde a garantia de entrega e o retry.

## Relacionado

- [05 — Sincronização](../rede-distribuida/05-sincronizacao.md)
- [07 — Plano incremental](../rede-distribuida/07-plano-incremental.md)
