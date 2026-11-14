# ADR-007 — Broadcast entre instâncias por tabela de sinais com polling

**Status:** aceita (Fase 3)
**Data:** 2026-11-11

## Contexto

`MessageStreamHub` e `CacheInvalidator` guardam estado **na memória de cada pod**: os streams SSE
abertos e o cache Caffeine. Numa única instância isso é correto. Com duas, a escrita atendida em A
só avisa o stream de A e só limpa o cache de A; a aba aberta em B fica sem o aviso até reconectar e
o cache de B serve a versão anterior até o TTL vencer.

A Fase 3 exige, literalmente: *"`MessageStreamHub` funciona com duas instâncias; cache invalida em
todas"* ([07 — Plano incremental](../rede-distribuida/07-plano-incremental.md)).

O [08 — Impacto](../rede-distribuida/08-impacto.md) §9 deixou a escolha em aberto: **Redis, ou
tabela de eventos com polling?**

## Decisão

**Tabela de sinais com polling por instância** (`network_instance_signals` + `network_instance_readers`,
V293). Sem Redis, sem broker — o que mantém a decisão do [ADR-004](ADR-004-scheduler-como-handler-do-outbox.md).

Por que **não** o outbox, que já existe: o `OutboxWorker` reserva cada evento com claim atômico e
grava o recibo do handler na mesma transação dos efeitos. Isso garante que o evento execute em
**exatamente uma** instância — correto para efeito de negócio, inútil para broadcast, que por
definição precisa chegar em todas. Um handler de outbox não consegue entregar em todas as
instâncias; tentar isso seria corromper a semântica do worker.

O desenho tem três peças:

1. **Emissão participa da transação do efeito.** Quem escreve aplica o efeito local (limpa o cache
   deste pod, avisa o próprio stream) e grava o sinal com `origin_instance` = esta instância.
2. **Leitura é por instância e não consome.** Cada instância guarda a própria posição em
   `network_instance_readers.last_seq` e lê `where seq > posição and origin_instance <> eu`. Não há
   recibo nem claim: três instâncias leem o mesmo sinal.
3. **Retenção por idade** (300 s). Sinal perdido é tolerado: o TTL do cache e o reconnect do
   EventSource cobrem a falta. Broadcast é aceleração, não garantia.

Duas regras que vieram de defeito real:

- **`seq`, não coluna de consumo.** A primeira versão tinha `consumed_at` compartilhada; a primeira
  instância que lesse esconderia o sinal das outras — exatamente o bug que a tabela corrige. Pelo
  mesmo motivo a coluna não se chama `cursor` (reservada no MariaDB).
- **Entrega isolada por sinal.** Um sinal que estoura num consumidor não bloqueia os vizinhos do
  lote, e a posição avança de qualquer forma; tudo-ou-nada faria um sinal envenenado parar o
  fan-out inteiro.

## Consequências

**Positivas**
- Nenhuma dependência de infra nova; a mesma MariaDB já operada.
- Uma consulta indexada por segundo por instância, com lote de 200 e poda de 5 minutos.
- O mecanismo serve a qualquer estado local futuro (é `LocalSignalConsumer`), não só stream e cache.
- Visibilidade: `network_instance_readers` mostra quantas instâncias estão lendo de verdade.

**Negativas**
- Latência de até 1 s (o `delay-ms` da varredura). Aceitável: é aceleração sobre um TTL de 20 s.
- A tabela é escrita na transação do efeito. Se a emissão falhar, o efeito continua válido e a
  perda é coberta pelo TTL — a emissão nunca derruba a escrita.
- Escala com o número de instâncias do Control Plane (dezenas), não com o número de nós. Não é
  mecanismo para fan-out massivo entre nós externos.

## Alternativas descartadas

- **Redis pub/sub:** infra nova (ADR-004), mais um serviço a operar, autenticar e monitorar, para
  entregar avisos que duram segundos e cuja perda já é tolerável.
- **Handler do outbox:** entrega em exatamente uma instância — não é broadcast (ver acima).
- **Coluna de consumo compartilhada:** o primeiro leitor esconde o sinal dos demais.
- **Compartilhar o cache (Redis cache):** o dado é recalculável e de frescor curto; o custo não se
  justifica.

## Relacionado

- [ADR-004 — Scheduler como handler do outbox](ADR-004-scheduler-como-handler-do-outbox.md)
- [ADR-006 — Estado de retentativa na linha da tarefa](ADR-006-estado-de-retentativa-na-linha-da-tarefa.md)
- [05 — Sincronização](../rede-distribuida/05-sincronizacao.md) §6
- [08 — Impacto](../rede-distribuida/08-impacto.md) §9