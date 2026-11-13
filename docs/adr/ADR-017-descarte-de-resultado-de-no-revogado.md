# ADR-017 — Descarte auditado do resultado de nó revogado no meio

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

A doc `06-contingencia.md` §5 promete, para o cenário "o nó está executando uma tarefa e é
revogado":

> 2. O resultado que chegar depois é **descartado** (`NODE_REVOKED`), com registro em auditoria.
> 3. A tarefa volta para a fila e é redistribuída, com `attempt` incrementado.

O item 3 já funcionava: `drainSuspendedNodes()` pergunta pelo status do nó
(`n.status <> 'ACTIVE'`), então a revogação devolve a tarefa à fila na varredura seguinte e a
próxima designação incrementa o `attempt`.

O item 2 não funcionava, e o modo da falha é o pior tipo: **silencioso**. `NetworkNodeService`
chama `nodes.updateStatus(...)` e `keyService.revokeAllForNode(...)` na mesma transação, de modo que
a chave do nó morre junto com o status. Quando o resultado tardio chega em
`POST /network/tasks/{taskId}/result`, `NodeKeyService.authenticate` — cujo filtro
`.filter(NodeKeyRecord::active)` é justamente o que impede chave revogada de autenticar — responde
`401 NODE_KEY_INVALID`. `NetworkTaskService.acceptResult` nunca é alcançado, e portanto a linha
`NETWORK_TASK_RESULT_DISCARDED` prometida em auditoria nunca é gravada.

A consequência operacional não é o corpo do 401: é a ausência de rastro. A tarefa foi
redistribuída, o nó anterior reportou um resultado, e nada disso aparece na trilha — quem investiga
um incidente de duplicação vê a tarefa com `attempt` 2 sem saber que alguém declarou tê-la
executado na 1.

Duas saídas foram descartadas antes da decisão:

- **Auditar dentro do tratamento de erro da autenticação.** Rejeitada por segurança: nesse ponto a
  identidade não está provada. Qualquer um que soubesse o id de um nó revogado poderia escrever
  linhas de auditoria com o nome dele — auditoria não autenticada é pior que auditoria ausente.
- **Aceitar a chave revogada em toda a autenticação.** Rejeitada por escopo: devolveria ao nó
  expulso o direito de pedir tarefa nova, ler config e bater heartbeat. Revogação terminal deixaria
  de ser terminal.

## Decisão

1. **Um caminho de identidade só para esta rota.** `NodeKeyService.authenticateForResult(nodeId,
   presentedKey)` localiza a chave pelo hash via `findByHash` — que já não filtra `revokedAt` — e
   confere que ela pertence ao `nodeId` apresentado. A chave revogada **prova de quem veio o
   pedido**; não autoriza nada além de ser descartado. O retorno é um `ResultCredential(node,
   revoked)`, para o chamador não reconsultar o banco só para saber o status.

2. **Toda outra rota continua em `authenticate`.** Heartbeat, `GET /network/tasks/next`,
   `GET /network/tasks/config`, `GET /network/media/{id}/source` e `PUT /network/media/{id}/result`
   seguem recusando chave revogada. `authenticate` não mudou de comportamento: o filtro de chave
   ativa e o `403 NODE_REVOKED` estão onde estavam. O único código compartilhado novo é o helper
   privado `keyMatching`, que não decide nada.

3. **O descarte é um método próprio, e ele não aplica nada.** `NetworkTaskService.
   discardRevokedResult(node, command)` grava `NETWORK_TASK_RESULT_DISCARDED` com
   `reason=NODE_REVOKED`, `reportedAttempt`, `currentAttempt` e o `node` de origem, e devolve
   `applied=false` com `reason=NODE_REVOKED`. A tarefa não é tocada: já foi devolvida pela drenagem
   e pode estar na mão de outro nó. Revogação é terminal, e o resultado de um nó revogado não volta
   a mover tarefa — só documenta o que aconteceu.

4. **Fora a revogação, o comportamento é exatamente o anterior.** `authenticateForResult` delega
   para `authenticate` quando o nó não está revogado, então chave rotacionada continua 401 e nó
   suspenso ou em quarentena continua 403 pela checagem de operação. A superfície nova é uma
   bifurcação, não um relaxamento: nenhum caminho antigo passa a aceitar o que antes recusava.

## Consequências

- O nó revogado recebe `200 {applied:false, reason:"NODE_REVOKED"}` em vez de 401. A resposta deixa
  de ser um erro de credencial e passa a ser o que de fato aconteceu: o resultado foi entendido e
  descartado. O runtime não precisa de tratamento especial — `applied=false` já é o sinal de "não
  reenviar".
- A trilha de auditoria passa a explicar a redistribuição: para cada tarefa com `attempt` maior há
  agora, quando houve resultado tardio, a linha do descarte com o nó e as duas tentativas.
- O efeito duplo continua impedido pelo mesmo mecanismo de antes (`attempt` diferente), não por este.
  Este ADR é sobre rastro, não sobre correção de aplicação — a correção de aplicação já existia.
- `last_used` da chave revogada não é atualizado. Marcar uso de uma credencial morta distorceria a
  leitura de "quando esta chave foi usada pela última vez".
- Fica aberto o item §6 da mesma doc (Control Plane fora por mais que o TTL de config): a config
  assinada não tem expiração e o runtime segue com a última indefinidamente. É outra rodada.

## Provas

**Q0 em dois níveis.** O teste `NetworkDisasterTests.revogacaoNoMeioDaTarefaDescartaOResultadoEAudita`
roda contra o código anterior e falha com `Status expected:<200> but was:<401>` — o defeito
reproduzido sem ambiguidade. Com a correção, passa. Depois, com a bifurcação do controller
neutralizada (`credential.revoked() ? … : …` trocado por `false ? … : …`), o mesmo teste volta a
falhar e os outros dois da classe continuam verdes: a mutação atinge só o caminho novo.

**Guarda de escopo.** `chaveRevogadaContinuaBarradaForaDaRotaDeResultado` fixa que a chave revogada
segue 401 em `GET /network/tasks/next` e em `GET /network/tasks/config`.
`chaveDeOutroNoNaoProduzDescarteAuditado` fixa que a chave de um nó não serve para o id de outro — e
que, nesse caso, nenhuma linha de auditoria é escrita.

**Classes vizinhas verdes** na mesma execução: `NetworkTasksTests` 22, `NetworkNodesTests` 25,
`NetworkTermConflictTests` 6, `NetworkDrainTests` 4, `NetworkSchedulerDrainTests` 1,
`NetworkDisasterTests` 3 — os comportamentos que a mudança encostava continuam iguais.

**Suíte completa:** 2686 testes, 0 falhas, 3 skipped (2683 anteriores + 3 novos).