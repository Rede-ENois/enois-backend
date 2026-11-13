# ADR-014 — Regra 5: nó que vê dois controladores entra em quarentena sem prazo

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

A ADR-013 entregou as regras 1–4 do anti split-brain (doc 06 §4): termo monotônico por CAS, quórum do
eleitorado declarado, termo na linha assinada, e o nó recusando config de termo menor
(`term_regressao`). Elas impedem que **dois Control Planes se promovam**. Não cobrem o outro lado do
split-brain: o nó que, por estar no meio de uma partição, falou com os dois e aceitou o termo maior.
Até esta rodada esse nó operava normalmente — o Control Plane não tinha como saber que existia um
mando maior do que o dele, e a rede seguiria despachando tarefa para um nó que obedece a outro dono.

Quatro perguntas de desenho, cada uma com a alternativa rejeitada:

1. **Como o Control Plane descobre?** **O nó pergunta, no heartbeat** — o termo aceito viaja como
   `observedTerm` no corpo do `POST /network/nodes/heartbeat`, lido de `$NODE_HOME/term`, o mesmo
   registro que a `term_regressao` mantém. Alternativas rejeitadas: um endpoint novo de "reportar
   autoridade vista" (mais um canal para autenticar, versionar e manter, e o nó já conversa no
   heartbeat a cada ciclo); pôr o termo observado na config (a config é resposta do servidor, não
   canal de subida); um campo novo na linha assinada (obrigaria a subir o `formatVersion` de novo, e
   o termo observado é estado do nó, não permissão assinada pelo Control Plane).

2. **O que conta como conflito?** Termo reportado **maior** que o da autoridade vigente. Igual é o
   caso normal; menor é o passado chegando atrasado, e o próprio nó já recusa essa config. Punir quem
   está atrasado tiraria nós de circulação a cada reconexão de uma autoridade deposta e — pior —
   faria o alarme disparar por motivo que não é conflito. Sem termo reportado nada acontece: a regra
   existe para o nó que **falou** com dois controladores, e quem nunca aceitou config não provou
   nada. Nó em `PENDING_APPROVAL`/`SUSPENDED`/`REVOKED` também não entra: não tem tarefa a perder, e
   trocar o status apagaria a fila de aprovação humana.

3. **Qual quarentena?** **A de status (`NodeStatus.QUARANTINED`), com motivo `TERM_CONFLICT`, e não
   a janela de saúde.** São duas mecânicas distintas no código: a janela (`quarantined_until`,
   V282) existe para falha, que se resolve sozinha, e o `NodeHealthWorker` a libera por relógio. A
   divergência de termo não se resolve esperando: liberar por prazo devolveria o nó a operar ainda
   dividido entre dois mandos. O status `QUARANTINED` já basta para tirá-lo de circulação —
   `requireOperable` recusa `nextFor`/`acceptResult` com 403 `NODE_QUARANTINED`, `findCandidates` só
   seleciona `ACTIVE`, e `availableCapacity` não conta a vaga dele. A janela é gravada mesmo assim,
   como horizonte de revisão para o painel (`rede.network.authority.conflict-quarantine-seconds`,
   padrão 600 s).

4. **Como o nó drena e como volta?** Drena pelo **mesmo heartbeat** que detectou: a resposta é 200
   com `status: "QUARANTINED"` e `acceptTasks: false` — 403 fecharia o único canal pelo qual o nó
   reporta o termo, e o conflito ficaria invisível para sempre. A volta é **decisão humana**:
   reaprovação administrativa (`POST /admin/network/nodes/{id}/approve`) devolve o `ACTIVE` **e**
   limpa a janela, porque aprovar sem limpar deixaria o nó liberado no papel e fora do balanceamento
   na prática. A limpeza é restrita ao motivo `TERM_CONFLICT`: a quarentena de saúde continua sob o
   worker, e a quarentena manual continua respondendo 403 no heartbeat como sempre respondeu.

## Decisão

O nó reporta o termo aceito no heartbeat. Termo reportado maior que o vigente põe o nó em
`QUARANTINED` com motivo `TERM_CONFLICT`, auditoria `NETWORK_NODE_QUARANTINED` com `observedTerm` e
`authorityTerm`, e `acceptTasks=false` na mesma resposta. O heartbeat continua aceito (200) para o
nó em `TERM_CONFLICT`, e só para ele. A quarentena não expira por relógio; a reaprovação humana
encerra e limpa a janela; `acceptTasks` passa a considerar o próprio nó, não só o modo da rede.

## Consequências

- Nenhuma migração: o motivo `TERM_CONFLICT` cabe em `quarantine_reason` (`varchar(40)`, V282) e o
  termo observado não é armazenado — o heartbeat é transiente por desenho. V289 continua livre.
- `HeartbeatCommand` e `HeartbeatRequest` ganham `observedTerm` (aditivo e opcional): nó de runtime
  antigo que não manda o campo não é penalizado, e continua operando como antes.
- `approve` passa a chamar `release` quando o motivo é `TERM_CONFLICT`. Para os demais motivos o
  comportamento é idêntico ao anterior.
- `heartbeat` deixa de exigir `requireOperable` **apenas** para `QUARANTINED` com motivo
  `TERM_CONFLICT`; `REVOKED`, `SUSPENDED`, `PENDING_APPROVAL` e quarentena manual seguem barradas.
- As queries `update` de status e quarentena ganham `clearAutomatically`: o chamador relê o nó logo
  depois, e sem limpar a releitura traria a linha anterior ao `UPDATE` — o nó recém-quarentenado
  leria como `ACTIVE` na mesma transação e receberia tarefa no ciclo em que o conflito foi
  detectado. O defeito apareceu no primeiro teste da regra 5.

## Provas

- Q0 do servidor: com a chamada `quarantineOnTermConflict` desligada, `NetworkTermConflictTests`
  falha em 3 de 6 (`noQueReportaTermoMaiorEntraEmQuarentenaEParaDeAceitar`,
  `quarentenaDeTermoNaoExpiraPorRelogio`, `reaprovacaoHumanaLiberaNoDeConflito`) e passa nos 6 com
  ela.
- Assimetria: `termoIgualOuMenorNaoEConflito` prova que não há punição por atraso e que o nó
  continua recebendo tarefa; `noSemTermoReportadoNaoEntraEmConflito` prova que omissão não é
  conflito.
- Não regressão de comportamento: `quarentenaManualContinuaRecusandoHeartbeat` fixa que a exceção
  vale só para `TERM_CONFLICT`.
- Sem prazo: `quarentenaDeTermoNaoExpiraPorRelogio` roda o `NodeHealthWorker` com a janela no
  passado e o nó continua `QUARANTINED`, batendo coração com `acceptTasks=false`.
- Runtime: `node-executor-test.sh` cenário 5f — o heartbeat carrega `"observedTerm":4` lido de
  `$NODE_HOME/term`, e não carrega o campo quando o nó nunca aceitou config.
- Suíte completa: 2660 testes, 0 falhas, 3 skipped.